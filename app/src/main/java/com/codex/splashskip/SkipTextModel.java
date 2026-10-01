package com.codex.splashskip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.nnapi.NnApiDelegate;

/** Computer-trained classifier; inference stays on the phone. */
final class SkipTextModel implements AutoCloseable {
    private static final int WIDTH = 32;
    private static final int HEIGHT = 20;
    private Interpreter interpreter;
    private NnApiDelegate delegate;

    SkipTextModel(Context context) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = context.getAssets().open("skip_text.tflite")) {
            byte[] chunk = new byte[8192];
            int length;
            while ((length = stream.read(chunk)) != -1) bytes.write(chunk, 0, length);
        }
        ByteBuffer model = ByteBuffer.allocateDirect(bytes.size()).order(ByteOrder.nativeOrder());
        model.put(bytes.toByteArray());
        model.rewind();
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                delegate = new NnApiDelegate();
                interpreter = new Interpreter(model,
                        new Interpreter.Options().addDelegate(delegate).setNumThreads(1));
                Log.i("SplashSkipModel", "NNAPI delegate attached; accelerator selection is unverified");
            } catch (RuntimeException | LinkageError error) {
                if (delegate != null) delegate.close();
                delegate = null;
                Log.w("SplashSkipModel", "NNAPI unavailable, using CPU", error);
            }
        }
        if (interpreter == null) {
            interpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(1));
            Log.i("SplashSkipModel", "CPU interpreter ready");
        }
    }

    float probability(Bitmap screen, Rect region) {
        if (region.left < 0 || region.top < 0 || region.right > screen.getWidth() ||
                region.bottom > screen.getHeight() || region.isEmpty()) return 0f;
        Bitmap crop = Bitmap.createBitmap(screen, region.left, region.top,
                region.width(), region.height());
        Bitmap small = Bitmap.createScaledBitmap(crop, WIDTH, HEIGHT, true);
        int[] colors = new int[WIDTH * HEIGHT];
        small.getPixels(colors, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        small.recycle();
        crop.recycle();
        float[][] input = new float[1][colors.length];
        float mean = 0f;
        for (int i = 0; i < colors.length; i++) {
            int c = colors[i];
            float gray = ((c >> 16) & 255) * .299f +
                    ((c >> 8) & 255) * .587f + (c & 255) * .114f;
            input[0][i] = gray;
            mean += gray;
        }
        mean /= colors.length;
        float variance = 0f;
        for (float value : input[0]) variance += (value - mean) * (value - mean);
        float scale = (float) Math.sqrt(variance / colors.length) + 20f;
        for (int i = 0; i < colors.length; i++) {
            float normalized = (input[0][i] - mean) / scale;
            input[0][i] = Math.max(-3f, Math.min(3f, normalized));
        }
        float[][] output = new float[1][1];
        interpreter.run(input, output);
        return output[0][0];
    }

    @Override public void close() {
        if (interpreter != null) interpreter.close();
        if (delegate != null) delegate.close();
    }
}
