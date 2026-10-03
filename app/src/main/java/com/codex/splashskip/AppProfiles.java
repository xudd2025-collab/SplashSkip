package com.codex.splashskip;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The registered, tested layouts; ordinary apps continue using accessibility nodes. */
final class AppProfiles {
    static final class Layout {
        final String name;
        final int cropWidth,cropHeight;
        Layout(JSONObject json) throws Exception {
            name = json.getString("name");
            JSONArray size = json.getJSONArray("cropSize");
            if(size.length()!=2 || size.getInt(0)<1 || size.getInt(1)<1)throw new IllegalArgumentException("Invalid crop dimensions");
            cropWidth=size.getInt(0);cropHeight=size.getInt(1);
        }
    }
    static final class Profile {
        final String packageName, label, inAppRules;
        final int referenceWidth, referenceHeight, scanWindowMs;
        final boolean bypassNodeTree;
        final Layout[] layouts;
        final JSONObject popup;
        Profile(JSONObject json) throws Exception {
            packageName = json.getString("package");
            label = json.getString("label");
            inAppRules = json.optString("inAppRules", "");
            referenceWidth = json.getInt("referenceWidth");
            referenceHeight = json.getInt("referenceHeight");
            scanWindowMs = json.optInt("scanWindowMs", 20000);
            bypassNodeTree = json.optBoolean("bypassNodeTree", false);
            if (referenceWidth <= 0 || referenceHeight <= 0 || scanWindowMs > 30000 || scanWindowMs < 1000)
                throw new IllegalArgumentException("Invalid profile dimensions or duration");
            JSONArray list = json.getJSONArray("layouts");
            layouts = new Layout[list.length()];
            for (int i = 0; i < layouts.length; i++) layouts[i] = new Layout(list.getJSONObject(i));
            popup = json.optJSONObject("popup");
        }
    }
    private static Map<String, Profile> profiles;
    static synchronized Map<String, Profile> all(Context context) {
        if (profiles != null) return profiles;
        Map<String, Profile> loaded = new LinkedHashMap<>();
        try (InputStream input = context.getAssets().open("app_profiles.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONArray list = new JSONObject(bytes.toString("UTF-8")).getJSONArray("apps");
            for (int i = 0; i < list.length(); i++) {
                Profile profile = new Profile(list.getJSONObject(i));
                loaded.put(profile.packageName, profile);
            }
        } catch (Exception error) {
            Diagnostics.append(context, "AI profile load failed: " + error.getClass().getSimpleName());
            loaded.clear();
        }
        profiles = Collections.unmodifiableMap(loaded);
        return profiles;
    }
    static Profile find(Context context, String pkg) { return all(context).get(pkg); }
    static boolean enabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        return prefs.getBoolean("ai_enhanced", prefs.getBoolean("special_visual", true));
    }
    static String labels(Context context) {
        StringBuilder names = new StringBuilder();
        for (Profile profile : all(context).values()) {
            if (names.length() > 0) names.append("、");
            names.append(profile.label);
        }
        return names.length() == 0 ? "暂无" : names.toString();
    }
}
