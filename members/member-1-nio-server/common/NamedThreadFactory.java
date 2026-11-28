// Owner: Member 1, Java NIO (shared helper) | Lecture topic: Multithreading, naming pool threads
package com.classpulse.common;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gives pool threads readable names such as "export-worker-1" or "poll-timer-2".
 * Without this, threads are called "pool-3-thread-1", which is impossible to explain in a demo.
 */
public final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final boolean daemon;
    private final AtomicInteger counter = new AtomicInteger(1);

    public NamedThreadFactory(String prefix, boolean daemon) {
        this.prefix = prefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable task) {
        Thread t = new Thread(task, prefix + "-" + counter.getAndIncrement());
        t.setDaemon(daemon);
        return t;
    }
}
