// Owner: Member 4, Multithreading + export (test) | Lecture topic: thread-safe counters, CSV export
package com.classpulse.tests;

import com.classpulse.engine.AttendanceRecord;
import com.classpulse.engine.Poll;
import com.classpulse.export.CsvBuilder;
import com.classpulse.server.nio.ClassSession;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Member 4 checks: poll state machine, 200 parallel voters, and the CSV files that the export server sends
 * (header, escaping of commas and quotes, vote counts and percentages).
 * Run: java -cp out com.classpulse.tests.ExportCsvTest
 */
public class ExportCsvTest {

    public static void main(String[] args) throws Exception {
        ClassSession s = new ClassSession("ABC123", "IN2120", "Networks, week 5", "lecturer");

        // ---- poll state machine ----
        Poll created = new Poll("P1", "ABC123", "Q?", List.of("A", "B"), 30);
        check("vote before open -> CLOSED", created.vote("s001", 0) == Poll.VoteResult.CLOSED);
        check("open() works once", created.open() && !created.open());
        check("vote OK", created.vote("s001", 1) == Poll.VoteResult.OK);
        check("invalid option rejected", created.vote("s002", 5) == Poll.VoteResult.INVALID_OPTION);
        check("second vote rejected", created.vote("s001", 0) == Poll.VoteResult.ALREADY_VOTED);
        created.close();
        check("state CLOSED after close()", created.state() == Poll.State.CLOSED);

        // ---- 200 students vote at the same moment ----
        Poll big = new Poll("P2", "ABC123", "Which protocol is reliable?", List.of("TCP", "UDP", "Both", "None"), 60);
        big.open();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 200; i++) {
            int n = i;
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                if (big.vote("u" + n, n % 4) == Poll.VoteResult.OK) {
                    ok.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        int[] counts = big.close();
        check("200 parallel votes all accepted", ok.get() == 200 && big.totalVotes() == 200);
        check("no lost updates: 50/50/50/50", counts[0] == 50 && counts[1] == 50 && counts[2] == 50 && counts[3] == 50);

        // ---- poll CSV ----
        String polls = CsvBuilder.polls(s, List.of(created, big));
        check("poll CSV header", polls.contains("pollId,question,option,votes,percent,totalVotes,state"));
        check("title with comma is quoted in comment", polls.startsWith("# IN2120 - Networks, week 5 (join code ABC123)"));
        check("100% row for P1 option B", polls.contains("P1,Q?,B,1,100.0,1,CLOSED"));
        check("25% rows for P2", polls.contains("P2,Which protocol is reliable?,TCP,50,25.0,200,CLOSED"));
        check("one row per option (2 + 4 + 2 header lines)", polls.split("\n").length == 8);

        // ---- attendance CSV ----
        AttendanceRecord a = new AttendanceRecord("s001", System.currentTimeMillis(), "192.168.1.20", AttendanceRecord.Status.PRESENT);
        a.setHostname("laptop,\"room 4\"");
        AttendanceRecord b = new AttendanceRecord("s002", System.currentTimeMillis(), "192.168.1.20", AttendanceRecord.Status.SUSPICIOUS);
        String att = CsvBuilder.attendance(s, List.of(a, b));
        check("attendance CSV header", att.contains("username,time,ip,hostname,status"));
        check("comma and quote escaped", att.contains(",\"laptop,\"\"room 4\"\"\",PRESENT"));
        check("SUSPICIOUS status written", att.contains("s002,") && att.trim().endsWith("SUSPICIOUS"));
        check("unresolved hostname shown", att.contains("(resolving)"));

        System.out.println("ExportCsvTest: ALL PASSED");
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (!ok) {
            System.out.println("ExportCsvTest: FAILED");
            System.exit(1);
        }
    }
}
