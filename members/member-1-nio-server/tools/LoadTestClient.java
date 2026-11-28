// Owner: Member 1, Java NIO (load test) | Lecture topic: Showing that one selector thread scales to 100 clients
package com.classpulse.tools;

import com.classpulse.common.Config;
import com.classpulse.common.Protocol;
import com.classpulse.security.SecureLoginClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless load test: N fake students (default 100, users load001..load100, registered if needed)
 *   1. log in over TLS,
 *   2. connect to the NIO server, AUTH, JOIN_SESSION,
 *   3. mark attendance when it is open (reads the code from data/current_code.txt, which the
 *      server writes only when debug.exposeAttendanceCode=true),
 *   4. vote a random option on the running poll (or the next poll started within waitSec).
 *
 * Usage: java -cp out com.classpulse.tools.LoadTestClient [host] [students] [waitSec]
 * Start a session first (and ideally attendance + a poll). The server log will show that all
 * 100 connections are served by the single thread "NIO-selector".
 */
public class LoadTestClient {

    private static final Path CODE_FILE = Paths.get("data", "current_code.txt");

    private static final AtomicInteger loggedIn = new AtomicInteger();
    private static final AtomicInteger joined = new AtomicInteger();
    private static final AtomicInteger attended = new AtomicInteger();
    private static final AtomicInteger voted = new AtomicInteger();
    private static final AtomicInteger errors = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int waitSec = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        Config config = Config.load();

        String[] codeLine = readCodeFile();
        if (codeLine == null || "-".equals(codeLine[0])) {
            System.out.println("No active session found in " + CODE_FILE + ". Create a session in the Lecturer Dashboard first,");
            System.out.println("and make sure debug.exposeAttendanceCode=true in config.properties.");
            return;
        }
        String joinCode = codeLine[0];
        System.out.println("Load test: " + n + " students -> " + host + ", session " + joinCode + ", wait up to " + waitSec + " s");

        // Step 1: TLS logins (10 in parallel; each one is a full TLS handshake)
        String[] tokens = new String[n];
        SecureLoginClient login = new SecureLoginClient(config);
        ExecutorService loginPool = Executors.newFixedThreadPool(10);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            loginPool.execute(() -> {
                String user = String.format("load%03d", idx + 1);
                String pass = user + "pass";
                try {
                    tokens[idx] = login.login(host, user, pass).token();
                } catch (SecureLoginClient.LoginException e) {
                    try {
                        tokens[idx] = login.register(host, user, pass).token(); // first run: create the user
                    } catch (SecureLoginClient.LoginException e2) {
                        System.out.println(user + ": login failed: " + e2.getMessage());
                        errors.incrementAndGet();
                        return;
                    }
                }
                loggedIn.incrementAndGet();
            });
        }
        loginPool.shutdown();
        loginPool.awaitTermination(2, TimeUnit.MINUTES);
        System.out.println("TLS logins done: " + loggedIn.get() + "/" + n + " in " + (System.currentTimeMillis() - t0) + " ms");

        // Step 2..4: one client thread per fake student (the SERVER still uses only one thread!)
        CountDownLatch done = new CountDownLatch(n);
        long deadline = System.currentTimeMillis() + waitSec * 1000L;
        for (int i = 0; i < n; i++) {
            final String token = tokens[i];
            final String user = String.format("load%03d", i + 1);
            Thread t = new Thread(() -> {
                try {
                    if (token != null) {
                        runStudent(host, config.classroomPort(), user, token, joinCode, deadline);
                    }
                } finally {
                    done.countDown();
                }
            }, "student-" + user);
            t.start();
            Thread.sleep(5); // a tiny stagger, like real students who do not all click in the same millisecond
        }
        done.await();
        System.out.println();
        System.out.println("===== Load test result =====");
        System.out.println("logged in : " + loggedIn.get() + "/" + n);
        System.out.println("joined    : " + joined.get() + "/" + n);
        System.out.println("attended  : " + attended.get() + "/" + n + "  (all from one IP, so most are flagged SUSPICIOUS: expected)");
        System.out.println("voted     : " + voted.get() + "/" + n);
        System.out.println("errors    : " + errors.get());
        System.out.println("Total time: " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static void runStudent(String host, int port, String user, String token, String joinCode, long deadline) {
        Random random = new Random();
        boolean attendanceOpen = false;
        boolean hasAttended = false;
        boolean hasVoted = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(1000); // wake up every second to check the deadline
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.print("AUTH|" + token + "\n");
            out.print("JOIN_SESSION|" + joinCode + "\n");
            out.flush();
            int attendTries = 0;
            while (System.currentTimeMillis() < deadline) {
                String line;
                try {
                    line = in.readLine();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (line == null) {
                    break; // server closed
                }
                String[] p = Protocol.split(line, 5);
                switch (p[0]) {
                    case "SESSION" -> joined.incrementAndGet();
                    case "ATT_OPEN" -> {
                        attendanceOpen = true;
                        attendTries++;
                        sendAttend(out);
                    }
                    case "ATT_OK" -> {
                        if (!hasAttended) {
                            hasAttended = true;
                            attended.incrementAndGet();
                        }
                    }
                    case "POLL_START" -> {
                        int options = p[3].split(";").length;
                        out.print("VOTE|" + p[1] + "|" + random.nextInt(options) + "\n");
                        out.flush();
                    }
                    case "VOTE_OK" -> {
                        hasVoted = true;
                        voted.incrementAndGet();
                    }
                    case "ERR" -> {
                        String reason = p.length > 1 ? p[1] : "";
                        if (reason.contains("expired") && attendTries < 3) {
                            attendTries++;
                            sendAttend(out); // the code rotated while we were sending: re-read it
                        } else if (!reason.contains("already")) {
                            System.out.println(user + ": " + line);
                            errors.incrementAndGet();
                        }
                    }
                    default -> { }
                }
                if (hasVoted && (hasAttended || !attendanceOpen)) {
                    break; // this student is finished
                }
            }
            out.print("QUIT\n");
            out.flush();
        } catch (Exception e) {
            System.out.println(user + ": " + e.getMessage());
            errors.incrementAndGet();
        }
    }

    private static void sendAttend(PrintWriter out) {
        String[] code = readCodeFile();
        if (code != null && !"-".equals(code[1])) {
            out.print("ATTEND|" + code[1] + "\n");
            out.flush();
        }
    }

    private static String[] readCodeFile() {
        try {
            String[] p = Files.readString(CODE_FILE).trim().split("\\|");
            return p.length == 2 ? p : null;
        } catch (Exception e) {
            return null;
        }
    }
}
