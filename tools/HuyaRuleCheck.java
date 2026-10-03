package com.codex.splashskip;
import java.awt.image.BufferedImage;
import java.awt.Color;
public final class HuyaRuleCheck {
    static HuyaVisualMatcher matcher;
    static BilibiliVisualMatcher.Hit find(BufferedImage im,boolean enabled) {
        return matcher.find(new BilibiliVisualMatcher.Frame(im.getRGB(0,0,im.getWidth(),im.getHeight(),null,0,im.getWidth()),im.getWidth(),im.getHeight()),enabled);
    }
    static void none(String name,BufferedImage im) {
        if(find(im,true)!=null)throw new AssertionError(name+" falsely matched");System.out.println("PASS reject "+name);
    }
    public static void main(String[] args)throws Exception {
        matcher=SceneRuleCheck.huya(args[0]);
        BufferedImage source=ScreenshotRuleCheck.read(args[1]);
        for(int width:new int[]{1216,1920,2640}) {
            BufferedImage im=ScreenshotRuleCheck.resize(source,width);BilibiliVisualMatcher.Hit hit=find(im,true);
            float scale=width/1216f;
            if(hit==null || Math.abs(hit.x-1138*scale)>10*scale || Math.abs(hit.y-192*scale)>10*scale)
                throw new AssertionError("width="+width+" "+(hit==null?"none":hit.x+","+hit.y));
            System.out.printf("PASS huya width=%d tap=%d,%d score=%.3f%n",width,hit.x,hit.y,hit.score);
            if(find(im,false)!=null)throw new AssertionError("disabled rule matched");
        }
        BufferedImage base=ScreenshotRuleCheck.resize(source,1216);
        none("no close",ScreenshotRuleCheck.erase(base,1120,174,36,40,Color.WHITE));
        none("no download label",ScreenshotRuleCheck.erase(base,1010,399,112,37,Color.BLUE));
        none("no white panel context",ScreenshotRuleCheck.erase(base,974,165,33,23,Color.BLACK));
        for(int i=2;i<args.length;i++)none("portrait page",ScreenshotRuleCheck.read(args[i]));
    }
}
