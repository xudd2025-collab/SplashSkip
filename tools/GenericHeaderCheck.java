package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public final class GenericHeaderCheck {
    static void none(String label,BufferedImage im,TencentVisualMatcher t,UniversalSplashMatcher u,AdButtonModelCheck m) {
        if(t.opening(SceneRuleCheck.frame(im),m)!=null || u.find(SceneRuleCheck.frame(im),m,UniversalSplashMatcher.SKIP)!=null)
            throw new AssertionError(label);
        System.out.println("PASS reject "+label);
    }
    public static void main(String[] a)throws Exception {
        var t=SceneRuleCheck.tencent(a[0]);var u=SceneRuleCheck.universal(a[0]);var m=new AdButtonModelCheck(a[1]);
        var source=ScreenshotRuleCheck.read(a[2]);
        for(int width:new int[]{720,1080,1216,1440}) {
            var im=ScreenshotRuleCheck.resize(source,width);float scale=width/1216f;
            for(int mode=0;mode<2;mode++) {
                Hit hit=mode==0?t.opening(SceneRuleCheck.frame(im),m):u.find(SceneRuleCheck.frame(im),m,UniversalSplashMatcher.SKIP);
                if(hit==null || Math.abs(hit.x-1086*scale)>18*scale || Math.abs(hit.y-218*scale)>18*scale)
                    throw new AssertionError("source width="+width+" mode="+mode);
                System.out.println("PASS generic header width="+width+" mode="+mode+" tap="+hit.x+","+hit.y+" p="+hit.modelProbability);
            }
        }
        var original=ScreenshotRuleCheck.resize(source,1216);
        none("actual skip removed",ScreenshotRuleCheck.erase(original,997,150,190,145,new Color(85,85,70)),t,u,m);
        none("ad label removed",ScreenshotRuleCheck.erase(original,28,183,110,62,new Color(85,85,70)),t,u,m);
        none("first ad character absent",ScreenshotRuleCheck.erase(original,35,183,41,61,new Color(85,85,70)),t,u,m);
        none("second ad character absent",ScreenshotRuleCheck.erase(original,76,183,48,61,new Color(85,85,70)),t,u,m);
        for(String word:new String[]{"警告","广播","视频","播放","推荐","设置","取消","更新","返回"}) {
            BufferedImage im=ScreenshotRuleCheck.erase(original,28,183,110,62,new Color(85,85,70));
            Graphics2D g=im.createGraphics();g.setFont(new Font("Microsoft YaHei",Font.BOLD,32));
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);var metrics=g.getFontMetrics();g.drawString(word,77-metrics.stringWidth(word)/2,218);g.dispose();
            none("non-ad label "+word,im,t,u,m);
        }
        Frame blank=SceneRuleCheck.frame(new BufferedImage(1216,2640,BufferedImage.TYPE_INT_RGB));
        Hit candidate=new Hit("test",1086,218,0,1216,2640);
        if(SkipGlyphVerifier.accepts(blank,candidate,SceneRuleCheck.glyphs(a[0]),1,.68f))throw new AssertionError("Blank region accepted as glyph");
        System.out.println("PASS blank glyph region rejected");
    }
}
