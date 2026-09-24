// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import dev.jfrq.core.jfr.JfrFixtures;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.stalls.Stall.Verdict;
import dev.jfrq.core.util.Glob;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link StallCollector} on real recordings, for the answers that depend on threads the
 * filter did not select and on events a thread never finished. Assertions are on verdicts,
 * not durations.
 */
class StallCollectorTest {

    private static final long GAP = 50_000_000L;

    @TempDir
    Path dir;

    static void parks(final Recording r) {
        r.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(10)).withStackTrace();
    }

    @Test
    void aLockAnotherThreadAlsoParksOnIsNotTheWatchedThreadsPerch() throws Exception {
        // perch-a spends most of the recording parked on the queue, alone it would be its perch;
        // other-b parks on the same queue once. Watching perch-a alone must not hide that.
        final Object queue = new Object();
        final Path file = JfrFixtures.record(dir, "shared", StallCollectorTest::parks, () -> {
            final Thread a = new Thread(() -> {
                for (int i = 0; i < 4; i++) {
                    LockSupport.parkNanos(queue, 250_000_000L);
                }
            }, "perch-a");
            final Thread b = new Thread(() -> LockSupport.parkNanos(queue, 100_000_000L), "other-b");
            a.start();
            b.start();
            a.join();
            b.join();
        });
        final StallReport watched = stalls(file, "perch-a");
        assertEquals(4, count(watched, Verdict.PARKED), watched.stalls().toString());
        // And the verdict is the same whichever threads the filter selects.
        final StallReport both = stalls(file, "perch-a,other-b");
        assertEquals(4, both.stallsOf(watched.threads().getFirst().thread()).size(), both.stalls().toString());
    }

    @Test
    void aBackoffLoopsBlockerlessParksAreStalls() throws Exception {
        // parkNanos with no blocker: every such park in the JVM shares the one "lock", so the
        // shape rule must not read a pacing loop as the thread's idle point.
        final Path file = JfrFixtures.record(dir, "backoff", StallCollectorTest::parks,
                () -> JfrFixtures.onThread("retrier", () -> {
                    for (int i = 0; i < 4; i++) {
                        LockSupport.parkNanos(250_000_000L);
                    }
                }));
        final StallReport r = stalls(file, "retrier");
        assertEquals(4, count(r, Verdict.PARKED), r.stalls().toString());
        assertTrue(r.stalls().getFirst().detail().contains(ParkShapes.NO_BLOCKER), r.stalls().getFirst().detail());
    }

    @Test
    void aThreadStuckUntilTheRecordingStopsIsReported() throws Exception {
        // The loop spins for a while, then blocks on a monitor that is released only after the
        // recording has stopped: JFR writes the monitor event when the wait ends, so the file
        // holds nothing but the loop's last sample. Sampling is at the machine's mercy, so a
        // starved recording is made again rather than asserted on.
        StallReport report = null;
        for (int attempt = 0; attempt < 3 && !stuckToTheEnd(report); attempt++) {
            report = stuckRecording("stuck" + attempt);
        }
        assertTrue(stuckToTheEnd(report), report.stalls().toString());
    }

    private StallReport stuckRecording(final String name) throws Exception {
        final Object lock = new Object();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                await(release);
            }
        }, "holder");
        holder.start();
        await(held);
        final Thread[] loop = new Thread[1];
        final Path file = JfrFixtures.record(dir, name, r -> {
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(10)).withStackTrace();
            r.enable("jdk.ThreadStart");
            r.enable("jdk.ThreadEnd");
        }, () -> {
            loop[0] = new Thread(() -> {
                JfrFixtures.burn(300);
                synchronized (lock) {
                    lock.notifyAll();
                }
            }, "stuck-loop");
            loop[0].start();
            JfrFixtures.sleep(1_000);
        });
        release.countDown();
        loop[0].join();
        holder.join();
        return stalls(file, "stuck-loop");
    }

    private static boolean stuckToTheEnd(final StallReport report) {
        if (report == null) {
            return false;
        }
        for (final Stall s : report.stalls()) {
            if (s.interval().end() == report.info().span().end()
                    && s.detail().contains("runs to the thread's last moment in the recording")
                    && s.duration() >= 400_000_000L) {
                return true;
            }
        }
        return false;
    }

    private static StallReport stalls(final Path file, final String glob) throws Exception {
        final StallCollector collector = new StallCollector(Glob.of(glob), IdleMatcher.defaults(), GAP);
        final RecordingInfo info = JfrReader.read(file, collector);
        assertEquals(info, collector.report().info());
        return collector.report();
    }

    private static long count(final StallReport r, final Verdict verdict) {
        return r.stalls().stream().filter(s -> s.verdict() == verdict).count();
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void matchingThreadsWithNoEvidenceAreNamedInTheReport() throws Exception {
        final Object blocker = new Object();
        final Path file = JfrFixtures.record(dir, "quiet", r -> {
            parks(r);
            r.enable("jdk.ThreadStart");
            r.enable("jdk.ThreadEnd");
        }, () -> {
            JfrFixtures.onThread("parker", () -> LockSupport.parkNanos(blocker, 100_000_000L));
            // Started and ended inside the recording, and nothing else: no sample, no block.
            JfrFixtures.onThread("quiet", () -> JfrFixtures.sleep(1));
        });
        final StallReport r = stalls(file, "parker,quiet");
        assertEquals(List.of("parker"), r.threads().stream().map(t -> t.thread().name()).toList());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("1 matching thread has no samples")
                && w.contains("(quiet)")), r.warnings().toString());
    }
}
