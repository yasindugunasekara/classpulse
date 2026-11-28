// Owner: Member 4, Multithreading and thread pools | Lecture topic: ScheduledExecutorService, ConcurrentHashMap
package com.classpulse.engine;

import com.classpulse.common.Log;
import com.classpulse.common.NamedThreadFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates, starts and closes polls.
 *
 * Threads involved:
 *  - NIO-selector thread: creates polls and calls vote() for each VOTE message.
 *  - poll-timer-1 / poll-timer-2 (a ScheduledExecutorService with 2 threads):
 *      * closes each poll exactly when its time limit ends,
 *      * every 250 ms checks which polls changed and asks for a POLL_UPDATE (throttling:
 *        100 votes in one second become at most 4 updates instead of 100 messages).
 * The listener (the NIO server) is told about updates/ends; it moves the work back to its own
 * thread with server.submit(), because only the NIO thread may touch the sockets.
 */
public class PollManager {

    private static final String COMPONENT = "PollManager";

    /** Implemented by the classroom server. Called from poll-timer threads. */
    public interface Listener {
        void onPollUpdate(Poll poll, int[] counts, int total);

        void onPollEnded(Poll poll, int[] counts, int total);
    }

    private final Map<String, Poll> polls = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, new NamedThreadFactory("poll-timer", true));
    private volatile Listener listener;

    public PollManager() {
        // throttled live updates: at most one POLL_UPDATE per poll every 250 ms (4 per second)
        scheduler.scheduleAtFixedRate(this::sendThrottledUpdates, 250, 250, TimeUnit.MILLISECONDS);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The shared scheduler (AttendanceBook uses it to close attendance on time). */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /** Validates and creates a poll. Throws IllegalArgumentException with a user-friendly message. */
    public Poll createPoll(String joinCode, String question, List<String> options, int timeLimitSec) {
        if (question == null || question.isBlank() || question.length() > 300) {
            throw new IllegalArgumentException("question must be 1-300 characters");
        }
        List<String> clean = new ArrayList<>();
        for (String o : options) {
            if (o != null && !o.isBlank()) {
                clean.add(o.trim());
            }
        }
        if (clean.size() < 2 || clean.size() > 6) {
            throw new IllegalArgumentException("a poll needs 2 to 6 options");
        }
        if (timeLimitSec < 10 || timeLimitSec > 600) {
            throw new IllegalArgumentException("time limit must be 10 to 600 seconds");
        }
        String id = "P" + nextId.getAndIncrement();
        Poll poll = new Poll(id, joinCode, question.trim(), clean, timeLimitSec);
        polls.put(id, poll);
        Log.info(COMPONENT, "created poll " + id + " (" + clean.size() + " options, " + timeLimitSec + " s): " + question);
        return poll;
    }

    public Poll get(String pollId) {
        return polls.get(pollId);
    }

    /** All polls of one session, in creation order. */
    public List<Poll> pollsOf(String joinCode) {
        List<Poll> list = new ArrayList<>();
        for (Poll p : polls.values()) {
            if (p.joinCode().equals(joinCode)) {
                list.add(p);
            }
        }
        list.sort((a, b) -> Integer.compare(Integer.parseInt(a.id().substring(1)), Integer.parseInt(b.id().substring(1))));
        return list;
    }

    public Poll openPollOf(String joinCode) {
        for (Poll p : pollsOf(joinCode)) {
            if (p.state() == Poll.State.OPEN) {
                return p;
            }
        }
        return null;
    }

    /** Opens the poll and schedules its automatic close on a poll-timer thread. */
    public boolean startPoll(Poll poll) {
        if (!poll.open()) {
            return false;
        }
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            Log.info(COMPONENT, "time is up for poll " + poll.id() + ", closing it");
            finish(poll);
        }, poll.timeLimitSec(), TimeUnit.SECONDS);
        poll.setTimer(timer);
        Log.info(COMPONENT, "started poll " + poll.id() + ", closes in " + poll.timeLimitSec() + " s");
        return true;
    }

    /** Lecturer pressed Stop. Returns false if it was not open. */
    public boolean stopPoll(Poll poll) {
        Log.info(COMPONENT, "lecturer stopped poll " + poll.id());
        return finish(poll);
    }

    /** Close + notify. Poll.close() is synchronized, so only ONE caller gets the snapshot. */
    private boolean finish(Poll poll) {
        int[] fin = poll.close();
        if (fin == null) {
            return false; // somebody else already closed it
        }
        Log.info(COMPONENT, "poll " + poll.id() + " CLOSED, final total = " + poll.totalVotes());
        Listener l = listener;
        if (l != null) {
            l.onPollEnded(poll, fin, poll.totalVotes());
        }
        return true;
    }

    /** Closes every open poll of a session (used by END_SESSION). */
    public void closeAll(String joinCode) {
        for (Poll p : pollsOf(joinCode)) {
            if (p.state() == Poll.State.OPEN) {
                finish(p);
            }
        }
    }

    private void sendThrottledUpdates() {
        try {
            Listener l = listener;
            for (Poll p : polls.values()) {
                if (p.state() == Poll.State.OPEN && p.takeChanged() && l != null) {
                    l.onPollUpdate(p, p.snapshot(), p.totalVotes());
                }
            }
        } catch (RuntimeException e) {
            // an exception would silently cancel scheduleAtFixedRate, so catch everything
            Log.error(COMPONENT, "update task failed: " + e);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
