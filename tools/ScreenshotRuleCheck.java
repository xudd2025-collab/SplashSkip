package com.codex.splashskip;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Runs the production matcher against external screenshots without bundling personal images. */
public final class ScreenshotRuleCheck {
    static BilibiliVisualMatcher matcher;
    static BufferedImage read(String path) throws Exception { return ImageIO.read(new File(path)); }
    static BilibiliVisualMatcher.Template template(String folder, String name, int w, int h) throws Exception {
        BufferedImage image = read(folder + "/" + name + ".png");
        return new BilibiliVisualMatcher.Template(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()),
                image.getWidth(), image.getHeight(), w, h);
    }
    static BilibiliVisualMatcher.Hit find(BufferedImage image, boolean ad, boolean live) {
        return matcher.find(new BilibiliVisualMatcher.Frame(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()),
                image.getWidth(), image.getHeight()), ad, live);
    }
    static BufferedImage resize(BufferedImage original, int width) {
        BufferedImage result = new BufferedImage(width, Math.round(original.getHeight() * width / (float)original.getWidth()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, result.getWidth(), result.getHeight(), null); g.dispose(); return result;
    }
    static BufferedImage erase(BufferedImage original, int left, int top, int width, int height, java.awt.Color color) {
        BufferedImage copy = resize(original, original.getWidth()); Graphics2D g = copy.createGraphics();
        g.setColor(color); g.fillRect(left, top, width, height); g.dispose(); return copy;
    }
    static void expectNone(String name, BufferedImage image) {
        BilibiliVisualMatcher.Hit hit = find(image, true, true);
        if (hit != null) throw new AssertionError(name + " falsely matched " + hit.rule + " at " + hit.x + "," + hit.y);
        System.out.println("PASS reject " + name);
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("template-folder ad-screenshot live-screenshot [negative-screenshots...]");
        matcher = new BilibiliVisualMatcher(template(args[0], "bili_ad_cross", 32, 32), template(args[0], "bili_ad_menu", 32, 32),
                template(args[0], "bili_sheet_handle", 48, 8), template(args[0], "bili_ad_label", 32, 20),
                template(args[0], "bili_auto_live", 80, 16), template(args[0], "bili_live_cancel", 40, 20)).withAdGlyphs(SceneRuleCheck.glyphs(args[0],"generic_ad_glyphs"));
        BufferedImage ad = read(args[1]), live = read(args[2]);
        for (int width : new int[]{720, 1080, 1216, 1260}) {
            for (int i = 0; i < 2; i++) {
                BufferedImage source = i == 0 ? ad : live;
                long start = System.nanoTime();
                BilibiliVisualMatcher.Hit hit = find(resize(source, width), true, true);
                String rule = i == 0 ? BilibiliVisualMatcher.AD : BilibiliVisualMatcher.LIVE;
                float scale = width / 1216f;
                if (hit == null || !rule.equals(hit.rule) || Math.abs(hit.x - (i == 0 ? 1133 : 521) * scale) > 16 * scale ||
                        Math.abs(hit.y - (i == 0 ? 950 : 1736) * scale) > 16 * scale)
                    throw new AssertionError(rule + " wrong/missing at width " + width + ": " + (hit == null ? "none" : hit.rule + " " + hit.x + "," + hit.y));
                System.out.printf("PASS %s width=%d tap=%d,%d score=%.3f elapsed=%dms%n", rule, width, hit.x, hit.y, hit.score, (System.nanoTime() - start) / 1000000);
            }
        }
        expectNone("advertisement with no ad label", erase(ad, 30, 1980, 100, 65, java.awt.Color.WHITE));
        expectNone("advertisement with no close button", erase(ad, 1080, 898, 110, 110, java.awt.Color.WHITE));
        expectNone("live preview with no cancel button", erase(live, 450, 1695, 145, 90, java.awt.Color.BLACK));
        expectNone("cancel button without auto-enter label", erase(live, 280, 1540, 525, 100, java.awt.Color.BLACK));
        if (find(ad, false, false) != null || find(live, true, false) != null) throw new AssertionError("Disabled rule matched");
        System.out.println("PASS rule switches");
        for (int i = 3; i < args.length; i++) expectNone(new File(args[i]).getName(), read(args[i]));
    }
}
