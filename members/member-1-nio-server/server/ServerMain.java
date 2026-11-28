// Owner: Member 1, Java NIO (server start-up) | Lecture topic: Running several network services in one JVM
package com.classpulse.server;

import com.classpulse.addressing.NetworkGuard;
import com.classpulse.addressing.QuestionImporter;
import com.classpulse.common.Config;
import com.classpulse.common.Log;
import com.classpulse.engine.AttendanceBook;
import com.classpulse.engine.PollManager;
import com.classpulse.export.ExportServer;
import com.classpulse.security.AttendanceCodeGenerator;
import com.classpulse.security.SecureLoginServer;
import com.classpulse.security.TokenStore;
import com.classpulse.security.UserStore;
import com.classpulse.server.nio.ClassroomServer;
import com.classpulse.server.nio.SessionRegistry;
import com.classpulse.udp.DiscoveryServer;

import java.net.InetAddress;
import java.nio.file.Paths;

/**
 * Starts the whole ClassPulse server on the lecturer's laptop:
 *   port 6000  NIO classroom server      (Member 1)
 *   port 6001  TLS login                 (Member 5)
 *   port 6002  UDP discovery             (Member 3)
 *   port 6003  CSV export + thread pool  (Member 4)
 * The shared objects are created ONCE here and passed to the services by constructor
 * (no global variables), so every service sees the same users, tokens, polls and sessions.
 */
public class ServerMain {

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("main");
        Config config = Config.load();

        // ---- shared in-memory state ----
        UserStore users = new UserStore(Paths.get("data", "users.txt"));                    // Member 5
        TokenStore tokens = new TokenStore();                                                // Member 5
        AttendanceCodeGenerator codes = new AttendanceCodeGenerator(config.getInt("attendance.codeWindowSec", 30)); // M5
        SessionRegistry registry = new SessionRegistry();                                    // Member 1
        PollManager polls = new PollManager();                                               // Member 4
        AttendanceBook attendance = new AttendanceBook(polls.scheduler());                   // Member 4
        NetworkGuard guard = new NetworkGuard(config);                                       // Member 3
        QuestionImporter importer = new QuestionImporter();                                  // Member 3

        // ---- the four services ----
        SecureLoginServer login = new SecureLoginServer(config, users, tokens);
        ClassroomServer classroom = new ClassroomServer(config, registry, tokens, codes, polls, attendance, guard, importer);
        DiscoveryServer discovery = new DiscoveryServer(config, registry);
        ExportServer export = new ExportServer(config, tokens, registry, attendance, polls);
        classroom.setSessionEndListener(export::autoSave);

        try {
            login.start();
        } catch (Exception e) {
            Log.error("Main", "TLS login could not start: " + e.getMessage());
            Log.error("Main", "Run scripts/gen-keystore.bat (Windows) or sh scripts/gen-keystore.sh first.");
            System.exit(1);
        }
        classroom.start();
        discovery.start();
        export.start();

        InetAddress lan = NetworkGuard.lanAddress();
        System.out.println();
        System.out.println("==============================================================");
        System.out.println("  ClassPulse server is running");
        System.out.println("  LAN IP      : " + lan.getHostAddress() + "   (host name " + lan.getHostName() + ")");
        System.out.println("  Classroom   : TCP " + config.classroomPort() + "  (Java NIO, 1 selector thread)");
        System.out.println("  Login       : TCP " + config.loginPort() + "  (TLS)");
        System.out.println("  Discovery   : UDP " + config.discoveryPort() + "  (announce to UDP " + config.announcePort() + ")");
        System.out.println("  Export      : TCP " + config.exportPort() + "  (4 worker threads)");
        System.out.println("  Users       : " + users.size() + " (demo: lecturer/lecturer123, s001/s001pass ...)");
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println("==============================================================");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Main", "shutting down");
            classroom.stop();
            login.stop();
            discovery.stop();
            export.stop();
            polls.shutdown();
            guard.shutdown();
            importer.shutdown();
        }, "shutdown"));
    }
}
