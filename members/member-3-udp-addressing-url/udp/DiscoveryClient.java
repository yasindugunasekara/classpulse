// Owner: Member 3, UDP | Lecture topic: Broadcasting a probe and collecting replies with a timeout
package com.classpulse.udp;

import com.classpulse.common.Config;
import com.classpulse.common.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One discovery round: broadcast CLASSPULSE_DISCOVER, then collect CLASSPULSE_HERE replies
 * until nothing arrives for the timeout. UDP gives no guarantee that a reply comes at all, which
 * is why setSoTimeout() is essential: without it receive() would wait forever.
 */
public class DiscoveryClient {

    private final Config config;

    public DiscoveryClient(Config config) {
        this.config = config;
    }

    public List<SessionInfo> discover(int timeoutMs) throws IOException {
        List<SessionInfo> found = new ArrayList<>();
        // new DatagramSocket() = any free local port; the server replies to that port.
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);        // needed to send to 255.255.255.255 / x.x.x.255
            socket.setSoTimeout(timeoutMs);   // receive() throws SocketTimeoutException after this
            byte[] probe = Protocol.DISCOVER.getBytes(StandardCharsets.UTF_8);

            List<InetAddress> targets = DiscoveryServer.broadcastAddresses();
            targets.add(InetAddress.getLoopbackAddress()); // also find a server on this laptop
            for (InetAddress target : targets) {
                try {
                    socket.send(new DatagramPacket(probe, probe.length, target, config.discoveryPort()));
                } catch (IOException ignored) {
                    // one unusable interface must not stop the scan
                }
            }

            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    break; // no more replies: the round is over
                }
                String msg = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
                SessionInfo info = SessionInfo.parse(msg, reply.getAddress());
                if (info != null) {
                    merge(found, info);
                }
            }
        }
        return found;
    }

    /**
     * The same session may answer twice (via 127.0.0.1 and via the LAN IP). Keep one entry per join
     * code and prefer loopback: on the same laptop it always works and matches the certificate.
     */
    static void merge(List<SessionInfo> list, SessionInfo info) {
        for (int i = 0; i < list.size(); i++) {
            SessionInfo old = list.get(i);
            if (old.joinCode().equals(info.joinCode())) {
                if (info.isLoopback() && !old.isLoopback()) {
                    list.set(i, info);
                } else {
                    old.touch();
                }
                return;
            }
        }
        list.add(info);
    }
}
