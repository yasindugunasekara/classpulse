// Owner: Member 2, TCP clients (test) | Lecture topic: Socket life cycle, reader thread, disconnect detection
package com.classpulse.tests;

import com.classpulse.client.ClassroomClient;
import com.classpulse.client.ServerListener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Tests ClassroomClient against a tiny fake server (a plain ServerSocket), so no real server is needed.
 * Checks: connect + AUTH as first line, JOIN_SESSION after AUTH, lines delivered to the listener,
 * send() writes exactly one line, server close is detected, connect to a closed port fails clearly.
 * Run: java -cp out com.classpulse.tests.ClientTest
 */
public class ClientTest {

    public static void main(String[] args) throws Exception {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        BlockingQueue<String> status = new LinkedBlockingQueue<>();
        ServerListener listener = new ServerListener() {
            @Override
            public void onLine(String line) {
                lines.add(line);
            }

            @Override
            public void onConnectionStatus(String message, boolean connected) {
                status.add((connected ? "UP:" : "DOWN:") + message);
            }
        };

        try (ServerSocket fake = new ServerSocket(0)) {
            int port = fake.getLocalPort();
            // The token supplier fails, so auto-reconnect gives up quickly (we only test detection here).
            ClassroomClient client = new ClassroomClient("localhost", port, () -> {
                throw new IllegalStateException("no reconnect in test");
            }, listener);
            client.rememberJoinCode("ABC123");
            client.connect("token-xyz");

            try (Socket peer = fake.accept()) {
                peer.setSoTimeout(3000);
                BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(peer.getOutputStream(), true, StandardCharsets.UTF_8);

                check("first line is AUTH|token", "AUTH|token-xyz".equals(in.readLine()));
                check("JOIN_SESSION sent after AUTH", "JOIN_SESSION|ABC123".equals(in.readLine()));
                check("listener told: connected", poll(status).startsWith("UP:"));
                check("isConnected() true", client.isConnected());

                out.println("POLL_START|P1|30|A;B|question");
                check("server line reaches listener (reader thread)", "POLL_START|P1|30|A;B|question".equals(poll(lines)));

                check("send() returns true", client.send("VOTE|P1|0"));
                check("server receives exactly the sent line", "VOTE|P1|0".equals(in.readLine()));

                // 20 threads send at once: every line must arrive whole (synchronized send)
                Thread[] ts = new Thread[20];
                for (int i = 0; i < ts.length; i++) {
                    int n = i;
                    ts[i] = new Thread(() -> client.send("PING|" + n + "|" + "x".repeat(500)));
                    ts[i].start();
                }
                for (Thread t : ts) {
                    t.join();
                }
                boolean allWhole = true;
                for (int i = 0; i < ts.length; i++) {
                    String l = in.readLine();
                    allWhole &= l != null && l.matches("PING\\|\\d+\\|x{500}");
                }
                check("20 parallel sends never interleave", allWhole);
            } // peer closed here -> client must notice

            String down = poll(status);
            check("server close detected: " + down, down != null && down.startsWith("DOWN:") && down.contains("lost"));
            check("isConnected() false after close", !client.isConnected());
            client.close();
        }

        // Connecting to a port where nothing listens
        int freePort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            freePort = tmp.getLocalPort();
        }
        ClassroomClient nobody = new ClassroomClient("localhost", freePort, () -> "t", listener);
        boolean refused = false;
        try {
            nobody.connect("t");
        } catch (ConnectException e) {
            refused = true;
            check("friendly message for ConnectException", ClassroomClient.friendly(e).contains("not running"));
        }
        check("connect to closed port -> ConnectException", refused);
        check("friendly message for UnknownHostException",
                ClassroomClient.friendly(new UnknownHostException("x")).equals("unknown host name"));

        ClassroomClient notConnected = new ClassroomClient("localhost", freePort, () -> "t", listener);
        check("send() without connection returns false", !notConnected.send("PING"));

        System.out.println("ClientTest: ALL PASSED");
        System.exit(0);
    }

    private static String poll(BlockingQueue<String> q) throws InterruptedException {
        return q.poll(3, TimeUnit.SECONDS);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (!ok) {
            System.out.println("ClientTest: FAILED");
            System.exit(1);
        }
    }
}
