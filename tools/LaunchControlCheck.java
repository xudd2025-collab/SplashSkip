package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
/** Alternate word negatives preserve the real ad context, exercising the new stroke fallback. */
public final class LaunchControlCheck {
    public static void main(String[] a)throws Exception {
        var source=ScreenshotRuleCheck.read(a[2]);var t=SceneRuleCheck.tencent(a[0]);var u=SceneRuleCheck.universal(a[0]);var model=new AdButtonModelCheck(a[1]);
        String[] words={"播放","取消","关注","购买","开启","进入","广告","预约","收藏","退出","返回","详情","下一步","跳转","跳绳","路过","打开","同意","设置","更多"};
        int count=0;
        for(String word:words)for(String font:new String[]{"Microsoft YaHei","SimHei","SimSun"}) {
            BufferedImage im=ScreenshotRuleCheck.erase(source,1000,155,175,130,Color.DARK_GRAY);
            Graphics2D g=im.createGraphics();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(font,Font.BOLD,44));g.setColor(Color.WHITE);
            var fm=g.getFontMetrics();g.drawString(word,1086-fm.stringWidth(word)/2,218+(fm.getAscent()-fm.getDescent())/2);g.dispose();
            Frame f=SceneRuleCheck.frame(im);
            Hit th=t.opening(f,model,true),uh=u.findLaunching(f,model,HuyaVisualMatcher.SPLASH,()->false);
            if(th!=null || uh!=null) {
                String label=java.util.Arrays.toString(word.codePoints().toArray());
                Hit hit=th!=null?th:uh;
                System.out.println("accepted wrong word="+label+" tencent="+(th!=null)+" universal="+(uh!=null)+" p="+hit.modelProbability+" scale="+hit.cropScale+" xy="+hit.x+","+hit.y);
                throw new AssertionError("wrong word accepted "+label+" font="+font);
            }
            count++;
        }
        System.out.println("PASS "+count+" non-skip labels rejected while real advertisement cues remain");
    }
}
