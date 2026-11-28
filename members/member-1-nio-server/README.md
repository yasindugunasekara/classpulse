# Member 1: Java NIO classroom server

**Concept:** Java NIO (compulsory part of the assignment): `ServerSocketChannel`, `SocketChannel`,
`Selector`, `SelectionKey`, `ByteBuffer`, non-blocking mode.
**Service:** classroom server, TCP port **6000**.

---

## 1. Responsibilities

- Write the shared foundation first (Phase 1): `Protocol` (the wire protocol), `Config`
  (`config.properties`), `Log` (thread-name logging), `NamedThreadFactory`.
- Build the **single-threaded NIO server** that serves every lecturer and student connection.
- Implement **every command** of the classroom protocol (AUTH, CREATE_SESSION, START_ATTENDANCE,
  CREATE_POLL, IMPORT_POLLS, START_POLL, JOIN_SESSION, ATTEND, VOTE, ...): validate the role, then
  call the other members' components.
- Keep sessions and connected clients in `SessionRegistry` / `ClassSession`.
- The **thread hand-off**: other threads never touch sockets; they call `submit()`.
- `ServerMain`: create all shared objects and start all 4 services.
- `LoadTestClient`: prove that ONE thread handles 100 students.

## 2. The concept in 5 sentences (lecture theory)

1. Classic blocking I/O needs one thread per client, because `read()` blocks until data arrives; 100 students would need 100 threads.
2. In NIO, channels are put into **non-blocking mode**: `read()` and `write()` return immediately with whatever they could do (possibly 0 bytes).
3. A **Selector** watches many channels at once; `select()` sleeps until at least one channel is ready, and `selectedKeys()` tells us which ones and what they are ready for (`OP_ACCEPT`, `OP_READ`, `OP_WRITE`).
4. Data moves through **ByteBuffers**; because a read can contain half a message or several messages, each connection needs its own buffer to rebuild complete lines.
5. One thread looping over ready keys can therefore serve hundreds of clients with little memory and no locking, as long as no handler ever blocks.

## 3. Files and classes

| File | What it does |
|---|---|
| `common/Protocol.java` | All command names, `split()` with limit, `sanitize()`, count lists |
| `common/Config.java` | Loads `config.properties`, typed getters, port getters |
| `common/Log.java` | `[HH:mm:ss.SSS] [thread] [component] message` |
| `common/NamedThreadFactory.java` | Readable thread names (`export-worker-1`, ...) |
| `server/ServerMain.java` | Creates shared objects, starts the 4 services, prints the banner |
| `server/nio/ClassroomServer.java` | **The selector loop** and all protocol handling |
| `server/nio/ClientConnection.java` | Per-client read buffer, partial line, write queue, user info |
| `server/nio/SessionRegistry.java` | Sessions (`SecureRandom` join codes) + online clients |
| `server/nio/ClassSession.java` | One session: join code, module, title, joined students |
| `tools/LoadTestClient.java` | 100 fake students: TLS login, join, attend, vote |

### The 5 key code blocks to show

1. **Opening the server**: `ClassroomServer.start()`. `ServerSocketChannel.open()`, `bind(port, 256)`,
   `configureBlocking(false)`, `register(selector, OP_ACCEPT)`, start the `NIO-selector` thread.
2. **The selector loop**: `ClassroomServer.runLoop()`. `select(500)`, `runPendingTasks()`, iterate
   `selectedKeys()`, `it.remove()`, dispatch to accept/read/write, and the `try/catch` per key (one bad
   client never kills the thread).
3. **Reading and framing**: `ClassroomServer.read()` + `ClientConnection.extractLines()`.
   `read() == -1` means disconnect; `flip()`; collect bytes until `'\n'`; decode only complete lines; reject lines > 8192 bytes.
4. **Partial writes and OP_WRITE**: `ClassroomServer.send()` + `flush()`. A queue of ByteBuffers; if
   `write()` leaves bytes, turn **on** `OP_WRITE`; when the queue is empty, turn it **off**.
5. **Thread hand-off**: `ClassroomServer.submit()` / `runOnNio()`. A `ConcurrentLinkedQueue` plus
   `selector.wakeup()`; used by poll timers (Member 4), URL import and DNS (Member 3).

## 4. How my part connects to the others

- **Member 5:** `TokenStore.validate(token)` on `AUTH`; `AttendanceCodeGenerator.isValid()` on `ATTEND`; codes sent to the lecturer when the 30 s window changes (`rotateAttendanceCodes()`).
- **Member 4:** `PollManager.createPoll/startPoll/stopPoll`, `Poll.vote()`, `AttendanceBook.open/mark`; I implement `PollManager.Listener` to receive `onPollUpdate` / `onPollEnded` from the timer threads.
- **Member 3:** `NetworkGuard.isInClassroomSubnet()`, `registerDevice()`, `resolveHostnameAsync()`; `QuestionImporter.importAsync()` whose result comes back through `submit()`. `DiscoveryServer` reads my `SessionRegistry`.
- **Member 2:** the clients speak my protocol (see `common/Protocol.java`).

## 5. Two-minute demo script (word for word)

| Time | Do | Say |
|---|---|---|
| 0:00 | Open `ClassroomServer.java` at `start()` | "My part is the classroom server on port 6000. It is built with Java NIO. Here I open a ServerSocketChannel, switch it to **non-blocking** mode and register it with a **Selector** for OP_ACCEPT." |
| 0:20 | Scroll to `runLoop()` | "This loop is the whole server. `select()` sleeps until some channel is ready. Then I go through the selected keys: a new client is accepted, a readable client is read, a writable client gets its queued data. **One thread serves everyone.**" |
| 0:45 | Show `read()` + `extractLines()` | "A non-blocking read gives me whatever bytes have arrived: half a line or three lines. So each client has its own ByteBuffer and I only process complete lines that end in a newline. `read()` returning -1 means the client disconnected." |
| 1:05 | Show `flush()` | "Writes can be partial too. If the socket is full I keep the rest in a queue and turn on OP_WRITE. When the queue is empty I turn it off again, otherwise the selector would spin at 100% CPU." |
| 1:20 | Show `submit()` | "Other threads, like the poll timer, may never touch a socket. They put a task in this queue and call `selector.wakeup()`." |
| 1:30 | Run `scripts\run-loadtest.bat` with a poll running | "Now 100 students log in, join, mark attendance and vote." |
| 1:45 | Show the lecturer chart at 100 votes and the server log | "The chart jumped to 100 votes, and every classroom log line says **[NIO-selector]**: one thread, 100 clients." |

## 6. Likely viva questions

1. **Why `selector.wakeup()`?** The NIO thread may be sleeping in `select()`. When another thread adds a task to the queue, `wakeup()` makes `select()` return at once, so the task runs without waiting up to 500 ms.
2. **What if `write()` is partial?** The unsent bytes stay in the ByteBuffer at the head of the client's queue, I enable `OP_WRITE`, and when the selector reports the channel writable I continue. Data is never lost and the thread never blocks.
3. **Why must you remove the key from `selectedKeys()`?** The selector only adds keys to that set and never clears it. If I don't remove a key, I would handle the same event again next loop.
4. **What do `flip()` and `clear()` do?** `flip()` switches the buffer from writing (the channel filled it) to reading (I take bytes out): limit = position, position = 0. `clear()` resets it to be filled again.
5. **What happens if one client sends garbage or throws an exception?** Each key is handled inside a try/catch; only that client is closed. Lines longer than 8192 bytes get `ERR|line too long` and are closed. Unknown commands get `ERR`.
6. **How do you detect a disconnected client?** `read()` returns -1 (orderly close) or throws an IOException (reset); I cancel the key, close the channel and remove the user from the registry.
7. **Why not one thread per client?** Threads cost memory (about 1 MB stack each) and context switches; most students are idle most of the time. With a selector, idle clients cost almost nothing.
8. **Why is `SessionRegistry` safe if other threads read it?** Only the NIO thread changes it. The sessions live in a `ConcurrentHashMap` and `ClassSession.active` is `volatile`, so the UDP and export threads can read safely.

## 7. Paragraph for the report (≈150 words)

The classroom server is the core of ClassPulse and is implemented with Java NIO. A single
`ServerSocketChannel` and all client `SocketChannel`s are configured in non-blocking mode and registered with one
`Selector`. A dedicated thread, named *NIO-selector*, repeatedly calls `select()`, which returns only the channels
that are ready to accept, read or write. Because a non-blocking read may deliver partial or multiple messages,
every connection owns a `ByteBuffer` and a line accumulator that frames the UTF-8 text protocol on newline
characters. Outgoing messages are queued per connection; if `write()` cannot send everything, interest in
`OP_WRITE` is enabled until the queue drains. Other threads (poll timers, URL import, DNS) never touch channels:
they hand tasks to the selector thread through a concurrent queue and `selector.wakeup()`. A load test with 100
simulated students showed that all logins, attendance marks and votes were handled correctly by this single
thread.
