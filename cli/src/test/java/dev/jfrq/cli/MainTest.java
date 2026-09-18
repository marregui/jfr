package dev.jfrq.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs every command against one small recording made once for the class: a named loop
 * thread that idles, sleeps, burns CPU and blocks on a monitor, plus an allocating thread.
 */
class MainTest {

    @TempDir
    static Path dir;
    static Path recording;

    /** A loop idle in a Java method so the sampler sees its idle point. */
    static final class Loop {
        static void idle(long millis) {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
    }

    @BeforeAll
    static void record() throws Exception {
        recording = dir.resolve("cli.jfr");
        Object lock = new Object();
        try (Recording r = new Recording()) {
            r.enable("jdk.ActiveSetting");
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ThreadPark").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ObjectAllocationSample").with("throttle", "2000/s").withStackTrace();
            r.setDestination(recording);
            r.start();

            Thread allocator = new Thread(() -> {
                byte[][] keep = new byte[32][];
                for (int i = 0; i < 3_000; i++) {
                    keep[i % keep.length] = new byte[32 * 1024];
                }
            }, "alloc-cli");
            allocator.start();

            Thread loop = new Thread(() -> {
                try {
                    Loop.idle(300);
                    Thread.sleep(150);
                    Loop.idle(300);
                    CountDownLatch held = new CountDownLatch(1);
                    Thread holder = new Thread(() -> {
                        synchronized (lock) {
                            held.countDown();
                            sleep(150);
                        }
                        sleep(150);
                    }, "holder-cli");
                    holder.start();
                    held.await();
                    Thread.sleep(20);
                    synchronized (lock) {
                        lock.hashCode();
                    }
                    holder.join();
                    Loop.idle(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "loop-cli");
            loop.start();
            loop.join();
            allocator.join();
            r.stop();
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Run(int status, String out, String err) {
    }

    static Run run(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = new Main(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(argv);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpAndVersion() {
        assertEquals(0, run().status());
        assertTrue(run().out().contains("usage: jfrq"));
        assertTrue(run("--help").out().contains("stalls"));
        assertTrue(run("-h").out().contains("locks"));
        assertTrue(run("help").out().contains("alloc"));
        assertTrue(run("info", "--help").out().contains("usage: jfrq"));
        assertEquals("jfrq " + Main.VERSION + "\n", run("--version").out());
    }

    @Test
    void usageErrorsExitWithTwo() {
        Run unknown = run("frobnicate", "x.jfr");
        assertEquals(2, unknown.status());
        assertTrue(unknown.err().contains("unknown command 'frobnicate'"));
        assertEquals(2, run("info").status());
        assertEquals(2, run("info", recording.toString(), "--bogus").status());
        assertEquals(2, run("stalls", recording.toString()).status());
        assertEquals(2, run("stalls", recording.toString(), "--thread", " ").status());
        assertEquals(2, run("stalls", recording.toString(), "--thread", "x", "--gap", "never").status());
        assertEquals(2, run("stalls", recording.toString(), "--thread", "x", "--idle", "(").status());
        assertEquals(2, run("alloc", recording.toString(), "--top", "-1").status());
        assertEquals(2, run("locks", recording.toString(), "--min", "abc").status());
    }

    @Test
    void missingRecordingExitsWithOne() {
        Run r = run("info", dir.resolve("missing.jfr").toString());
        assertEquals(1, r.status());
        assertTrue(r.err().contains("no such file"), r.err());
        assertEquals(1, run("alloc", recording.toString(), "--baseline", "nope.jfr").status());
    }

    @Test
    void timingGoesToStderr() {
        Run r = run("info", recording.toString(), "--timing");
        assertEquals(0, r.status(), r.err());
        assertTrue(r.err().contains("timing: read"), r.err());
        assertTrue(r.err().contains("timing: render"), r.err());
        assertFalse(r.out().contains("timing:"));
    }

    @Test
    void info() {
        Run r = run("info", recording.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().startsWith("Recording  cli.jfr"));
        assertTrue(r.out().contains("Sampling   ExecutionSample 10.0 ms"));
        assertTrue(r.out().contains("jdk.ThreadSleep"));
        assertTrue(r.out().contains("Event type"));
    }

    @Test
    void alloc() throws Exception {
        Path html = dir.resolve("alloc.html");
        Run r = run("alloc", recording.toString(), "--top", "3", "--sites", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("BY THREAD"));
        assertTrue(r.out().contains("alloc-cli"));
        assertTrue(r.out().contains("BY CLASS"));
        assertTrue(r.out().contains("byte[]"));
        assertTrue(r.out().contains("BY SITE"));
        assertTrue(r.err().contains("HTML report written"));
        assertTrue(Files.readString(html).contains("<title>jfrq alloc"));

        Run plain = run("alloc", recording.toString());
        assertFalse(plain.out().contains("BY SITE"));
    }

    @Test
    void allocDiff() throws Exception {
        Path html = dir.resolve("diff.html");
        Run r = run("alloc", recording.toString(), "--baseline", recording.toString(), "--sites", "--html",
                html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("Baseline   cli.jfr"));
        assertTrue(r.out().contains("Change     0 B/s (+0%)"), r.out());
        assertTrue(r.out().contains("BY SITE"));
        assertTrue(Files.readString(html).contains("<title>jfrq alloc diff"));
    }

    @Test
    void locks() throws Exception {
        Path html = dir.resolve("locks.html");
        Run r = run("locks", recording.toString(), "--top", "5", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("LOCKS BY TOTAL WAIT"), r.out());
        assertTrue(r.out().contains("java.lang.Object@"), r.out());
        assertTrue(r.out().contains("loop-cli"), r.out());
        assertTrue(r.out().contains("held by holder-cli"), r.out());
        assertTrue(r.out().contains("LONGEST WAITS"));
        assertTrue(Files.readString(html).contains("Locks by total wait"));

        Run filtered = run("locks", recording.toString(), "--thread", "nobody-*", "--min", "1ms");
        assertEquals(0, filtered.status());
        assertTrue(filtered.out().contains("No contended monitor enters or parks"));
    }

    @Test
    void stalls() throws Exception {
        Path html = dir.resolve("stalls.html");
        Run r = run("stalls", recording.toString(), "--thread", "loop-*", "--gap", "50ms",
                "--idle", ".*MainTest\\$Loop\\.idle", "--top", "10", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("Threads    1 matched: loop-cli"), r.out());
        assertTrue(r.out().contains("SLEEP"), r.out());
        assertTrue(r.out().contains("BLOCKED_MONITOR"), r.out());
        assertTrue(r.out().contains("held by holder-cli"), r.out());
        assertTrue(r.out().contains("BY VERDICT"));
        assertTrue(r.out().contains("PER THREAD"));
        assertTrue(Files.readString(html).contains("<svg"));

        Run none = run("stalls", recording.toString(), "--thread", "nobody");
        assertEquals(0, none.status());
        assertTrue(none.out().contains("No thread matched"));

        Run defaults = run("stalls", recording.toString(), "--thread", "loop-cli");
        assertEquals(0, defaults.status());
        assertTrue(defaults.out().contains("Gap        50.0 ms"));
    }
}
