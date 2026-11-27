// Owner: Member 1, Java NIO (server state) | Lecture topic: Shared state between server threads
package com.classpulse.server.nio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds all classroom sessions and the currently connected clients.
 *
 * Rule: only the NIO thread CHANGES this registry. Other threads (UDP discovery, export pool)
 * only READ it via snapshot methods. Sessions are in a ConcurrentHashMap so those reads are safe.
 * The 'online' map (username -> connection) is a plain HashMap because ONLY the NIO thread
 * ever touches connections.
 */
public class SessionRegistry {

    /** No 0/O and 1/I, so a code read from the projector is never ambiguous. */
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Map<String, ClassSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ClientConnection> online = new HashMap<>(); // NIO thread only
    private final SecureRandom random = new SecureRandom();

    /** Creates a session with a fresh, unique, unpredictable 6-character join code. */
    public ClassSession createSession(String moduleCode, String title, String lecturer) {
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            code = sb.toString();
        } while (sessions.containsKey(code));
        ClassSession s = new ClassSession(code, moduleCode, title, lecturer);
        sessions.put(code, s);
        return s;
    }

    public ClassSession get(String joinCode) {
        return joinCode == null ? null : sessions.get(joinCode.trim().toUpperCase());
    }

    public void endSession(ClassSession s) {
        s.end(); // kept in the map so its attendance/polls can still be exported afterwards
    }

    /** Snapshot of the active sessions (thread-safe; used by the UDP threads). */
    public List<ClassSession> activeSessions() {
        List<ClassSession> list = new ArrayList<>();
        for (ClassSession s : sessions.values()) {
            if (s.isActive()) {
                list.add(s);
            }
        }
        return list;
    }

    public ClassSession activeSessionOf(String lecturer) {
        for (ClassSession s : sessions.values()) {
            if (s.isActive() && s.lecturerUsername().equals(lecturer)) {
                return s;
            }
        }
        return null;
    }

    // ---- connected clients (NIO thread only) ----

    public ClientConnection online(String username) {
        return online.get(username);
    }

    public ClientConnection putOnline(String username, ClientConnection conn) {
        return online.put(username, conn);
    }

    /** Removes the mapping only if it still points to this connection (a newer login may have replaced it). */
    public void removeOnline(String username, ClientConnection conn) {
        if (username != null && online.get(username) == conn) {
            online.remove(username);
        }
    }

    public Collection<ClientConnection> onlineClients() {
        return online.values();
    }
}
