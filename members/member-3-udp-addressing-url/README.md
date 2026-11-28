# Member 3: UDP discovery, addressing and URLs

**Concepts:** UDP (`DatagramSocket`, `DatagramPacket`, broadcast, timeouts); addressing
(`InetAddress`, `NetworkInterface`, `InterfaceAddress`, subnet prefix length); URLs
(`java.net.URL`, `HttpURLConnection`).
**Service:** session discovery, UDP port **6002** (announcements to UDP **6004**), plus the question import and the network checks.

---

## 1. Responsibilities

- `DiscoveryServer`: answers `CLASSPULSE_DISCOVER` probes; broadcasts `CLASSPULSE_ANNOUNCE` every 3 s
  to the **subnet broadcast address** computed from `InterfaceAddress.getBroadcast()`.
- `DiscoveryClient` / `SessionScanner` / `SessionInfo`: the student side; probe with a timeout, listen
  for announcements, drop sessions not seen for 10 s.
- `NetworkGuard` (**addressing**):
  1. reject attendance from outside the classroom subnet (prefix-length comparison),
  2. flag one device marking attendance for several students (`SUSPICIOUS`),
  3. reverse DNS (`getHostName()`) on a pool thread for the CSV.
- `QuestionImporter` (**URL**): validate the URL, download with `HttpURLConnection` (timeouts, size
  limit, response code, content type), parse CSV, report imported/skipped.
- `tools/QuestionWebServer`: a tiny JDK `HttpServer` serving `docs/sample-questions.csv` for an offline demo.

## 2. The concepts in 5 sentences (lecture theory)

1. **UDP** is connectionless: each `DatagramPacket` is sent on its own, with no handshake and no delivery guarantee, so the receiver must use a timeout (`setSoTimeout`) and the sender may need to repeat.
2. Because UDP needs no connection, one datagram can be sent to a **broadcast address** (e.g. `192.168.1.255`) and reach every host in the subnet. That is how students find the lecturer without knowing the IP.
3. An **IPv4 address** has 32 bits; the **prefix length** (e.g. `/24`) says how many leading bits are the network part, so two hosts are on the same subnet when those leading bits are equal.
4. `InetAddress` represents an IP address (and can do DNS lookups such as `getHostName()`), while `NetworkInterface`/`InterfaceAddress` tell us our own addresses, prefix lengths and broadcast addresses.
5. A **URL** names a resource (scheme, host, port, path); `HttpURLConnection` sends an HTTP GET and lets us check the status code and content type, and set connect and read timeouts.

## 3. Files and classes

| File | What it does |
|---|---|
| `udp/DiscoveryServer.java` | `listenLoop()` (answers probes, unicast), `announceLoop()` (every 3 s), `broadcastAddresses()` |
| `udp/DiscoveryClient.java` | `discover(timeout)`: send probe to every broadcast address + 127.0.0.1, collect replies until `SocketTimeoutException` |
| `udp/SessionScanner.java` | Background threads `udp-scanner` and `udp-announce-listener` (SO_REUSEADDR), stale removal |
| `udp/SessionInfo.java` | Parsed session; host = the datagram's source address |
| `addressing/NetworkGuard.java` | `isInClassroomSubnet()`, `sameNetwork()`, `registerDevice()`, `resolveHostnameAsync()`, `lanAddress()` |
| `addressing/QuestionImporter.java` | `importAsync()`, `importNow()`, `parse()` |
| `tools/QuestionWebServer.java` | `HttpServer` on port 8080: `/questions.csv` |

### The 5 key code blocks to show

1. **Answering a probe**: `DiscoveryServer.listenLoop()`. `socket.receive(packet)` blocks; reply to `packet.getAddress()` and `packet.getPort()` (unicast).
2. **Broadcasting**: `DiscoveryServer.broadcastAddresses()` + `announceLoop()`. `InterfaceAddress.getBroadcast()`, `setBroadcast(true)`, fallback `255.255.255.255`.
3. **Probe with timeout**: `DiscoveryClient.discover()`. `setSoTimeout(2000)`, loop until `SocketTimeoutException`.
4. **Subnet check**: `NetworkGuard.sameNetwork()`. Compare the first `prefixLength` bits byte by byte with a bit mask.
5. **URL import**: `QuestionImporter.importNow()`. `new URI(..).toURL()`, scheme check, `HttpURLConnection` with 3 s / 5 s timeouts, response code 200, `Content-Type`, max 256 KB.

## 4. How my part connects to the others

- **Member 1:** `DiscoveryServer` reads `SessionRegistry.activeSessions()`. The NIO server calls `NetworkGuard` on every `ATTEND`, and `QuestionImporter.importAsync()` for `IMPORT_POLLS`; my result goes back via `server.submit()` because only the NIO thread may write to sockets.
- **Member 2:** `StudentApp` shows my `SessionScanner` list; selecting a session fills the host and join code.
- **Member 4:** `resolveHostnameAsync()` fills `AttendanceRecord.hostname` for the export CSV.
- **Member 5:** the subnet check plus Member 5's rotating code together stop "proxy" attendance from home.

## 5. Two-minute demo script (word for word)

| Time | Do | Say |
|---|---|---|
| 0:00 | Server running, lecturer created a session. Start a Student App with no IP typed | "My first concept is UDP. The student did not type any IP address, yet the session appears in the list." |
| 0:15 | Show `DiscoveryClient.discover()` | "The app broadcasts a CLASSPULSE_DISCOVER datagram to the subnet broadcast address. UDP needs no connection, so one packet reaches every laptop. Because UDP gives no guarantee, I set a **2-second timeout**." |
| 0:35 | Show `DiscoveryServer.listenLoop()` and `broadcastAddresses()` | "The server answers with a unicast reply to the sender's address and port, and every 3 seconds it announces itself. The broadcast address comes from `InterfaceAddress.getBroadcast()`." |
| 0:55 | Lecturer: Polls tab → Import from `http://<ip>:8080/questions.csv` | "Second concept: URLs. The lecturer imports questions from a URL. I use `HttpURLConnection` with timeouts, check the status code and content type, and limit the size. It runs on a pool thread, not on the NIO thread." |
| 1:20 | Two Student Apps on the SAME laptop mark attendance | "Third concept: addressing. Two students marked attendance from one laptop, so the lecturer gets a SUSPICIOUS warning." |
| 1:35 | Show `NetworkGuard.sameNetwork()` | "And attendance from outside the classroom is rejected: with a /24 prefix, the first 24 bits of the student's address must equal the lecturer's network. An IP check is a hint, not proof, because of NAT and shared PCs." |

## 6. Likely viva questions

1. **What is a subnet prefix length?** The number of leading bits of an IP address that form the network part. `/24` = mask `255.255.255.0`, so `192.168.1.10` and `192.168.1.77` are in the same network, `192.168.2.5` is not.
2. **Why can IP-based duplicate detection give false alarms?** Several honest students can share one public/NAT address, use a shared lab PC, or a VPN; and one cheater could use two devices. So we flag, we do not block.
3. **Why UDP for discovery and not TCP?** TCP needs a known partner to connect to; the student does not know the lecturer's IP yet. UDP can broadcast one datagram to the whole subnet.
4. **What happens if a UDP packet is lost?** Nothing breaks: the scanner probes again every 3 s and the server announces every 3 s; entries are kept for 10 s.
5. **Why `setSoTimeout()`?** `receive()` blocks forever by default. With a timeout it throws `SocketTimeoutException`, which ends a discovery round.
6. **Why does the reply go to `packet.getPort()`?** The student's socket uses a random free port; the server must answer exactly that address and port (unicast).
7. **Why do you need SO_REUSEADDR on port 6004?** Several Student Apps on one laptop all want to receive the announcements; without it only the first could bind the port.
8. **Why limit the downloaded file and set timeouts?** A slow or huge (possibly malicious) URL must not hang a thread or fill the server's memory. 3 s connect, 5 s read, 256 KB max.

## 7. Paragraph for the report (≈150 words)

Member 3 implemented session discovery with UDP and two addressing/URL features. The server listens on UDP port
6002 with a `DatagramSocket`; a student application broadcasts a `CLASSPULSE_DISCOVER` datagram to every subnet
broadcast address, obtained from `InterfaceAddress.getBroadcast()`, and collects the unicast replies until a
two-second socket timeout expires. In addition, the server announces active sessions every three seconds so the
student list refreshes automatically, and entries not seen for ten seconds are removed. The `NetworkGuard` class uses
each student's remote `InetAddress`: attendance is accepted only if the address shares the network prefix of the
lecturer's interface (prefix length from `getNetworkPrefixLength()`), several students marking from one IP are
flagged as suspicious, and host names are resolved on a separate thread pool. Questions can be imported from a CSV
file through `java.net.URL` and `HttpURLConnection`, with timeouts, status and content-type checks and a size limit.
