// Owner: Member 3, UDP discovery | Lecture topic: Data carried in a UDP datagram
package com.classpulse.udp;

import com.classpulse.common.Protocol;

import java.net.InetAddress;

/**
 * A session found on the LAN. 'host' is NOT inside the message: it is the source address of the
 * datagram (packet.getAddress()), i.e. whoever answered us is the server.
 */
public class SessionInfo {

    private final String host;
    private final int classPort;
    private final int loginPort;
    private final int exportPort;
    private final String joinCode;
    private final String moduleCode;
    private final String title;
    private volatile long lastSeenMillis;

    public SessionInfo(String host, int classPort, int loginPort, int exportPort, String joinCode, String moduleCode, String title) {
        this.host = host;
        this.classPort = classPort;
        this.loginPort = loginPort;
        this.exportPort = exportPort;
        this.joinCode = joinCode;
        this.moduleCode = moduleCode;
        this.title = title;
        this.lastSeenMillis = System.currentTimeMillis();
    }

    /**
     * Parses "CLASSPULSE_HERE|6000|6001|6003|ABC123|IN2120|title" (or _ANNOUNCE).
     * Returns null for anything else (UDP can deliver any random datagram, so validate).
     */
    public static SessionInfo parse(String message, InetAddress from) {
        String[] p = Protocol.split(message.trim(), 7);
        if (p.length != 7 || !(Protocol.HERE.equals(p[0]) || Protocol.ANNOUNCE.equals(p[0]))) {
            return null;
        }
        int c = Protocol.parseIntOr(p[1], -1);
        int l = Protocol.parseIntOr(p[2], -1);
        int e = Protocol.parseIntOr(p[3], -1);
        if (c <= 0 || l <= 0 || e <= 0 || p[4].length() != 6) {
            return null;
        }
        return new SessionInfo(from.getHostAddress(), c, l, e, p[4], p[5], p[6]);
    }

    public boolean isLoopback() {
        return host.startsWith("127.") || "0:0:0:0:0:0:0:1".equals(host);
    }

    public String host() { return host; }
    public int classPort() { return classPort; }
    public int loginPort() { return loginPort; }
    public int exportPort() { return exportPort; }
    public String joinCode() { return joinCode; }
    public String moduleCode() { return moduleCode; }
    public String title() { return title; }
    public long lastSeenMillis() { return lastSeenMillis; }

    public void touch() {
        lastSeenMillis = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return moduleCode + " - " + title + "   [join code " + joinCode + "]   @ " + host;
    }
}
