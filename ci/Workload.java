// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A JVM for CI to record, with something in it for every jfrq command: two event loops that
 * contend for a lock a housekeeper holds for 100 ms at a time (locks, stalls, convoys), a
 * pool that makes a thread per task in bursts (info --thread), throwables, and allocation.
 * Plain Java 21, run as a source file: {@code java ci/Workload.java SECONDS}. It prints
 * {@code ready} once every thread runs, and exits after SECONDS.
 */
public final class Workload {

    private static final Object REGISTRY = new Object();
    private static volatile boolean running = true;
    private static volatile long sink;

    public static void main(final String[] args) throws Exception {
        final long seconds = Long.parseLong(args[0]);
        final Thread housekeeper = new Thread(Workload::housekeeper, "housekeeper");
        housekeeper.start();
        final Thread[] loops = new Thread[2];
        for (int i = 0; i < loops.length; i++) {
            loops[i] = new Thread(Workload::eventLoop, "event-loop-" + (i + 1));
            loops[i].start();
        }
        final AtomicInteger n = new AtomicInteger();
        final ThreadFactory named = r -> new Thread(r, "worker-" + n.incrementAndGet());
        final ExecutorService pool = Executors.newCachedThreadPool(named);
        System.out.println("ready");
        System.out.flush();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            // A burst: more tasks than idle threads, so the pool starts a thread for most of them.
            for (int i = 0; i < 40; i++) {
                pool.execute(() -> spin(20));
            }
            Thread.sleep(2_000);
        }
        running = false;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        housekeeper.join();
        for (final Thread loop : loops) {
            loop.join();
        }
        System.exit(0);
    }

    private static void housekeeper() {
        while (running) {
            synchronized (REGISTRY) {
                spin(100);
            }
            sleep(150);
        }
    }

    private static void eventLoop() {
        long served = 0;
        while (running) {
            synchronized (REGISTRY) {
                sink += new byte[4096].length;
            }
            if (++served % 500 == 0) {
                try {
                    throw new IllegalStateException("request " + served + " rejected");
                } catch (final IllegalStateException e) {
                    sink += e.getMessage().length();
                }
            }
            spin(1);
        }
    }

    private static void spin(final long millis) {
        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        long acc = 1;
        while (System.nanoTime() < until) {
            acc = acc * 31 + 7;
        }
        sink += acc;
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Workload() {
    }
}
