package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** External live-room screenshots, real PP-OCRv4 crops and exported action weights.
 * Personal originals remain external; generated counterexamples belong only in work/.
 * Arguments: templates assets weights landscape.jpg portrait.jpg work-counterexamples [budget-ms]. */
public final class HuyaLiveAdCheck {
    private static int checked;
    private static final String SHIPPED_MODEL="bd0dc2362e2de311233a6be980c9d9ea099925a52bcc95b274ee2815a5037894";
    private static final String SHIPPED_WEIGHTS="76cb1742f7b467c26df5f98b24678d85925fc0a9364820e0d3fcdc84bef1ff38";
    private final HuyaVisualMatcher matcher;
    private final OnnxUiText ocr;
    private final AdButtonModelCheck classifier;
    private final Path counterexamples;
    private final long budgetMs;

    private static final class Result {
        final Hit hit;
        final float probability;
        final long elapsed;
        final int reads;
        final boolean expired;
        Result(Hit hit,float probability,long elapsed,int reads,boolean expired) {
            this.hit=hit;this.probability=probability;this.elapsed=elapsed;this.reads=reads;this.expired=expired;
        }
        String description() {
            return (hit==null?"none":hit.rule+" tap="+hit.x+","+hit.y+" scene="+hit.score+
                    " model="+probability+" cropScale="+hit.cropScale)+" elapsed="+elapsed+"ms reads="+reads+" expired="+expired;
        }
    }

    private HuyaLiveAdCheck(String templates,OnnxUiText ocr,String weights,Path output,long budgetMs)throws Exception {
        this.matcher=SceneRuleCheck.huya(templates);this.ocr=ocr;
        classifier=new AdButtonModelCheck(weights);counterexamples=output;this.budgetMs=budgetMs;
    }
    private static void ok(boolean passed,String name) {
        if(!passed)throw new AssertionError(name);checked++;System.out.println("PASS "+name);
    }
    private Result live(BufferedImage image,boolean ads,boolean cancel) {
        long began=System.nanoTime(),deadline=began+budgetMs*1_000_000L;
        BooleanSupplier bounded=()->cancel || System.nanoTime()>deadline;
        final int[] reads={0};
        UiFeatureSearch.setCancellation(bounded);
        try {
            Frame frame=SceneRuleCheck.frame(image);
            Hit hit=matcher.findLive(frame,ads,(f,b)->{
                if(bounded.getAsBoolean())return null;
                // Use exactly the production adapter's frame-to-original mapping.
                float scale=f.originalWidth/(float)f.width;
                int l=Math.max(0,Math.round(b[0]*scale)),t=Math.max(0,Math.round(b[1]*scale));
                int r=Math.min(f.originalWidth,Math.round(b[2]*scale)),bottom=Math.min(f.originalHeight,Math.round(b[3]*scale));
                int w=r-l,h=bottom-t;if(w<1 || h<1)return null;
                int[] pixels=new int[w*h];
                for(int y=0;y<h;y++)System.arraycopy(f.pixels,(t+y)*f.originalWidth+l,pixels,y*w,w);
                long remaining=Math.min(80,(deadline-System.nanoTime())/1_000_000L);
                if(remaining<=0)return null;
                reads[0]++;
                try {
                    UiControlPolicy.Word word=ocr.readRegion(pixels,w,h,bounded,remaining);
                    System.out.printf(Locale.ROOT,"OCR crop=%d,%d,%d,%d word=%s confidence=%.5f weakest=%.5f%n",
                            l,t,r,bottom,word==null?"<unread>":word.text,word==null?0:word.confidence,word==null?0:word.weakest);
                    return word;
                } catch(Exception error) {throw new IllegalStateException("Real local OCR failed",error);}
            });
            // This matches confirmScene's action model and verified-cross scoring.
            float probability=hit==null?Float.NaN:classifier.probability(frame,hit);
            long elapsed=(System.nanoTime()-began)/1_000_000L;
            return new Result(hit,probability,elapsed,reads[0],System.nanoTime()>deadline);
        } finally {UiFeatureSearch.setCancellation(null);}
    }
    private void baseline(String name,BufferedImage image) {
        UiFeatureSearch.setCancellation(null);
        Hit old=matcher.find(SceneRuleCheck.frame(image),true,false);
        ok(old==null,"legacy matcher misses "+name+" "+image.getWidth()+"x"+image.getHeight());
    }
    private void positive(String name,BufferedImage source,int width,String rule,int x,int y,int tolerance) {
        BufferedImage image=width==source.getWidth()?source:ScreenshotRuleCheck.resize(source,width);
        Result result=live(image,true,false);float scale=width/(float)source.getWidth();
        ok(result.hit!=null && rule.equals(result.hit.rule) && result.hit.frameWidth==image.getWidth() &&
                result.hit.frameHeight==image.getHeight() && Math.abs(result.hit.x-x*scale)<=tolerance*scale &&
                Math.abs(result.hit.y-y*scale)<=tolerance*scale && Float.isFinite(result.probability) &&
                result.probability>=.85f && !result.expired,
                name+" width="+width+" "+result.description());
    }
    private void negative(String name,BufferedImage image)throws Exception {
        ImageIO.write(image,"png",counterexamples.resolve(name+".png").toFile());
        Result result=live(image,true,false);
        // A budget expiry cannot masquerade as a successful safety rejection.
        ok(!result.expired && result.hit==null,"reject "+name+" "+result.description());
    }
    private void gates(String name,BufferedImage image) {
        Result disabled=live(image,false,false),cancelled=live(image,true,true);
        ok(disabled.hit==null && disabled.reads==0,"reject ads disabled "+name);
        ok(cancelled.hit==null && cancelled.reads==0,"reject cancelled scene "+name);
    }
    private static BufferedImage copy(BufferedImage original) {
        BufferedImage image=new BufferedImage(original.getWidth(),original.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();g.drawImage(original,0,0,null);g.dispose();return image;
    }
    private static BufferedImage erase(BufferedImage original,int l,int t,int r,int b,int color) {
        BufferedImage image=copy(original);Graphics2D g=image.createGraphics();g.setColor(new Color(color));
        g.fillRect(l,t,r-l,b-t);g.dispose();return image;
    }
    private static BufferedImage move(BufferedImage original,int l,int t,int r,int b,int x,int y,int eraseColor) {
        BufferedImage image=erase(original,l,t,r,b,eraseColor);
        int w=r-l,h=b-t;int[] pixels=original.getRGB(l,t,w,h,null,0,w);
        image.setRGB(x,y,w,h,pixels,0,w);return image;
    }
    private static BufferedImage landscapeImageCard(BufferedImage landscape,BufferedImage portrait) {
        BufferedImage result=copy(landscape);Graphics2D g=result.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // A layout transform verifies the portrait style in a landscape frame.
        // It is not a separately captured real landscape advertisement.
        g.setColor(new Color(25,20,25));g.fillRect(1720,170,810,930);
        g.drawImage(portrait,1800,190,2506,1072,782,1849,1174,2339,null);g.dispose();return result;
    }
    private static UiControlPolicy.Word word(String text,float confidence,float weakest) {
        return new UiControlPolicy.Word(text,confidence,weakest,0,0,180,40);
    }
    private static String sha256(Path path)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));StringBuilder text=new StringBuilder();
        for(byte value:digest)text.append(String.format(Locale.ROOT,"%02x",value&255));return text.toString();
    }
    private static void timerSemantics() {
        // Pure semantic fixtures exercise the parser; the screenshot Reader above is never mocked.
        boolean range=true;
        for(int seconds=0;seconds<=99;seconds++)for(String unit:new String[]{"s","秒"})
            range&=HuyaVisualMatcher.countdownSeconds(word(seconds+unit,1,1));
        ok(range,"timer grammar accepts every 0..99 seconds with s or 秒");
        ok(HuyaVisualMatcher.countdownSeconds(word(" １３ S ",1,1)),"timer grammar normalizes whitespace, case and full-width digits");
        for(String wrong:new String[]{"","13","13ms","13分","13小时","-1s","100s","999秒","1.5s","13sec","关闭","13s|关闭","13s跳过"})
            ok(!HuyaVisualMatcher.countdownSeconds(word(wrong,1,1)),"timer grammar rejects '"+wrong+"'");
        ok(!HuyaVisualMatcher.countdownSeconds(null) && !HuyaVisualMatcher.countdownSeconds(word("13s",.959f,1)) &&
                !HuyaVisualMatcher.countdownSeconds(word("13s",1,.899f)),"timer grammar rejects missing and uncertain OCR");
    }
    private void screenshotNegatives(BufferedImage landscape,BufferedImage portrait)throws Exception {
        // Coordinates identify mutations only; positive selection still runs the production matcher.
        int capsule=landscape.getRGB(2325,194),blackFooter=landscape.getRGB(1745,990);
        int blue=landscape.getRGB(1910,943),header=landscape.getRGB(2005,194);
        negative("landscape-only-timer-no-close",erase(landscape,2385,195,2486,247,capsule));
        negative("landscape-only-close-no-timer",erase(landscape,2280,195,2366,247,capsule));
        negative("landscape-no-promotion-header",erase(landscape,1808,193,2000,249,header));
        negative("landscape-no-cta-glyph",erase(landscape,2040,916,2190,971,blue));
        negative("landscape-no-cta",erase(landscape,1754,894,2477,986,blackFooter));
        negative("landscape-cta-in-other-branch",move(landscape,1754,894,2477,986,910,894,blackFooter));
        negative("landscape-close-and-timer-detached",move(landscape,2270,184,2500,257,1260,184,capsule));
        int portraitBlue=portrait.getRGB(850,2260),white=portrait.getRGB(793,2308),imageColor=new Color(45,18,75).getRGB();
        negative("portrait-no-white-cross",erase(portrait,1121,1867,1158,1905,imageColor));
        negative("portrait-no-cta-glyph",erase(portrait,907,2243,1050,2293,portraitBlue));
        negative("portrait-no-cta",erase(portrait,809,2219,1148,2309,white));
        negative("portrait-cta-in-other-branch",move(portrait,809,2219,1148,2309,100,2219,white));
        negative("portrait-cross-outside-current-card",move(portrait,1121,1867,1158,1905,650,1867,imageColor));
        negative("portrait-missing-card-top-border",erase(portrait,782,1848,1175,1861,new Color(25,19,28).getRGB()));
    }
    public static void main(String[] args)throws Exception {
        if(args.length<6)throw new IllegalArgumentException("templates assets weights landscape.jpg portrait.jpg work-counterexamples [budget-ms]");
        Path assets=Paths.get(args[1]),output=Paths.get(args[5]);Files.createDirectories(output);
        String modelHash=sha256(assets.resolve("ad_action.tflite")),weightsHash=sha256(Paths.get(args[2]));
        if(!SHIPPED_MODEL.equals(modelHash) || !SHIPPED_WEIGHTS.equals(weightsHash))
            throw new IllegalArgumentException("Use the weights exported from this shipped ad_action.tflite, not close_rank weights: model="+modelHash+" weights="+weightsHash);
        System.out.println("MODEL ad_action.tflite sha256="+modelHash+" exported weights sha256="+weightsHash);
        long budget=args.length>6?Long.parseLong(args[6]):260;
        if(budget<=0)throw new IllegalArgumentException("Positive budget required");
        BufferedImage landscape=ScreenshotRuleCheck.read(args[3]),portrait=ScreenshotRuleCheck.read(args[4]);
        if(landscape==null || portrait==null || landscape.getWidth()!=2640 || landscape.getHeight()!=1216 ||
                portrait.getWidth()!=1216 || portrait.getHeight()!=2640)throw new IllegalArgumentException("Expected the two external 2640x1216 / 1216x2640 regression captures");
        try(OnnxUiText ocr=new OnnxUiText(Files.readAllBytes(assets.resolve("ui_text_det.onnx")),
                Files.readAllBytes(assets.resolve("ui_text_rec.onnx")),
                new String(Files.readAllBytes(assets.resolve("ui_text_keys.txt")),StandardCharsets.UTF_8))) {
            HuyaLiveAdCheck check=new HuyaLiveAdCheck(args[0],ocr,args[2],output,budget);
            check.baseline("landscape countdown card",landscape);check.baseline("portrait image cross card",portrait);
            ocr.warm();timerSemantics();
            for(int width:new int[]{2640,1920,1216})check.positive("landscape countdown close",landscape,width,HuyaVisualMatcher.GAME_AD,2433,220,20);
            for(int width:new int[]{1216,1080,720})check.positive("portrait image cross",portrait,width,HuyaVisualMatcher.PORTRAIT_AD,1139,1884,12);
            check.positive("countdown close with ad label removed",erase(landscape,2400,285,2493,329,new Color(35,15,45).getRGB()),2640,HuyaVisualMatcher.GAME_AD,2433,220,20);
            BufferedImage horizontal=landscapeImageCard(landscape,portrait);
            ImageIO.write(horizontal,"png",output.resolve("landscape-image-card-layout-transform.png").toFile());
            for(int width:new int[]{2640,1216})check.positive("image cross landscape layout transform",horizontal,width,HuyaVisualMatcher.AD,2443,253,20);
            check.screenshotNegatives(landscape,portrait);
            check.gates("landscape",landscape);check.gates("portrait",portrait);
        }
        System.out.println("Huya live checks: "+checked+" passed; real local OCR + action model >=0.85; frame budget="+budget+"ms; counterexamples="+output.toAbsolutePath());
    }
}
