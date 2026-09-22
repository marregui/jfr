// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

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
        static void idle() {
            final long deadline = System.nanoTime() + 300 * 1_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
    }

    @BeforeAll
    static void record() throws Exception {
        recording = dir.resolve("cli.jfr");
        final Object lock = new Object();
        try (final Recording r = new Recording()) {
            r.enable("jdk.ActiveSetting");
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ThreadPark").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ObjectAllocationSample").with("throttle", "2000/s").withStackTrace();
            r.setDestination(recording);
            r.start();

            final Thread allocator = new Thread(() -> {
                final byte[][] keep = new byte[32][];
                for (int i = 0; i < 3_000; i++) {
                    keep[i % keep.length] = new byte[32 * 1024];
                }
                if (keep[0].length == 0) {
                    throw new IllegalStateException();
                }
            }, "alloc-cli");
            allocator.start();

            final Thread loop = new Thread(() -> {
                try {
                    Loop.idle();
                    Thread.sleep(150);
                    Loop.idle();
                    final CountDownLatch held = new CountDownLatch(1);
                    final Thread holder = new Thread(() -> {
                        synchronized (lock) {
                            held.countDown();
                            sleep();
                        }
                        sleep();
                    }, "holder-cli");
                    holder.start();
                    held.await();
                    Thread.sleep(20);
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    holder.join();
                    Loop.idle();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "loop-cli");
            loop.start();
            loop.join();
            allocator.join();
            r.stop();
        }
    }

    static void sleep() {
        try {
            Thread.sleep(150);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Run(int status, String out, String err) {
    }

    static Run run(final String... argv) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int status = new Main(new PrintStream(out, true, StandardCharsets.UTF_8),
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
        assertEquals("jfrq " + Main.VERSION + "\n", run("info", recording.toString(), "--version").out());
    }

    @Test
    void usageErrorsExitWithTwo() {
        final Run unknown = run("frobnicate", "x.jfr");
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
        assertEquals(2, run("locks", recording.toString(), "--thread", "").status());
        assertEquals(2, run("alloc", recording.toString(), "--sites", "--app", " ,").status());
        // Each command's grouping option belongs to it alone.
        assertEquals(2, run("locks", recording.toString(), "--app", "dev.jfrq").status());
        assertEquals(2, run("alloc", recording.toString(), "--by-site").status());
        // Options belong to their command, and durations need a unit.
        final Run misplaced = run("locks", recording.toString(), "--gap", "1s");
        assertEquals(2, misplaced.status());
        assertTrue(misplaced.err().contains("unknown option --gap"), misplaced.err());
        assertEquals(2, run("info", recording.toString(), "--top", "3").status());
        assertEquals(2, run("stalls", recording.toString(), "--thread", "x", "--baseline", "y").status());
        final Run bare = run("stalls", recording.toString(), "--thread", "x", "--gap", "50");
        assertEquals(2, bare.status());
        assertTrue(bare.err().contains("needs a unit"), bare.err());
        assertEquals(2, run("info", recording.toString(), "extra.jfr").status());
        final Run directory = run("info", dir.toString());
        assertEquals(2, directory.status());
        assertTrue(directory.err().contains("is a directory"), directory.err());
    }

    @Test
    void damagedRecordingsExitWithOneAndSayWhy() throws Exception {
        final byte[] bytes = Files.readAllBytes(recording);
        final Path cut = dir.resolve("cut.jfr");
        Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        final Run r = run("stalls", cut.toString(), "--thread", "loop-*");
        assertEquals(1, r.status(), r.out());
        assertTrue(r.err().contains("truncated"), r.err());
        assertFalse(r.out().contains("No thread matched"), r.out());

        final Path junk = dir.resolve("junk.jfr");
        Files.writeString(junk, "definitely not a recording, but long enough to have a header");
        final Run j = run("info", junk.toString());
        assertEquals(1, j.status());
        assertTrue(j.err().contains("not a Flight Recorder file"), j.err());

        java.nio.ByteBuffer.wrap(bytes).putLong(8, 0);
        final Path live = dir.resolve("live.jfr");
        Files.write(live, bytes);
        final Run l = run("alloc", live.toString());
        assertEquals(1, l.status());
        assertTrue(l.err().contains("still being written"), l.err());
    }

    @Test
    void unwritableHtmlTargetIsReportedAsSuch() {
        final Run r = run("info", recording.toString());
        assertEquals(0, r.status());
        final Run bad = run("locks", recording.toString(), "--html", dir.resolve("no-such-dir").resolve("x.html").toString());
        assertEquals(1, bad.status());
        assertTrue(bad.err().contains("cannot write HTML report"), bad.err());
        assertFalse(bad.err().contains("cannot read recording"), bad.err());
    }

    @Test
    void missingRecordingExitsWithOne() {
        final Run r = run("info", dir.resolve("missing.jfr").toString());
        assertEquals(1, r.status());
        assertTrue(r.err().contains("no such file"), r.err());
        assertEquals(1, run("alloc", recording.toString(), "--baseline", "nope.jfr").status());
    }

    @Test
    void timingGoesToStderr() {
        final Run r = run("stalls", recording.toString(), "--thread", "*", "--timing");
        assertEquals(0, r.status(), r.err());
        // Parsing and analysing are separate numbers: the analysis runs inside the read,
        // in the sink's finish(), and one number could not say which of the two was slow.
        assertTrue(r.err().contains("timing: parse"), r.err());
        assertTrue(r.err().contains("timing: analyse"), r.err());
        assertTrue(r.err().contains("timing: render"), r.err());
        assertFalse(r.out().contains("timing:"));
    }

    @Test
    void info() throws Exception {
        final Path html = dir.resolve("info.html");
        final Run withHtml = run("info", recording.toString(), "--html", html.toString());
        assertEquals(0, withHtml.status(), withHtml.err());
        assertTrue(Files.readString(html).contains("<title>jfrq info"));
        assertTrue(Files.readString(html).contains("jdk.ThreadSleep"));
        final Run r = run("info", recording.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().startsWith("Recording  cli.jfr"));
        assertTrue(r.out().contains("Chunks     1"), r.out());
        assertTrue(r.out().contains("Sampling   ExecutionSample 10.0 ms"));
        assertTrue(r.out().contains("jdk.ThreadSleep"));
        assertTrue(r.out().contains("Event type"));
    }

    @Test
    void alloc() throws Exception {
        final Path html = dir.resolve("alloc.html");
        final Run r = run("alloc", recording.toString(), "--top", "3", "--sites", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("BY THREAD"));
        assertTrue(r.out().contains("alloc-cli"));
        assertTrue(r.out().contains("BY CLASS"));
        assertTrue(r.out().contains("byte[]"));
        assertTrue(r.out().contains("BY SITE"));
        assertTrue(r.err().contains("HTML report written"));
        assertTrue(Files.readString(html).contains("<title>jfrq alloc"));

        final Run plain = run("alloc", recording.toString());
        assertFalse(plain.out().contains("BY SITE"));

        // --app attributes each stack to the test's own frames instead of to the JDK method
        // that allocated, so the rows are named after this file rather than after the JDK.
        final Run app = run("alloc", recording.toString(), "--sites", "--app", "dev.jfrq");
        assertEquals(0, app.status(), app.err());
        assertTrue(app.out().contains("dev.jfrq."), app.out());
    }

    @Test
    void locksBySite() {
        final Run r = run("locks", recording.toString(), "--by-site");
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("LOCK SITES BY TOTAL WAIT") || r.out().contains("No contended"), r.out());
        assertFalse(r.out().contains("\nLOCKS BY TOTAL WAIT\n"), r.out());
    }

    @Test
    void allocDiff() throws Exception {
        final Path html = dir.resolve("diff.html");
        final Run r = run("alloc", recording.toString(), "--baseline", recording.toString(), "--sites", "--html",
                html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("Baseline   cli.jfr"));
        // A recording against itself: every site is unchanged, and both sides carry the same
        // samples, which is what says the row is a real comparison and not one side only.
        assertTrue(r.out().contains("BY SITE ("), r.out());
        // --app groups the diff the same way it groups a single report.
        final Run byApp = run("alloc", recording.toString(), "--baseline", recording.toString(), "--app", "dev.jfrq");
        assertEquals(0, byApp.status(), byApp.err());
        assertTrue(byApp.out().contains("dev.jfrq."), byApp.out());
        assertFalse(r.out().contains("WARNING"), r.out());

        // A damaged baseline is said so on the diff, text and HTML.
        final byte[] bytes = Files.readAllBytes(recording);
        final Path cut = dir.resolve("cut-baseline.jfr");
        Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length + 20));
        final Path cutHtml = dir.resolve("cut-diff.html");
        final Run damaged = run("alloc", recording.toString(), "--baseline", cut.toString(), "--html", cutHtml.toString());
        assertEquals(0, damaged.status(), damaged.err());
        assertTrue(damaged.out().contains("WARNING    baseline: the file is truncated"), damaged.out());
        assertTrue(Files.readString(cutHtml).contains("baseline: the file is truncated"));
        assertTrue(r.out().contains("Change     0 B/s (+0%)"), r.out());
        assertTrue(r.out().contains("BY SITE"));
        assertTrue(Files.readString(html).contains("<title>jfrq alloc diff"));
    }

    @Test
    void locks() throws Exception {
        final Path html = dir.resolve("locks.html");
        final Run r = run("locks", recording.toString(), "--top", "5", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("LOCKS BY TOTAL WAIT"), r.out());
        assertTrue(r.out().contains("java.lang.Object@"), r.out());
        assertTrue(r.out().contains("loop-cli"), r.out());
        assertTrue(r.out().contains("held by holder-cli"), r.out());
        assertTrue(r.out().contains("LONGEST WAITS"));
        assertTrue(Files.readString(html).contains("Locks by total wait"));

        final Run filtered = run("locks", recording.toString(), "--thread", "nobody-*", "--min", "1ms");
        assertEquals(0, filtered.status());
        assertTrue(filtered.out().contains("No contended monitor enters or parks"));
        // The documented default is accepted explicitly.
        final Run zero = run("locks", recording.toString(), "--min", "0");
        assertEquals(0, zero.status(), zero.err());
        assertTrue(zero.out().contains("held by holder-cli"), zero.out());
    }

    @Test
    void stalls() throws Exception {
        final Path html = dir.resolve("stalls.html");
        final Run r = run("stalls", recording.toString(), "--thread", "loop-*", "--gap", "50ms",
                "--idle", ".*MainTest\\$Loop\\.idle", "--top", "10", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("Threads    1 matched: loop-cli"), r.out());
        assertTrue(r.out().contains("SLEEP"), r.out());
        assertTrue(r.out().contains("BLOCKED_MONITOR"), r.out());
        // Event-based stalls carry no evidence tag; the less exact kinds do.
        assertFalse(r.out().contains("Thread.sleep ["), r.out());
        final dev.jfrq.core.model.ThreadRef t = new dev.jfrq.core.model.ThreadRef(1, "t");
        final dev.jfrq.core.model.Interval i = new dev.jfrq.core.model.Interval(0, 1);
        assertEquals("", Text.evidence(new dev.jfrq.core.stalls.Stall(t, i, dev.jfrq.core.stalls.Stall.Verdict.SLEEP, "",
                dev.jfrq.core.model.Stack.EMPTY, dev.jfrq.core.stalls.Stall.Evidence.EVENT, 0)));
        assertEquals(" [samples]", Text.evidence(new dev.jfrq.core.stalls.Stall(t, i, dev.jfrq.core.stalls.Stall.Verdict.BUSY,
                "", dev.jfrq.core.model.Stack.EMPTY, dev.jfrq.core.stalls.Stall.Evidence.SAMPLES, 3)));
        assertEquals(" [silence]", Text.evidence(new dev.jfrq.core.stalls.Stall(t, i,
                dev.jfrq.core.stalls.Stall.Verdict.UNEXPLAINED, "", dev.jfrq.core.model.Stack.EMPTY,
                dev.jfrq.core.stalls.Stall.Evidence.SILENCE, 0)));
        assertTrue(r.out().contains("held by holder-cli"), r.out());
        assertTrue(r.out().contains("BY VERDICT"));
        assertTrue(r.out().contains("PER THREAD"));
        assertTrue(Files.readString(html).contains("<svg"));

        final Run none = run("stalls", recording.toString(), "--thread", "nobody");
        assertEquals(0, none.status());
        assertTrue(none.out().contains("No thread matched"));

        final Run defaults = run("stalls", recording.toString(), "--thread", "loop-cli");
        assertEquals(0, defaults.status());
        assertTrue(defaults.out().contains("Gap        50.0 ms"));
    }
}
