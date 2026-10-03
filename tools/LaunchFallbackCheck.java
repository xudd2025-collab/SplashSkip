package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.image.BufferedImage;

/** Exercises actual launch entry points, including backgrounds and an unrelated overlay. */
public final class LaunchFallbackCheck {
    public static void main(String[] a)throws Exception {
        var t=SceneRuleCheck.tencent(a[0]);var u=SceneRuleCheck.universal(a[0]);var model=new AdButtonModelCheck(a[1]);
        for(int i=2;i<=4;i++) {
            BufferedImage source=ScreenshotRuleCheck.read(a[i]);final boolean huya=i==4;
            SceneRuleCheck.Find find=im->huya?u.findLaunching(SceneRuleCheck.frame(im),model,HuyaVisualMatcher.SPLASH,()->false):t.opening(SceneRuleCheck.frame(im),model,true);
            for(int width:new int[]{720,1080,1216,1440}) {
                long start=System.nanoTime();Hit h=find.run(ScreenshotRuleCheck.resize(source,width));
                if(h==null || Math.abs(h.x/((float)width/1216)-1080)>45 || h.y/((float)width/1216)<180 || h.y/((float)width/1216)>255)
                    throw new AssertionError("launch source="+i+" width="+width+" "+(h==null?"none":h.x+","+h.y));
                System.out.printf("PASS launch source=%d width=%d tap=%d,%d p=%.4f ms=%d%n",i,width,h.x,h.y,h.modelProbability,(System.nanoTime()-start)/1000000);
            }
            BufferedImage im=ScreenshotRuleCheck.resize(source,1216);
            SceneRuleCheck.none("launch skip absent source="+i,ScreenshotRuleCheck.erase(im,1000,155,175,130,Color.GRAY),find);
            BufferedImage noContext=ScreenshotRuleCheck.erase(im,0,130,995,170,Color.GRAY);
            noContext=ScreenshotRuleCheck.erase(noContext,0,1900,1216,650,Color.GRAY);
            SceneRuleCheck.none("launch all ad cues absent source="+i,noContext,find);
            for(int half=0;half<2;half++) {
                BufferedImage partial=ScreenshotRuleCheck.erase(noContext,0,285,1216,1600,Color.GRAY);
                var g=partial.createGraphics();int x=half==0?475:608;
                g.drawImage(im,x,2190,x+133,2320,x,2190,x+133,2320,null);g.dispose();
                SceneRuleCheck.none("only half interaction text source="+i+" half="+half,partial,find);
            }
            BufferedImage replacement=ScreenshotRuleCheck.erase(im,0,285,1216,1600,new Color(35,93,127));
            if(find.run(replacement)==null)throw new AssertionError("creative changed source="+i);
            System.out.println("PASS replaced creative source="+i);
        }
        for(int i=5;i<a.length;i++) {
            var f=SceneRuleCheck.frame(ScreenshotRuleCheck.read(a[i]));
            if(t.opening(f,model,true)!=null || u.findLaunching(f,model,HuyaVisualMatcher.SPLASH,()->false)!=null)
                throw new AssertionError("normal content accepted source="+i);
            System.out.println("PASS launch rejects normal content source="+i);
        }
    }
}
