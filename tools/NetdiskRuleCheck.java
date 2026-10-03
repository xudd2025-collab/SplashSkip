package com.codex.splashskip;

import java.awt.Color;
import java.awt.image.BufferedImage;

/** External screenshots stay outside the source tree. Exercise actual production matching. */
public final class NetdiskRuleCheck {
    static NetdiskVisualMatcher matcher;
    static BilibiliVisualMatcher.Hit find(BufferedImage image, boolean promo, boolean customer) {
        return matcher.find(new BilibiliVisualMatcher.Frame(image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()),
                image.getWidth(),image.getHeight()), promo, customer);
    }
    static void none(String name, BufferedImage image) {
        BilibiliVisualMatcher.Hit hit = find(image, true, true);
        if (hit != null) throw new AssertionError(name + " matched " + hit.rule + " " + hit.x + "," + hit.y);
        System.out.println("PASS reject " + name);
    }
    public static void main(String[] args) throws Exception {
        matcher = new NetdiskVisualMatcher(ScreenshotRuleCheck.template(args[0],"netdisk_promo_cross",32,32),
                ScreenshotRuleCheck.template(args[0],"netdisk_coupon_title",96,16),
                ScreenshotRuleCheck.template(args[0],"netdisk_customer_cross",24,24),
                ScreenshotRuleCheck.template(args[0],"netdisk_customer_label",64,16));
        for (int i=1; i<args.length; i++) {
            BufferedImage source = ScreenshotRuleCheck.read(args[i]);
            boolean promo = i <= 3;
            for (int width : new int[]{720,1080,1216}) {
                BufferedImage image = ScreenshotRuleCheck.resize(source, width);
                BilibiliVisualMatcher.Hit hit = find(image,true,true);
                String expected = promo ? NetdiskVisualMatcher.PROMO : NetdiskVisualMatcher.CUSTOMER;
                float scale = width/1216f;
                if (hit == null || !expected.equals(hit.rule) || Math.abs(hit.x - (promo ? 1074 : 1178)*scale) > 15*scale ||
                        Math.abs(hit.y - (promo ? 759 : 642)*scale) > 15*scale)
                    throw new AssertionError("sample " + i + " width=" + width + " " + (hit==null ? "none" : hit.rule+" "+hit.x+","+hit.y));
                System.out.printf("PASS sample=%d width=%d %s tap=%d,%d score=%.3f%n",i,width,hit.rule,hit.x,hit.y,hit.score);
            }
            BufferedImage full = ScreenshotRuleCheck.resize(source,1216);
            if (promo) {
                none("coupon without close", ScreenshotRuleCheck.erase(full,1010,690,140,140,Color.GRAY));
                none("cross without coupon title", ScreenshotRuleCheck.erase(full,280,1000,670,120,Color.WHITE));
                if (find(full,false,true)!=null) throw new AssertionError("disabled promo matched");
            } else {
                none("embedded screenshot with no outer close", ScreenshotRuleCheck.erase(full,1145,604,68,74,Color.WHITE));
                none("cross without customer label", ScreenshotRuleCheck.erase(full,970,1025,200,65,Color.BLACK));
                if (find(full,true,false)!=null) throw new AssertionError("disabled screenshot rule matched");
            }
        }
        none("blank page",new BufferedImage(1216,2640,BufferedImage.TYPE_INT_RGB));
    }
}
