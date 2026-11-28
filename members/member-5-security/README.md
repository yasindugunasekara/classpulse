# Member 5: Secure login and anti-proxy attendance codes

**Concept:** secure network programming: SSL/TLS (`SSLServerSocket`, `SSLSocket`, `SSLContext`,
`KeyManagerFactory`, `TrustManagerFactory`), keystore and truststore, password hashing (PBKDF2 + salt),
random tokens, HMAC-based rotating codes.
**Service:** TLS login, TCP port **6001**.

---

## 1. Responsibilities

- `scripts/gen-keystore.sh` / `.bat`: `keytool` commands that create the RSA 2048 key pair (`certs/server.p12`), export the certificate, and build the client truststore (`certs/client-truststore.p12`); optional LAN IP in the SAN.
- `SecureLoginServer`: keystore → `KeyManagerFactory` → `SSLContext("TLS")` → `SSLServerSocket`
  on 6001, TLSv1.3/1.2 only; small pool (`login-worker-N`); logs protocol and cipher suite.
- `SecureLoginClient`: truststore → `TrustManagerFactory` → `SSLSocket`, hostname verification,
  `startHandshake()`, clear errors (wrong password vs. certificate problem vs. server down).
- `UserStore`: `data/users.txt` = `username:role:saltB64:hashB64`; **PBKDF2WithHmacSHA256**, 65536
  iterations, 256-bit, 16-byte salt; constant-time compare; seeds the demo users; never logs passwords.
- `TokenStore`: 32 random bytes, Base64-URL, 4-hour expiry, `issue / validate / revoke`.
- `AttendanceCodeGenerator`: TOTP-style code = first 6 chars of `Base32(HMAC-SHA256(secret, sessionId + window))`; accepts the current and previous 30 s window.
- `Principal`: username + role (LECTURER / STUDENT).

## 2. The concept in 5 sentences (lecture theory)

1. **TLS** runs on top of TCP: in the handshake the server proves its identity with a **certificate**, both sides agree on keys, and afterwards everything is encrypted and integrity-protected.
2. The server keeps its **private key and certificate in a keystore**; clients keep the certificates they trust in a **truststore**, and the handshake fails if the server's certificate is not trusted or does not match the host name (SAN).
3. Passwords must never be stored in plain text: we store a **slow, salted hash** (PBKDF2, 65536 iterations), so a stolen file does not reveal passwords and identical passwords get different hashes.
4. After login the server issues a **random token**; other channels (the NIO classroom channel) carry only the token, never the password.
5. An **HMAC** with a secret key produces values nobody can predict without the key, so `HMAC(secret, time window)` gives one-time attendance codes that change every 30 seconds, like a TOTP authenticator app.

## 3. Files and classes

| File | What it does |
|---|---|
| `security/SecureLoginServer.java` | `createServerContext()`, `start()`, `handle()` (handshake log), `process()` LOGIN/REGISTER |
| `security/SecureLoginClient.java` | `createClientContext()`, `send()` with hostname verification and friendly errors |
| `security/UserStore.java` | `register()`, `authenticate()`, `hash()` (PBKDF2), seeding |
| `security/TokenStore.java` | `issue()`, `validate()`, `revoke()` |
| `security/AttendanceCodeGenerator.java` | `startSession()`, `codeForWindow()`, `isValidAt()`, `base32()` |
| `security/Principal.java` | record (username, role) |
| `scripts/gen-keystore.*` | `keytool -genkeypair / -exportcert / -importcert` |

### The 5 key code blocks to show

1. **TLS server set-up**: `SecureLoginServer.createServerContext()` + `start()`. `KeyStore.load`, `KeyManagerFactory.init`, `SSLContext.init`, `createServerSocket`, `setEnabledProtocols(TLSv1.3, TLSv1.2)`.
2. **Handshake log**: `SecureLoginServer.handle()`. `startHandshake()`, `getSession().getProtocol()` / `getCipherSuite()`.
3. **Trust on the client**: `SecureLoginClient.createClientContext()` + `send()`. `TrustManagerFactory`, `setEndpointIdentificationAlgorithm("HTTPS")`, `SSLHandshakeException` → "certificate problem".
4. **Password hashing**: `UserStore.hash()` + `authenticate()`. `PBEKeySpec(password, salt, 65536, 256)`, `MessageDigest.isEqual`.
5. **Rotating code**: `AttendanceCodeGenerator.codeForWindow()` + `isValidAt()`. `Mac.getInstance("HmacSHA256")`, window = time / 30 s, accept the current and previous window.

## 4. How my part connects to the others

- **Member 2:** `LoginDialog` calls my `SecureLoginClient`; the token it gets is sent as `AUTH|token` to the NIO server.
- **Member 1:** the NIO server calls `TokenStore.validate()` on AUTH, `AttendanceCodeGenerator.isValid()` on ATTEND, and sends `ATT_CODE` to the lecturer when the window changes.
- **Member 4:** `ExportTask` checks the lecturer token with my `TokenStore`.
- **Member 3:** the subnet check complements the rotating code: a forwarded code expires within a minute *and* is rejected from outside the classroom network.

## 5. Two-minute demo script (word for word)

| Time | Do | Say |
|---|---|---|
| 0:00 | Open `gen-keystore.bat` | "My topic is secure networking. This script uses keytool to make an RSA key pair in a **keystore** for the server, and a **truststore** with only the public certificate for the clients." |
| 0:15 | Open `SecureLoginServer.createServerContext()`; show the server log | "The login server loads the keystore into a KeyManagerFactory and an SSLContext and opens an **SSLServerSocket** on 6001. The log shows each handshake: **TLSv1.3, TLS_AES_256_GCM_SHA384**." |
| 0:35 | Log in with a wrong password, then the right one | "Wrong password: rejected. Correct password: the server returns a random **token**. The NIO channel only ever sees this token." |
| 0:50 | Open `data/users.txt` | "This is the user file: only salt and PBKDF2 hash, 65,536 iterations. Even I cannot read the passwords. Without TLS, Wireshark would show the password in plain text; with TLS it shows only encrypted records." |
| 1:10 | Dashboard: Start attendance | "Now the anti-proxy attendance code. It is the first 6 characters of an **HMAC-SHA256** of a secret random key and the current 30-second window, like a Google Authenticator code. It changes every 30 seconds." |
| 1:30 | Student types a code that is 2 windows old (or wait) → rejected | "An old code is rejected. I accept the current and the previous window only, to allow for typing delay. A code photographed and sent to a friend at home stops working within a minute." |

## 6. Likely viva questions

1. **Why a salt?** So two users with the same password get different hashes, and pre-computed rainbow tables are useless; an attacker must attack each hash separately.
2. **Why PBKDF2 with 65536 iterations instead of plain SHA-256?** It is deliberately slow; one login check costs milliseconds, but billions of guesses become far too expensive.
3. **Why accept the previous code window?** A student may read the code at second 29 and submit at second 31; accepting one previous window (max 60 s) tolerates delay and small clock differences while keeping forwarded codes short-lived.
4. **What is a truststore?** The list of certificates a client trusts. During the handshake the server's certificate must be signed by (here: be) one of them, otherwise `SSLHandshakeException`.
5. **What is the difference between keystore and truststore?** Keystore = my own private key + certificate (server side, secret). Truststore = other parties' public certificates I trust (client side, shareable).
6. **Why `MessageDigest.isEqual` for comparing hashes?** It takes the same time whether the first or the last byte differs, so an attacker cannot learn the hash byte by byte from response times.
7. **The classroom channel (NIO) is not encrypted: is that a problem?** It never carries passwords, only a random 4-hour token. Adding TLS to NIO needs `SSLEngine`; that is listed as future work.
8. **What does the SAN in the certificate do?** It lists the host names/IPs the certificate is valid for. With hostname verification on, connecting to the lecturer's LAN IP only works if that IP is in the SAN (`gen-keystore <ip>`).

## 7. What an attacker on the classroom Wi-Fi sees

- **Without TLS** (plain TCP login) a Wireshark capture with filter `tcp.port == 6001` would show
  `LOGIN|s001|s001pass` in clear text: anyone on the same Wi-Fi could steal the password.
- **With TLS** (our implementation) the capture shows only the handshake (`Client Hello`,
  `Server Hello`, certificate in TLS 1.2 / encrypted in TLS 1.3) and then `Application Data`
  records with encrypted bytes. Neither the password nor the token is visible.
- **Design note:** TLS protects the login channel. The NIO classroom channel (port 6000) only
  carries the random token, attendance codes (valid for at most 60 s and only inside the subnet)
  and votes. Encrypting the NIO channel would require `javax.net.ssl.SSLEngine` driven by the
  selector loop (wrap/unwrap of ByteBuffers); this is **future work**.
- A fake server cannot steal passwords: its certificate is not in our truststore, so the handshake
  fails *before* the password is sent.

## 8. Paragraph for the report (≈150 words)

Member 5 secured authentication and attendance. A self-signed RSA 2048 certificate is generated with `keytool`
into a PKCS12 keystore, and its public certificate is imported into a client truststore. The login server loads the
keystore through a `KeyManagerFactory` into an `SSLContext` and accepts connections on an `SSLServerSocket` restricted to
TLS 1.3 and 1.2; each handshake's protocol and cipher suite are logged. Clients trust only certificates in the
truststore and verify the host name, so credentials are never sent to an impostor and are never visible on the
network. Passwords are stored as PBKDF2-HMAC-SHA256 hashes with 65,536 iterations and a random 16-byte salt and
compared in constant time. A successful login yields a random 256-bit token with a four-hour lifetime, which is the
only credential used on the NIO channel. Attendance codes are derived as HMAC-SHA256 of a per-session secret and
the current 30-second window, which prevents code sharing outside the classroom.
