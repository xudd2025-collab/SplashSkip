package com.codex.splashskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BilibiliRules {
    static final String PACKAGE = "tv.danmaku.bili";
    private final BilibiliVisualMatcher matcher;
    BilibiliRules(Context context) {
        matcher = new BilibiliVisualMatcher(load(context, R.drawable.bili_ad_cross, 32, 32),
                load(context, R.drawable.bili_ad_menu, 32, 32), load(context, R.drawable.bili_sheet_handle, 48, 8),
                load(context, R.drawable.bili_ad_label, 32, 20), load(context, R.drawable.bili_auto_live, 80, 16),
                load(context, R.drawable.bili_live_cancel, 40, 20)).withAdGlyphs(InAppSceneRules.glyphs(context,R.drawable.generic_ad_glyphs));
    }
    static boolean ads(Context context) {
        SharedPreferences p = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        return p.getBoolean("enabled", true) && AppProfiles.enabled(context) && p.getBoolean("bili_close_ads", true);
    }
    static boolean live(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("bili_cancel_live", false);
    }
    BilibiliVisualMatcher.Hit image(Bitmap screen, boolean ads, boolean live) {
        int width = screen.getWidth(), height = screen.getHeight();
        int[] pixels = new int[width * height]; screen.getPixels(pixels, 0, width, 0, 0, width, height);
        return matcher.find(new BilibiliVisualMatcher.Frame(pixels, width, height), ads, live);
    }
    static final class NodeHit {
        final AccessibilityNodeInfo node;
        final String rule;
        final Rect bounds;
        NodeHit(AccessibilityNodeInfo node, String rule, Rect bounds) { this.node = node; this.rule = rule; this.bounds = bounds; }
    }
    NodeHit node(AccessibilityNodeInfo root, int width, int height, boolean ads, boolean live) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>(); collect(root, nodes, 0);
        AccessibilityNodeInfo auto = null;
        boolean adLabel = false, adDetails = false;
        for (AccessibilityNodeInfo n : nodes) {
            String label = label(n);
            if (label.contains("自动进入直播间")) auto = n;
            if (label.equals("广告")) adLabel = true;
            if (label.contains("开发者") || label.contains("权限") && label.contains("隐私") || label.contains("前往观看") || label.equals("UP主推荐")) adDetails = true;
        }
        if (live && auto != null) {
            Rect title = bounds(auto);
            for (AccessibilityNodeInfo n : nodes) {
                Rect rect = bounds(n);
                if (label(n).equals("取消") && rect.centerY() > title.centerY() &&
                        rect.centerY() - title.centerY() < height * .14f &&
                        Math.abs(rect.centerX() - title.centerX()) < width * .2f && valid(rect, width, height))
                    return new NodeHit(n, BilibiliVisualMatcher.LIVE, rect);
            }
        }
        if (ads && adLabel && adDetails) {
            for (AccessibilityNodeInfo n : nodes) {
                String id = String.valueOf(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                String label = label(n);
                Rect rect = bounds(n);
                boolean adId = id.contains("/ad_") || id.contains("_ad_") || id.contains("advert") || id.contains("commercial") || id.contains("promotion");
                boolean closeId = id.endsWith("close") || id.endsWith("close_button") || id.endsWith("close_btn");
                if ((label.equals("关闭广告") || adId && closeId) &&
                        rect.centerX() > width * .84f && rect.centerY() > height * .15f && rect.centerY() < height * .76f &&
                        rect.width() < width * .16f && rect.height() < height * .08f && valid(rect, width, height))
                    return new NodeHit(n, BilibiliVisualMatcher.AD, rect);
            }
        }
        return null;
    }
    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result, int depth) {
        if (node == null || depth > 24 || result.size() >= 280) return;
        if (node.isVisibleToUser()) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), result, depth + 1);
    }
    private static String label(AccessibilityNodeInfo node) {
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        return text.isEmpty() && node.getContentDescription() != null ? node.getContentDescription().toString().trim() : text;
    }
    private static Rect bounds(AccessibilityNodeInfo node) { Rect r = new Rect(); node.getBoundsInScreen(r); return r; }
    private static boolean valid(Rect rect, int width, int height) {
        return !rect.isEmpty() && rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height;
    }
    private static BilibiliVisualMatcher.Template load(Context context, int id, int columns, int rows) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false;
        Bitmap image = BitmapFactory.decodeResource(context.getResources(), id, options);
        int w = image.getWidth(), h = image.getHeight(); int[] pixels = new int[w * h];
        image.getPixels(pixels, 0, w, 0, 0, w, h); image.recycle();
        return new BilibiliVisualMatcher.Template(pixels, w, h, columns, rows);
    }
}
