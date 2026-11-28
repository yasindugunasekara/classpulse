// Owner: Member 5, Secure networking (SSL/TLS) | Lecture topic: SSLServerSocket, keystore, KeyManagerFactory
package com.classpulse.security;

import com.classpulse.common.Config;
import com.classpulse.common.Log;
import com.classpulse.common.NamedThreadFactory;
import com.classpulse.common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * TLS login service on port 6001. Protocol: one request line, one reply line, then close.
 *   LOGIN|user|pass     -> OK|token|role   or ERR|reason
 *   REGISTER|user|pass  -> OK|token|STUDENT or ERR|reason
 *
 * Steps (the "TLS recipe" from the lecture):
 *   1. load the keystore (our private key + certificate)
 *   2. KeyManagerFactory: "which key do I present to clients?"
 *   3. SSLContext.getInstance("TLS").init(keyManagers, ...)
 *   4. SSLServerSocketFactory -> SSLServerSocket.accept() just like a normal ServerSocket
 * Everything after the handshake is encrypted, so the password is invisible in Wireshark.
 */
public class SecureLoginServer {

    private static final String COMPONENT = "TLS-Login";

    private final Config config;
    private final UserStore users;
    private final TokenStore tokens;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, new NamedThreadFactory("login-worker", true));
    private SSLServerSocket serverSocket;
    private volatile boolean running;

    public SecureLoginServer(Config config, UserStore users, TokenStore tokens) {
        this.config = config;
        this.users = users;
        this.tokens = tokens;
    }

    /** Build the SSLContext from the PKCS12 keystore created by scripts/gen-keystore. */
    static SSLContext createServerContext(Config config) throws Exception {
        Path ksPath = Paths.get(config.get("tls.keystore", "certs/server.p12"));
        char[] password = config.get("tls.keystore.password", "changeit").toCharArray();
        if (!Files.exists(ksPath)) {
            throw new IOException("Keystore " + ksPath + " not found. Run scripts/gen-keystore first.");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ksPath)) {
            keyStore.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password); // the key manager hands our private key to the TLS handshake
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null); // no client certificates, default SecureRandom
        return ctx;
    }

    public void start() throws Exception {
        SSLContext ctx = createServerContext(config);
        SSLServerSocketFactory factory = ctx.getServerSocketFactory();
        serverSocket = (SSLServerSocket) factory.createServerSocket(config.loginPort());
        serverSocket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"}); // no old, broken SSL/TLS versions
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "tls-login-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.info(COMPONENT, "TLS login service listening on port " + config.loginPort() + " (TLSv1.3, TLSv1.2)");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept(); // blocks until a client connects
                pool.execute(() -> handle((SSLSocket) client)); // a small pool task per login
            } catch (IOException e) {
                if (running) {
                    Log.error(COMPONENT, "accept failed: " + e.getMessage());
                }
            }
        }
    }

    /** One request line, one reply line, close. try-with-resources always closes the socket. */
    private void handle(SSLSocket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        try (socket) {
            socket.setSoTimeout(10_000); // a silent client cannot hold a worker forever
            socket.startHandshake();     // explicit handshake so we can log the result
            SSLSession s = socket.getSession();
            Log.info(COMPONENT, "handshake with " + remote + ": " + s.getProtocol() + ", " + s.getCipherSuite());

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String line = in.readLine();
            String reply = (line == null || line.length() > Protocol.MAX_LINE) ? "ERR|bad request" : process(line, remote);
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (SocketException e) {
            Log.warn(COMPONENT, "connection from " + remote + " closed: " + e.getMessage());
        } catch (IOException e) {
            // e.g. a client that does not trust our certificate aborts the handshake
            Log.warn(COMPONENT, "TLS problem with " + remote + ": " + e.getMessage());
        }
    }

    private String process(String line, String remote) {
        String[] p = Protocol.split(line, 3);
        if (p.length < 3) {
            return "ERR|expected LOGIN|user|password or REGISTER|user|password";
        }
        String cmd = p[0];
        String user = p[1].trim();
        String pass = p[2];
        if (Protocol.LOGIN.equals(cmd)) {
            Principal principal = users.authenticate(user, pass);
            if (principal == null) {
                Log.info(COMPONENT, "login FAILED for '" + user + "' from " + remote); // password never logged
                return "ERR|wrong username or password";
            }
            Log.info(COMPONENT, "login OK for " + principal.username() + " (" + principal.role() + ")");
            return Protocol.join("OK", tokens.issue(principal.username(), principal.role()), principal.role());
        }
        if (Protocol.REGISTER.equals(cmd)) {
            String error = users.register(user, pass);
            if (error != null) {
                return "ERR|" + error;
            }
            return Protocol.join("OK", tokens.issue(user, Protocol.ROLE_STUDENT), Protocol.ROLE_STUDENT);
        }
        return "ERR|unknown command";
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // shutting down anyway
        }
        pool.shutdownNow();
    }
}
