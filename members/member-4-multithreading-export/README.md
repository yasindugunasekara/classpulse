# Member 4: Multithreaded poll engine and export server

**Concept:** multithreading, thread pools (`ExecutorService`, `ScheduledExecutorService`),
thread-safe data (`AtomicIntegerArray`, `ConcurrentHashMap`, `putIfAbsent`), and `synchronized`.
**Service:** export server, TCP port **6003**, plus the poll/attendance engine used by the NIO server.

---

## 1. Responsibilities

- `Poll`: one poll with **atomic vote counting** and **one vote per student**, enforced without locks;
  a `synchronized` `close()` for the OPEN → CLOSED change plus the final snapshot.
- `PollManager`: creates/validates polls (2-6 options, 10-600 s), a **ScheduledExecutorService**
  (`poll-timer-1`, `poll-timer-2`) that closes each poll **exactly on time**, and **throttled live
  updates** (a task every 250 ms sends `POLL_UPDATE` only if counts changed).
- `AttendanceBook` / `AttendanceRecord`: one mark per student per session with `putIfAbsent`; attendance closes automatically on the same scheduler.
- `ExportServer`: `ServerSocket` accept loop plus a **fixed pool of 4** (`export-worker-1..4`).
- `ExportTask`: validate the lecturer token, build the CSV, send `OK|name|size` and then the bytes.
- `CsvBuilder`: attendance CSV (username, time, ip, hostname, status) and poll CSV (counts and %).
- `ExportClient`: download on a `SwingWorker` with a progress bar.
- Auto-save both CSVs into `exports/` when a session ends (in the background).

## 2. The concept in 5 sentences (lecture theory)

1. A **thread** is an independent path of execution; a server uses several threads so that slow work (timers, file building, DNS) does not stop other work.
2. When several threads change the same data, a **race condition** can happen: `count++` is really read-add-write, and two threads can both read 5 and both write 6, losing an update.
3. **Atomic classes** (`AtomicInteger`, `AtomicIntegerArray`) and **concurrent collections** (`ConcurrentHashMap`, `newKeySet()`) perform such read-modify-write steps as one indivisible operation, without a lock.
4. `synchronized` gives **mutual exclusion**: only one thread at a time runs the block, which is needed when *several* steps must look like one (change state and take the final snapshot).
5. A **thread pool** (`ExecutorService`) reuses a fixed number of worker threads for many tasks, limiting resource use; a `ScheduledExecutorService` runs tasks after a delay or at a fixed rate.

## 3. Files and classes

| File | What it does |
|---|---|
| `engine/Poll.java` | `vote()` (lock-free), `close()` (synchronized), `snapshot()`, `takeChanged()` |
| `engine/PollManager.java` | `createPoll()`, `startPoll()` (schedules close), `stopPoll()`, `sendThrottledUpdates()` |
| `engine/AttendanceBook.java` | `open()` (schedules close), `close()`, `mark()` (`putIfAbsent`), `records()` |
| `engine/AttendanceRecord.java` | username, time, ip, `volatile hostname`, status PRESENT/SUSPICIOUS |
| `export/ExportServer.java` | accept loop → `workers.execute(new ExportTask(...))`, `autoSave()` |
| `export/ExportTask.java` | one request, token check, CSV, `OK|name|size` + bytes, try-with-resources, 30 s timeout |
| `export/CsvBuilder.java` | CSV text with escaping |
| `export/ExportClient.java` | `download()` (reads exactly `size` bytes), `downloadInBackground()` (SwingWorker) |

### The 5 key code blocks to show

1. **Lock-free voting**: `Poll.vote()`. `voters.add(username)` (atomic check-and-insert) and `counts.incrementAndGet(i)`.
2. **Where synchronized is required**: `Poll.close()`. State OPEN → CLOSED, wait for votes already in progress, final snapshot, cancel the timer; only one caller gets the snapshot.
3. **Timer closing the poll**: `PollManager.startPoll()`. `scheduler.schedule(() -> finish(poll), timeLimit, SECONDS)`.
4. **Throttling**: `PollManager` constructor + `sendThrottledUpdates()`. `scheduleAtFixedRate(..., 250 ms)` with `takeChanged()`.
5. **Fixed pool server**: `ExportServer.acceptLoop()` + `ExportTask.run()`. `Executors.newFixedThreadPool(4, NamedThreadFactory("export-worker"))`.

## 4. How my part connects to the others

- **Member 1:** the NIO thread calls `createPoll`, `startPoll`, `vote`, `attendance.mark`. My timer threads call back `onPollUpdate` / `onPollEnded`, and Member 1 moves the sending onto the NIO thread with `submit()`.
- **Member 3:** `NetworkGuard` decides PRESENT vs SUSPICIOUS and fills the hostname of my record from a DNS thread (that is why `hostname` is `volatile`).
- **Member 5:** `ExportTask` validates the lecturer token with `TokenStore`.
- **Member 2:** the dashboard uses `ExportClient.downloadInBackground` with a `JFileChooser` and a progress bar.

## 5. Two-minute demo script (word for word)

| Time | Do | Say |
|---|---|---|
| 0:00 | Open `Poll.vote()` | "My concept is multithreading. Votes can arrive at the same moment, so the counts are an **AtomicIntegerArray** and the voters a concurrent set. `voters.add` returns false if this student already voted, atomically, so no lock is needed." |
| 0:25 | Open `Poll.close()` | "Here I *do* need `synchronized`: closing the poll and taking the final snapshot must happen together and only once, even if the timer and the lecturer's Stop button come at the same time." |
| 0:40 | Lecturer starts a **20-second** poll; run `run-loadtest` | "I start a 20-second poll and 100 students vote. The chart updates at most 4 times per second: the throttling task." |
| 1:00 | Show the server log | "After exactly 20 seconds the thread **poll-timer-1** closes the poll. It is a ScheduledExecutorService." |
| 1:10 | Student app: try to vote again | "A second vote is rejected: ERR already voted." |
| 1:20 | Click "Export attendance CSV" and "Export poll CSV" quickly | "Two exports at once. In the log you see **export-worker-1** and **export-worker-2** working in parallel: a fixed thread pool of 4 workers behind a normal ServerSocket." |
| 1:45 | Open the attendance CSV | "The file has username, time, IP, hostname and status, including the SUSPICIOUS flag." |

## 6. Likely viva questions

1. **Why `AtomicIntegerArray` instead of `int[]`?** `counts[i]++` is read-add-write; two threads can interleave and lose a vote. `incrementAndGet(i)` is one atomic hardware operation (compare-and-swap).
2. **Why is `vote()` safe without `synchronized`?** Each step is atomic on its own: `voters.add` decides "first vote or not" atomically, the counter increment is atomic, and the order makes a double vote impossible.
3. **Then why is `close()` synchronized?** Two steps (change state, take the final snapshot, cancel the timer) must happen as one unit, and only once if the timer and STOP_POLL race; `synchronized` gives mutual exclusion.
4. **Why a fixed pool instead of a new thread per request?** Thread creation is expensive and unlimited threads can exhaust memory under load; a fixed pool caps concurrency and queues extra work.
5. **Why throttle the updates?** 100 votes in one second would mean 100 chart messages to the lecturer; checking a "changed" flag every 250 ms sends at most 4 per second with the same final result.
6. **What does `putIfAbsent` give you?** An atomic "insert only if no value exists", so one student can never get two attendance records even if two ATTENDs are processed in parallel.
7. **Why `volatile` on `hostname` and `state`?** They are written by one thread and read by others; `volatile` guarantees the readers see the newest value.
8. **Why does `ExportTask` use try-with-resources and a 30 s timeout?** The socket is always closed, even after an error, and a client that never sends its request cannot block a worker forever.

## 7. Paragraph for the report (≈150 words)

Member 4 implemented the concurrent poll engine and the export service. Each poll stores its vote counts in an
`AtomicIntegerArray` and its voters in a concurrent set created with `ConcurrentHashMap.newKeySet()`; the atomic
`add` operation enforces one vote per student and `incrementAndGet` prevents lost updates, so voting needs no lock.
A `synchronized` `close` method changes the poll state and captures the final snapshot exactly once, even if the
timer and the lecturer's stop command coincide. A `ScheduledExecutorService` with two named threads closes polls
and attendance windows on time, and a fixed-rate task sends live updates at most four times per second. Attendance
uses `putIfAbsent` for one mark per student. The export server is a classic `ServerSocket` accept loop that hands
each connection to a fixed pool of four workers, which validate the lecturer token and stream a CSV file preceded
by its size. A concurrency test with 50 simultaneous threads produced exact counts.
