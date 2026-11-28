// Owner: Member 5, Secure networking | Lecture topic: Password hashing (PBKDF2 + salt), secure storage
package com.classpulse.security;

import com.classpulse.common.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stores users in data/users.txt, one per line:  username:role:saltBase64:hashBase64
 *
 * We NEVER store the password itself. We store PBKDF2WithHmacSHA256(password, salt), with
 * 65536 iterations (slow on purpose: makes brute force expensive) and a random 16-byte salt
 * per user (so two users with the same password get different hashes, and pre-computed
 * "rainbow tables" are useless).
 */
public class UserStore {

    private static final String COMPONENT = "UserStore";
    private static final int ITERATIONS = 65536;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private record UserRecord(String username, String role, byte[] salt, byte[] hash) { }

    private final Path file;
    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    /** A fixed dummy salt so a login for an unknown user costs the same time as a real one. */
    private final byte[] dummySalt = new byte[SALT_BYTES];

    public UserStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.toAbsolutePath().getParent());
        load();
        if (users.isEmpty()) {
            seed();
        }
    }

    /** Read users.txt into memory. */
    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            String[] p = line.trim().split(":");
            if (p.length != 4) {
                continue; // skip blank / corrupt lines
            }
            Base64.Decoder d = Base64.getDecoder();
            users.put(p[0].toLowerCase(), new UserRecord(p[0], p[1], d.decode(p[2]), d.decode(p[3])));
        }
        Log.info(COMPONENT, "loaded " + users.size() + " users from " + file);
    }

    /** First run: create the demo accounts. Passwords are only hashed, never written. */
    private void seed() throws IOException {
        addUser("lecturer", "lecturer123", "LECTURER");
        addUser("s001", "s001pass", "STUDENT");
        addUser("s002", "s002pass", "STUDENT");
        addUser("s003", "s003pass", "STUDENT");
        Log.info(COMPONENT, "seeded demo users: lecturer, s001, s002, s003");
    }

    /**
     * Register a new STUDENT. Returns null on success, or an error message.
     * synchronized: two simultaneous registrations of the same name must not both succeed,
     * and two threads must not append to users.txt at the same time.
     */
    public synchronized String register(String username, String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            return "username must be 3-16 characters: letters, digits or _";
        }
        if (password == null || password.length() < 6) {
            return "password must be at least 6 characters";
        }
        if (users.containsKey(username.toLowerCase())) {
            return "username already taken";
        }
        try {
            addUser(username, password, "STUDENT");
            Log.info(COMPONENT, "registered new student " + username); // never log the password
            return null;
        } catch (IOException e) {
            return "server could not save the user";
        }
    }

    /** Check a username/password. Returns the Principal, or null if wrong. */
    public Principal authenticate(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        UserRecord rec = users.get(username.toLowerCase());
        if (rec == null) {
            hash(password, dummySalt); // same work as a real check: do not leak "user exists" by timing
            return null;
        }
        byte[] attempt = hash(password, rec.salt());
        // MessageDigest.isEqual compares in constant time: an attacker cannot learn
        // how many leading bytes matched by measuring the response time.
        if (MessageDigest.isEqual(attempt, rec.hash())) {
            return new Principal(rec.username(), rec.role());
        }
        return null;
    }

    private synchronized void addUser(String username, String password, String role) throws IOException {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt); // a fresh random salt for every user
        byte[] hash = hash(password, salt);
        UserRecord rec = new UserRecord(username, role, salt, hash);
        Base64.Encoder e = Base64.getEncoder();
        String line = username + ":" + role + ":" + e.encodeToString(salt) + ":" + e.encodeToString(hash)
                + System.lineSeparator();
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        users.put(username.toLowerCase(), rec);
    }

    /** PBKDF2WithHmacSHA256, 65536 iterations, 256-bit output. */
    static byte[] hash(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    public int size() {
        return users.size();
    }
}
