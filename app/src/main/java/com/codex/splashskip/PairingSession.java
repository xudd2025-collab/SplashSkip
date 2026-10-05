package com.codex.splashskip;

import java.util.UUID;

/** A notification reply belongs to one live discovery and one resolved endpoint. */
final class PairingSession {
    static final long TIMEOUT_MS = 180000;
    private long expiresAt;
    private int port;
    private String token;

    void begin(long now) { clear(); expiresAt = now + TIMEOUT_MS; }
    boolean update(int endpoint, long now) {
        if (endpoint < 1 || endpoint > 65535 || now >= expiresAt) return false;
        if (port == endpoint) return false;
        port = endpoint;
        token = UUID.randomUUID().toString();
        return true;
    }
    boolean accepts(int endpoint, String replyToken, long now) {
        return token != null && token.equals(replyToken) && port == endpoint && now < expiresAt;
    }
    int port() { return port; }
    String token() { return token; }
    void lost() { port = 0; token = null; }
    void clear() { lost(); expiresAt = 0; }
}
