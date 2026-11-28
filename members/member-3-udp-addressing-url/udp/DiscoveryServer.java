// Owner: Member 3, UDP | Lecture topic: DatagramSocket, DatagramPacket, broadcast, InetAddress
package com.classpulse.udp;

import com.classpulse.common.Config;
import com.classpulse.common.Log;
import com.classpulse.common.Protocol;
import com.classpulse.server.nio.ClassSession;
import com.classpulse.server.nio.SessionRegistry;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * UDP session discovery on port 6002, so students never have to type the lecturer's IP.
 *
 *  - Thread "udp-discovery": waits for CLASSPULSE_DISCOVER and answers the sender (unicast)
 *    with one CLASSPULSE_HERE line per active session.
 *  - Thread "udp-announce": every 3 s broadcasts CLASSPULSE_ANNOUNCE to the subnet broadcast
 *    address, so open Student Apps update their list without clicking Refresh.
 *
 * Why UDP? It is connectionless: one small datagram can go to EVERY host on the subnet
 * (broadcast). TCP cannot do that; it always needs one known partner.
 */
public class DiscoveryServer {

    private static final String COMPONENT = "UDP-Discovery";

    private final Config config;
    private final SessionRegistry registry;
    private DatagramSocket socket;
    private volatile boolean running;

    public DiscoveryServer(Config config, SessionRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    public void start() throws SocketException {
        socket = new DatagramSocket(config.discoveryPort()); // bound to all interfaces, port 6002
        socket.setBroadcast(true);                           // allowed to send to broadcast addresses
        running = true;
        Thread listener = new Thread(this::listenLoop, "udp-discovery");
        listener.setDaemon(true);
        listener.start();
        Thread announcer = new Thread(this::announceLoop, "udp-announce");
        announcer.setDaemon(true);
        announcer.start();
        Log.info(COMPONENT, "UDP discovery on port " + config.discoveryPort() + ", announcing to " + broadcastAddresses()
                + " port " + config.announcePort());
    }

    private void listenLoop() {
        byte[] buf = new byte[1024];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet); // blocks until a datagram arrives
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                if (!Protocol.DISCOVER.equals(msg)) {
                    continue; // ignore anything that is not our probe
                }
                List<ClassSession> active = registry.activeSessions();
                Log.info(COMPONENT, "DISCOVER from " + packet.getAddress().getHostAddress() + ":" + packet.getPort()
                        + ", answering with " + active.size() + " session(s)");
                for (ClassSession s : active) {
                    // unicast reply: send back to the exact address and port the probe came from
                    send(describe(Protocol.HERE, s), packet.getAddress(), packet.getPort());
                }
            } catch (IOException e) {
                if (running) {
                    Log.warn(COMPONENT, "receive failed: " + e.getMessage());
                }
            }
        }
    }

    private void announceLoop() {
        while (running) {
            try {
                Thread.sleep(3000);
                List<ClassSession> active = registry.activeSessions();
                if (active.isEmpty()) {
                    continue; // nothing to advertise
                }
                List<InetAddress> targets = broadcastAddresses();
                targets.add(InetAddress.getLoopbackAddress()); // student apps on this same laptop
                for (ClassSession s : active) {
                    String msg = describe(Protocol.ANNOUNCE, s);
                    for (InetAddress target : targets) {
                        send(msg, target, config.announcePort());
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                Log.warn(COMPONENT, "announce failed: " + e);
            }
        }
    }

    private String describe(String type, ClassSession s) {
        return Protocol.join(type, config.classroomPort(), config.loginPort(), config.exportPort(),
                s.joinCode(), s.moduleCode(), s.title());
    }

    private void send(String msg, InetAddress address, int port) {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        try {
            socket.send(new DatagramPacket(data, data.length, address, port));
        } catch (IOException e) {
            // e.g. a broadcast address of a disconnected adapter: just skip it
            Log.warn(COMPONENT, "send to " + address.getHostAddress() + " failed: " + e.getMessage());
        }
    }

    /**
     * The subnet broadcast address of every active IPv4 interface, e.g. 192.168.1.255 for
     * 192.168.1.10/24, plus the "limited broadcast" 255.255.255.255 as a fallback.
     */
    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast(); // null for IPv6 (it has no broadcast)
                    if (b != null && !list.contains(b)) {
                        list.add(b);
                    }
                }
            }
        } catch (SocketException e) {
            Log.warn(COMPONENT, "cannot list interfaces: " + e.getMessage());
        }
        try {
            InetAddress all = InetAddress.getByName("255.255.255.255");
            if (!list.contains(all)) {
                list.add(all);
            }
        } catch (IOException ignored) {
            // cannot happen for a literal IP
        }
        return list;
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }
}
