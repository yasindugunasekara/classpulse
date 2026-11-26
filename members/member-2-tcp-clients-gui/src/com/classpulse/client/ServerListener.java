// Owner: Member 2, TCP sockets (client) | Lecture topic: Callbacks from a socket reader thread
package com.classpulse.client;

/**
 * Implemented by the GUIs. ClassroomClient's reader thread calls these methods, so the GUI must
 * move every Swing update onto the event thread with SwingUtilities.invokeLater().
 */
public interface ServerListener {

    /** One protocol line from the server, e.g. "POLL_START|P1|30|A;B;C|question". */
    void onLine(String line);

    /** Connection state changed (connected, lost, reconnecting, gave up). */
    void onConnectionStatus(String message, boolean connected);
}
