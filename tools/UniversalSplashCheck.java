package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Package-independent positive and negative scenes, using actual exported model weights. */
public final class UniversalSplashCheck {
    static void positive(String name,BufferedImage im,UniversalSplashMatcher matcher,AdButtonModelCheck model,int x,int y) {
        for(int width:new int[]{720,1080,1216,1440}) {
            BufferedImage sized=ScreenshotRuleCheck.resize(im,width);long start=System.nanoTime();
            Hit hit=matcher.find(SceneRuleCheck.frame(sized),model,UniversalSplashMatcher.SKIP);
            float scale=width/1216f;
            if(hit==null || Math.abs(hit.x-x*scale)>12*scale || Math.abs(hit.y-y*scale)>12*scale || hit.modelProbability<UniversalSplashMatcher.THRESHOLD)
                throw new AssertionError(name+" width="+width+" "+(hit==null?"none":hit.x+","+hit.y+" p="+hit.modelProbability));
            System.out.printf("PASS universal %s width=%d tap=%d,%d model=%.4f elapsed=%dms%n",name,width,hit.x,hit.y,hit.modelProbability,(System.nanoTime()-start)/1000000);
        }
    }
    static void none(String name,BufferedImage im,UniversalSplashMatcher matcher,AdButtonModelCheck model) {
        long start=System.nanoTime();
        if(matcher.find(SceneRuleCheck.frame(im),model,UniversalSplashMatcher.SKIP)!=null)throw new AssertionError(name);
        System.out.println("PASS reject universal "+name+" elapsed="+(System.nanoTime()-start)/1000000+"ms");
    }
    static BufferedImage moveButton(BufferedImage im,int x,int y) {
        BufferedImage result=ScreenshotRuleCheck.erase(im,970,180,216,106,new Color(15,29,30));
        Graphics2D g=result.createGraphics();g.drawImage(im.getSubimage(1018,202,116,62),x-58,y-31,null);g.dispose();return result;
    }
    public static void main(String[] args)throws Exception {
        UniversalSplashMatcher matcher=SceneRuleCheck.universal(args[0]);AdButtonModelCheck model=new AdButtonModelCheck(args[1]);
        BufferedImage huya=ScreenshotRuleCheck.read(args[2]);
        positive("Huya portrait",huya,matcher,model,1076,233);
        positive("independent upper-left position",moveButton(huya,175,180),matcher,model,175,180);
        positive("independent bottom-right position",moveButton(huya,1070,2370),matcher,model,1070,2370);
        none("no skip",ScreenshotRuleCheck.erase(huya,970,180,216,106,new Color(15,29,30)),matcher,model);
        none("no ad label",ScreenshotRuleCheck.erase(huya,25,2420,161,80,new Color(15,29,30)),matcher,model);
        none("model unavailable",huya,matcher,null);
        if(args.length>3 && args[3].endsWith("083157.jpg")) {
            BufferedImage candy=ScreenshotRuleCheck.read(args[3]);
            positive("Huya textured skip",candy,matcher,model,1076,233);
            none("textured skip removed",ScreenshotRuleCheck.erase(candy,1004,200,142,66,new Color(80,50,30)),matcher,model);
            none("textured ad label removed",ScreenshotRuleCheck.erase(candy,25,2420,161,80,new Color(80,50,30)),matcher,model);
        }
        for(int i=args.length>3 && args[3].endsWith("083157.jpg")?4:3;i<args.length;i++)none("ordinary/landscape scene "+i,ScreenshotRuleCheck.read(args[i]),matcher,model);
    }
}
