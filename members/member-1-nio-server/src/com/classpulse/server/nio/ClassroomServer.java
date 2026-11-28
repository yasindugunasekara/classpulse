// Owner: Member 1, Java NIO | Lecture topic: Selector, ServerSocketChannel, SocketChannel, ByteBuffer, non-blocking I/O
package com.classpulse.server.nio;

import com.classpulse.addressing.NetworkGuard;
import com.classpulse.addressing.QuestionImporter;
import com.classpulse.common.Config;
import com.classpulse.common.Log;
import com.classpulse.common.Protocol;
import com.classpulse.engine.AttendanceBook;
import com.classpulse.engine.AttendanceRecord;
import com.classpulse.engine.Poll;
import com.classpulse.engine.PollManager;
import com.classpulse.security.AttendanceCodeGenerator;
import com.classpulse.security.Principal;
import com.classpulse.security.TokenStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The classroom server: ONE thread ("NIO-selector") serves ALL lecturers and students.
 *
 * How it works:
 *   1. All channels are in NON-BLOCKING mode and registered with one Selector.
 *   2. selector.select() sleeps until at least one channel is ready (new client, data to read,
 *      or room to write), then tells us WHICH ones via selectedKeys().
 *   3. We handle each ready key quickly and go back to select(). No thread ever waits on one
 *      slow client, so 100 students need 1 thread instead of 100.
 *
 * Thread hand-off: other threads (poll timers, URL import, DNS) must NEVER touch a channel.
 * They call submit(task); the task is queued and selector.wakeup() makes select() return at once,
 * so the NIO thread runs the task in its next loop iteration.
 */
public final class ClassroomServer implements PollManager.Listener {

    private static final String COMPONENT = "Classroom";
    private static final long AUTH_TIMEOUT_MS = 10_000;

    private final Config config;
    private final SessionRegistry registry;
    private final TokenStore tokens;
    private final AttendanceCodeGenerator codes;
    private final PollManager polls;
    private final AttendanceBook attendance;
    private final NetworkGuard guard;
    private final QuestionImporter importer;
    private final boolean exposeCode;
    private final boolean showResultsAfterVote;

    /** Tasks from other threads, executed by the NIO thread. */
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    /** All open connections (NIO thread only). */
    private final Set<ClientConnection> connections = new HashSet<>();

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread nioThread;
    private volatile boolean running;
    private long lastHousekeeping;
    private Consumer<String> sessionEndListener = code -> { };

    public ClassroomServer(Config config, SessionRegistry registry, TokenStore tokens, AttendanceCodeGenerator codes,
                           PollManager polls, AttendanceBook attendance, NetworkGuard guard, QuestionImporter importer) {
        this.config = config;
        this.registry = registry;
        this.tokens = tokens;
        this.codes = codes;
        this.polls = polls;
        this.attendance = attendance;
        this.guard = guard;
        this.importer = importer;
        this.exposeCode = config.getBool("debug.exposeAttendanceCode", false);
        this.showResultsAfterVote = config.getBool("poll.showResultsAfterVote", true);
        polls.setListener(this);
    }

    /** Called (on the NIO thread) when a session ends, e.g. to auto-save CSV files. */
    public void setSessionEndListener(Consumer<String> listener) {
        this.sessionEndListener = listener;
    }

    // =====================================================================================
    // 1. Start-up and the selector loop
    // =====================================================================================

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        // all interfaces, port 6000. Backlog 256 = how many connections the OS queues before we accept()
        // them; the default (50) is too small when a whole class connects at the same second.
        serverChannel.bind(new InetSocketAddress(config.classroomPort()), 256);
        serverChannel.configureBlocking(false);                            // accept() will never block
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);          // "wake me when a client connects"
        running = true;
        nioThread = new Thread(this::runLoop, "NIO-selector");
        nioThread.start();
        Log.info(COMPONENT, "NIO classroom server listening on port " + config.classroomPort());
    }

    private void runLoop() {
        Log.info(COMPONENT, "selector loop started (this ONE thread serves every client)");
        while (running) {
            try {
                // Wait at most 500 ms, so timeouts and code rotation are checked even when it is quiet.
                selector.select(500);
            } catch (IOException e) {
                Log.error(COMPONENT, "select failed: " + e.getMessage());
                continue;
            }
            runPendingTasks(); // work handed over by other threads

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove(); // must remove, otherwise the key is handled again next time
                try {
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        accept();
                    } else {
                        if (key.isReadable()) {
                            read(key);
                        }
                        if (key.isValid() && key.isWritable()) {
                            flush((ClientConnection) key.attachment());
                        }
                    }
                } catch (Exception e) {
                    // One broken client must never kill the selector thread: close only that client.
                    Object att = key.attachment();
                    if (att instanceof ClientConnection c) {
                        close(c, "error: " + e);
                    } else {
                        key.cancel();
                    }
                }
            }
            housekeeping();
        }
        closeAll();
    }

    private void runPendingTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                Log.error(COMPONENT, "task failed: " + e);
            }
        }
    }

    /** Thread-safe: queue a task for the NIO thread and wake the selector. */
    public void submit(Runnable task) {
        tasks.add(task);
        // Without wakeup() the NIO thread could sleep in select() for up to 500 ms before noticing.
        selector.wakeup();
    }

    /** Runs immediately if we already are the NIO thread, otherwise hands over with submit(). */
    private void runOnNio(Runnable task) {
        if (Thread.currentThread() == nioThread) {
            task.run();
        } else {
            submit(task);
        }
    }

    // =====================================================================================
    // 2. Accept, read, write, close
    // =====================================================================================

    private void accept() throws IOException {
        SocketChannel ch = serverChannel.accept();
        if (ch == null) {
            return; // in non-blocking mode accept() may return null
        }
        ch.configureBlocking(false);                                 // client channel non-blocking too
        SelectionKey key = ch.register(selector, SelectionKey.OP_READ); // only interested in reads for now
        InetSocketAddress remote = (InetSocketAddress) ch.getRemoteAddress();
        ClientConnection c = new ClientConnection(ch, key, remote.getAddress(), remote.toString());
        key.attach(c); // the key now carries "who is this"
        connections.add(c);
        Log.info(COMPONENT, "accepted " + remote + " (clients=" + connections.size() + ")");
    }

    private void read(SelectionKey key) throws IOException {
        ClientConnection c = (ClientConnection) key.attachment();
        int n = c.channel.read(c.readBuffer); // reads only the bytes that already arrived, never blocks
        if (n == -1) {
            close(c, "client disconnected"); // -1 = the other side closed the connection
            return;
        }
        c.readBuffer.flip(); // switch the buffer from "writing into it" to "reading out of it"
        List<String> lines = c.extractLines(new ArrayList<>());
        c.readBuffer.clear(); // ready for the next read
        if (lines == null) {
            sendAndClose(c, "ERR|line too long");
            return;
        }
        for (String line : lines) {
            if (!key.isValid() || c.closeAfterWrite) {
                break;
            }
            handleLine(c, line);
        }
    }

    /** Queue one line for a client and try to write it straight away. */
    private void send(ClientConnection c, String line) {
        if (!c.key.isValid()) {
            return;
        }
        c.writeQueue.add(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));
        flush(c);
    }

    private void sendAndClose(ClientConnection c, String line) {
        c.closeAfterWrite = true;
        send(c, line);
    }

    /**
     * Writes as much of the queue as the socket accepts.
     * write() may write only PART of a buffer when the client's receive window / our send buffer is
     * full (slow Wi-Fi). Then we keep the rest and turn ON OP_WRITE, so the selector tells us when
     * there is room again. When the queue is empty we turn OP_WRITE OFF again, otherwise select()
     * would return immediately forever (a socket is almost always writable) and burn 100% CPU.
     */
    private void flush(ClientConnection c) {
        try {
            while (!c.writeQueue.isEmpty()) {
                ByteBuffer buf = c.writeQueue.peek();
                c.channel.write(buf);
                if (buf.hasRemaining()) {
                    break; // socket full: try again when OP_WRITE fires
                }
                c.writeQueue.poll();
            }
        } catch (IOException e) {
            close(c, "write failed: " + e.getMessage());
            return;
        }
        if (!c.key.isValid()) {
            return;
        }
        if (c.writeQueue.isEmpty()) {
            c.key.interestOps(SelectionKey.OP_READ);
            if (c.closeAfterWrite) {
                close(c, "closed by server");
            }
        } else {
            c.key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
    }

    private void close(ClientConnection c, String reason) {
        if (!connections.remove(c)) {
            return; // already closed
        }
        c.key.cancel(); // unregister from the selector
        try {
            c.channel.close();
        } catch (IOException ignored) {
            // nothing more we can do
        }
        registry.removeOnline(c.username, c);
        Log.info(COMPONENT, "closed " + c + " (" + reason + ") (clients=" + connections.size() + ")");
    }

    private void closeAll() {
        for (ClientConnection c : new ArrayList<>(connections)) {
            close(c, "server stopping");
        }
        try {
            serverChannel.close();
            selector.close();
        } catch (IOException ignored) {
            // shutting down
        }
    }

    /** Once per second: kick clients that never sent AUTH, and rotate attendance codes. */
    private void housekeeping() {
        long now = System.currentTimeMillis();
        if (now - lastHousekeeping < 1000) {
            return;
        }
        lastHousekeeping = now;
        for (ClientConnection c : new ArrayList<>(connections)) {
            if (!c.isAuthenticated() && !c.closeAfterWrite && now - c.connectedAtMillis > AUTH_TIMEOUT_MS) {
                sendAndClose(c, "ERR|AUTH timeout");
            }
        }
        rotateAttendanceCodes();
    }

    // =====================================================================================
    // 3. Public, thread-safe sending helpers
    // =====================================================================================

    public void sendTo(String username, String line) {
        runOnNio(() -> {
            ClientConnection c = registry.online(username);
            if (c != null) {
                send(c, line);
            }
        });
    }

    public void broadcastToSession(String joinCode, String line) {
        runOnNio(() -> {
            // copy: send() may close a client, which changes the online map
            for (ClientConnection c : new ArrayList<>(registry.onlineClients())) {
                if (joinCode.equals(c.joinCode)) {
                    send(c, line);
                }
            }
        });
    }

    public void sendToLecturer(String joinCode, String line) {
        runOnNio(() -> {
            ClassSession s = registry.get(joinCode);
            if (s == null) {
                return;
            }
            ClientConnection c = registry.online(s.lecturerUsername());
            if (c != null && joinCode.equals(c.joinCode)) {
                send(c, line);
            }
        });
    }

    // =====================================================================================
    // 4. Protocol handling
    // =====================================================================================

    private void handleLine(ClientConnection c, String line) {
        String[] p = Protocol.split(line, 2);
        String cmd = p[0].trim();
        Log.info(COMPONENT, c + " -> " + (Protocol.AUTH.equals(cmd) ? "AUTH|***" : line));

        if (!c.isAuthenticated()) {
            if (!Protocol.AUTH.equals(cmd)) {
                sendAndClose(c, "ERR|first message must be AUTH|token");
                return;
            }
            handleAuth(c, p.length > 1 ? p[1].trim() : "");
            return;
        }
        switch (cmd) {
            case Protocol.PING -> send(c, Protocol.PONG);
            case Protocol.QUIT -> sendAndClose(c, "SYS|bye");
            case Protocol.CREATE_SESSION, Protocol.START_ATTENDANCE, Protocol.STOP_ATTENDANCE, Protocol.CREATE_POLL,
                 Protocol.IMPORT_POLLS, Protocol.START_POLL, Protocol.STOP_POLL, Protocol.END_SESSION -> {
                if (!c.isLecturer()) {
                    send(c, "ERR|only a lecturer may use " + cmd); // role check BEFORE doing anything
                    return;
                }
                handleLecturer(c, cmd, line);
            }
            case Protocol.JOIN_SESSION, Protocol.ATTEND, Protocol.VOTE -> {
                if (!c.isStudent()) {
                    send(c, "ERR|only a student may use " + cmd);
                    return;
                }
                handleStudent(c, cmd, line);
            }
            default -> send(c, "ERR|unknown command " + Protocol.sanitize(cmd));
        }
    }

    private void handleAuth(ClientConnection c, String token) {
        Principal who = tokens.validate(token);
        if (who == null) {
            sendAndClose(c, "ERR|invalid or expired token, please log in again");
            return;
        }
        c.username = who.username();
        c.role = who.role();
        ClientConnection old = registry.putOnline(c.username, c);
        if (old != null && old != c) {
            sendAndClose(old, "ERR|logged in elsewhere"); // the newest connection wins
        }
        send(c, Protocol.join(Protocol.OK, "AUTH", c.username, c.role));

        // A lecturer who reconnects gets the state of their running session back.
        if (c.isLecturer()) {
            ClassSession s = registry.activeSessionOf(c.username);
            if (s != null) {
                c.joinCode = s.joinCode();
                send(c, sessionLine(s));
                sendPollList(c, s.joinCode());
                if (attendance.isOpen(s.joinCode())) {
                    send(c, Protocol.join(Protocol.ATT_OPEN, attendance.closesAt(s.joinCode())));
                    s.lastCodeWindowSent = -1; // forces the code to be re-sent
                    rotateAttendanceCodes();
                }
                sendAttCount(s.joinCode());
                Poll open = polls.openPollOf(s.joinCode());
                if (open != null) {
                    send(c, pollStartLine(open, open.secondsLeft()));
                    send(c, countsLine(Protocol.POLL_UPDATE, open, open.snapshot(), open.totalVotes()));
                }
            }
        }
    }

    // ---------------- lecturer commands ----------------

    private void handleLecturer(ClientConnection c, String cmd, String line) {
        if (Protocol.CREATE_SESSION.equals(cmd)) {
            createSession(c, line);
            return;
        }
        ClassSession s = registry.get(c.joinCode);
        if (s == null || !s.isActive()) {
            send(c, "ERR|create a session first");
            return;
        }
        String code = s.joinCode();
        switch (cmd) {
            case Protocol.START_ATTENDANCE -> {
                String[] p = Protocol.split(line, 2);
                int dur = p.length > 1 ? Protocol.parseIntOr(p[1], -1) : config.getInt("attendance.defaultDurationSec", 300);
                if (dur < 30 || dur > 3600) {
                    send(c, "ERR|attendance duration must be 30 to 3600 seconds");
                    return;
                }
                long closesAt = attendance.open(code, dur, () -> submit(() -> onAttendanceClosed(code)));
                broadcastToSession(code, Protocol.join(Protocol.ATT_OPEN, closesAt));
                s.lastCodeWindowSent = -1;
                rotateAttendanceCodes(); // send the first code right now
                sendAttCount(code);
            }
            case Protocol.STOP_ATTENDANCE -> {
                if (attendance.close(code)) {
                    onAttendanceClosed(code);
                } else {
                    send(c, "ERR|attendance is not open");
                }
            }
            case Protocol.CREATE_POLL -> {
                String[] p = Protocol.split(line, 4); // CREATE_POLL|time|opt1;opt2|question (question last)
                if (p.length < 4) {
                    send(c, "ERR|usage CREATE_POLL|timeLimitSec|opt1;opt2;...|question");
                    return;
                }
                List<String> options = new ArrayList<>();
                for (String o : p[2].split(Protocol.LIST_SEP)) {
                    options.add(Protocol.sanitize(o));
                }
                try {
                    Poll poll = polls.createPoll(code, Protocol.sanitizeFreeText(p[3]), options, Protocol.parseIntOr(p[1], -1));
                    send(c, "SYS|poll " + poll.id() + " created");
                    sendPollList(c, code);
                } catch (IllegalArgumentException e) {
                    send(c, "ERR|" + e.getMessage());
                }
            }
            case Protocol.IMPORT_POLLS -> {
                String[] p = Protocol.split(line, 2);
                if (p.length < 2 || p[1].isBlank()) {
                    send(c, "ERR|usage IMPORT_POLLS|url");
                    return;
                }
                String lecturer = c.username;
                send(c, "SYS|importing questions from " + p[1].trim() + " ...");
                // Slow network work goes to the import pool; the result comes back via submit().
                importer.importAsync(p[1].trim(), result -> submit(() -> finishImport(lecturer, code, result)));
            }
            case Protocol.START_POLL -> {
                Poll poll = pollOfSession(c, line, code);
                if (poll == null) {
                    return;
                }
                if (polls.openPollOf(code) != null) {
                    send(c, "ERR|another poll is running, stop it first");
                    return;
                }
                if (!polls.startPoll(poll)) {
                    send(c, "ERR|poll " + poll.id() + " was already used");
                    return;
                }
                broadcastToSession(code, pollStartLine(poll, poll.timeLimitSec()));
                send(c, countsLine(Protocol.POLL_UPDATE, poll, poll.snapshot(), 0));
                sendPollList(c, code);
            }
            case Protocol.STOP_POLL -> {
                Poll poll = pollOfSession(c, line, code);
                if (poll != null && !polls.stopPoll(poll)) { // stopPoll -> onPollEnded -> POLL_END
                    send(c, "ERR|poll " + poll.id() + " is not open");
                }
            }
            case Protocol.END_SESSION -> endSession(c, s);
            default -> send(c, "ERR|unknown command");
        }
    }

    private void createSession(ClientConnection c, String line) {
        String[] p = Protocol.split(line, 3); // CREATE_SESSION|module|title
        if (p.length < 3) {
            send(c, "ERR|usage CREATE_SESSION|moduleCode|title");
            return;
        }
        String module = Protocol.sanitize(p[1]).toUpperCase();
        String title = Protocol.sanitizeFreeText(p[2]);
        if (!module.matches("[A-Z0-9]{2,20}")) {
            send(c, "ERR|module code must be 2-20 letters or digits");
            return;
        }
        if (title.isEmpty() || title.length() > 100) {
            send(c, "ERR|title must be 1-100 characters");
            return;
        }
        ClassSession old = registry.activeSessionOf(c.username);
        if (old != null) {
            endSession(c, old); // one active session per lecturer
        }
        ClassSession s = registry.createSession(module, title, c.username);
        codes.startSession(s.joinCode()); // fresh HMAC secret for this session (Member 5)
        c.joinCode = s.joinCode();
        Log.info(COMPONENT, "session " + s.joinCode() + " created by " + c.username + ": " + module + " " + title);
        send(c, sessionLine(s));
        sendPollList(c, s.joinCode());
        writeDebugCode(s.joinCode(), "-");
    }

    private void endSession(ClientConnection lecturer, ClassSession s) {
        String code = s.joinCode();
        polls.closeAll(code);      // POLL_END goes out through onPollEnded
        if (attendance.close(code)) {
            broadcastToSession(code, Protocol.ATT_CLOSED);
        }
        broadcastToSession(code, "SYS|Session ended by the lecturer");
        for (ClientConnection c : registry.onlineClients()) {
            if (code.equals(c.joinCode) && c.isStudent()) {
                c.joinCode = null;
            }
        }
        registry.endSession(s);
        codes.endSession(code);
        lecturer.joinCode = null;
        Log.info(COMPONENT, "session " + code + " ended");
        writeDebugCode("-", "-");
        sessionEndListener.accept(code); // Member 4 auto-saves the CSVs in the background
    }

    private void onAttendanceClosed(String code) {
        broadcastToSession(code, Protocol.ATT_CLOSED);
        sendAttCount(code);
        writeDebugCode(code, "-");
    }

    private void finishImport(String lecturer, String code, QuestionImporter.ImportResult result) {
        if (result.error() != null) {
            Log.warn(COMPONENT, "import failed: " + result.error());
            sendTo(lecturer, "ERR|import failed: " + result.error());
            return;
        }
        int created = 0;
        for (QuestionImporter.ImportedQuestion q : result.questions()) {
            try {
                polls.createPoll(code, q.question(), q.options(), q.timeLimitSec());
                created++;
            } catch (IllegalArgumentException e) {
                Log.warn(COMPONENT, "imported question rejected: " + e.getMessage());
            }
        }
        sendTo(lecturer, "SYS|imported " + created + " questions, skipped " + (result.skipped() + result.questions().size() - created) + " bad lines");
        ClientConnection c = registry.online(lecturer);
        if (c != null) {
            sendPollList(c, code);
        }
    }

    // ---------------- student commands ----------------

    private void handleStudent(ClientConnection c, String cmd, String line) {
        if (Protocol.JOIN_SESSION.equals(cmd)) {
            joinSession(c, line);
            return;
        }
        ClassSession s = registry.get(c.joinCode);
        if (s == null || !s.isActive()) {
            send(c, "ERR|join a session first");
            return;
        }
        if (Protocol.ATTEND.equals(cmd)) {
            attend(c, s, line);
        } else {
            vote(c, s, line);
        }
    }

    private void joinSession(ClientConnection c, String line) {
        String[] p = Protocol.split(line, 2);
        ClassSession s = p.length > 1 ? registry.get(p[1]) : null;
        if (s == null || !s.isActive()) {
            send(c, "ERR|no active session with that join code");
            return;
        }
        String code = s.joinCode();
        c.joinCode = code;
        s.joinedStudents().add(c.username);
        send(c, sessionLine(s));
        // Bring a late joiner up to date.
        if (attendance.isOpen(code)) {
            send(c, Protocol.join(Protocol.ATT_OPEN, attendance.closesAt(code)));
        }
        AttendanceRecord rec = attendance.recordOf(code, c.username);
        if (rec != null) {
            send(c, Protocol.join(Protocol.ATT_OK, rec.timeMillis()));
        }
        Poll open = polls.openPollOf(code);
        if (open != null) {
            send(c, pollStartLine(open, open.secondsLeft()));
            if (open.hasVoted(c.username)) {
                send(c, Protocol.join(Protocol.VOTE_OK, open.id()));
                if (showResultsAfterVote) {
                    send(c, countsLine(Protocol.POLL_UPDATE, open, open.snapshot(), open.totalVotes()));
                }
            }
        }
        sendAttCount(code);
    }

    private void attend(ClientConnection c, ClassSession s, String line) {
        String code = s.joinCode();
        String[] p = Protocol.split(line, 2);
        String given = p.length > 1 ? p[1].trim() : "";
        if (!attendance.isOpen(code)) {
            send(c, "ERR|attendance is closed");
            return;
        }
        if (attendance.recordOf(code, c.username) != null) {
            send(c, "ERR|attendance already marked");
            return;
        }
        // Member 3: is the student physically on the classroom network?
        if (!guard.isInClassroomSubnet(c.remoteAddress)) {
            Log.warn(COMPONENT, c + " rejected: outside the classroom subnet");
            send(c, "ERR|attendance is only allowed from the classroom network");
            return;
        }
        // Member 5: is it the current (or previous) rotating code?
        if (!codes.isValid(code, given)) {
            send(c, "ERR|wrong or expired attendance code");
            return;
        }
        // Member 3: how many different students used this device?
        String ip = c.remoteAddress.getHostAddress();
        int studentsOnDevice = guard.registerDevice(code, ip, c.username);
        boolean suspicious = guard.isSuspicious(studentsOnDevice);
        AttendanceRecord.Status status = suspicious ? AttendanceRecord.Status.SUSPICIOUS : AttendanceRecord.Status.PRESENT;
        // Member 4: atomic one-mark-per-student
        AttendanceRecord rec = attendance.mark(code, c.username, ip, status);
        if (rec == null) {
            send(c, "ERR|attendance already marked");
            return;
        }
        send(c, Protocol.join(Protocol.ATT_OK, rec.timeMillis()));
        sendAttCount(code);
        if (suspicious) {
            String msg = "SYS|WARNING: " + c.username + " marked attendance from " + ip + ", a device already used by "
                    + guard.studentsOnDevice(code, ip) + " (flagged SUSPICIOUS)";
            sendToLecturer(code, msg);
            Log.warn(COMPONENT, msg.substring(4));
        }
        guard.resolveHostnameAsync(c.remoteAddress, rec::setHostname); // reverse DNS on a pool thread
    }

    private void vote(ClientConnection c, ClassSession s, String line) {
        String[] p = Protocol.split(line, 3); // VOTE|pollId|optionIndex
        if (p.length < 3) {
            send(c, "ERR|usage VOTE|pollId|optionIndex");
            return;
        }
        Poll poll = polls.get(p[1].trim());
        if (poll == null || !poll.joinCode().equals(s.joinCode())) {
            send(c, "ERR|no such poll");
            return;
        }
        Poll.VoteResult r = poll.vote(c.username, Protocol.parseIntOr(p[2], -1)); // Member 4: atomic counting
        switch (r) {
            case OK -> {
                send(c, Protocol.join(Protocol.VOTE_OK, poll.id()));
                if (showResultsAfterVote) {
                    send(c, countsLine(Protocol.POLL_UPDATE, poll, poll.snapshot(), poll.totalVotes()));
                }
            }
            case ALREADY_VOTED -> send(c, "ERR|already voted");
            case CLOSED -> send(c, "ERR|poll closed");
            default -> send(c, "ERR|invalid option");
        }
    }

    // ---------------- callbacks from Member 4's poll-timer threads ----------------

    @Override
    public void onPollUpdate(Poll poll, int[] counts, int total) {
        String line = countsLine(Protocol.POLL_UPDATE, poll, counts, total);
        runOnNio(() -> {
            sendToLecturer(poll.joinCode(), line);
            if (showResultsAfterVote) {
                for (String voter : poll.voters()) {
                    ClientConnection c = registry.online(voter);
                    if (c != null && poll.joinCode().equals(c.joinCode)) {
                        send(c, line);
                    }
                }
            }
        });
    }

    @Override
    public void onPollEnded(Poll poll, int[] counts, int total) {
        String line = countsLine(Protocol.POLL_END, poll, counts, total);
        runOnNio(() -> {
            broadcastToSession(poll.joinCode(), line);
            ClassSession s = registry.get(poll.joinCode());
            ClientConnection lecturer = s == null ? null : registry.online(s.lecturerUsername());
            if (lecturer != null && poll.joinCode().equals(lecturer.joinCode)) {
                sendPollList(lecturer, poll.joinCode());
            }
        });
    }

    // ---------------- helpers ----------------

    /** Sends the new attendance code to the lecturer whenever the 30-second window changes. */
    private void rotateAttendanceCodes() {
        long window = codes.currentWindow();
        for (ClassSession s : registry.activeSessions()) {
            if (!attendance.isOpen(s.joinCode()) || s.lastCodeWindowSent == window) {
                continue;
            }
            s.lastCodeWindowSent = window;
            String code = codes.currentCode(s.joinCode());
            sendToLecturer(s.joinCode(), Protocol.join(Protocol.ATT_CODE, code, codes.secondsLeftInWindow()));
            Log.info(COMPONENT, "attendance code for " + s.joinCode() + " rotated (" + codes.secondsLeftInWindow() + " s left)");
            writeDebugCode(s.joinCode(), code);
        }
    }

    private Poll pollOfSession(ClientConnection c, String line, String code) {
        String[] p = Protocol.split(line, 2);
        Poll poll = p.length > 1 ? polls.get(p[1].trim()) : null;
        if (poll == null || !poll.joinCode().equals(code)) {
            send(c, "ERR|no such poll in this session");
            return null;
        }
        return poll;
    }

    private void sendAttCount(String code) {
        ClassSession s = registry.get(code);
        if (s != null) {
            sendToLecturer(code, Protocol.join(Protocol.ATT_COUNT, attendance.presentCount(code), s.joinedStudents().size()));
        }
    }

    private void sendPollList(ClientConnection c, String code) {
        StringBuilder sb = new StringBuilder(Protocol.POLL_LIST).append(Protocol.SEP);
        boolean first = true;
        for (Poll p : polls.pollsOf(code)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(p.id()).append(':').append('[').append(p.state()).append("] ").append(p.question());
        }
        send(c, sb.toString());
    }

    private static String sessionLine(ClassSession s) {
        return Protocol.join(Protocol.SESSION, s.joinCode(), s.moduleCode(), s.title());
    }

    private static String pollStartLine(Poll poll, int secondsLeft) {
        return Protocol.join(Protocol.POLL_START, poll.id(), secondsLeft, String.join(Protocol.LIST_SEP, poll.options()), poll.question());
    }

    private static String countsLine(String type, Poll poll, int[] counts, int total) {
        return Protocol.join(type, poll.id(), Protocol.joinCounts(counts), total);
    }

    /** DEBUG ONLY (debug.exposeAttendanceCode=true): lets LoadTestClient read the current code. */
    private void writeDebugCode(String joinCode, String code) {
        if (!exposeCode) {
            return;
        }
        try {
            Path path = Paths.get("data", "current_code.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, joinCode + "|" + code);
        } catch (IOException e) {
            Log.warn(COMPONENT, "cannot write debug code file: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }
}
