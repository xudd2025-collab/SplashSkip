package com.codex.splashskip;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** ADB credentials stay in app-private storage; only the local loopback address is used. */
final class LocalAdbClient extends AbsAdbConnectionManager {
    private final PrivateKey key;
    private final Certificate certificate;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();

    LocalAdbClient(Context context) throws Exception {
        File directory = new File(context.getNoBackupFilesDir(), "local-adb");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot save local key");
        File privateFile = new File(directory, "private.pk8");
        File certFile = new File(directory, "certificate.der");
        if (privateFile.isFile() && certFile.isFile()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile.toPath())));
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            key = pair.getPrivate();
            byte[] encoded = selfSigned(pair);
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
            Files.write(privateFile.toPath(), key.getEncoded());
            Files.write(certFile.toPath(), encoded);
        }
        setApi(Build.VERSION.SDK_INT);
        setHostAddress("127.0.0.1");
        setTimeout(5, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
    }
    @Override protected PrivateKey getPrivateKey() { return key; }
    @Override protected Certificate getCertificate() { return certificate; }
    @Override protected String getDeviceName() { return "SplashSkip@localhost"; }

    String shell(String command, int timeoutMs) throws Exception {
        try (AdbStream stream = openStream("shell:" + command)) {
            AtomicBoolean timedOut = new AtomicBoolean();
            ScheduledFuture<?> deadline = deadlines.schedule(() -> {
                timedOut.set(true);
                try { stream.close(); } catch (Exception ignored) { }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                try {
                    while ((count = stream.read(buffer, 0, buffer.length)) != -1) {
                        output.write(buffer, 0, count);
                        if (output.size() > 65536) throw new java.io.IOException("Unexpected command output");
                    }
                } catch (java.io.IOException error) {
                    // The library reports EOF as a closed-stream exception after the final packet.
                    if (output.size() == 0 || timedOut.get()) throw error;
                }
                if (timedOut.get()) throw new java.net.SocketTimeoutException("Local ADB command timed out");
                return output.toString("UTF-8").trim();
            } finally { deadline.cancel(false); }
        }
    }

    // Minimal DER X.509 encoder avoids Android hidden APIs and additional certificate runtimes.
    private static byte[] selfSigned(KeyPair pair) throws Exception {
        byte[] algorithm = der(0x30, new byte[]{6,9,42,(byte)134,72,(byte)134,(byte)247,13,1,1,11,5,0});
        byte[] name = der(0x30, der(0x31, der(0x30, new byte[]{6,3,85,4,3}, der(12, "SplashSkip".getBytes(StandardCharsets.UTF_8)))));
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] validity = der(0x30, der(23, format.format(new Date(now - 86400000L)).getBytes(StandardCharsets.US_ASCII)),
                der(23, format.format(new Date(now + 315360000000L)).getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = der(0x30, der(0xa0, der(2, new byte[]{2})), der(2, new byte[]{1}), algorithm,
                name, validity, name, pair.getPublic().getEncoded());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate()); signer.update(tbs);
        return der(0x30, tbs, algorithm, der(3, new byte[]{0}, signer.sign()));
    }
    private static byte[] der(int tag, byte[]... parts) throws Exception {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        for (byte[] part : parts) contents.write(part);
        int length = contents.size();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(tag);
        if (length < 128) output.write(length);
        else if (length < 256) { output.write(0x81); output.write(length); }
        else { output.write(0x82); output.write(length >> 8); output.write(length); }
        output.write(contents.toByteArray()); return output.toByteArray();
    }
}
