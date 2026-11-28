// Owner: Member 1, Java NIO (server state) | Lecture topic: Server-side session state
package com.classpulse.server.nio;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One classroom session, e.g. "IN2120 - Network Programming, week 5", identified by a 6-char join code.
 * Changed only by the NIO thread; the concurrent set and volatile flag let other threads
 * (UDP announcer, export pool) READ it safely.
 */
public class ClassSession {

    private final String joinCode;
    private final String moduleCode;
    private final String title;
    private final String lecturerUsername;
    private final long createdAtMillis = System.currentTimeMillis();
    private final Set<String> joinedStudents = ConcurrentHashMap.newKeySet();
    private volatile boolean active = true;

    /** Last attendance-code window sent to the lecturer (NIO thread only). */
    long lastCodeWindowSent = -1;

    public ClassSession(String joinCode, String moduleCode, String title, String lecturerUsername) {
        this.joinCode = joinCode;
        this.moduleCode = moduleCode;
        this.title = title;
        this.lecturerUsername = lecturerUsername;
    }

    public String joinCode() { return joinCode; }
    public String moduleCode() { return moduleCode; }
    public String title() { return title; }
    public String lecturerUsername() { return lecturerUsername; }
    public long createdAtMillis() { return createdAtMillis; }
    public boolean isActive() { return active; }
    public Set<String> joinedStudents() { return joinedStudents; }

    void end() {
        active = false;
    }
}
