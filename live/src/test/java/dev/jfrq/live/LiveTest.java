// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import dev.jfrq.cli.Args;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loop against the JVM the tests run in: the test task sets
 * {@code -Djdk.attach.allowAttachSelf=true}, and everything else is what a user does to
 * another process. The recorder is one per JVM, so the loop test names its recording and
 * addresses it with {@code --recording} in case another test's is running.
 */
class LiveTest {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final String PID = Long.toString(ProcessHandle.current().pid());

    @TempDir
    Path dir;

    record Run(int status, String out, String err) {
    }

    static Run run(final String... argv) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int status = new Live(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(argv);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpAndVersion() {
        assertEquals(0, run().status());
        assertTrue(run().out().contains("usage: jfrq-live"));
        assertTrue(run("--help").out().contains("delta"));
        assertTrue(run("-h").out().contains("again"));
        assertTrue(run("help").out().contains("bound"));
        assertTrue(run(PID, "status", "--help").out().contains("usage: jfrq-live"));
        assertEquals("jfrq-live 0.1.0\n", run("--version").out());
        assertEquals("jfrq-live 0.1.0\n", run(PID, "status", "--version").out());
    }

    @Test
    void usageErrorsExitWithTwoBeforeAttaching() {
        final Run pid = run("not-a-pid", "status");
        assertEquals(2, pid.status());
        assertTrue(pid.err().contains("pid"));
        assertEquals(2, run(PID).status());
        final Run unknown = run(PID, "frobnicate");
        assertEquals(2, unknown.status());
        assertTrue(unknown.err().contains("unknown command 'frobnicate'"));
        assertEquals(2, run(PID, "status", "--out", "x.jfr").status());
        assertEquals(2, run(PID, "full", "--max-age", "1m").status());
        assertEquals(2, run(PID, "full", "extra").status());
        assertEquals(2, run(PID, "status", "--", "info").status());
        assertEquals(2, run(PID, "full", "--").status());
        assertEquals(2, run(PID, "bound").status());
        assertEquals(2, run(PID, "start", "--max-size", "lots").status());
    }

    @Test
    void attachFailureExitsWithOne() {
        // No process has this pid: ProcessHandle.of is the check the JDK's attach makes first.
        final Run r = run("999999999", "status");
        assertEquals(1, r.status());
        assertTrue(r.err().contains("cannot attach to 999999999"), r.err());
    }

    @Test
    void parseSize() {
        assertEquals(0, Live.parseSize("0"));
        assertEquals(200_000_000L, Live.parseSize("200MB"));
        assertEquals(200_000_000L, Live.parseSize("200m"));
        assertEquals(1_000_000_000L, Live.parseSize(" 1 GB "));
        assertEquals(512_000L, Live.parseSize("512k"));
        assertEquals(3_000_000_000_000L, Live.parseSize("3T"));
        assertEquals(7, Live.parseSize("7B"));
        assertThrows(Args.UsageException.class, () -> Live.parseSize("lots"));
        assertThrows(Args.UsageException.class, () -> Live.parseSize("1.5GB"));
        assertThrows(Args.UsageException.class, () -> Live.parseSize("99999999999999999999"));
        assertThrows(Args.UsageException.class, () -> Live.parseSize("9999999999T"));
    }

    @Test
    void cursorIsPerJvmIncarnation() throws Exception {
        final Cursor fresh = Cursor.load(dir, "1", 100);
        assertNull(fresh.next());
        assertNull(fresh.lastWindow());
        final Instant stop = Instant.parse("2026-09-21T14:05:12.004Z");
        fresh.advance(new Window(Instant.parse("2026-09-21T14:03:07.121Z"), null), stop);

        final Cursor same = Cursor.load(dir, "1", 100);
        assertEquals(stop.plusMillis(1), same.next());
        assertEquals(new Window(Instant.parse("2026-09-21T14:03:07.121Z"), stop), same.lastWindow());

        final Cursor restarted = Cursor.load(dir, "1", 101);
        assertNull(restarted.next());
        assertNull(restarted.lastWindow());

        fresh.advance(Window.EVERYTHING, stop.plusSeconds(60));
        final Cursor afterFull = Cursor.load(dir, "1", 100);
        assertEquals(new Window(null, stop.plusSeconds(60)), afterFull.lastWindow());

        Files.writeString(dir.resolve("2.properties"), "jvm=100\ncursor=yesterday\n");
        assertThrows(java.io.IOException.class, () -> Cursor.load(dir, "2", 100));
    }

    @Test
    void theLoop() throws Exception {
        final String name = "live-test-" + System.nanoTime();
        final String[] pick = {"--recording", name, "--state", dir.toString()};

        final Run status = run(PID, "status", "--state", dir.toString());
        assertEquals(0, status.status(), status.err());
        assertTrue(status.out().contains("JVM        " + PID + "@"), status.out());
        assertTrue(status.out().contains("Cursor     none"), status.out());

        final Run missing = run(concat(new String[] {PID, "full"}, pick));
        assertEquals(1, missing.status());
        assertTrue(missing.err().contains("has no recording '" + name + "'"), missing.err());

        final Run start = run(PID, "start", "--name", name, "--max-age", "5m", "--state", dir.toString());
        assertEquals(0, start.status(), start.err());
        assertTrue(start.out().contains(name + " "), start.out());
        assertTrue(start.out().contains("RUNNING"), start.out());
        assertTrue(start.out().contains("max-age 5m00s"), start.out());
        assertFalse(start.out().contains("WARNING"), start.out());

        final Run early = run(concat(new String[] {PID, "delta"}, pick));
        assertEquals(2, early.status());
        assertTrue(early.err().contains("run 'full' first"), early.err());
        assertEquals(2, run(concat(new String[] {PID, "again"}, pick)).status());

        work();
        final Path t0 = dir.resolve("t0.jfr");
        final Run full = run(concat(new String[] {PID, "full", "--out", t0.toString()}, pick, "--", "info"));
        assertEquals(0, full.status(), full.err());
        assertTrue(Files.size(t0) > 0);
        assertTrue(full.out().contains("Dumped     " + t0), full.out());
        assertTrue(full.out().contains("Window     the start .. now (everything the recording kept)"), full.out());
        assertTrue(full.out().contains("Cursor     next delta from"), full.out());
        assertTrue(full.out().contains("Recording  t0.jfr"), full.out());
        assertFalse(full.out().contains("WARNING"), full.out());

        final long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        final Cursor cursor = Cursor.load(dir, PID, jvmStart);
        assertNotNull(cursor.next());
        final RecordingInfo i0 = JfrReader.read(t0);

        work();
        final Path t1 = dir.resolve("t1.jfr");
        final Run delta = run(concat(new String[] {PID, "delta", "--out", t1.toString()}, pick, "--", "info"));
        assertEquals(0, delta.status(), delta.err());
        assertTrue(delta.out().contains("Window     " + TIME.format(cursor.next()) + " .. now (since the previous dump)"),
                delta.out());
        assertFalse(delta.out().contains("Note"), delta.out());
        assertFalse(delta.out().contains("WARNING"), delta.out());
        final RecordingInfo i1 = JfrReader.read(t1);
        // The delta starts where the previous dump stopped: the chunk the full dump sealed is not in it.
        assertTrue(i1.startNanos() >= i0.endNanos() - 5_000_000L,
                "delta starts " + i1.startNanos() + ", full ended " + i0.endNanos());
        assertTrue(i1.endNanos() > i0.endNanos());

        final Instant afterDelta = Cursor.load(dir, PID, jvmStart).next();
        final Path t1b = dir.resolve("t1b.jfr");
        final Run again = run(concat(new String[] {PID, "again", "--out", t1b.toString()}, pick));
        assertEquals(0, again.status(), again.err());
        assertTrue(again.out().contains("(the previous window again)"), again.out());
        final RecordingInfo i1b = JfrReader.read(t1b);
        assertEquals(i1.startNanos(), i1b.startNanos());
        assertEquals(i1.endNanos(), i1b.endNanos());
        assertEquals(afterDelta, Cursor.load(dir, PID, jvmStart).next(), "again must not move the cursor");

        final Run bound = run(concat(new String[] {PID, "bound", "--max-age", "0", "--max-size", "64MB"}, pick));
        assertEquals(0, bound.status(), bound.err());
        assertTrue(bound.out().contains("max-size 64.0 MB"), bound.out());
        assertFalse(bound.out().contains("max-age"), bound.out());

        final Run after = run(PID, "status", "--state", dir.toString());
        assertTrue(after.out().contains("Cursor     next delta from"), after.out());
        assertTrue(after.out().contains(name), after.out());

        final Run stop = run(concat(new String[] {PID, "stop"}, pick));
        assertEquals(0, stop.status(), stop.err());
        assertTrue(stop.out().contains("Stopped"), stop.out());
        final Run gone = run(concat(new String[] {PID, "stop"}, pick));
        assertEquals(1, gone.status());
    }

    @Test
    void startWithAJfcFileAndAnUnboundedWarning() throws Exception {
        final String name = "live-jfc-" + System.nanoTime();
        final Path jfc = dir.resolve("tiny.jfc");
        Files.writeString(jfc, """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration version="2.0" label="tiny">
                  <event name="jdk.ThreadSleep">
                    <setting name="enabled">true</setting>
                    <setting name="threshold">0 ms</setting>
                  </event>
                </configuration>
                """);
        final Run start = run(PID, "start", "--name", name, "--settings", jfc.toString(), "--state", dir.toString());
        assertEquals(0, start.status(), start.err());
        assertTrue(start.out().contains("WARNING    the recording has no bound"), start.out());
        assertTrue(start.out().contains("no bound"), start.out());

        final Run full = run(PID, "full", "--recording", name, "--state", dir.toString(), "--out", dir.resolve("f.jfr").toString());
        assertEquals(0, full.status(), full.err());
        assertTrue(full.out().contains("WARNING    the recording has no bound"), full.out());

        final Run unknownProfile = run(PID, "start", "--name", name + "-x", "--settings", "no-such-profile");
        assertEquals(1, unknownProfile.status());
        assertTrue(unknownProfile.err().contains("the JVM refused"), unknownProfile.err());

        assertEquals(0, run(PID, "stop", "--recording", name).status());
    }

    @Test
    void severalRunningRecordingsNeedAChoice() throws Exception {
        final String a = "live-a-" + System.nanoTime();
        final String b = "live-b-" + System.nanoTime();
        assertEquals(0, run(PID, "start", "--name", a, "--max-size", "1MB", "--state", dir.toString()).status());
        assertEquals(0, run(PID, "start", "--name", b, "--max-size", "1MB", "--state", dir.toString()).status());
        try {
            final Run ambiguous = run(PID, "full", "--state", dir.toString(), "--out", dir.resolve("x.jfr").toString());
            assertEquals(1, ambiguous.status());
            assertTrue(ambiguous.err().contains("running recordings; pick one with --recording"), ambiguous.err());
            assertTrue(ambiguous.err().contains(a) && ambiguous.err().contains(b), ambiguous.err());
        } finally {
            run(PID, "stop", "--recording", a);
            run(PID, "stop", "--recording", b);
        }
    }

    @Test
    void inMemoryRecordingsHaveNoChunksToDump() throws Exception {
        final String name = "live-mem-" + System.nanoTime();
        try (final jdk.jfr.Recording r = new jdk.jfr.Recording()) {
            r.setName(name);
            r.setToDisk(false);
            r.enable("jdk.ThreadSleep").withThreshold(java.time.Duration.ZERO);
            r.start();
            Thread.sleep(50);
            final Run full = run(PID, "full", "--recording", name, "--state", dir.toString(), "--out",
                    dir.resolve("mem.jfr").toString());
            assertEquals(1, full.status(), full.out());
            assertTrue(full.err().contains("holds no data in the window"), full.err());
            assertFalse(Files.exists(dir.resolve("mem.jfr")));
            r.stop();
        }
    }

    private static void work() throws InterruptedException {
        final byte[][] keep = new byte[16][];
        for (int i = 0; i < 400; i++) {
            keep[i % keep.length] = new byte[64 * 1024];
        }
        if (keep[0].length == 0) {
            throw new IllegalStateException();
        }
        Thread.sleep(250);
    }

    private static String[] concat(final String[] a, final String[] b, final String... c) {
        final String[] r = new String[a.length + b.length + c.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        System.arraycopy(c, 0, r, a.length + b.length, c.length);
        return r;
    }
}
