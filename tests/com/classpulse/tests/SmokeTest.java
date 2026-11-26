// Owner: All members (integration test) | Lecture topic: Testing a network protocol end to end with raw sockets
package com.classpulse.tests;

import com.classpulse.common.Config;
import com.classpulse.export.ExportClient;
import com.classpulse.security.SecureLoginClient;
import com.classpulse.udp.DiscoveryClient;
import com.classpulse.udp.SessionInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end smoke test against a RUNNING server (scripts/run-server) on localhost.
 * Start scripts/run-question-web too, for the URL import check.
 * Run: java -cp out com.classpulse.tests.SmokeTest
 */
public class SmokeTest {

    static int passed;
    static int failed;

    /** A tiny raw-socket client that can wait for a line with a given prefix. */
    static class Conn implements AutoCloseable {
        final Socket socket = new Socket();
        final BufferedReader in;
        final OutputStream out;
        final List<String> seen = new ArrayList<>();

        Conn(int port) throws Exception {
            socket.connect(new InetSocketAddress("localhost", port), 3000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void send(String line) throws Exception {
            raw(line + "\n");
        }

        void raw(String text) throws Exception {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** Reads lines until one starts with prefix (returns it) or the timeout passes (returns null). */
        String expect(String prefix, int timeoutMs) throws Exception {
            // first look at lines that arrived earlier but were not consumed yet
            for (int i = 0; i < seen.size(); i++) {
                if (seen.get(i).startsWith(prefix)) {
                    return seen.remove(i);
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                socket.setSoTimeout((int) Math.max(1, end - System.currentTimeMillis()));
                String line;
                try {
                    line = in.readLine();
                } catch (SocketTimeoutException e) {
                    return null;
                }
                if (line == null) {
                    return null;
                }
                if (line.startsWith(prefix)) {
                    return line;
                }
                seen.add(line);
            }
            return null;
        }

        @Override
        public void close() throws java.io.IOException {
            socket.close();
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        int port = config.classroomPort();
        SecureLoginClient login = new SecureLoginClient(config);

        // ---------- Member 5: TLS login ----------
        String lecToken = login.login("localhost", "lecturer", "lecturer123").token();
        check("TLS login lecturer", lecToken != null);
        boolean wrong = false;
        try {
            login.login("localhost", "s001", "nope-nope");
        } catch (SecureLoginClient.LoginException e) {
            wrong = true;
        }
        check("wrong password rejected", wrong);
        String s1Token = login.login("localhost", "s001", "s001pass").token();
        String s2Token = login.login("localhost", "s002", "s002pass").token();

        // ---------- Member 1: NIO server basics ----------
        Conn idle = new Conn(port); // never sends AUTH: must be kicked after 10 s (checked later)
        Conn lec = new Conn(port);
        lec.send("AUTH|" + lecToken);
        check("lecturer AUTH ok", lec.expect("OK|AUTH|lecturer|LECTURER", 3000) != null);
        lec.send("CREATE_SESSION|IN2120|Network Programming | week 5");
        String session = lec.expect("SESSION|", 3000);
        check("session created: " + session, session != null);
        String joinCode = session.split("\\|")[1];

        Conn bad = new Conn(port);
        bad.send("AUTH|not-a-real-token");
        check("invalid token rejected", bad.expect("ERR|invalid", 3000) != null);
        bad.close();

        Conn s1 = new Conn(port);
        s1.send("AUTH|" + s1Token);
        s1.expect("OK|AUTH", 3000);
        s1.send("CREATE_SESSION|HACK|evil");
        check("student cannot run lecturer commands", s1.expect("ERR|only a lecturer", 3000) != null);
        s1.raw("JOIN_SES");          // partial line: the server must wait for the rest
        Thread.sleep(300);
        s1.raw("SION|" + joinCode.toLowerCase() + "\n");
        check("partial line reassembled, student joined", s1.expect("SESSION|" + joinCode, 3000) != null);

        Conn s2 = new Conn(port);
        s2.send("AUTH|" + s2Token);
        s2.expect("OK|AUTH", 3000);
        s2.send("JOIN_SESSION|" + joinCode);
        s2.expect("SESSION|", 3000);

        // ---------- Member 3: UDP discovery ----------
        List<SessionInfo> found = new DiscoveryClient(config).discover(2000);
        boolean discovered = false;
        for (SessionInfo si : found) {
            discovered |= si.joinCode().equals(joinCode);
        }
        check("UDP discovery finds the session (no IP typed): " + found, discovered);

        // ---------- Members 5 + 3 + 4: attendance ----------
        s1.send("ATTEND|AAAAAA");
        check("attendance before open rejected", s1.expect("ERR|attendance is closed", 3000) != null);
        lec.send("START_ATTENDANCE|120");
        String codeLine = lec.expect("ATT_CODE|", 3000);
        check("lecturer receives rotating code: " + codeLine, codeLine != null);
        String code = codeLine.split("\\|")[1];
        check("students get ATT_OPEN", s1.expect("ATT_OPEN|", 3000) != null && s2.expect("ATT_OPEN|", 3000) != null);
        s1.send("ATTEND|ZZZZZ2");
        check("wrong code rejected", s1.expect("ERR|wrong or expired", 3000) != null);
        s1.send("ATTEND|" + code);
        check("correct code -> ATT_OK", s1.expect("ATT_OK|", 3000) != null);
        check("lecturer counter ATT_COUNT|1|2", lec.expect("ATT_COUNT|1|2", 3000) != null);
        s1.send("ATTEND|" + code);
        check("second attendance rejected", s1.expect("ERR|attendance already marked", 3000) != null);
        s2.send("ATTEND|" + code);
        check("s002 marked from same laptop", s2.expect("ATT_OK|", 3000) != null);
        check("lecturer warned: same device -> SUSPICIOUS", lec.expect("SYS|WARNING", 3000) != null);

        // ---------- Member 4: polls ----------
        lec.send("CREATE_POLL|10|Application;Transport;Network;Link|Which layer does TCP work in?");
        String list = lec.expect("POLL_LIST|P", 3000);
        String pollId = list.substring("POLL_LIST|".length()).split(":")[0];
        lec.send("CREATE_POLL|5|only one|Bad poll");
        check("invalid poll (1 option, 5 s) rejected", lec.expect("ERR|", 3000) != null);
        lec.send("START_POLL|" + pollId);
        check("students get POLL_START", s1.expect("POLL_START|" + pollId, 3000) != null && s2.expect("POLL_START|", 3000) != null);
        long pollStarted = System.currentTimeMillis();
        s1.send("VOTE|" + pollId + "|1");
        check("vote accepted", s1.expect("VOTE_OK|" + pollId, 3000) != null);
        s1.send("VOTE|" + pollId + "|2");
        check("second vote rejected", s1.expect("ERR|already voted", 3000) != null);
        s2.send("VOTE|" + pollId + "|9");
        check("invalid option rejected", s2.expect("ERR|invalid option", 3000) != null);
        s2.send("VOTE|" + pollId + "|2");
        s2.expect("VOTE_OK", 3000);
        check("lecturer live update 0;1;1;0 total 2", lec.expect("POLL_UPDATE|" + pollId + "|0;1;1;0|2", 3000) != null);

        // ---------- Member 3: URL import (needs run-question-web) ----------
        lec.send("IMPORT_POLLS|http://localhost:" + config.questionWebPort() + "/questions.csv");
        String imp = lec.expect("SYS|imported", 8000);
        if (imp == null) {
            System.out.println("  SKIP  URL import (is scripts/run-question-web running?)");
        } else {
            check("URL import: " + imp, imp.contains("imported 6"));
        }
        lec.send("IMPORT_POLLS|ftp://example.com/x.csv");
        check("non-http URL rejected", lec.expect("ERR|import failed", 5000) != null);

        String end = lec.expect("POLL_END|" + pollId, 12000);
        long took = System.currentTimeMillis() - pollStarted;
        check("poll closed automatically after ~10 s (" + took + " ms): " + end, end != null && took > 9000 && took < 11500);
        check("students get POLL_END", s1.expect("POLL_END|" + pollId, 3000) != null);
        s2.send("VOTE|" + pollId + "|0");
        check("vote after close rejected", s2.expect("ERR|poll closed", 3000) != null);

        // ---------- Member 1: protection ----------
        check("silent client kicked after AUTH timeout", idle.expect("ERR|AUTH timeout", 2000) != null);
        Conn big = new Conn(port);
        big.send("AUTH|" + login.login("localhost", "s003", "s003pass").token());
        big.expect("OK|AUTH", 3000);
        big.raw("X".repeat(9000));
        check("line > 8192 bytes -> ERR|line too long", big.expect("ERR|line too long", 3000) != null);
        Conn s1b = new Conn(port);
        s1b.send("AUTH|" + s1Token);
        check("second login of s001 replaces the first", s1.expect("ERR|logged in elsewhere", 3000) != null);
        s1b.send("PING");
        check("PING -> PONG", s1b.expect("PONG", 3000) != null);

        // ---------- Member 4: two parallel exports ----------
        ExportClient exporter = new ExportClient(config);
        File dir = Files.createTempDirectory("cp-export").toFile();
        File a = new File(dir, "att.csv");
        File p = new File(dir, "polls.csv");
        AtomicReference<String> errA = new AtomicReference<>();
        AtomicReference<String> errP = new AtomicReference<>();
        long t0 = System.currentTimeMillis();
        Thread ta = new Thread(() -> {
            try {
                exporter.download("localhost", lecToken, "ATTENDANCE", joinCode, a, x -> { });
            } catch (Exception e) {
                errA.set(e.getMessage());
            }
        });
        Thread tp = new Thread(() -> {
            try {
                exporter.download("localhost", lecToken, "POLLS", joinCode, p, x -> { });
            } catch (Exception e) {
                errP.set(e.getMessage());
            }
        });
        ta.start();
        tp.start();
        ta.join();
        tp.join();
        long exportMs = System.currentTimeMillis() - t0;
        check("two exports downloaded (" + exportMs + " ms; parallel if < 2x demo delay)", errA.get() == null && errP.get() == null);
        String att = Files.readString(a.toPath());
        check("attendance CSV has username,time,ip,hostname,status", att.contains("username,time,ip,hostname,status")
                && att.contains("s001,") && att.contains("SUSPICIOUS"));
        check("poll CSV has counts and percentages", Files.readString(p.toPath()).contains(",Transport,1,50.0,2,CLOSED"));
        boolean denied = false;
        try {
            exporter.download("localhost", s2Token, "ATTENDANCE", joinCode, a, x -> { });
        } catch (Exception e) {
            denied = e.getMessage().contains("lecturer");
        }
        check("student token cannot export", denied);

        lec.send("END_SESSION");
        check("students told session ended", s2.expect("SYS|Session ended", 3000) != null);

        for (Conn c : new Conn[] {idle, lec, s1, s2, big, s1b}) {
            c.close();
        }
        System.out.println();
        System.out.println("SmokeTest: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }
}
