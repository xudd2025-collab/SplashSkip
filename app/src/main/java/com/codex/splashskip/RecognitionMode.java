package com.codex.splashskip;

import android.content.Context;

/** Shared opt-in for screen-based recognition; node reading is always preferred. */
final class RecognitionMode {
    private static final String VISUAL_SUPPLEMENT = "visual_supplement";

    private RecognitionMode() { }

    static boolean visuals(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(VISUAL_SUPPLEMENT, false);
    }

    static void setVisuals(Context context, boolean enabled) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean(VISUAL_SUPPLEMENT, enabled).apply();
    }
}
