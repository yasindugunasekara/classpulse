// Owner: Member 5, Secure networking (anti-proxy attendance) | Lecture topic: HMAC, TOTP-style one-time codes
package com.classpulse.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Rotating attendance codes, like the 6-digit codes of an authenticator app (TOTP).
 *
 *   window = currentTimeMillis / 30 000           (a new window every 30 seconds)
 *   code   = first 6 chars of Base32( HMAC-SHA256(secret, sessionId + window) )
 *
 * The secret is 32 random bytes, different for each session, and never leaves the server.
 * Without the secret nobody can predict the next code. A code photographed and sent to a friend
 * at home stops working within one minute (and Member 3's subnet check blocks outside IPs).
 */
public class AttendanceCodeGenerator {

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private final Map<String, byte[]> secrets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long windowMillis;

    public AttendanceCodeGenerator(int windowSeconds) {
        this.windowMillis = windowSeconds * 1000L;
    }

    /** Create a fresh random secret for a session. */
    public void startSession(String sessionId) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        secrets.put(sessionId, secret);
    }

    public void endSession(String sessionId) {
        secrets.remove(sessionId);
    }

    public long windowNumber(long nowMillis) {
        return nowMillis / windowMillis;
    }

    public long currentWindow() {
        return windowNumber(System.currentTimeMillis());
    }

    /** Seconds until the current code is replaced. */
    public int secondsLeftInWindow() {
        long now = System.currentTimeMillis();
        long nextWindowStart = (windowNumber(now) + 1) * windowMillis;
        return (int) Math.ceil((nextWindowStart - now) / 1000.0);
    }

    public String currentCode(String sessionId) {
        return codeForWindow(sessionId, currentWindow());
    }

    /** The code for one specific 30-second window. */
    public String codeForWindow(String sessionId, long window) {
        byte[] secret = secrets.get(sessionId);
        if (secret == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((sessionId + window).getBytes(StandardCharsets.UTF_8));
            return base32(digest).substring(0, 6);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    public boolean isValid(String sessionId, String code) {
        return isValidAt(sessionId, code, System.currentTimeMillis());
    }

    /**
     * Accepts the CURRENT and the PREVIOUS window.
     * Why the previous one? A student may read the code at second 29 and press "Mark" at
     * second 31, after it rotated. One extra window (max 60 s total) tolerates typing delay
     * and small clock differences, while a code forwarded to someone far away still expires fast.
     */
    public boolean isValidAt(String sessionId, String code, long nowMillis) {
        if (code == null || code.length() != 6) {
            return false;
        }
        byte[] given = code.trim().toUpperCase().getBytes(StandardCharsets.US_ASCII);
        long window = windowNumber(nowMillis);
        for (long w = window; w >= window - 1; w--) {
            String expected = codeForWindow(sessionId, w);
            // constant-time comparison, same reason as in UserStore
            if (expected != null && MessageDigest.isEqual(given, expected.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** RFC 4648 Base32 (A-Z, 2-7): easy to read aloud and type; it has no digits 0 or 1, so they cannot be mistaken for O or I. */
    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1F]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }
}
