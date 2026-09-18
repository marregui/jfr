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

    /** Every {@code periodMillis}, compacts the registry holding its lock for {@code holdMillis}. */
    void housekeeper(SessionRegistry registry, long periodMillis, long holdMillis) {
        start("housekeeper", () -> {
            while (running) {
                Thread.sleep(periodMillis);
                registry.compact(holdMillis);
            }
        });
    }

    /** Every {@code periodMillis}, takes the persistence lock for {@code holdMillis}. */
    void flusher(Persistence persistence, long periodMillis, long holdMillis) {
        start("persistence-flusher", () -> {
            while (running) {
                Thread.sleep(periodMillis);
                persistence.checkpoint(holdMillis);
            }
        });
    }

    /**
     * Allocates byte arrays in bulk, keeping a rolling window alive so a share of them
     * survives young collections and the collector has real work to do.
     */
    void allocators(int count, long retainedBytes) {
        for (int i = 1; i <= count; i++) {
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
                        while (retained > retainedBytes) {
                            retained -= window.pollFirst().length;
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
