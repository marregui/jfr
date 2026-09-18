package dev.jfrq.core.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import jdk.jfr.Recording;

/**
 * Makes real recordings in-process for the tests that exercise the JFR path. Each fixture
 * produces one pathology on a thread with a known name so assertions can be precise.
 */
public final class JfrFixtures {

    private JfrFixtures() {
    }

    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    /** Records {@code body} with the given settings into a fresh file under {@code dir}. */
    public static Path record(Path dir, String name, Consumer<Recording> settings, Body body) throws Exception {
        Path file = Files.createTempFile(dir, name, ".jfr");
        try (Recording r = new Recording()) {
            // Without this event the file does not say which thresholds and periods were active.
            r.enable("jdk.ActiveSetting");
            settings.accept(r);
            r.setDestination(file);
            r.start();
            body.run();
            r.stop();
        }
        return file;
    }

    /** Runs {@code body} on a thread with the given name and waits for it. */
    public static void onThread(String name, Body body) throws Exception {
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, name);
        t.start();
        t.join();
        if (failure[0] != null) {
            throw new AssertionError("thread " + name + " failed", failure[0]);
        }
    }

    /**
     * Makes {@code waiter} block on {@code lock} for about {@code holdMillis} while
     * {@code holder} holds it; returns when both are done.
     */
    public static void contend(Object lock, String holder, String waiter, long holdMillis) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        Thread h = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                sleep(holdMillis);
            }
            // Stay alive a moment: JFR resolves the previous owner's identity lazily, and a
            // thread that exits the instant it releases the lock can be recorded as unknown.
            sleep(150);
        }, holder);
        Thread w = new Thread(() -> {
            await(held);
            sleep(20); // let the holder settle inside the critical section
            synchronized (lock) {
                lock.hashCode();
            }
        }, waiter);
        h.start();
        w.start();
        h.join();
        w.join();
    }

    /** Burns CPU on the calling thread for about {@code millis}; sampled as Java execution. */
    public static long burn(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long acc = 1;
        do {
            for (int i = 0; i < 5_000; i++) {
                acc = acc * 6364136223846793005L + 1442695040888963407L + i;
            }
        } while (System.nanoTime() < deadline);
        return acc;
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    public static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // temp files; best effort
        }
    }
}
