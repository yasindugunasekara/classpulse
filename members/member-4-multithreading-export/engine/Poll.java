// Owner: Member 4, Multithreading and synchronisation | Lecture topic: Atomic variables, concurrent collections, synchronized
package com.classpulse.engine;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * One multiple-choice poll. Votes may arrive from many threads at the same time.
 *
 * Why is vote() safe WITHOUT synchronized?
 *  - voters is a concurrent Set: voters.add(username) is ONE atomic step. If two votes from the
 *    same student race, exactly one add() returns true; the other gets "already voted".
 *  - counts is an AtomicIntegerArray: incrementAndGet(i) is an atomic read-modify-write.
 *    With a plain int[] "counts[i]++" is really read, add, write: two threads could both read 5
 *    and both write 6, LOSING a vote.
 *
 * Where synchronized IS needed: close(). Changing the state OPEN -> CLOSED and taking the final
 * snapshot must happen as one unit, and only once, even if the timer thread and the lecturer's
 * STOP_POLL arrive together.
 */
public class Poll {

    public enum State { CREATED, OPEN, CLOSED }

    public enum VoteResult { OK, ALREADY_VOTED, CLOSED, INVALID_OPTION }

    private final String id;
    private final String joinCode;
    private final String question;
    private final List<String> options;
    private final int timeLimitSec;

    private final AtomicIntegerArray counts;
    private final Set<String> voters = ConcurrentHashMap.newKeySet();
    private final AtomicInteger total = new AtomicInteger();
    /** Set to true on every vote; the 250 ms update task resets it (throttling). */
    private final AtomicBoolean changed = new AtomicBoolean();
    /** Votes currently inside vote(); close() waits for them so none is lost from the final snapshot. */
    private final AtomicInteger votesInProgress = new AtomicInteger();

    private volatile State state = State.CREATED; // volatile: every thread sees the newest state
    private volatile long endsAtMillis;
    private volatile int[] finalCounts;
    private ScheduledFuture<?> timer;

    public Poll(String id, String joinCode, String question, List<String> options, int timeLimitSec) {
        this.id = id;
        this.joinCode = joinCode;
        this.question = question;
        this.options = List.copyOf(options);
        this.timeLimitSec = timeLimitSec;
        this.counts = new AtomicIntegerArray(options.size());
    }

    /** CREATED -> OPEN. Returns false if the poll was already started. */
    public synchronized boolean open() {
        if (state != State.CREATED) {
            return false;
        }
        endsAtMillis = System.currentTimeMillis() + timeLimitSec * 1000L;
        state = State.OPEN;
        return true;
    }

    public VoteResult vote(String username, int optionIndex) {
        votesInProgress.incrementAndGet(); // "I am voting now" (close() waits for this to reach 0)
        try {
            if (state != State.OPEN) {
                return VoteResult.CLOSED;
            }
            if (optionIndex < 0 || optionIndex >= options.size()) {
                return VoteResult.INVALID_OPTION; // never trust a client-supplied index
            }
            if (!voters.add(username)) {
                return VoteResult.ALREADY_VOTED; // atomic check-and-insert: one vote per student
            }
            counts.incrementAndGet(optionIndex);
            total.incrementAndGet();
            changed.set(true);
            return VoteResult.OK;
        } finally {
            votesInProgress.decrementAndGet();
        }
    }

    /**
     * OPEN -> CLOSED plus final snapshot, done exactly once.
     * Returns the final counts, or null if the poll was not open (already closed by someone else).
     */
    public synchronized int[] close() {
        if (state != State.OPEN) {
            return null;
        }
        state = State.CLOSED; // from now on new votes are refused...
        while (votesInProgress.get() > 0) {
            Thread.onSpinWait(); // ...and votes that already passed the check finish counting (microseconds)
        }
        finalCounts = snapshot();
        if (timer != null) {
            timer.cancel(false); // closed early by the lecturer: the timer is no longer needed
        }
        return finalCounts;
    }

    /** Copy of the current counts (each element read atomically). */
    public int[] snapshot() {
        int[] copy = new int[counts.length()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = counts.get(i);
        }
        return copy;
    }

    /** True once if something changed since the last call (used for throttled updates). */
    public boolean takeChanged() {
        return changed.getAndSet(false);
    }

    synchronized void setTimer(ScheduledFuture<?> timer) {
        this.timer = timer;
    }

    public boolean hasVoted(String username) {
        return voters.contains(username);
    }

    public Set<String> voters() {
        return Collections.unmodifiableSet(voters);
    }

    public int totalVotes() {
        return total.get();
    }

    public int secondsLeft() {
        long left = endsAtMillis - System.currentTimeMillis();
        return (int) Math.max(0, Math.ceil(left / 1000.0));
    }

    public String id() { return id; }
    public String joinCode() { return joinCode; }
    public String question() { return question; }
    public List<String> options() { return options; }
    public int timeLimitSec() { return timeLimitSec; }
    public State state() { return state; }
    public int[] finalCounts() { return finalCounts; }
}
