// Owner: Member 1, Java NIO (shared logging) | Lecture topic: Observing threads in network servers
package com.classpulse.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Tiny logger. Format: [HH:mm:ss.SSS] [thread] [component] message
 * Printing the THREAD NAME is important for the demo: it proves that all classroom traffic
 * runs on the single "NIO-selector" thread, polls close on "poll-timer-N", exports run on
 * "export-worker-N", and so on.
 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() { }

    public static void info(String component, String message) {
        print(System.out, component, message);
    }

    public static void warn(String component, String message) {
        print(System.out, component, "WARNING: " + message);
    }

    public static void error(String component, String message) {
        print(System.err, component, "ERROR: " + message);
    }

    private static void print(java.io.PrintStream out, String component, String message) {
        String line = "[" + LocalTime.now().format(TIME) + "] [" + Thread.currentThread().getName() + "] ["
                + component + "] " + message;
        // synchronized on the stream so lines from different threads never interleave
        synchronized (out) {
            out.println(line);
        }
    }
}
