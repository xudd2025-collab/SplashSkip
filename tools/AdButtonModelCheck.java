package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Checks the learned fallback with the exported candidate weights and source-separated scenes. */
public final class AdButtonModelCheck implements AdButtonClassifier {
    private final float[] weights;
    private final int hiddenSize;
    private AdButtonModelCheck fallback;
    AdButtonModelCheck(String file)throws Exception {
        String[] files=file.split("\\|",2);
        ByteBuffer buffer=ByteBuffer.wrap(Files.readAllBytes(Paths.get(files[0]))).order(ByteOrder.LITTLE_ENDIAN);
        weights=new float[buffer.remaining()/4];buffer.asFloatBuffer().get(weights);
        hiddenSize=(weights.length-3)/644;
        if((hiddenSize!=32 && hiddenSize!=64) || weights.length!=hiddenSize*644+3)throw new IllegalArgumentException("Model dimensions");
        if(files.length==2)fallback=new AdButtonModelCheck(files[1]);
    }
    @Override public float probability(Frame f,Hit candidate) {
        int[] box=AdActionRegion.box(candidate,f.originalWidth,f.originalHeight);if(box==null)return 0;
        BufferedImage crop=new BufferedImage(box[2]-box[0],box[3]-box[1],BufferedImage.TYPE_INT_RGB);
        crop.setRGB(0,0,crop.getWidth(),crop.getHeight(),f.pixels,box[1]*f.originalWidth+box[0],f.originalWidth);
        float p=score(crop,AdActionRegion.classIndex(candidate.rule));
        if(ChinaMobileVisualMatcher.CLOSE.equals(candidate.rule) || TencentFeedVisualMatcher.FEED.equals(candidate.rule) || HuyaVisualMatcher.PORTRAIT_AD.equals(candidate.rule) || HuyaVisualMatcher.AD.equals(candidate.rule) && Math.abs(candidate.cropScale-1f)>.05f) {
            for(int threshold:new int[]{215,190,235}) {
                crop.setRGB(0,0,crop.getWidth(),crop.getHeight(),ButtonStrokeInput.crop(f,box,threshold),0,crop.getWidth());
                p=Math.max(p,score(crop,2));if(p>=.98f)break;
            }
        }
        return p;
    }
    @Override public float strokeProbability(Frame f,Hit candidate) {
        float best=strokeCrop(f,candidate);
        if(best<.98f && Math.abs(candidate.cropScale-1f)>.05f)best=Math.max(best,strokeCrop(f,candidate.withCropScale(1f)));
        return best;
    }
    private float strokeCrop(Frame f,Hit candidate) {
        int[] box=AdActionRegion.box(candidate,f.originalWidth,f.originalHeight);if(box==null)return 0;
        int w=box[2]-box[0],h=box[3]-box[1];float best=0;
        for(int threshold:new int[]{215,190,235}) {
            int[] strokes=ButtonStrokeInput.crop(f,box,threshold);
            for(int polarity=0;polarity<2;polarity++) {
                BufferedImage im=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                im.setRGB(0,0,w,h,polarity==0?strokes:ButtonStrokeInput.inverted(strokes),0,w);
                best=Math.max(best,score(im,0));if(best>=.98f)break;
            }
            if(best>=.98f)break;
        }
        return best;
    }
    private float score(BufferedImage crop,int index) {
        if(index==0 && fallback!=null)return fallback.score(crop,index);
        BufferedImage small=new BufferedImage(32,20,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=small.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(crop,0,0,32,20,null);g.dispose();
        float[] x=new float[640];float mean=0;
        for(int i=0;i<x.length;i++) {
            int c=small.getRGB(i%32,i/32);x[i]=((c>>16)&255)*.299f+((c>>8)&255)*.587f+(c&255)*.114f;mean+=x[i];
        }
        mean/=x.length;float variance=0;for(float v:x)variance+=(v-mean)*(v-mean);
        float scale=(float)Math.sqrt(variance/x.length)+20;
        for(int i=0;i<x.length;i++)x[i]=Math.max(-3,Math.min(3,(x[i]-mean)/scale));
        float[] hidden=new float[hiddenSize];
        for(int i=0;i<hiddenSize;i++) {
            float v=weights[hiddenSize*640+i];for(int j=0;j<640;j++)v+=weights[i*640+j]*x[j];hidden[i]=Math.max(0,v);
        }
        int offset=hiddenSize*640+hiddenSize;
        float v=weights[offset+3*hiddenSize+index];for(int i=0;i<hiddenSize;i++)v+=weights[offset+index*hiddenSize+i]*hidden[i];
        float probability=(float)(1/(1+Math.exp(-v)));
        return probability;
    }
    static void none(String name,BufferedImage im,TencentVisualMatcher t,AdButtonModelCheck model) {
        if(t.find(SceneRuleCheck.frame(im),true,true,true,model)!=null)throw new AssertionError(name);
        System.out.println("PASS reject with AI "+name);
    }
    public static void main(String[] args)throws Exception {
        TencentVisualMatcher t=SceneRuleCheck.tencent(args[0]);HuyaVisualMatcher h=SceneRuleCheck.huya(args[0]);
        AdButtonModelCheck model=new AdButtonModelCheck(args[1]);
        BufferedImage black=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(args[2]),1216);
        BufferedImage alternative=ScreenshotRuleCheck.erase(black,1027,180,120,77,new Color(30,30,30));
        Graphics2D g=alternative.createGraphics();g.setColor(Color.WHITE);g.setFont(new Font("SimSun",Font.PLAIN,36));
        g.drawString("跳过",1050,231);g.dispose();
        if(t.find(SceneRuleCheck.frame(alternative),true,false,false)!=null)throw new AssertionError("Changed glyph still matches template; choose a distinct regression case");
        for(int width:new int[]{720,1080,1260,1440}) {
            BufferedImage im=ScreenshotRuleCheck.resize(alternative,width);
            Hit hit=t.find(SceneRuleCheck.frame(im),true,false,false,model);
            if(hit==null || !TencentVisualMatcher.SPLASH.equals(hit.rule))throw new AssertionError("AI failed to find changed skip glyph width="+width);
            float p=model.probability(SceneRuleCheck.frame(im),hit);if(p<.98f)throw new AssertionError("confidence "+p);
            System.out.println("PASS independent AI skip candidate width="+width+" probability="+p+" tap="+hit.x+","+hit.y);
        }
        none("changed glyph without ad label",ScreenshotRuleCheck.erase(alternative,812,180,174,76,Color.BLACK),t,model);
        none("changed glyph without interactive prompt",ScreenshotRuleCheck.erase(alternative,468,2198,282,102,Color.BLACK),t,model);
        none("missing skip button",ScreenshotRuleCheck.erase(black,1010,174,148,87,Color.DARK_GRAY),t,model);
        BufferedImage feed=ScreenshotRuleCheck.read(args[3]);
        for(int width:new int[]{720,1080,1260,1440})none("drama recommendation width="+width,ScreenshotRuleCheck.resize(feed,width),t,model);
        BufferedImage game=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(args[4]),1216),fish=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(args[5]),1216);
        for(BufferedImage im:new BufferedImage[]{ScreenshotRuleCheck.erase(game,1096,86,52,32,Color.DARK_GRAY),ScreenshotRuleCheck.erase(fish,1090,337,16,17,Color.GRAY)})
            if(h.find(SceneRuleCheck.frame(im),true,true)!=null)throw new AssertionError("Disappeared Huya button passed the required glyph check");
        System.out.println("PASS Huya requires an actual close glyph before model confirmation");
    }
}
