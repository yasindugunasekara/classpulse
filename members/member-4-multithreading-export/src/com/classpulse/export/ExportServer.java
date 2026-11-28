// Owner: Member 4, Thread pools | Lecture topic: ServerSocket accept loop + fixed ExecutorService
package com.classpulse.export;

import com.classpulse.common.Config;
import com.classpulse.common.Log;
import com.classpulse.common.NamedThreadFactory;
import com.classpulse.engine.AttendanceBook;
import com.classpulse.engine.PollManager;
import com.classpulse.security.TokenStore;
import com.classpulse.server.nio.ClassSession;
import com.classpulse.server.nio.SessionRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Classic "blocking server + thread pool" design (the opposite of Member 1's NIO server):
 *   one thread blocks in accept(); every accepted Socket becomes an ExportTask that a FIXED pool
 *   of 4 worker threads executes.
 *
 * Why a FIXED pool and not "new Thread per request"? Creating threads is expensive and unlimited
 * threads can exhaust memory if many requests arrive. With 4 workers, at most 4 exports run at the
 * same time; extra requests wait in the pool's queue.
 */
public class ExportServer {

    private static final String COMPONENT = "Export";

    private final Config config;
    private final TokenStore tokens;
    private final SessionRegistry registry;
    private final AttendanceBook attendance;
    private final PollManager polls;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, new NamedThreadFactory("export-worker", true));
    private ServerSocket serverSocket;
    private volatile boolean running;

    public ExportServer(Config config, TokenStore tokens, SessionRegistry registry, AttendanceBook attendance, PollManager polls) {
        this.config = config;
        this.tokens = tokens;
        this.registry = registry;
        this.attendance = attendance;
        this.polls = polls;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.exportPort());
        running = true;
        Thread t = new Thread(this::acceptLoop, "export-accept");
        t.setDaemon(true);
        t.start();
        Log.info(COMPONENT, "export server listening on port " + config.exportPort() + " (pool of 4 workers)");
    }

    private void acceptLoop() {
        int delay = config.getInt("export.demoDelayMs", 0);
        while (running) {
            try {
                Socket s = serverSocket.accept();                 // blocks until a client connects
                Log.info(COMPONENT, "accepted " + s.getRemoteSocketAddress() + ", handing to the pool");
                workers.execute(new ExportTask(s, tokens, registry, attendance, polls, delay));
            } catch (IOException e) {
                if (running) {
                    Log.error(COMPONENT, "accept failed: " + e.getMessage());
                }
            }
        }
    }

    /** When a session ends, save both CSVs into exports/ in the background (on a worker thread). */
    public void autoSave(String joinCode) {
        workers.execute(() -> {
            ClassSession s = registry.get(joinCode);
            if (s == null) {
                return;
            }
            try {
                Path dir = Paths.get("exports");
                Files.createDirectories(dir);
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path a = dir.resolve(joinCode + "_attendance_" + stamp + ".csv");
                Path p = dir.resolve(joinCode + "_polls_" + stamp + ".csv");
                Files.writeString(a, CsvBuilder.attendance(s, attendance.records(joinCode)), StandardCharsets.UTF_8);
                Files.writeString(p, CsvBuilder.polls(s, polls.pollsOf(joinCode)), StandardCharsets.UTF_8);
                Log.info(COMPONENT, "auto-saved " + a + " and " + p);
            } catch (IOException e) {
                Log.error(COMPONENT, "auto-save failed: " + e.getMessage());
            }
        });
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        workers.shutdown();
    }
}
