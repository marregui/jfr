// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.jfrq.core.alloc.AllocationCollector;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.locks.ContentionCollector;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.Stall.Verdict;
import dev.jfrq.core.stalls.StallCollector;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.util.Glob;
import jdk.jfr.consumer.RecordedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end through real JFR files: each test records a known pathology in-process and
 * checks that the collectors see it. Timings are generous so the assertions hold on a
 * loaded CI machine.
 */
class RecordingTest {

    @TempDir
    Path dir;

    @Test
    void readerCollectsInfoAndDispatchesToSinks() throws Exception {
        final Path file = JfrFixtures.record(dir, "info", r -> {
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20));
        }, () -> JfrFixtures.onThread("sleeper-thread", () -> JfrFixtures.sleep(60)));

        final List<String> seen = new ArrayList<>();
        final JfrReader.Sink sleeps = new JfrReader.Sink() {
            @Override
            public Set<String> eventTypes() {
                return Set.of("jdk.ThreadSleep");
            }

            @Override
            public void accept(final RecordedEvent event) {
                seen.add(Events.thread(event).name());
            }
        };
        final int[] all = new int[1];
        final JfrReader.Sink everything = new JfrReader.Sink() {
            @Override
            public Set<String> eventTypes() {
                return Set.of();
            }

            @Override
            public void accept(final RecordedEvent event) {
                all[0]++;
            }
        };
        final RecordingInfo info = JfrReader.read(file, sleeps, everything);

        assertTrue(seen.contains("sleeper-thread"), seen.toString());
        assertTrue(info.has("jdk.ThreadSleep"));
        assertEquals(info.eventCounts().values().stream().mapToLong(Long::longValue).sum(), all[0]);
        assertTrue(info.threads().stream().anyMatch(t -> t.name().equals("sleeper-thread")));
        assertTrue(info.duration().toMillis() >= 50, info.duration().toString());
        assertEquals(file, info.file());
        assertEquals(Duration.ofMillis(20), info.period("jdk.ExecutionSample").orElseThrow());
        assertEquals(Duration.ZERO, info.threshold("jdk.ThreadSleep").orElseThrow());
        assertTrue(info.enabled("jdk.ThreadSleep"));
        assertFalse(info.enabled("jdk.NoSuchEvent"));
        assertTrue(info.setting("jdk.ThreadSleep", "enabled").isPresent());
        assertTrue(info.threshold("jdk.NoSuchEvent").isEmpty());
        // Chunk-relative periods are not durations and must not throw.
        assertTrue(info.period("jdk.JVMInformation").isEmpty());
        assertEquals(dev.jfrq.core.coll.Nulls.LONG_NULL, info.periodNanos("jdk.JVMInformation"));
        assertEquals(20_000_000L, info.periodNanos("jdk.ExecutionSample"));
        assertEquals(0L, info.thresholdNanos("jdk.ThreadSleep"));
        assertEquals(dev.jfrq.core.coll.Nulls.LONG_NULL, info.thresholdNanos("jdk.NoSuchEvent"));
        assertEquals(0, info.count("jdk.NoSuchEvent"));
        assertNotNull(info.start());
        assertTrue(info.endNanos() > info.startNanos());
    }

    @Test
    void missingFileIsAnIoException() {
        assertThrows(IOException.class, () -> JfrReader.read(dir.resolve("nope.jfr")));
    }

    @Test
    void recordingWithoutActiveSettingsHasNoThresholds() throws Exception {
        final Path file = JfrFixtures.record(dir, "nosettings", r -> {
            r.disable("jdk.ActiveSetting");
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
        }, () -> JfrFixtures.sleep(5));
        final RecordingInfo info = JfrReader.read(file);
        assertFalse(info.hasSettings());
        assertTrue(info.threshold("jdk.ThreadSleep").isEmpty());
        assertFalse(info.enabled("jdk.ThreadSleep"));
        assertTrue(info.endNanos() >= info.startNanos());
    }

    @Test
    void contentionCollectorSeesTheMonitorWait() throws Exception {
        final Object lock = new Object();
        final Path file = JfrFixtures.record(dir, "locks", r -> {
            r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ThreadPark").withThreshold(Duration.ZERO);
            // The holder's sleep inside the lock is then an event of its own, which makes JFR write the
            // holder into the file's thread pool before it exits; without one, previousOwner is
            // occasionally left unresolved on a loaded machine.
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
        }, () -> JfrFixtures.contend(lock, "holder-thread", "waiter-thread",
                // The waiter reaches the monitor 20 ms in, so it waits nearly the whole hold.
                // Held this long because a scheduler that wakes it 82 ms late — seen once on a
                // loaded CI machine — must still leave the wait above the floor asserted below.
                400));

        final ContentionCollector collector = new ContentionCollector();
        JfrReader.read(file, collector);
        final ContentionReport report = collector.report();

        final Wait wait = report.waits().stream().filter(w -> w.waiter().name().equals("waiter-thread")).findFirst()
                .orElseThrow(() -> new AssertionError("no wait recorded: " + report.waits()));
        // JFR may omit previousOwner even for a real contended enter. That limits the diagnosis,
        // but must not turn a valid wait into a failed read; holder resolution itself is covered
        // deterministically by ContentionReportTest.
        assertTrue(wait.owner() == null || wait.owner().name().equals("holder-thread"), wait.toString());
        assertEquals("java.lang.Object", wait.lock().className());
        assertEquals(Wait.Kind.MONITOR_ENTER, wait.kind());
        assertTrue(wait.duration() >= 80_000_000L, "waited " + wait.duration());
        assertFalse(wait.stack().isEmpty());
        assertEquals(wait.lock(), report.locks(5).getFirst().lock());

        // Filters: by minimum duration and by waiter name.
        final ContentionCollector filtered = new ContentionCollector(10_000_000_000L, _ -> true);
        JfrReader.read(file, filtered);
        assertTrue(filtered.report().isEmpty());
        final ContentionCollector other = new ContentionCollector(0, Glob.of("nobody-*"));
        JfrReader.read(file, other);
        assertTrue(other.report().isEmpty());
        assertThrows(IllegalStateException.class, () -> new ContentionCollector().report());
    }

    @Test
    void allocationCollectorAttributesBytesToTheAllocatingThread() throws Exception {
        final Path file = JfrFixtures.record(dir, "alloc", r -> {
            r.enable("jdk.ObjectAllocationSample").with("throttle", "5000/s").withStackTrace();
            r.enable("jdk.ThreadAllocationStatistics").with("period", "everyChunk");
        }, () -> {
            // The calling thread is alive at both chunk boundaries, so its counter is in the file twice.
            final byte[][] keep = new byte[64][];
            for (int i = 0; i < 4_000; i++) {
                keep[i % keep.length] = new byte[64 * 1024];
            }
            if (keep[0].length == 0) {
                throw new IllegalStateException();
            }
            JfrFixtures.onThread("alloc-thread", () -> {
                for (int i = 0; i < 4_000; i++) {
                    keep[i % keep.length] = new byte[64 * 1024];
                }
            });
        });

        final AllocationCollector collector = new AllocationCollector();
        JfrReader.read(file, collector);
        final AllocationReport report = collector.report();

        assertEquals(AllocationCollector.SAMPLE, report.source());
        assertTrue(report.samples() > 0);
        final String self = Thread.currentThread().getName();
        final List<AllocationReport.Row<String>> top = report.threads(2);
        assertTrue(top.stream().anyMatch(r -> r.key().equals("alloc-thread")), top.toString());
        assertTrue(top.stream().anyMatch(r -> r.key().equals(self)), top.toString());
        assertEquals("[B", report.classes(1).getFirst().key());
        assertFalse(report.sitesByStack(1).getFirst().key().isEmpty());
        assertTrue(report.totalBytes() > 200L * 1024 * 1024, "estimated " + report.totalBytes());
        // The JVM's own counter for this thread, seen at both chunk boundaries, brackets the estimate:
        // 4000 × 64 KiB were allocated on it. The short-lived alloc-thread was seen at most once.
        assertTrue(report.hasCounters());
        final long mainCounted = report.counted(self).orElseThrow();
        assertTrue(mainCounted >= 4_000L * 64 * 1024, "counted " + mainCounted);
        assertTrue(report.counted("alloc-thread").isEmpty());
        // Without the first sample per thread the estimate is close; with it, this thread's whole test-suite
        // history (hundreds of MB) would land in the window.
        assertTrue(Math.abs(report.estimateError()) < 0.25, "estimate off by " + report.estimateError());

        // Every row says how many samples it rests on, and the counts survive the drop of each
        // thread's first sample: they sum to the report's own sample count, not to one more.
        long perClass = 0;
        for (final long n : report.support().byClass().values()) {
            perClass += n;
        }
        assertEquals(report.samples(), perClass, report.support().byClass().toString());
        assertTrue(report.support().className("[B") > 0);
        assertTrue(report.support().thread("alloc-thread") > 0);
        assertEquals(report.samples(), report.support().byThread().values().stream().mapToLong(Long::longValue).sum());
        assertThrows(IllegalStateException.class, () -> new AllocationCollector().report());
    }

    @Test
    void allocationCollectorFallsBackToTlabEvents() throws Exception {
        final Path file = JfrFixtures.record(dir, "tlab", r -> {
            r.disable("jdk.ObjectAllocationSample");
            r.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
            r.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
        }, () -> JfrFixtures.onThread("tlab-thread", () -> {
            final byte[][] keep = new byte[16][];
            for (int i = 0; i < 2_000; i++) {
                keep[i % keep.length] = new byte[(i % 2 == 0) ? 256 : 2 * 1024 * 1024];
            }
            if (keep[0].length == 0) {
                throw new IllegalStateException();
            }
        }));
        final AllocationCollector collector = new AllocationCollector();
        JfrReader.read(file, collector);
        assertTrue(collector.report().source().contains("InNewTLAB"));
        assertEquals("tlab-thread", collector.report().threads(1).getFirst().key());
    }

    /** A loop that is idle in a Java method, so the idle point is sampled like a selector. */
    static final class TestLoop {
        static void idle(final long millis) {
            final long deadline = System.nanoTime() + millis * 1_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
    }

    @Test
    void stallCollectorFindsSleepBusyAndMonitorStalls() throws Exception {
        final Object lock = new Object();
        // Sleep and the monitor block are events, so they are exact. BUSY is the one verdict here
        // that rests on samples alone: a loaded machine can leave the whole burn unsampled, and the
        // same window then reads as an unexplained silence (docs/DESIGN.md section 4.3). Record
        // again rather than assert on one roll of the dice; three starved recordings is a failure.
        Path file = null;
        StallReport report = null;
        for (int attempt = 0; attempt < 3 && !has(report, Verdict.BUSY); attempt++) {
            file = recordStalls("stalls" + attempt, lock);
            final StallCollector collector = new StallCollector(Glob.of("loop-*"),
                    IdleMatcher.of(".*RecordingTest\\$TestLoop\\.idle"), 50_000_000L);
            final RecordingInfo info = JfrReader.read(file, collector);
            report = collector.report();
            assertEquals(info, report.info());
        }

        assertEquals(1, report.threads().size(), report.threads().toString());
        assertEquals("loop-test", report.threads().getFirst().thread().name());
        assertTrue(report.threads().getFirst().samples() > 20, "samples " + report.threads().getFirst().samples());
        assertEquals(50_000_000L, report.gapNanos());

        final Stall sleep = find(report, Verdict.SLEEP);
        assertTrue(sleep.duration() >= 200_000_000L, sleep.toString());
        assertEquals(Stall.Evidence.EVENT, sleep.evidence());

        // A busy stretch is bounded by the samples that saw it, so its length is a property of the
        // sampler, not of the burn: under load a 500 ms burn has come back as 143 ms of samples.
        // What the recording does prove is the verdict, the frame it names, that more than one
        // observation backs it, and that it cleared the gap a stall is measured against.
        final Stall busy = find(report, Verdict.BUSY);
        assertTrue(busy.detail().contains("JfrFixtures.burn"), busy.detail());
        assertTrue(busy.duration() >= report.gapNanos(), busy.toString());
        assertTrue(busy.samples() >= 2, busy.toString());

        final Stall monitor = find(report, Verdict.BLOCKED_MONITOR);
        assertTrue(monitor.detail().contains("held by holder-thread"), monitor.detail());
        assertTrue(monitor.detail().contains("java.lang.Object@"), monitor.detail());
        assertTrue(monitor.duration() >= 150_000_000L, monitor.toString());

        // No thread matches: empty report, no exception.
        final StallCollector none = new StallCollector(Glob.of("nobody"), IdleMatcher.defaults(), 50_000_000L);
        JfrReader.read(file, none);
        assertTrue(none.report().threads().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> new StallCollector(Glob.any(), IdleMatcher.defaults(), 1).report());
    }

    @Test
    void stallCollectorSeesParksWaitsAndBlockingSocketReads() throws Exception {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        final Object monitor = new Object();
        try (final java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            final Thread slow = new Thread(() -> {
                try (final java.net.Socket s = server.accept()) {
                    JfrFixtures.sleep(200);
                    s.getOutputStream().write('x');
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }, "slow-server");
            slow.start();

            final Path file = JfrFixtures.record(dir, "io", r -> {
                r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
                r.enable("jdk.ThreadPark").withThreshold(Duration.ZERO).withStackTrace();
                r.enable("jdk.JavaMonitorWait").withThreshold(Duration.ZERO).withStackTrace();
                r.enable("jdk.SocketRead").withThreshold(Duration.ZERO).withStackTrace();
                r.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
                r.enable("jdk.SafepointBegin").withThreshold(Duration.ZERO);
                r.enable("jdk.SafepointEnd").withThreshold(Duration.ZERO);
            }, () -> JfrFixtures.onThread("loop-io", () -> {
                TestLoop.idle(200);
                final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
                final Thread holder = new Thread(() -> {
                    lock.lock();
                    try {
                        held.countDown();
                        JfrFixtures.sleep(200);
                    } finally {
                        lock.unlock();
                    }
                }, "lock-holder");
                holder.start();
                held.await();
                JfrFixtures.sleep(20);
                lock.lock();
                lock.unlock();
                holder.join();
                TestLoop.idle(200);
                synchronized (monitor) {
                    monitor.wait(200);
                }
                TestLoop.idle(200);
                try (final java.net.Socket s = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),
                        server.getLocalPort())) {
                    if (s.getInputStream().read() != 'x') {
                        throw new IllegalStateException("bad reply");
                    }
                }
                System.gc();
                TestLoop.idle(200);
            }));
            slow.join();

            final StallCollector collector = new StallCollector(Glob.of("loop-io"),
                    IdleMatcher.of(".*RecordingTest\\$TestLoop\\.idle"), 50_000_000L);
            JfrReader.read(file, collector);
            final StallReport report = collector.report();

            final Stall park = find(report, Verdict.PARKED);
            assertTrue(park.detail().contains("ReentrantLock"), park.detail());
            assertTrue(park.duration() >= 100_000_000L, park.toString());
            final Stall wait = find(report, Verdict.OBJECT_WAIT);
            assertTrue(wait.detail().contains("java.lang.Object@"), wait.detail());
            final Stall io = find(report, Verdict.BLOCKING_IO);
            assertTrue(io.detail().startsWith("blocking socket read from"), io.detail());
            assertTrue(io.detail().contains(":" + server.getLocalPort()), io.detail());
            assertTrue(io.duration() >= 100_000_000L, io.toString());
        }
    }

    @Test
    void internerSharesStacksThreadsAndClassNamesAcrossEvents() throws Exception {
        final Path file = JfrFixtures.record(dir, "intern",
                r -> r.enable("jdk.ObjectAllocationSample").with("throttle", "5000/s").withStackTrace(),
                () -> JfrFixtures.onThread("intern-thread", () -> {
            final byte[][] keep = new byte[16][];
            for (int i = 0; i < 3_000; i++) {
                keep[i % keep.length] = new byte[32 * 1024];
            }
            if (keep[0].length == 0) {
                throw new IllegalStateException();
            }
        }));

        final java.util.IdentityHashMap<dev.jfrq.core.model.Stack, Boolean> distinctInstances = new java.util.IdentityHashMap<>();
        final java.util.IdentityHashMap<ThreadRef, Boolean> threadInstances = new java.util.IdentityHashMap<>();
        final java.util.IdentityHashMap<String, Boolean> classNameInstances = new java.util.IdentityHashMap<>();
        final int[] events = new int[1];
        final dev.jfrq.core.model.Interner[] seen = new dev.jfrq.core.model.Interner[1];
        final JfrReader.Sink sink = new JfrReader.Sink() {
            dev.jfrq.core.model.Interner interner;

            @Override
            public Set<String> eventTypes() {
                return Set.of("jdk.ObjectAllocationSample");
            }

            @Override
            public void begin(final dev.jfrq.core.model.Interner i) {
                interner = i;
                seen[0] = i;
            }

            @Override
            public void accept(final RecordedEvent e) {
                events[0]++;
                distinctInstances.put(Events.stack(e, interner), Boolean.TRUE);
                final ThreadRef t = interner.thread(e);
                if (t != null && t.name().equals("intern-thread")) {
                    threadInstances.put(t, Boolean.TRUE);
                }
                classNameInstances.put(Events.className(e, "objectClass", interner), Boolean.TRUE);
            }
        };
        final RecordingInfo info = JfrReader.read(file, sink);

        assertTrue(events[0] > 50, "events " + events[0]);
        // Thousands of samples, a handful of distinct stacks, one instance each.
        assertTrue(distinctInstances.size() < events[0] / 4, distinctInstances.size() + " of " + events[0]);
        assertEquals(seen[0].distinctStacks(), distinctInstances.size());
        assertEquals(1, threadInstances.size());
        assertTrue(classNameInstances.size() <= 3, classNameInstances.toString());
        assertTrue(seen[0].distinctFrames() > 0);
        assertTrue(info.has("jdk.ObjectAllocationSample"));
        // A filtered read still learns the settings and the sampler thread field of the type.
        assertTrue(info.hasSettings());
    }

    private static Stall find(final StallReport report, final Verdict verdict) {
        return report.stalls().stream().filter(s -> s.verdict() == verdict).findFirst()
                .orElseThrow(() -> new AssertionError("no " + verdict + " in " + report.stalls()));
    }

    /** Whether a verdict is present at all; {@code null} is "nothing recorded yet". */
    private static boolean has(final StallReport report, final Verdict verdict) {
        return report != null && report.stalls().stream().anyMatch(s -> s.verdict() == verdict);
    }

    /**
     * A loop thread that idles, sleeps, burns CPU, blocks on a monitor another thread holds,
     * and idles again: one recording holding one of each stall this analysis names.
     */
    private Path recordStalls(final String name, final Object lock) throws Exception {
        return JfrFixtures.record(dir, name, r -> {
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
            r.enable("jdk.SafepointBegin").withThreshold(Duration.ZERO);
            r.enable("jdk.SafepointEnd").withThreshold(Duration.ZERO);
        }, () -> JfrFixtures.onThread("loop-test", () -> {
            TestLoop.idle(400);
            JfrFixtures.sleep(250);
            TestLoop.idle(400);
            // Long enough that the sampler has to miss half a second in a row to lose the burn.
            JfrFixtures.burn(500);
            TestLoop.idle(400);
            // Block this very thread on the lock while a holder sleeps inside it.
            final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
            final Thread holder = new Thread(() -> {
                synchronized (lock) {
                    held.countDown();
                    // This thread reaches the monitor 20 ms in, so it blocks for nearly the whole
                    // hold. Held well past the floor asserted on it: a loaded scheduler that wakes
                    // it late eats into the wait, and the block is what makes it BLOCKED_MONITOR.
                    JfrFixtures.sleep(400);
                }
                JfrFixtures.sleep(JfrFixtures.HOLDER_TAIL_MILLIS);
            }, "holder-thread");
            holder.start();
            held.await();
            JfrFixtures.sleep(20);
            synchronized (lock) {
                lock.notifyAll();
            }
            // Not joined, for the reason JfrFixtures.contend gives: the holder has to outlive the
            // recording or it is written as an unknown previous owner, and this stall is the one
            // that names it.
            TestLoop.idle(300);
        }));
    }

    /**
     * A JVM commonly runs a continuous recording next to an on-demand one. The on-demand
     * file carries {@code jdk.ActiveRecording} events for both, so anchoring the span on
     * the earliest recording start would stretch a one-second dump to the continuous
     * recording's age and divide every rate by it. The span must be the file's own.
     */
    @Test
    void spanIsTheDumpedRecordingsOwnEvenWithAnOlderRecordingRunning() throws Exception {
        final Path file = dir.resolve("ondemand.jfr");
        try (final jdk.jfr.Recording continuous = new jdk.jfr.Recording()) {
            continuous.enable("jdk.ActiveRecording");
            continuous.enable("jdk.ActiveSetting");
            continuous.start();
            JfrFixtures.sleep(600);
            try (final jdk.jfr.Recording onDemand = new jdk.jfr.Recording()) {
                onDemand.enable("jdk.ActiveRecording");
                onDemand.enable("jdk.ActiveSetting");
                onDemand.setDestination(file);
                onDemand.start();
                JfrFixtures.sleep(200);
                onDemand.stop();
            }
            continuous.stop();
        }
        final RecordingInfo info = JfrReader.read(file);
        assertTrue(info.count("jdk.ActiveRecording") >= 2, "recordings seen: " + info.count("jdk.ActiveRecording"));
        final long millis = info.duration().toMillis();
        assertTrue(millis >= 150 && millis < 500, "span " + millis + " ms should be the 200 ms dump, not the 800 ms JVM history");
        // A filtered pass reports the same span as the full one.
        final RecordingInfo filtered = JfrReader.read(file, new ContentionCollector());
        assertEquals(info.span(), filtered.span());
    }

    /**
     * With the JDK's own profiles {@code jdk.SafepointEnd} is disabled, so a safepoint's
     * length is only known through the VM operation that ran inside it.
     */
    @Test
    void safepointsAreNamedAfterTheirVmOperationWhenTheEndEventIsDisabled() throws Exception {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final List<Thread> parked = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final Thread t = new Thread(() -> {
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "parked-" + i);
            t.setDaemon(true);
            t.start();
            parked.add(t);
        }
        final Path file = JfrFixtures.record(dir, "safepoints", r -> {
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.SafepointBegin").withThreshold(Duration.ZERO);
            r.disable("jdk.SafepointEnd");
            r.enable("jdk.ExecuteVMOperation").withThreshold(Duration.ZERO);
            r.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
        }, () -> {
            for (int i = 0; i < 20; i++) {
                // A thread dump is a safepoint VM operation, and with hundreds of threads it takes a while.
                if (Thread.getAllStackTraces().isEmpty()) {
                    throw new IllegalStateException();
                }
            }
            JfrFixtures.sleep(50);
        });
        release.countDown();
        for (final Thread t : parked) {
            t.join();
        }

        final StallCollector collector = new StallCollector(Glob.of("main"), IdleMatcher.defaults(), 1_000L);
        final RecordingInfo info = JfrReader.read(file, collector);
        assertEquals(0, info.count("jdk.SafepointEnd"));
        assertTrue(info.count("jdk.ExecuteVMOperation") > 0);
        final List<dev.jfrq.core.stalls.Timeline.Pause> pauses = collector.report().pauses();
        assertTrue(pauses.stream().anyMatch(p -> p.detail().startsWith("VM operation ThreadDump")),
                "pauses: " + pauses);
        // No pause is reported twice: a GC's own safepoint is folded into the GC pause.
        for (final dev.jfrq.core.stalls.Timeline.Pause p : pauses) {
            if (p.kind() != dev.jfrq.core.stalls.Timeline.PauseKind.SAFEPOINT) {
                continue;
            }
            for (final dev.jfrq.core.stalls.Timeline.Pause gc : pauses) {
                if (gc.kind() == dev.jfrq.core.stalls.Timeline.PauseKind.GC) {
                    assertTrue(gc.interval().overlap(p.interval()) < 0.5 * p.length(), "reported twice: " + p + " and " + gc);
                }
            }
        }
    }
}
