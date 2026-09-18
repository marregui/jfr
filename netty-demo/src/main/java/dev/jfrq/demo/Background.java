// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.demo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * The background threads that create the lock and allocation pathologies. Each is a
 * daemon that runs until {@link #stop()}.
 */
final class Background {

    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    /** How often the housekeeper compacts the registry (holding its lock for {@link SessionRegistry#COMPACT_HOLD_MILLIS}). */
    static final long HOUSEKEEPER_PERIOD_MILLIS = 500;
    /** How often the flusher checkpoints persistence (holding its lock for {@link Persistence#CHECKPOINT_HOLD_MILLIS}). */
    static final long FLUSHER_PERIOD_MILLIS = 400;
    /** Bulk allocators started, and the live window each keeps so collections have real work. */
    static final int ALLOCATORS = 2;
    static final long ALLOCATOR_RETAINED_BYTES = 48L * 1024 * 1024;

    /** Every {@link #HOUSEKEEPER_PERIOD_MILLIS}, compacts the registry, holding its lock throughout. */
    void housekeeper(SessionRegistry registry) {
        start("housekeeper", () -> {
            while (running) {
                //noinspection BusyWait
                Thread.sleep(HOUSEKEEPER_PERIOD_MILLIS);
                registry.compact();
            }
        });
    }

    /** Every {@link #FLUSHER_PERIOD_MILLIS}, takes the persistence lock for a checkpoint. */
    void flusher(Persistence persistence) {
        start("persistence-flusher", () -> {
            while (running) {
                //noinspection BusyWait
                Thread.sleep(FLUSHER_PERIOD_MILLIS);
                persistence.checkpoint();
            }
        });
    }

    /**
     * Allocates byte arrays in bulk, keeping a rolling window alive so a share of them
     * survives young collections and the collector has real work to do.
     */
    void allocators() {
        for (int i = 1; i <= ALLOCATORS; i++) {
            start("bulk-allocator-" + i, () -> {
                ArrayDeque<byte[]> window = new ArrayDeque<>();
                List<Long> boxed = new ArrayList<>();
                long retained = 0;
                while (running) {
                    for (int n = 0; n < 200; n++) {
                        int size = ThreadLocalRandom.current().nextInt(1024, 64 * 1024);
                        byte[] chunk = new byte[size];
                        chunk[0] = (byte) n;
                        window.addLast(chunk);
                        retained += size;
                        byte[] oldest;
                        while (retained > ALLOCATOR_RETAINED_BYTES && (oldest = window.pollFirst()) != null) {
                            retained -= oldest.length;
                        }
                        boxed.add(ThreadLocalRandom.current().nextLong());
                        if (boxed.size() > 100_000) {
                            boxed = new ArrayList<>();
                        }
                    }
                    LockSupport.parkNanos(200_000);
                }
            });
        }
    }

    private void start(String name, Task task) {
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        }, name);
        t.setDaemon(true);
        t.start();
        threads.add(t);
    }

    void stop() {
        running = false;
        for (Thread t : threads) {
            t.interrupt();
        }
    }

    @FunctionalInterface
    private interface Task {
        void run() throws InterruptedException;
    }
}
