package com.codex.splashskip;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Replace the entire creative body without retraining; the controls and ad label are unchanged. */
public final class CreativeVariationCheck {
    public static void main(String[] a)throws Exception {
        var matcher=SceneRuleCheck.tencent(a[0]);var model=new AdButtonModelCheck(a[1]);int count=0;
        for(int source=2;source<a.length;source++) {
            BufferedImage original=ScreenshotRuleCheck.read(a[source]);
            for(int style=0;style<4;style++)for(int width:new int[]{720,1080,1216,1440}) {
                BufferedImage im=ScreenshotRuleCheck.resize(original,width);
                var expected=matcher.opening(SceneRuleCheck.frame(im),model);
                if(expected==null)throw new AssertionError("Unrecognized source="+source+" width="+width);
                Graphics2D g=im.createGraphics();
                g.setColor(new Color[]{Color.GREEN,Color.BLACK,Color.WHITE,Color.ORANGE}[style]);
                g.fillRect(0,(int)(im.getHeight()*.13),width,im.getHeight());
                g.setColor(Color.MAGENTA);g.fillOval(width/4,im.getHeight()/3,width/2,im.getHeight()/3);g.dispose();
                var hit=matcher.opening(SceneRuleCheck.frame(im),model);
                if(hit==null || Math.abs(hit.x-expected.x)>4 || Math.abs(hit.y-expected.y)>4)
                    throw new AssertionError("Creative changed detection source="+source+" style="+style+" width="+width);
                count++;
            }
        }
        System.out.println("PASS "+count+" wholly replaced creative bodies; no training changes; actual skip coordinates retained");
    }
}
