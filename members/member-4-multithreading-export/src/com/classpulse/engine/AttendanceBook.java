// Owner: Member 4, Multithreading | Lecture topic: ConcurrentHashMap.putIfAbsent, scheduled tasks
package com.classpulse.engine;

import com.classpulse.common.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Attendance records: joinCode -> (username -> record).
 * putIfAbsent() is atomic, so a student can be marked at most ONCE per session even if two
 * ATTEND messages are processed at the same time.
 * Closing attendance on time is scheduled on the same poll-timer pool.
 */
public class AttendanceBook {

    private static final String COMPONENT = "Attendance";

    /** State of the attendance window of one session. */
    private static final class Window {
        volatile boolean open;
        volatile long closesAtMillis;
        ScheduledFuture<?> closer;
    }

    private final Map<String, ConcurrentHashMap<String, AttendanceRecord>> book = new ConcurrentHashMap<>();
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public AttendanceBook(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Opens attendance for durationSec seconds. onClosed runs on a poll-timer thread when time is up.
     * Returns the closing time (epoch millis).
     */
    public synchronized long open(String joinCode, int durationSec, Runnable onClosed) {
        Window w = windows.computeIfAbsent(joinCode, k -> new Window());
        if (w.closer != null) {
            w.closer.cancel(false); // restarting: forget the old timer
        }
        w.open = true;
        w.closesAtMillis = System.currentTimeMillis() + durationSec * 1000L;
        w.closer = scheduler.schedule(() -> {
            if (close(joinCode)) {
                Log.info(COMPONENT, "attendance for " + joinCode + " closed automatically (time is up)");
                onClosed.run();
            }
        }, durationSec, TimeUnit.SECONDS);
        book.computeIfAbsent(joinCode, k -> new ConcurrentHashMap<>());
        Log.info(COMPONENT, "attendance OPEN for " + joinCode + " for " + durationSec + " s");
        return w.closesAtMillis;
    }

    /** Returns true if it was open and is now closed. */
    public synchronized boolean close(String joinCode) {
        Window w = windows.get(joinCode);
        if (w == null || !w.open) {
            return false;
        }
        w.open = false;
        if (w.closer != null) {
            w.closer.cancel(false);
        }
        return true;
    }

    public boolean isOpen(String joinCode) {
        Window w = windows.get(joinCode);
        return w != null && w.open;
    }

    public long closesAt(String joinCode) {
        Window w = windows.get(joinCode);
        return w == null ? 0 : w.closesAtMillis;
    }

    /** Atomically records one mark. Returns null if the student was already marked, else the new record. */
    public AttendanceRecord mark(String joinCode, String username, String ip, AttendanceRecord.Status status) {
        ConcurrentHashMap<String, AttendanceRecord> session = book.computeIfAbsent(joinCode, k -> new ConcurrentHashMap<>());
        AttendanceRecord rec = new AttendanceRecord(username, System.currentTimeMillis(), ip, status);
        AttendanceRecord existing = session.putIfAbsent(username, rec); // atomic "insert only if absent"
        return existing == null ? rec : null;
    }

    public AttendanceRecord recordOf(String joinCode, String username) {
        Map<String, AttendanceRecord> session = book.get(joinCode);
        return session == null ? null : session.get(username);
    }

    public int presentCount(String joinCode) {
        Map<String, AttendanceRecord> session = book.get(joinCode);
        return session == null ? 0 : session.size();
    }

    /** Snapshot sorted by time, for the CSV export. */
    public List<AttendanceRecord> records(String joinCode) {
        Map<String, AttendanceRecord> session = book.get(joinCode);
        List<AttendanceRecord> list = new ArrayList<>();
        if (session != null) {
            list.addAll(session.values());
        }
        list.sort((a, b) -> Long.compare(a.timeMillis(), b.timeMillis()));
        return list;
    }
}
