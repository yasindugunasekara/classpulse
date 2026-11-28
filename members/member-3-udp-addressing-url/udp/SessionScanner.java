// Owner: Member 3, UDP | Lecture topic: Background UDP listener, receiving broadcasts, stale entries
package com.classpulse.udp;

import com.classpulse.common.Config;
import com.classpulse.common.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps an up-to-date list of sessions for the Student App:
 *  - "udp-announce-listener": receives CLASSPULSE_ANNOUNCE broadcasts on port 6004
 *    (SO_REUSEADDR so several Student Apps on one laptop can all listen),
 *  - "udp-scanner": sends a DISCOVER probe every 3 s as a fallback (works even if broadcasts to
 *    6004 are blocked),
 *  - entries not seen for 10 s are removed (the lecturer ended the session or left the network).
 * The listener callback runs on a background thread; the GUI must use SwingUtilities.invokeLater.
 */
public class SessionScanner {

    private static final String COMPONENT = "UDP-Scanner";
    private static final long STALE_MS = 10_000;

    private final Config config;
    private final DiscoveryClient client;
    private final List<SessionInfo> sessions = new ArrayList<>(); // guarded by 'this'
    private final Consumer<List<SessionInfo>> onChange;
    private volatile boolean running;
    private DatagramSocket announceSocket;

    public SessionScanner(Config config, Consumer<List<SessionInfo>> onChange) {
        this.config = config;
        this.client = new DiscoveryClient(config);
        this.onChange = onChange;
    }

    public void start() {
        running = true;
        Thread scan = new Thread(this::scanLoop, "udp-scanner");
        scan.setDaemon(true);
        scan.start();
        Thread listen = new Thread(this::announceLoop, "udp-announce-listener");
        listen.setDaemon(true);
        listen.start();
    }

    /** Runs one discovery round right now (Refresh button). Blocking: call it off the Swing thread. */
    public void refreshNow() {
        try {
            for (SessionInfo s : client.discover(2000)) {
                add(s);
            }
        } catch (IOException e) {
            Log.warn(COMPONENT, "discovery failed: " + e.getMessage());
        }
        publish();
    }

    private void scanLoop() {
        while (running) {
            refreshNow(); // takes ~2 s (the receive timeout)
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void announceLoop() {
        try {
            // bind(null) first so we can set SO_REUSEADDR before binding to the shared port
            announceSocket = new DatagramSocket(null);
            announceSocket.setReuseAddress(true);
            announceSocket.bind(new InetSocketAddress(config.announcePort()));
        } catch (IOException e) {
            Log.warn(COMPONENT, "cannot listen for announcements on " + config.announcePort()
                    + " (" + e.getMessage() + "), using DISCOVER probes only");
            return;
        }
        byte[] buf = new byte[1024];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                announceSocket.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                SessionInfo info = SessionInfo.parse(msg, p.getAddress());
                if (info != null) {
                    add(info);
                    publish();
                }
            } catch (IOException e) {
                if (running) {
                    Log.warn(COMPONENT, "announce receive failed: " + e.getMessage());
                }
            }
        }
    }

    private synchronized void add(SessionInfo info) {
        DiscoveryClient.merge(sessions, info);
    }

    /** Removes stale entries and passes a copy of the list to the GUI. */
    private void publish() {
        List<SessionInfo> copy;
        synchronized (this) {
            long now = System.currentTimeMillis();
            sessions.removeIf(s -> now - s.lastSeenMillis() > STALE_MS);
            copy = new ArrayList<>(sessions);
        }
        onChange.accept(copy);
    }

    public void stop() {
        running = false;
        if (announceSocket != null) {
            announceSocket.close();
        }
    }
}
