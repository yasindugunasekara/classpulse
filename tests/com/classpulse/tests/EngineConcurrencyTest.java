// Owner: Member 4, Multithreading (test) | Lecture topic: Proving thread safety with many concurrent threads
package com.classpulse.tests;

import com.classpulse.engine.AttendanceBook;
import com.classpulse.engine.AttendanceRecord;
import com.classpulse.engine.Poll;
import com.classpulse.engine.PollManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 3 check: 50 threads vote at the same moment (each student also tries to vote twice).
 * Counts must be exact, no double votes. Then the timer must close a 10 s poll on time.
 * Run: java -cp out com.classpulse.tests.EngineConcurrencyTest
 */
public class EngineConcurrencyTest {

    public static void main(String[] args) throws Exception {
        PollManager manager = new PollManager();
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger updates = new AtomicInteger();
        manager.setListener(new PollManager.Listener() {
            public void onPollUpdate(Poll poll, int[] counts, int total) {
                updates.incrementAndGet();
            }

            public void onPollEnded(Poll poll, int[] counts, int total) {
                ended.countDown();
            }
        });

        Poll poll = manager.createPoll("TEST01", "Which layer does TCP work in?",
                List.of("Application", "Transport", "Network", "Link"), 10);
        manager.startPoll(poll);
        long started = System.currentTimeMillis();

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final String user = "u" + i;
            final int option = i % 4;
            pool.execute(() -> {
                try {
                    go.await(); // all 50 threads start voting at the same instant
                } catch (InterruptedException e) {
                    return;
                }
                for (int attempt = 0; attempt < 2; attempt++) { // second attempt must be rejected
                    Poll.VoteResult r = poll.vote(user, option);
                    if (r == Poll.VoteResult.OK) {
                        ok.incrementAndGet();
                    } else if (r == Poll.VoteResult.ALREADY_VOTED) {
                        rejected.incrementAndGet();
                    }
                }
            });
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        int[] counts = poll.snapshot();
        check("50 votes accepted", ok.get() == 50);
        check("50 second votes rejected", rejected.get() == 50);
        check("counts exact (13,13,12,12)", counts[0] == 13 && counts[1] == 13 && counts[2] == 12 && counts[3] == 12);
        check("total = 50", poll.totalVotes() == 50);
        check("invalid option rejected", poll.vote("zz", 9) == Poll.VoteResult.INVALID_OPTION);

        Thread.sleep(600);
        check("throttled update sent (" + updates.get() + " updates for 50 votes)", updates.get() >= 1 && updates.get() <= 3);

        boolean closedOnTime = ended.await(12, TimeUnit.SECONDS);
        long took = System.currentTimeMillis() - started;
        check("timer closed poll after ~10 s (" + took + " ms)", closedOnTime && took >= 9900 && took < 11000);
        check("vote after close rejected", poll.vote("late", 0) == Poll.VoteResult.CLOSED);
        check("closing twice gives no second snapshot", poll.close() == null);

        // Attendance: one mark per student, even from 20 threads at once
        AttendanceBook book = new AttendanceBook(manager.scheduler());
        book.open("TEST01", 60, () -> { });
        ExecutorService pool2 = Executors.newFixedThreadPool(20);
        AtomicInteger marks = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            pool2.execute(() -> {
                if (book.mark("TEST01", "s001", "127.0.0.1", AttendanceRecord.Status.PRESENT) != null) {
                    marks.incrementAndGet();
                }
            });
        }
        pool2.shutdown();
        pool2.awaitTermination(5, TimeUnit.SECONDS);
        check("20 parallel marks for one student -> exactly 1 record", marks.get() == 1 && book.presentCount("TEST01") == 1);

        manager.shutdown();
        System.out.println("EngineConcurrencyTest: ALL PASSED");
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (!ok) {
            System.exit(1);
        }
    }
}
