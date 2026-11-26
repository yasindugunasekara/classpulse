# Member 2: TCP clients and Swing GUIs

**Concept:** TCP sockets with `java.net.Socket`: connect, write, read with a dedicated reader thread,
close, disconnect detection, automatic reconnect. Frontend in Swing (`SwingUtilities.invokeLater`, Java2D).
**Programs:** Lecturer Dashboard and Student App (TCP client to port 6000).

---

## 1. Responsibilities

- `ClassroomClient`: the TCP client used by both apps (connect with timeout, reader thread,
  synchronised writes, disconnect detection, **auto-reconnect 3 × 2 s**, Reconnect button).
- `ServerListener`: the callback interface between the network code and the GUI.
- `LoginDialog`: host / username / password / Register; does the TLS login (Member 5) on a `SwingWorker`.
- `LecturerDashboard`: create session, big join code, attendance panel with the big rotating code,
  countdowns, present/joined counter, warnings; poll editor, URL import, poll list, live chart; CSV export buttons.
- `StudentApp`: discovered sessions list (Member 3), join, attendance box, poll pop-up with radio
  buttons and countdown, results chart.
- `BarChartPanel`: bar chart drawn with Java2D in `paintComponent` (no libraries).
- `LecturerMain`, `StudentMain`: entry points.

## 2. The concept in 5 sentences (lecture theory)

1. TCP is a **connection-oriented, reliable byte stream**: before data flows, client and server do a three-way handshake, and TCP guarantees order and delivery.
2. In Java a client does `new Socket()` → `connect(address, timeout)` → gets an `InputStream`/`OutputStream` → reads and writes → `close()`.
3. TCP has no message boundaries, so our protocol adds them: one message per line, and `BufferedReader.readLine()` returns one complete message.
4. `readLine()` **blocks** until a line arrives, so it must run on its own thread; otherwise the GUI (which has only one event thread) would freeze.
5. A broken connection shows up as `readLine()` returning `null` (server closed) or an `IOException` (reset); the client must catch it, tell the user clearly, and try to reconnect.

## 3. Files and classes

| File | What it does |
|---|---|
| `client/ClassroomClient.java` | `open()` (socket, connect, streams, reader thread, AUTH), `readLoop()`, `send()`, `autoReconnect()`, `reconnectNow()`, `close()` |
| `client/ServerListener.java` | `onLine(line)`, `onConnectionStatus(msg, connected)` |
| `client/LoginDialog.java` | Modal dialog; TLS login/register on a `SwingWorker` |
| `client/LecturerDashboard.java` | Lecturer UI; `handle(line)` updates widgets per message type |
| `client/StudentApp.java` | Student UI; UDP session list, attendance, poll |
| `client/BarChartPanel.java` | Java2D chart (`paintComponent`, `fillRoundRect`, percentages) |
| `client/LecturerMain.java`, `StudentMain.java` | `main()` methods |

### The 5 key code blocks to show

1. **socket → connect → write**: `ClassroomClient.open()`. `new Socket()`, `connect(new InetSocketAddress(host, port), 3000)`, wrap the streams in UTF-8 reader and writer, start the reader thread, send `AUTH|token`.
2. **The reader thread**: `ClassroomClient.readLoop()`. `while ((line = reader.readLine()) != null) listener.onLine(line);` and the `finally` block that detects the disconnect.
3. **Synchronised writing**: `ClassroomClient.send()`. `synchronized` so the Swing thread and the reconnect thread never interleave two lines; `flush()` after every line.
4. **Auto-reconnect**: `ClassroomClient.autoReconnect()`. 3 attempts, 2 s apart, a fresh TLS login (the server may have restarted and forgotten old tokens), and JOIN_SESSION is re-sent automatically.
5. **Thread-safe GUI updates**: `LecturerDashboard.onLine()` → `SwingUtilities.invokeLater(() -> handle(line))`, and `BarChartPanel.paintComponent()`.

## 4. How my part connects to the others

- **Member 1:** I speak the classroom protocol over TCP to the NIO server (`docs/PROTOCOL.md`).
- **Member 5:** `LoginDialog` calls `SecureLoginClient.login/register` (TLS) to get the token.
- **Member 3:** `StudentApp` uses `SessionScanner` (UDP) to fill the session list without typing an IP.
- **Member 4:** the dashboard's export buttons call `ExportClient.downloadInBackground` (SwingWorker + progress bar).

## 5. Two-minute demo script (word for word)

| Time | Do | Say |
|---|---|---|
| 0:00 | Show the Lecturer Dashboard and one Student App side by side | "I built the two client programs. Both talk to the server through a normal blocking TCP socket from java.net." |
| 0:15 | Open `ClassroomClient.open()` | "This is the life of a TCP socket: create the Socket, **connect** with a 3-second timeout, which is the three-way handshake, get the streams, and **write** our first line, AUTH with the token." |
| 0:35 | Show `readLoop()` | "`readLine()` blocks until the server sends a line, so it runs on its own **reader thread**. Each line goes to the GUI, and the GUI uses `invokeLater` because Swing is not thread-safe." |
| 0:55 | In the dashboard: start attendance; student types the code, marks it; start a poll, student votes | "Every button sends one protocol line. You can see the attendance counter and the bar chart, which I draw myself with Java2D, update live." |
| 1:25 | Stop the server (Ctrl+C) | "Now I stop the server. readLine returns null or throws, and the app shows a **friendly message** instead of crashing, then retries 3 times, 2 seconds apart." |
| 1:40 | Start the server again, click Reconnect if needed | "When the server is back the client logs in again and re-joins the session automatically. That is the **close** and reconnect part of the socket life cycle." |

## 6. Likely viva questions

1. **What happens during `connect()`?** TCP's three-way handshake (SYN, SYN-ACK, ACK). With a timeout, `connect` throws `SocketTimeoutException` instead of hanging if the host does not answer.
2. **Why a separate reader thread?** `readLine()` blocks. On the Swing event thread it would freeze the whole window; on a separate thread the GUI stays responsive.
3. **Why is `send()` synchronized?** It can be called from the Swing thread and the reconnect thread. Without the lock two messages could be mixed in the stream and the server would see a corrupted line.
4. **How do you know the server is gone?** `readLine()` returns `null` (the server closed the connection: FIN) or throws `IOException` (connection reset). `ConnectException` on reconnect means nothing is listening.
5. **Why `SwingUtilities.invokeLater`?** Swing components may only be changed on the Event Dispatch Thread; `invokeLater` queues the update there.
6. **Why does the reconnect log in again over TLS?** Tokens are kept in server memory; after a restart old tokens are invalid, so the client gets a fresh token with the saved credentials.
7. **TCP vs UDP for the classroom channel?** Votes and attendance must arrive exactly once and in order, so we need TCP's reliability. UDP is only used for discovery, where a lost packet just gets resent 3 s later.
8. **Why `setTcpNoDelay(true)`?** It disables Nagle's algorithm, so our small protocol lines are sent immediately instead of being delayed to be combined.

## 7. Paragraph for the report (≈150 words)

The client side of ClassPulse consists of a Lecturer Dashboard and a Student App built with Swing, both using
the `ClassroomClient` class to communicate with the classroom server over a plain TCP connection created with
`java.net.Socket`. The client connects with a timeout, sends an `AUTH` line containing the token obtained from the
TLS login, and then exchanges newline-delimited text messages. Because `BufferedReader.readLine()` blocks, a
dedicated reader thread receives server messages and forwards them through the `ServerListener` interface; the
GUIs apply every change on the Swing Event Dispatch Thread using `SwingUtilities.invokeLater`. Writes are
synchronised so that lines from different threads never interleave. Disconnections are detected when `readLine()`
returns null or throws, after which the client shows a clear message and retries three times at two-second
intervals, logging in again and re-joining the session automatically. Live poll results are drawn as a bar chart
with Java2D.....

