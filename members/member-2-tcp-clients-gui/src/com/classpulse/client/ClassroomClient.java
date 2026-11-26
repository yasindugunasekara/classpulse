// Owner: Member 2, TCP sockets | Lecture topic: java.net.Socket, connect/read/write/close, reader thread, reconnect
package com.classpulse.client;

import com.classpulse.common.Log;
import com.classpulse.common.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Classic BLOCKING TCP client to the classroom server (port 6000). The life of a TCP socket:
 *
 *   socket()  : new Socket()
 *   connect() : socket.connect(host:port, timeout)   -> TCP three-way handshake
 *   write()   : writer.write(line + "\n"); flush()
 *   read()    : reader.readLine()  (blocks until a full line arrives)
 *   close()   : socket.close()     -> TCP FIN
 *
 * readLine() BLOCKS, so it runs on a dedicated reader thread; otherwise the GUI would freeze.
 * Writes come from the Swing thread, so send() is synchronized (two lines must never mix).
 * If the connection drops, we try to reconnect 3 times, 2 seconds apart, then give up and let the
 * user press "Reconnect".
 */
public class ClassroomClient {

    private static final String COMPONENT = "TCP-Client";

    /** Gets a fresh token (does a new TLS login), used on every reconnect: the server may have restarted. */
    public interface TokenSupplier {
        String freshToken() throws Exception;
    }

    private final String host;
    private final int port;
    private final TokenSupplier tokenSupplier;
    private final ServerListener listener;

    private Socket socket;
    private BufferedWriter writer;
    private volatile boolean userClosed;
    private volatile boolean connected;
    private volatile String joinCode; // re-joined automatically after a reconnect
    private int generation;           // which connection a reader thread belongs to

    public ClassroomClient(String host, int port, TokenSupplier tokenSupplier, ServerListener listener) {
        this.host = host;
        this.port = port;
        this.tokenSupplier = tokenSupplier;
        this.listener = listener;
    }

    /** Remember the session so a reconnect can send JOIN_SESSION again (students). */
    public void rememberJoinCode(String code) {
        this.joinCode = code;
    }

    /** First connection, with the token from the login dialog. Call it OFF the Swing thread. */
    public void connect(String token) throws IOException {
        userClosed = false;
        open(token);
    }

    private synchronized void open(String token) throws IOException {
        Socket s = new Socket();                                  // 1. socket()
        try {
            s.connect(new InetSocketAddress(host, port), 3000);   // 2. connect() with 3 s timeout
        } catch (IOException e) {
            s.close();
            throw e;
        }
        s.setTcpNoDelay(true); // send small protocol lines immediately (no Nagle delay)
        socket = s;
        writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        connected = true;
        int myGeneration = ++generation;

        Thread t = new Thread(() -> readLoop(reader, myGeneration), "tcp-reader");
        t.setDaemon(true);
        t.start();

        send(Protocol.join(Protocol.AUTH, token));               // 3. write(): first line must be AUTH
        if (joinCode != null) {
            send(Protocol.join(Protocol.JOIN_SESSION, joinCode));
        }
        listener.onConnectionStatus("Connected to " + host + ":" + port, true);
        Log.info(COMPONENT, "connected to " + host + ":" + port);
    }

    /** The reader thread: blocks in readLine() and hands every line to the GUI. */
    private void readLoop(BufferedReader reader, int myGeneration) {
        String reason = "server closed the connection";
        try {
            String line;
            while ((line = reader.readLine()) != null) {         // 4. read(): null = server closed (EOF)
                listener.onLine(line);
            }
        } catch (IOException e) {
            reason = e.getMessage();                              // e.g. "Connection reset"
        } finally {
            boolean current;
            synchronized (this) {
                current = myGeneration == generation;
                if (current) {
                    connected = false;
                    closeQuietly();
                }
            }
            if (current && !userClosed) {
                Log.warn(COMPONENT, "connection lost: " + reason);
                listener.onConnectionStatus("Connection to the server was lost (" + reason + ")", false);
                autoReconnect();
            }
        }
    }

    /** 3 tries, 2 s apart. Runs on the (old) reader thread, never on the Swing thread. */
    private void autoReconnect() {
        for (int attempt = 1; attempt <= 3 && !userClosed; attempt++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            listener.onConnectionStatus("Reconnecting... (attempt " + attempt + " of 3)", false);
            try {
                open(tokenSupplier.freshToken());
                return; // success
            } catch (Exception e) {
                Log.warn(COMPONENT, "reconnect attempt " + attempt + " failed: " + e.getMessage());
            }
        }
        if (!userClosed) {
            listener.onConnectionStatus("Server not reachable. Start the server, then click Reconnect.", false);
        }
    }

    /** Reconnect button. Call it OFF the Swing thread. */
    public void reconnectNow() {
        userClosed = false;
        synchronized (this) {
            generation++; // the old reader thread (if any) must not react any more
            closeQuietly();
        }
        try {
            open(tokenSupplier.freshToken());
        } catch (Exception e) {
            listener.onConnectionStatus("Reconnect failed: " + friendly(e), false);
        }
    }

    /** Sends one line. synchronized: lines from different threads are never interleaved. */
    public synchronized boolean send(String line) {
        if (writer == null || !connected) {
            listener.onConnectionStatus("Not connected: message not sent", false);
            return false;
        }
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush(); // push the bytes to the socket now
            return true;
        } catch (IOException e) {
            listener.onConnectionStatus("Send failed: " + e.getMessage(), false);
            return false;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    /** 5. close(): say goodbye politely, then close the socket. */
    public void close() {
        userClosed = true;
        if (connected) {
            send(Protocol.QUIT);
        }
        synchronized (this) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close(); // also unblocks the reader thread's readLine()
            }
        } catch (IOException ignored) {
            // nothing else to do
        }
    }

    /** Turns exceptions into messages a student can understand. */
    public static String friendly(Exception e) {
        if (e instanceof ConnectException) {
            return "the server is not running or the firewall blocks the port";
        }
        if (e instanceof UnknownHostException) {
            return "unknown host name";
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
