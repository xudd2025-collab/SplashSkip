package com.codex.splashskip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** All special-app layouts and popup features come from the AI profile registry. */
final class VisualRuleMatcher {
    private static final int FEATURE_WIDTH = 48, FEATURE_HEIGHT = 32;
    private final Context context;
    private final Map<String, float[]> templates = new HashMap<>();
    static final class Match {
        final String rule;
        final float score;
        final int x, y;
        final Rect textBox;
        Match(String rule, float score, int x, int y, Rect textBox) {
            this.rule = rule; this.score = score; this.x = x; this.y = y; this.textBox = textBox;
        }
    }
    VisualRuleMatcher(Context context) { this.context = context; }
    Match findText(Bitmap screen, SkipTextModel model, AppProfiles.Profile profile) {
        if (screen.getHeight() < screen.getWidth() * 1.7f) return null;
        Match best = null;
        for (AppProfiles.Layout layout : profile.layouts) {
            Match candidate = classify(screen, model, profile, layout, 0, 0);
            if (best == null || candidate.score > best.score) best = candidate;
        }
        if (best != null && best.score >= .95f) return best;
        int[] offsets = {-16, 0, 16};
        for (AppProfiles.Layout layout : profile.layouts) {
            for (int dx : offsets) for (int dy : offsets) {
                if (dx == 0 && dy == 0) continue;
                Match candidate = classify(screen, model, profile, layout, dx, dy);
                if (best == null || candidate.score > best.score) best = candidate;
            }
        }
        return best;
    }
    private Match classify(Bitmap screen, SkipTextModel model, AppProfiles.Profile profile,
                           AppProfiles.Layout layout, int dx, int dy) {
        int[] rule = layout.coordinates;
        Rect bounds = new Rect(scale(screen, profile, rule[0] + dx), scale(screen, profile, rule[1] + dy),
                scale(screen, profile, rule[2] + dx), scale(screen, profile, rule[3] + dy));
        return new Match(layout.name, model.probability(screen, bounds),
                scale(screen, profile, rule[4] + dx), scale(screen, profile, rule[5] + dy), bounds);
    }
    Match findPopup(Bitmap screen, AppProfiles.Profile profile) {
        if (profile.popup == null || screen.getHeight() < screen.getWidth() * 1.7f) return null;
        try {
            JSONObject rule = profile.popup;
            float[] crossTemplate = template(rule.getString("crossTemplate"));
            float[] ringTemplate = template(rule.getString("ringTemplate"));
            if (crossTemplate == null || ringTemplate == null) return null;
            Match best = null;
            int[] offsets = {-20, 0, 20};
            for (int dx : offsets) for (int dy : offsets) {
                float cross = compare(screen, profile, rule.getJSONArray("crossBox"), dx, dy, crossTemplate);
                if (cross < rule.getDouble("crossThreshold")) continue;
                float ring = compare(screen, profile, rule.getJSONArray("ringBox"), dx, dy, ringTemplate);
                if (ring >= rule.getDouble("ringThreshold") && (best == null || cross > best.score)) {
                    JSONArray tap = rule.getJSONArray("tap");
                    best = new Match(rule.getString("name"), cross,
                            scale(screen, profile, tap.getInt(0) + dx), scaleY(screen, profile, tap.getInt(1) + dy), null);
                }
            }
            return best;
        } catch (Exception error) { return null; }
    }
    private float[] template(String name) {
        if (!templates.containsKey(name)) {
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            templates.put(name, id == 0 ? null : load(context, id));
        }
        return templates.get(name);
    }
    private static int scale(Bitmap screen, AppProfiles.Profile profile, int value) {
        return Math.round(value * screen.getWidth() / (float) profile.referenceWidth);
    }
    private static int scaleY(Bitmap screen, AppProfiles.Profile profile, int value) {
        return Math.round(value * screen.getHeight() / (float) profile.referenceHeight);
    }
    private static float compare(Bitmap screen, AppProfiles.Profile profile, JSONArray box,
                                 int dx, int dy, float[] template) throws Exception {
        int x = scale(screen, profile, box.getInt(0) + dx), y = scaleY(screen, profile, box.getInt(1) + dy);
        int w = scale(screen, profile, box.getInt(2) + dx) - x;
        int h = scaleY(screen, profile, box.getInt(3) + dy) - y;
        if (x < 0 || y < 0 || w < 2 || h < 2 || x + w > screen.getWidth() || y + h > screen.getHeight()) return -1f;
        Bitmap crop = Bitmap.createBitmap(screen, x, y, w, h);
        Bitmap small = Bitmap.createScaledBitmap(crop, FEATURE_WIDTH, FEATURE_HEIGHT, true);
        float[] sample = pixels(small); small.recycle(); crop.recycle();
        return correlation(sample, template);
    }
    private static float[] load(Context context, int id) {
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), id);
        Bitmap small = Bitmap.createScaledBitmap(bitmap, FEATURE_WIDTH, FEATURE_HEIGHT, true);
        float[] result = pixels(small);
        small.recycle();
        bitmap.recycle();
        return result;
    }

    private static float[] pixels(Bitmap bitmap) {
        int[] argb = new int[FEATURE_WIDTH * FEATURE_HEIGHT];
        bitmap.getPixels(argb, 0, FEATURE_WIDTH, 0, 0, FEATURE_WIDTH, FEATURE_HEIGHT);
        float[] grayscale = new float[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int color = argb[i];
            grayscale[i] = ((color >> 16) & 255) * .299f +
                    ((color >> 8) & 255) * .587f + (color & 255) * .114f;
        }
        return grayscale;
    }

    private static float correlation(float[] a, float[] b) {
        float am = 0, bm = 0;
        for (int i = 0; i < a.length; i++) { am += a[i]; bm += b[i]; }
        am /= a.length;
        bm /= b.length;
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            double av = a[i] - am, bv = b[i] - bm;
            dot += av * bv;
            aa += av * av;
            bb += bv * bv;
        }
        return aa > 0 && bb > 0 ? (float) (dot / Math.sqrt(aa * bb)) : -1f;
    }
}
