// Owner: Member 3, Addressing (InetAddress, subnets) | Lecture topic: InetAddress, NetworkInterface, prefix length
package com.classpulse.addressing;

import com.classpulse.common.Config;
import com.classpulse.common.Log;
import com.classpulse.common.NamedThreadFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Uses the student's remote InetAddress (from the TCP connection) for three checks:
 *
 *  1. Same subnet: an IPv4 address is 32 bits. A prefix length of /24 means the first 24 bits
 *     are the NETWORK part (e.g. 192.168.1.x). If the first 24 bits of the student's address equal
 *     the first 24 bits of the lecturer's address, both are on the same classroom network.
 *     Someone at home (different network) is rejected. Loopback (127.0.0.1) is always allowed
 *     for single-laptop demos.
 *  2. Same device: if more than attendance.maxPerDevice different students mark attendance from
 *     ONE IP, the record is flagged SUSPICIOUS. This is a hint, not proof: NAT routers, shared lab
 *     PCs and VPNs can make honest students share an IP.
 *  3. Reverse DNS: InetAddress.getHostName() may block for seconds, so it runs on a pool thread.
 */
public class NetworkGuard {

    private static final String COMPONENT = "NetworkGuard";

    /** One of the lecturer's own addresses with its prefix length, e.g. 192.168.1.10/24. */
    private record LocalNet(InetAddress address, short prefixLength) { }

    private final boolean requireSameSubnet;
    private final int maxPerDevice;
    private final List<LocalNet> localNets = new ArrayList<>();
    /** joinCode -> ip -> set of students that marked attendance from that ip. */
    private final Map<String, Map<String, Set<String>>> devices = new ConcurrentHashMap<>();
    private final ExecutorService dnsPool = Executors.newFixedThreadPool(2, new NamedThreadFactory("dns-resolver", true));

    public NetworkGuard(Config config) {
        this.requireSameSubnet = config.getBool("attendance.requireSameSubnet", true);
        this.maxPerDevice = config.getInt("attendance.maxPerDevice", 1);
        loadLocalNetworks();
    }

    /** Reads the lecturer laptop's network interfaces and their prefix lengths. */
    private void loadLocalNetworks() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    localNets.add(new LocalNet(ia.getAddress(), ia.getNetworkPrefixLength()));
                    Log.info(COMPONENT, "classroom network: " + ia.getAddress().getHostAddress() + "/"
                            + ia.getNetworkPrefixLength() + " on " + ni.getDisplayName());
                }
            }
        } catch (SocketException e) {
            Log.warn(COMPONENT, "cannot list network interfaces: " + e.getMessage());
        }
    }

    /** Check 1: is this student inside the classroom subnet? */
    public boolean isInClassroomSubnet(InetAddress student) {
        if (!requireSameSubnet || student.isLoopbackAddress()) {
            return true;
        }
        for (LocalNet net : localNets) {
            if (sameNetwork(student, net.address(), net.prefixLength())) {
                return true;
            }
        }
        return false;
    }

    /** Compares the first prefixLength bits of two addresses (works for IPv4 and IPv6). */
    static boolean sameNetwork(InetAddress a, InetAddress b, int prefixLength) {
        byte[] x = a.getAddress();
        byte[] y = b.getAddress();
        if (x.length != y.length) {
            return false; // IPv4 vs IPv6
        }
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (x[i] != y[i]) {
                return false;
            }
        }
        if (remainingBits > 0) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF; // e.g. 3 bits -> 11100000
            return (x[fullBytes] & mask) == (y[fullBytes] & mask);
        }
        return true;
    }

    /**
     * Check 2: remember that 'username' marked from 'ip'.
     * Returns how many DIFFERENT students have now used this ip in this session.
     */
    public int registerDevice(String joinCode, String ip, String username) {
        Map<String, Set<String>> byIp = devices.computeIfAbsent(joinCode, k -> new ConcurrentHashMap<>());
        Set<String> users = byIp.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet());
        users.add(username);
        return users.size();
    }

    public boolean isSuspicious(int studentsOnDevice) {
        return studentsOnDevice > maxPerDevice;
    }

    public Set<String> studentsOnDevice(String joinCode, String ip) {
        Map<String, Set<String>> byIp = devices.get(joinCode);
        if (byIp == null || byIp.get(ip) == null) {
            return Set.of();
        }
        return Set.copyOf(byIp.get(ip));
    }

    /** Check 3: reverse DNS lookup on a pool thread (it can block), result delivered to the callback. */
    public void resolveHostnameAsync(InetAddress address, Consumer<String> callback) {
        dnsPool.execute(() -> {
            String name = address.getHostName(); // reverse lookup, returns the IP text if no name is found
            Log.info(COMPONENT, "resolved " + address.getHostAddress() + " -> " + name);
            callback.accept(name);
        });
    }

    /** Best guess of this laptop's LAN IPv4 address (for the banner and the discovery replies). */
    public static InetAddress lanAddress() {
        try {
            InetAddress fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        // 10.x, 172.16-31.x or 192.168.x: a private LAN address.
                        // Adapters of virtual machines (VirtualBox, Hyper-V, VMware, WSL) are only a fallback.
                        if (!isVirtualAdapter(ni.getDisplayName())) {
                            return a;
                        }
                        if (fallback == null) {
                            fallback = a;
                        }
                    }
                }
            }
            return fallback != null ? fallback : InetAddress.getLocalHost();
        } catch (Exception e) {
            return InetAddress.getLoopbackAddress();
        }
    }

    private static boolean isVirtualAdapter(String name) {
        String n = name == null ? "" : name.toLowerCase();
        return n.contains("virtual") || n.contains("vmware") || n.contains("hyper-v") || n.contains("vethernet")
                || n.contains("wsl") || n.contains("docker");
    }

    public void shutdown() {
        dnsPool.shutdownNow();
    }
}
