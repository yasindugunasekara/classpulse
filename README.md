# ClassPulse: classroom live polling and attendance (Java Network Programming, Assignment 02)

A lecturer runs a live session from a laptop; students on the same Wi-Fi/LAN **discover it automatically**,
**mark attendance** with a code that changes every 30 seconds, and **answer live polls** whose results appear
as bar charts in real time.

Pure JDK (Java 17+): no Maven, Gradle or external libraries. Built with plain `javac`.

| Member | Concept | Folder (code + responsibilities + concepts + demo + viva) |
|---|---|---|
| 1 | **Java NIO** classroom server (TCP 6000) | [`members/member-1-nio-server`](members/member-1-nio-server/README.md) |
| 2 | **TCP sockets** + Swing GUIs | [`members/member-2-tcp-clients-gui`](members/member-2-tcp-clients-gui/README.md) |
| 3 | **UDP** discovery, **InetAddress/subnets**, **URL** import | [`members/member-3-udp-addressing-url`](members/member-3-udp-addressing-url/README.md) |
| 4 | **Multithreading**, thread pools, export server (TCP 6003) | [`members/member-4-multithreading-export`](members/member-4-multithreading-export/README.md) |
| 5 | **Secure networking**: TLS login (6001), hashing, rotating codes | [`members/member-5-security`](members/member-5-security/README.md) |

See [`members/README.md`](members/README.md) for how the folders fit together.

## Requirements

- JDK 17 or newer (`java -version`, `javac -version`, `keytool` on the PATH). Tested with JDK 24.
- Windows, Linux or macOS. All commands are run from this folder (the project root).

## Build and set up (once)

| Step | Windows | Linux / macOS |
|---|---|---|
| Compile everything into `out/` | `scripts\build.bat` | `sh scripts/build.sh` |
| Create TLS keystore + truststore | `scripts\gen-keystore.bat` | `sh scripts/gen-keystore.sh` |
| …valid for the lecturer's LAN IP too | `scripts\gen-keystore.bat 192.168.1.10` | `sh scripts/gen-keystore.sh 192.168.1.10` |

`run-server` creates the keystore automatically if it is missing (localhost only).

## Run

| What | Windows | Linux / macOS |
|---|---|---|
| Server (all 4 services) | `scripts\run-server.bat` | `sh scripts/run-server.sh` |
| Lecturer Dashboard | `scripts\run-lecturer.bat [host]` | `sh scripts/run-lecturer.sh [host]` |
| Student App | `scripts\run-student.bat [host]` | `sh scripts/run-student.sh [host]` |
| Question web server (`:8080/questions.csv`) | `scripts\run-question-web.bat` | `sh scripts/run-question-web.sh` |
| Load test (100 students) | `scripts\run-loadtest.bat [host] [n] [waitSec]` | `sh scripts/run-loadtest.sh ...` |
| Automated smoke test (server running) | `scripts\run-smoketest.bat` | `sh scripts/run-smoketest.sh` |

Unit tests: `java -cp out com.classpulse.tests.SecurityTest` and `java -cp out com.classpulse.tests.EngineConcurrencyTest`.

### Typical flow on one laptop
1. `run-server`. The banner shows the ports and the LAN IP.
2. `run-lecturer` → log in `lecturer` / `lecturer123` → **Create session**.
3. `run-student` (two or three times) → the session appears in the list → **Login & join** as `s001` / `s001pass`, `s002` / `s002pass`, ...
4. Dashboard → **Start attendance** → students type the big code → **Mark attendance**.
5. Dashboard → **Polls** → Create poll (or Import from `http://localhost:8080/questions.csv` after `run-question-web`) → select → **Start poll** → students vote → live chart.
6. **Export** tab → download attendance / poll CSV. Ending the session also auto-saves both CSVs into `exports/`.

### Load test
Create a session (and ideally start attendance and a poll) in the dashboard, then run `run-loadtest`.
It logs in `load001..load100` (registered automatically on first run), joins, marks attendance using the
code from `data/current_code.txt` (written only when `debug.exposeAttendanceCode=true`) and votes. All 100 come
from one IP, so they are flagged SUSPICIOUS: that is expected.

## Demo accounts

| User | Password | Role |
|---|---|---|
| `lecturer` | `lecturer123` | LECTURER |
| `s001`, `s002`, `s003` | `s001pass`, `s002pass`, `s003pass` | STUDENT |

Created on first server start in `data/users.txt` (PBKDF2 hashes only). Students can also **Register**.
Delete `data/users.txt` to reset.

## LAN notes (lecturer laptop + student laptops)

1. On the lecturer laptop find the LAN IP (`ipconfig` / `ip addr`; the server banner prints it too).
2. Run `gen-keystore <LAN-IP>` and **copy `certs/client-truststore.p12`** to every student laptop's `certs/` folder
   (it contains only the public certificate). Without the IP in the certificate, hostname verification fails.
3. Open the firewall on the lecturer laptop: **TCP 6000, 6001, 6003, 8080** and **UDP 6002**; on student
   laptops **UDP 6004** (for announcements). On Windows, allow Java on *Private networks* when asked.
4. Students start `run-student`; the session appears automatically. If UDP is blocked (common on campus
   Wi-Fi), type the lecturer's IP in **Host (manual fallback)** plus the join code.
5. All laptops need the same `config.properties` ports.

## Configuration (`config.properties`)

| Key | Default | Meaning |
|---|---|---|
| `port.classroom/login/discovery/export` | 6000/6001/6002/6003 | Service ports |
| `port.announce` | 6004 | UDP port students listen on for announcements |
| `port.questionweb` | 8080 | Question web server |
| `tls.verifyHostname` | true | Check the certificate SAN against the host |
| `attendance.requireSameSubnet` | true | Reject attendance from other networks (loopback always allowed) |
| `attendance.maxPerDevice` | 1 | More students than this from one IP → SUSPICIOUS |
| `attendance.codeWindowSec` | 30 | Code rotation period |
| `poll.showResultsAfterVote` | true | Students see live results right after voting |
| `export.demoDelayMs` | 1500 | Artificial delay so parallel exports are visible in the log (0 = off) |
| `debug.exposeAttendanceCode` | true | Write the current code to `data/current_code.txt` for the load test (**false for real classes**) |

Any key can be overridden on the command line: `java -Dport.questionweb=8090 -cp out ...`.

## Project structure

```
classpulse/
├── README.md, config.properties
├── scripts/          build, gen-keystore, run-server, run-lecturer, run-student,
│                     run-question-web, run-loadtest, run-smoketest  (.sh + .bat)
├── members/          one folder per member: README.md (responsibilities, concepts, demo, viva) + src/
├── tests/            SecurityTest, EngineConcurrencyTest, SmokeTest
├── docs/             ARCHITECTURE, PROTOCOL, DEMO_SCRIPT, REPORT_NOTES, MEMBER_1..5, sample-questions.csv
├── certs/            generated keystore / truststore
├── data/             users.txt (generated), current_code.txt (debug)
└── exports/          CSV files auto-saved when a session ends
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Keystore certs/server.p12 not found` | Run `gen-keystore`. |
| `Certificate problem ... not valid for '<ip>'` | Certificate lacks the LAN IP: `gen-keystore <ip>`, restart the server, re-copy the truststore. Quick workaround: `tls.verifyHostname=false` on the clients. |
| `Truststore ... not found` (student laptop) | Copy `certs/client-truststore.p12` from the lecturer laptop. |
| `Cannot reach the login service` / `server is not running` | Wrong host, server not started, or firewall. |
| No session in the Student App list | UDP blocked: use the manual host field. Check that a session was created. |
| `Address already in use` | A previous server is still running, or another program uses the port. Change it in `config.properties`. Example: port 8080 is often taken, so set `port.questionweb=8090`. |
| `attendance is only allowed from the classroom network` | The student is on a different subnet. For mixed networks set `attendance.requireSameSubnet=false`. |
| Load test says "No active session" | Create a session first; set `debug.exposeAttendanceCode=true`. |
| GUI shows "Connection ... lost" | Server stopped. It retries 3 times; afterwards click **Reconnect** once the server is back. Sessions live in server memory, so after a restart the lecturer creates a new session. |
"# classpulse" 
"# classpulse" 
