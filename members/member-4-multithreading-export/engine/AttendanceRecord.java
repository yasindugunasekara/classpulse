// Owner: Member 4, Multithreading | Lecture topic: Immutable data + volatile fields shared between threads
package com.classpulse.engine;

/**
 * One attendance mark. Everything is final except the hostname, which is filled in later by a
 * DNS pool thread (Member 3's NetworkGuard); volatile makes that later write visible to the
 * export threads.
 */
public class AttendanceRecord {

    public enum Status { PRESENT, SUSPICIOUS }

    private final String username;
    private final long timeMillis;
    private final String ip;
    private final Status status;
    private volatile String hostname;

    public AttendanceRecord(String username, long timeMillis, String ip, Status status) {
        this.username = username;
        this.timeMillis = timeMillis;
        this.ip = ip;
        this.status = status;
        this.hostname = "(resolving)";
    }

    public String username() { return username; }
    public long timeMillis() { return timeMillis; }
    public String ip() { return ip; }
    public Status status() { return status; }
    public String hostname() { return hostname; }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }
}
