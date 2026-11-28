// Owner: Member 3, UDP / addressing / URL (test) | Lecture topic: InetAddress, subnets, UDP messages, URL + HttpURLConnection
package com.classpulse.tests;

import com.classpulse.addressing.NetworkGuard;
import com.classpulse.addressing.QuestionImporter;
import com.classpulse.common.Config;
import com.classpulse.udp.SessionInfo;
import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Member 3 checks without a running ClassPulse server:
 * UDP discovery message parsing, subnet / same-device rules, reverse DNS on a pool thread,
 * and URL question import from a local HTTP server (good file, bad lines, 404, wrong scheme, bad host).
 * Run: java -cp out com.classpulse.tests.AddressingUrlTest
 */
public class AddressingUrlTest {

    public static void main(String[] args) throws Exception {
        InetAddress from = InetAddress.getByName("192.168.1.10");

        // ---- UDP discovery messages ----
        SessionInfo info = SessionInfo.parse("CLASSPULSE_HERE|6000|6001|6003|ABC123|IN2120|Networks", from);
        check("HERE message parsed", info != null && info.classPort() == 6000 && info.loginPort() == 6001
                && info.exportPort() == 6003 && "ABC123".equals(info.joinCode()));
        check("host is taken from the datagram sender", info != null && "192.168.1.10".equals(info.host()));
        check("ANNOUNCE message parsed", SessionInfo.parse("CLASSPULSE_ANNOUNCE|6000|6001|6003|XYZ789|IN2120|T", from) != null);
        check("random datagram ignored", SessionInfo.parse("hello world", from) == null);
        check("bad port ignored", SessionInfo.parse("CLASSPULSE_HERE|abc|6001|6003|ABC123|M|T", from) == null);
        check("bad join code length ignored", SessionInfo.parse("CLASSPULSE_HERE|6000|6001|6003|AB|M|T", from) == null);
        check("loopback detection", SessionInfo.parse("CLASSPULSE_HERE|6000|6001|6003|ABC123|M|T",
                InetAddress.getLoopbackAddress()).isLoopback());

        // ---- NetworkGuard: subnet + same device ----
        Properties p = new Properties();
        p.setProperty("attendance.requireSameSubnet", "true");
        p.setProperty("attendance.maxPerDevice", "1");
        NetworkGuard guard = new NetworkGuard(new Config(p));
        check("loopback always allowed", guard.isInClassroomSubnet(InetAddress.getLoopbackAddress()));
        check("address from TEST-NET-3 (203.0.113.7) rejected",
                !guard.isInClassroomSubnet(InetAddress.getByName("203.0.113.7")));
        check("lecturer's own LAN address allowed", guard.isInClassroomSubnet(NetworkGuard.lanAddress()));
        check("1st student on a device", guard.registerDevice("ABC123", "10.0.0.5", "s001") == 1);
        check("same student again still counts 1", guard.registerDevice("ABC123", "10.0.0.5", "s001") == 1);
        int n = guard.registerDevice("ABC123", "10.0.0.5", "s002");
        check("2nd student on same device -> SUSPICIOUS", n == 2 && guard.isSuspicious(n));
        check("other session is separate", guard.registerDevice("XYZ789", "10.0.0.5", "s003") == 1);
        check("studentsOnDevice lists both", guard.studentsOnDevice("ABC123", "10.0.0.5").size() == 2);

        CompletableFuture<String> name = new CompletableFuture<>();
        guard.resolveHostnameAsync(InetAddress.getLoopbackAddress(), name::complete);
        String host = name.get(5, TimeUnit.SECONDS);
        check("reverse DNS runs on pool thread: 127.0.0.1 -> " + host, host != null && !host.isEmpty());
        guard.shutdown();

        Properties off = new Properties();
        off.setProperty("attendance.requireSameSubnet", "false");
        NetworkGuard open = new NetworkGuard(new Config(off));
        check("requireSameSubnet=false allows any address", open.isInClassroomSubnet(InetAddress.getByName("203.0.113.7")));
        open.shutdown();

        // ---- URL import from a local web server ----
        HttpServer web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String csv = "question,timeLimitSec,option1,option2,option3,option4\n"
                + "# comment line\n"
                + "Which layer does TCP work on?,30,Transport,Network,Application\n"
                + "Is UDP reliable?,20,Yes,No\n"
                + "too short line,30\n"
                + "Bad time,5,A,B\n";
        web.createContext("/questions.csv", ex -> {
            byte[] b = csv.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/csv");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        web.createContext("/image.png", ex -> {
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, 3);
            ex.getResponseBody().write(new byte[3]);
            ex.close();
        });
        web.start();
        String base = "http://127.0.0.1:" + web.getAddress().getPort();
        QuestionImporter importer = new QuestionImporter();

        QuestionImporter.ImportResult ok = importer.importNow(base + "/questions.csv");
        check("2 good questions imported, 2 bad lines skipped",
                ok.error() == null && ok.questions().size() == 2 && ok.skipped() == 2);
        check("options parsed", ok.questions().get(0).options().size() == 3
                && ok.questions().get(0).timeLimitSec() == 30);
        check("HTTP 404 reported", importer.importNow(base + "/missing.csv").error().contains("404"));
        check("non-text content type rejected", importer.importNow(base + "/image.png").error().contains("CSV"));
        check("ftp:// rejected", importer.importNow("ftp://127.0.0.1/q.csv").error().contains("http"));
        check("garbage URL rejected", importer.importNow("::not a url::").error() != null);
        check("unknown host reported", importer.importNow("http://no-such-host.invalid/q.csv").error() != null);

        CompletableFuture<QuestionImporter.ImportResult> async = new CompletableFuture<>();
        importer.importAsync(base + "/questions.csv", async::complete);
        check("importAsync delivers result", async.get(5, TimeUnit.SECONDS).questions().size() == 2);

        importer.shutdown();
        web.stop(0);
        System.out.println("AddressingUrlTest: ALL PASSED");
        System.exit(0);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (!ok) {
            System.out.println("AddressingUrlTest: FAILED");
            System.exit(1);
        }
    }
}
