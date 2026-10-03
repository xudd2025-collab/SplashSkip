package com.codex.splashskip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

final class NetdiskRules {
    static final String PACKAGE = "com.baidu.netdisk";
    private final NetdiskVisualMatcher matcher;
    NetdiskRules(Context context) {
        matcher = new NetdiskVisualMatcher(template(context, R.drawable.netdisk_promo_cross, 32, 32),
                template(context, R.drawable.netdisk_coupon_title, 96, 16),
                template(context, R.drawable.netdisk_customer_cross, 24, 24),
                template(context, R.drawable.netdisk_customer_label, 64, 16));
    }
    static boolean promo(Context c) { return enabled(c) && c.getSharedPreferences("settings", 0).getBoolean("netdisk_promo", true); }
    static boolean customer(Context c) { return enabled(c) && c.getSharedPreferences("settings", 0).getBoolean("netdisk_screenshot", true); }
    private static boolean enabled(Context c) {
        return AppProfiles.enabled(c) && c.getSharedPreferences("settings", 0).getBoolean("enabled", true);
    }
    private static BilibiliVisualMatcher.Template template(Context c, int id, int columns, int rows) {
        Bitmap bitmap = BitmapFactory.decodeResource(c.getResources(), id);
        int w = bitmap.getWidth(), h = bitmap.getHeight(); int[] pixels = new int[w*h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h); bitmap.recycle();
        return new BilibiliVisualMatcher.Template(pixels, w, h, columns, rows);
    }
    BilibiliVisualMatcher.Hit image(Bitmap bitmap, boolean promo, boolean customer) {
        int w = bitmap.getWidth(), h = bitmap.getHeight(); int[] pixels = new int[w*h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        return matcher.find(new BilibiliVisualMatcher.Frame(pixels, w, h), promo, customer);
    }
}
