// Owner: Member 1, Java NIO | Lecture topic: Per-connection buffers with SocketChannel and ByteBuffer
package com.classpulse.server.nio;

import com.classpulse.common.Protocol;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Everything the server remembers about ONE connected client. It is attached to the client's
 * SelectionKey, so when the selector says "this key is readable" we immediately have the right
 * buffers and user info.
 *
 * Why buffers? In non-blocking mode one read() returns "whatever bytes have arrived": maybe half a
 * line, maybe three lines. So we collect bytes until we see '\n'. Writes may also be partial,
 * so outgoing data waits in a queue until the socket can take it.
 */
public class ClientConnection {

    final SocketChannel channel;
    final SelectionKey key;
    final InetAddress remoteAddress;
    final String remoteText;
    final long connectedAtMillis = System.currentTimeMillis();

    /** Bytes read from the socket (reused for every read). */
    final ByteBuffer readBuffer = ByteBuffer.allocate(4096);
    /** Bytes of the line being built (may span several reads). */
    private final ByteArrayOutputStream partialLine = new ByteArrayOutputStream();
    /** Outgoing messages not yet fully written. */
    final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();
    /** Close the connection as soon as the write queue is empty (used after ERR messages). */
    boolean closeAfterWrite;

    // ---- filled in after AUTH ----
    String username;
    String role;
    /** Join code of the session this client belongs to (lecturer: own session, student: joined one). */
    String joinCode;

    ClientConnection(SocketChannel channel, SelectionKey key, InetAddress remoteAddress, String remoteText) {
        this.channel = channel;
        this.key = key;
        this.remoteAddress = remoteAddress;
        this.remoteText = remoteText;
    }

    boolean isAuthenticated() {
        return username != null;
    }

    boolean isLecturer() {
        return Protocol.ROLE_LECTURER.equals(role);
    }

    boolean isStudent() {
        return Protocol.ROLE_STUDENT.equals(role);
    }

    /**
     * Takes the bytes currently in readBuffer (already flipped) and returns every COMPLETE line.
     * Unfinished bytes stay in partialLine for the next read.
     * Returns null if a line is longer than Protocol.MAX_LINE (the caller then closes the client).
     */
    List<String> extractLines(List<String> out) {
        while (readBuffer.hasRemaining()) {
            byte b = readBuffer.get();
            if (b == '\n') {
                // decode only complete lines, so a UTF-8 character split across two reads is never broken
                String line = partialLine.toString(StandardCharsets.UTF_8);
                partialLine.reset();
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1); // accept Windows "\r\n" too
                }
                out.add(line);
            } else {
                partialLine.write(b);
                if (partialLine.size() > Protocol.MAX_LINE) {
                    return null;
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return (username == null ? "?" : username) + "@" + remoteText;
    }
}
