package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.image.BufferedImage;

/** Launch-only header recognition, including the user's low-contrast preloaded advertisement. */
public final class TencentOpeningCheck {
    static void reject(String label,BufferedImage im,TencentVisualMatcher matcher,AdButtonClassifier model) {
        if(matcher.opening(SceneRuleCheck.frame(im),model)!=null)throw new AssertionError(label);
        System.out.println("PASS opening reject "+label);
    }
    public static void main(String[] args)throws Exception {
        var matcher=SceneRuleCheck.tencent(args[0]);var model=new AdButtonModelCheck(args[1]);
        for(int i=2;i<=3;i++) {
            BufferedImage source=ScreenshotRuleCheck.read(args[i]);
            float cx=i==2?1092:1088,cy=i==2?196:218;
            for(int width:i==2?new int[]{291,720,1080,1216,1440}:new int[]{720,1080,1216,1440}) {
                BufferedImage im=ScreenshotRuleCheck.resize(source,width);long start=System.nanoTime();
                Hit hit=matcher.opening(SceneRuleCheck.frame(im),model);float scale=width/1216f;
                if(hit==null || Math.abs(hit.x-cx*scale)>18*scale || Math.abs(hit.y-cy*scale)>18*scale || hit.modelProbability<.98f)
                    throw new AssertionError("source="+i+" width="+width+" "+(hit==null?"none":hit.x+","+hit.y+" probability="+hit.modelProbability));
                System.out.printf("PASS opening source=%d width=%d tap=%d,%d model=%.4f elapsed=%dms%n",i,width,hit.x,hit.y,hit.modelProbability,(System.nanoTime()-start)/1000000);
            }
            BufferedImage im=ScreenshotRuleCheck.resize(source,1216);
            reject("skip absent source="+i,ScreenshotRuleCheck.erase(im,1004,(int)cy-50,160,100,new Color(170,195,175)),matcher,model);
            reject("ad header absent source="+i,ScreenshotRuleCheck.erase(im,540,(int)cy-45,445,90,new Color(170,195,175)),matcher,model);
            reject("model absent source="+i,im,matcher,null);
        }
        for(int i=4;i<args.length;i++)reject("ordinary content source="+i,ScreenshotRuleCheck.read(args[i]),matcher,model);
        BufferedImage loading=new BufferedImage(1216,2640,BufferedImage.TYPE_INT_RGB);
        reject("empty loading page",loading,matcher,model);
    }
}
