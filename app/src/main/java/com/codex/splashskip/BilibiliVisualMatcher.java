package com.codex.splashskip;

/** Local UI feature matching, independent of Android so screenshot regressions can run on a PC. */
final class BilibiliVisualMatcher {
    static final String AD = "bili-ad-close", LIVE = "bili-live-cancel";
    static final class Hit {
        final String rule;
        final int x, y;
        final float score;
        Hit(String rule, int x, int y, float score) { this.rule = rule; this.x = x; this.y = y; this.score = score; }
    }
    static final class Frame {
        final int width = 608, height, originalWidth, originalHeight;
        final float[] gray;
        Frame(int[] pixels, int w, int h) {
            originalWidth = w; originalHeight = h;
            height = Math.round(h * width / (float) w);
            gray = new float[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int c = pixels[Math.min(h - 1, Math.round(y * h / (float) height)) * w +
                        Math.min(w - 1, Math.round(x * w / (float) width))];
                gray[y * width + x] = luminance(c);
            }
        }
        Hit hit(String rule, float x, float y, float score) {
            return new Hit(rule, Math.round(x * originalWidth / width), Math.round(y * originalHeight / height), score);
        }
    }
    static final class Template {
        final float width, height;
        final int columns, rows;
        final float[] normalized;
        final double norm;
        Template(int[] pixels, int w, int h, int columns, int rows) {
            width = w / 2f; height = h / 2f; this.columns = columns; this.rows = rows;
            normalized = new float[columns * rows];
            float mean = 0;
            for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) {
                float value = luminance(pixels[Math.min(h - 1, (int)((y + .5f) * h / rows)) * w +
                        Math.min(w - 1, (int)((x + .5f) * w / columns))]);
                normalized[y * columns + x] = value; mean += value;
            }
            mean /= normalized.length;
            double squared = 0;
            for (int i = 0; i < normalized.length; i++) { normalized[i] -= mean; squared += normalized[i] * normalized[i]; }
            norm = Math.sqrt(squared);
        }
    }
    private final Template cross, menu, handle, adLabel, live, cancel;
    BilibiliVisualMatcher(Template cross, Template menu, Template handle, Template adLabel, Template live, Template cancel) {
        this.cross = cross; this.menu = menu; this.handle = handle; this.adLabel = adLabel;
        this.live = live; this.cancel = cancel;
    }
    Hit find(Frame frame, boolean ads, boolean liveCancel) {
        if (frame.height < frame.width * 1.65f || frame.height > frame.width * 2.65f) return null;
        Hit hit = liveCancel ? findLive(frame) : null;
        return hit != null ? hit : ads ? findAd(frame) : null;
    }
    private Hit findAd(Frame f) {
        float best = .82f;
        Hit result = null;
        for (int y = (int)(f.height * .18f); y < f.height * .76f; y += 4)
            for (int x = (int)(f.width * .88f); x < f.width * .97f; x += 4) {
                // A white sheet, its close circle, neighbouring menu, drag handle and ad label must agree.
                if (grayAt(f, x - 24, y + 46) < 225) continue;
                float score = correlate(f, cross, x, y);
                if (score < .52f) continue;
                float[] close = refine(f, cross, x, y, 4);
                if (close[2] < best || refine(f, menu, close[0] - 72, close[1], 4)[2] < .76f ||
                        refine(f, handle, f.width / 2f - 1.75f, close[1] - 45.75f, 4)[2] < .70f) continue;
                boolean ad = false;
                for (int ay = (int)close[1] + 80; ay < f.height - 44 && !ad; ay += 4)
                    for (int ax = 26; ax < 58 && !ad; ax += 4) {
                        if (correlate(f, adLabel, ax, ay) > .45f && refine(f, adLabel, ax, ay, 4)[2] > .80f) ad = true;
                    }
                if (ad) { best = close[2]; result = f.hit(AD, close[0], close[1], best); }
            }
        return result;
    }
    private Hit findLive(Frame f) {
        Hit result = null;
        float best = .83f;
        for (int y = (int)(f.height * .35f); y < f.height * .79f; y += 4)
            for (int x = (int)(f.width * .37f); x < f.width * .58f; x += 4) {
                if (correlate(f, live, x, y) < .45f) continue;
                float[] title = refine(f, live, x, y, 4);
                if (title[2] < best) continue;
                float[] stop = refine(f, cancel, title[0] - 8.5f, title[1] + 75.25f, 7);
                if (stop[2] < .82f || grayAt(f, stop[0] - cancel.width, stop[1]) > 100 ||
                        grayAt(f, stop[0] + cancel.width, stop[1]) > 100) continue;
                best = title[2]; result = f.hit(LIVE, stop[0], stop[1], Math.min(best, stop[2]));
            }
        return result;
    }
    private float[] refine(Frame f, Template t, float x, float y, int range) {
        float[] best = {x, y, -1};
        for (int dy = -range * 2; dy <= range * 2; dy++) for (int dx = -range * 2; dx <= range * 2; dx++) {
            float px = x + dx / 2f, py = y + dy / 2f;
            float value = correlate(f, t, px, py);
            if (value > best[2]) { best[0] = px; best[1] = py; best[2] = value; }
        }
        return best;
    }
    private static float correlate(Frame f, Template t, float cx, float cy) {
        float left = cx - t.width / 2f, top = cy - t.height / 2f;
        if (left < 0 || top < 0 || left + t.width >= f.width || top + t.height >= f.height) return -1;
        double sum = 0, square = 0, dot = 0;
        for (int y = 0, i = 0; y < t.rows; y++) for (int x = 0; x < t.columns; x++, i++) {
            int px = (int)(left + (x + .5f) * t.width / t.columns);
            int py = (int)(top + (y + .5f) * t.height / t.rows);
            float value = f.gray[py * f.width + px];
            sum += value; square += value * value; dot += value * t.normalized[i];
        }
        double variance = square - sum * sum / t.normalized.length;
        return variance < 1 || t.norm < 1 ? -1 : (float)(dot / (Math.sqrt(variance) * t.norm));
    }
    private static float grayAt(Frame f, float x, float y) {
        int ix = Math.round(x), iy = Math.round(y);
        return ix < 0 || iy < 0 || ix >= f.width || iy >= f.height ? 0 : f.gray[iy * f.width + ix];
    }
    private static float luminance(int color) {
        return ((color >> 16) & 255) * .299f + ((color >> 8) & 255) * .587f + (color & 255) * .114f;
    }
}
