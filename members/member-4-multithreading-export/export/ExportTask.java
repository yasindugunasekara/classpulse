// Owner: Member 4, Thread pools | Lecture topic: One task per connection, executed by a worker thread
package com.classpulse.export;

import com.classpulse.common.Log;
import com.classpulse.common.Protocol;
import com.classpulse.engine.AttendanceBook;
import com.classpulse.engine.PollManager;
import com.classpulse.security.Principal;
import com.classpulse.security.TokenStore;
import com.classpulse.server.nio.ClassSession;
import com.classpulse.server.nio.SessionRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Handles ONE export request on a pool thread ("export-worker-N"):
 *   EXPORT|token|ATTENDANCE|joinCode   or   EXPORT|token|POLLS|joinCode
 * Reply: OK|fileName|sizeBytes\n  followed by exactly sizeBytes of CSV,  or  ERR|reason
 * Sending the size first lets the client show progress and know when the file is complete.
 */
public class ExportTask implements Runnable {

    private static final String COMPONENT = "Export";

    private final Socket socket;
    private final TokenStore tokens;
    private final SessionRegistry registry;
    private final AttendanceBook attendance;
    private final PollManager polls;
    private final int demoDelayMs;

    public ExportTask(Socket socket, TokenStore tokens, SessionRegistry registry, AttendanceBook attendance,
                      PollManager polls, int demoDelayMs) {
        this.socket = socket;
        this.tokens = tokens;
        this.registry = registry;
        this.attendance = attendance;
        this.polls = polls;
        this.demoDelayMs = demoDelayMs;
    }

    @Override
    public void run() {
        // try-with-resources: the socket is closed whatever happens
        try (Socket s = socket) {
            s.setSoTimeout(30_000); // a client that never sends its request cannot block a worker forever
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            String line = in.readLine();
            Log.info(COMPONENT, "task started for " + s.getRemoteSocketAddress());

            String[] p = line == null ? new String[0] : Protocol.split(line, 4);
            if (p.length != 4 || !Protocol.EXPORT.equals(p[0])) {
                reply(out, "ERR|usage EXPORT|token|ATTENDANCE or POLLS|joinCode");
                return;
            }
            Principal who = tokens.validate(p[1]);
            if (who == null || !who.isLecturer()) {
                reply(out, "ERR|only a logged-in lecturer may export");
                return;
            }
            ClassSession session = registry.get(p[3]);
            if (session == null || !session.lecturerUsername().equals(who.username())) {
                reply(out, "ERR|no such session for this lecturer");
                return;
            }
            String csv;
            String fileName;
            if (Protocol.EXPORT_ATTENDANCE.equals(p[2])) {
                csv = CsvBuilder.attendance(session, attendance.records(session.joinCode()));
                fileName = session.joinCode() + "_attendance.csv";
            } else if (Protocol.EXPORT_POLLS.equals(p[2])) {
                csv = CsvBuilder.polls(session, polls.pollsOf(session.joinCode()));
                fileName = session.joinCode() + "_polls.csv";
            } else {
                reply(out, "ERR|type must be ATTENDANCE or POLLS");
                return;
            }
            if (demoDelayMs > 0) {
                Thread.sleep(demoDelayMs); // DEMO ONLY: makes two parallel exports visible in the log
            }
            byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
            reply(out, Protocol.join("OK", fileName, bytes.length)); // header line first
            out.write(bytes);                                         // then the raw file bytes
            out.flush();
            Log.info(COMPONENT, "sent " + fileName + " (" + bytes.length + " bytes) to " + who.username());
        } catch (IOException e) {
            Log.warn(COMPONENT, "export failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
