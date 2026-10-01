package com.codex.splashskip;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Diagnostics {
    private static File directory(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "diagnostics");
        dir.mkdirs();
        return dir;
    }

    static synchronized void append(Context context, String message) {
        try {
            File file = new File(directory(context), "trace.txt");
            if (file.length() > 128 * 1024) {
                File previous = new File(file.getParentFile(), "trace.previous.txt");
                if (previous.exists()) previous.delete();
                file.renameTo(previous);
            }
            String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            try (FileOutputStream stream = new FileOutputStream(file, true)) {
                stream.write((time + " " + message + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    static void frame(Context context, Bitmap screen, String name) {
        Bitmap crop = null;
        try {
            int left = screen.getWidth() * 3 / 5;
            crop = Bitmap.createBitmap(screen, left, 0, screen.getWidth() - left,
                    Math.min(screen.getHeight(), screen.getWidth() / 3));
            try (FileOutputStream stream = new FileOutputStream(new File(directory(context), name))) {
                crop.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
        } catch (Exception ignored) {
        } finally { if (crop != null) crop.recycle(); }
    }

    static String read(Context context) {
        try {
            byte[] bytes = Files.readAllBytes(new File(directory(context), "trace.txt").toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception error) { return "暂无诊断日志"; }
    }
}
