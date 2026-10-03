package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public final class AdaptivePopupCheck {
    public static void main(String[] a)throws Exception {
        var mobile=SceneRuleCheck.mobile(a[0]);var huya=SceneRuleCheck.huya(a[0]);var model=new AdButtonModelCheck(a[1]);
        for(int i=2;i<=3;i++) {
            boolean isMobile=i==2;var source=ScreenshotRuleCheck.read(a[i]);
            SceneRuleCheck.Find find=im->isMobile?mobile.find(SceneRuleCheck.frame(im)):huya.find(SceneRuleCheck.frame(im),true,true);
            for(int width:new int[]{480,720,1080,1216,1440,2160}) {
                var im=ScreenshotRuleCheck.resize(source,width);long start=System.nanoTime();var hit=find.run(im);float scale=width/1216f;
                if(hit==null || Math.abs(hit.x/scale-(isMobile?1078:1140))>15 || Math.abs(hit.y/scale-(isMobile?503:1780))>15)
                    throw new AssertionError("source="+i+" width="+width+" "+(hit==null?"none":hit.rule+" "+hit.x+","+hit.y));
                float p=model.probability(SceneRuleCheck.frame(im),hit);
                System.out.printf("source=%d width=%d tap=%d,%d scene=%.3f model=%.5f ms=%d%n",i,width,hit.x,hit.y,hit.score,p,(System.nanoTime()-start)/1000000);
                if(p<.85f)throw new AssertionError("production model gate source="+i+" width="+width+" p="+p);
            }
            var im=ScreenshotRuleCheck.resize(source,1216);
            SceneRuleCheck.none("actual X absent source="+i,ScreenshotRuleCheck.erase(im,isMobile?1042:1115,isMobile?462:1754,isMobile?76:52,isMobile?82:52,isMobile?Color.GRAY:Color.WHITE),find);
            if(isMobile) {
                SceneRuleCheck.none("promotion card absent",ScreenshotRuleCheck.erase(im,180,715,850,1145,Color.GRAY),find);
                SceneRuleCheck.none("previous navigation absent",ScreenshotRuleCheck.erase(im,330,2060,175,90,Color.GRAY),find);
                SceneRuleCheck.none("next navigation absent",ScreenshotRuleCheck.erase(im,716,2060,185,90,Color.GRAY),find);
            } else {
                SceneRuleCheck.none("download text absent",ScreenshotRuleCheck.erase(im,880,2230,200,67,new Color(62,101,255)),find);
                SceneRuleCheck.none("no white card",ScreenshotRuleCheck.erase(im,782,1730,393,607,new Color(50,50,50)),find);
                if(huya.find(SceneRuleCheck.frame(im),false,true)!=null)throw new AssertionError("disabled ads matched");
                var changed=ScreenshotRuleCheck.erase(im,820,1795,300,420,new Color(178,98,78));
                if(find.run(changed)==null)throw new AssertionError("replacement game creative rejected");
            }
        }
        for(int i=4;i<a.length;i++) {
            var im=ScreenshotRuleCheck.read(a[i]);
            if(mobile.find(SceneRuleCheck.frame(im))!=null)throw new AssertionError("mobile matched unrelated page");
        }
    }
}
