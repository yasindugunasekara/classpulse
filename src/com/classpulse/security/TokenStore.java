// Owner: Member 5, Secure networking | Lecture topic: Session tokens (bearer tokens) after TLS login
package com.classpulse.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After a successful TLS login the user receives a random token. The NIO classroom channel
 * (which is not encrypted) only ever sees this token, never the password.
 *
 * Token = 32 random bytes from SecureRandom, Base64-URL encoded (about 43 characters),
 * so guessing one is practically impossible. Tokens expire after 4 hours.
 * Thread-safe: the login pool issues tokens, the NIO thread and export pool validate them.
 */
public class TokenStore {

    private record Entry(Principal principal, long expiresAtMillis) { }

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long lifetimeMillis;

    public TokenStore(long lifetimeMillis) {
        this.lifetimeMillis = lifetimeMillis;
    }

    public TokenStore() {
        this(4L * 60 * 60 * 1000); // 4 hours
    }

    public String issue(String username, String role) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new Entry(new Principal(username, role), System.currentTimeMillis() + lifetimeMillis));
        return token;
    }

    /** Returns the Principal for a valid token, or null if unknown or expired. */
    public Principal validate(String token) {
        if (token == null) {
            return null;
        }
        Entry e = tokens.get(token);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() > e.expiresAtMillis()) {
            tokens.remove(token); // expired: forget it
            return null;
        }
        return e.principal();
    }

    public void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }
}
