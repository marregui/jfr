// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import dev.jfrq.core.jfr.JfrFixtures;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionCollector;
import dev.jfrq.core.locks.ContentionReport;
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
        // other-b parks on the same queue once. Watching perch-a alone must not hide that. Each
        // park is woken before its timeout, so none of them is a timer loop's own deadline.
        final Object queue = new Object();
        final Path file = JfrFixtures.record(dir, "shared", StallCollectorTest::parks, () -> {
            final AtomicInteger parked = new AtomicInteger();
            final Thread a = new Thread(() -> {
                for (int i = 0; i < 4; i++) {
                    parked.incrementAndGet();
                    LockSupport.parkNanos(queue, WOKEN_BEFORE_NANOS);
                }
            }, "perch-a");
            final Thread b = new Thread(() -> LockSupport.parkNanos(queue, 100_000_000L), "other-b");
            a.start();
            b.start();
            wake(a, parked, 4);
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
    void aMailboxACollectionMovedIsStillItsThreadsPerch() throws Exception {
        // A worker waits on its own mailbox and is signalled every 40 ms by a thread of its
        // own, so no wait runs out a timeout and the timer rule has nothing to say. Young
        // collections at a fifth, two fifths, three and four fifths of the way move the
        // condition, which JFR then names by a new address: five pieces, none holding the
        // thread for half the window on its own. Four changes of 40 ms among 50 waits meet a
        // pause by chance at odds of about 1 in 12 each, so together at 1 in 20 000.
        final Path file = JfrFixtures.record(dir, "moved", r -> {
            parks(r);
            r.enable("jdk.GCPhasePause");
        }, () -> {
            final ReentrantLock lock = new ReentrantLock();
            final Condition mail = lock.newCondition();
            final AtomicBoolean stop = new AtomicBoolean();
            final Thread worker = new Thread(() -> {
                lock.lock();
                try {
                    while (!stop.get()) {
                        mail.awaitUninterruptibly();
                    }
                } finally {
                    lock.unlock();
                }
            }, "mailbox");
            final Thread postman = new Thread(() -> {
                while (worker.isAlive()) {
                    JfrFixtures.sleep(40);
                    lock.lock();
                    try {
                        mail.signal();
                    } finally {
                        lock.unlock();
                    }
                }
            }, "postman");
            worker.start();
            postman.start();
            for (int i = 0; i < 4; i++) {
                JfrFixtures.sleep(400);
                youngCollection();
            }
            JfrFixtures.sleep(400);
            stop.set(true);
            worker.join();
            postman.join();
        });

        // The premise: the lock was split, and no piece is a perch by itself. A collector that
        // moves objects outside its pauses, or none that moved this one, leaves nothing to test.
        final ContentionCollector raw = new ContentionCollector(0, "mailbox"::equals, IdleMatcher.none());
        final RecordingInfo info = JfrReader.read(file, raw);
        final List<ContentionReport.LockStats> pieces = raw.report().locks(100);
        assumeTrue(pieces.size() >= 2, "the collections did not move the mailbox: " + pieces);
        for (final ContentionReport.LockStats piece : pieces) {
            assumeTrue(2 * piece.totalNanos() < info.span().duration(), piece + " is a perch on its own");
        }

        // locks: every piece is the worker waiting for mail.
        final ContentionCollector locks = new ContentionCollector(0, "mailbox"::equals);
        JfrReader.read(file, locks);
        assertTrue(locks.report().waits().isEmpty(), locks.report().waits().toString());
        assertEquals(pieces.size(), locks.report().perchCount());

        // stalls: none of its waits is a stall.
        final StallReport r = stalls(file, "mailbox");
        assertEquals(0, count(r, Verdict.PARKED), r.stalls().toString());
    }

    /** Allocates until a collection has run: a young one, which copies what it keeps. */
    private static void youngCollection() {
        final long before = collections();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (collections() == before) {
            for (int i = 0; i < 1_024; i++) {
                garbage = new byte[16 * 1_024];
            }
            assertTrue(System.nanoTime() < deadline, "no collection in 30 s");
        }
    }

    private static long collections() {
        long n = 0;
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionCount());
        }
        return n;
    }

    /** Where {@link #youngCollection} drops its allocations, so they cannot be optimised away. */
    private static volatile byte[] garbage;

    @Test
    void aBackoffLoopsBlockerlessParksAreStalls() throws Exception {
        // parkNanos with no blocker: every such park in the JVM shares the one "lock", so the
        // shape rule must not read a pacing loop as the thread's idle point. Woken before their
        // timeout, so the timer-loop rule has nothing to say about them either.
        final Path file = JfrFixtures.record(dir, "backoff", StallCollectorTest::parks, () -> {
            final AtomicInteger parked = new AtomicInteger();
            final Thread retrier = new Thread(() -> {
                for (int i = 0; i < 4; i++) {
                    parked.incrementAndGet();
                    LockSupport.parkNanos(WOKEN_BEFORE_NANOS);
                }
            }, "retrier");
            retrier.start();
            wake(retrier, parked, 4);
            retrier.join();
        });
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

    /** A park timeout no fixture waits out: the thread is always woken first. */
    private static final long WOKEN_BEFORE_NANOS = 30_000_000_000L;

    /**
     * Unparks {@code t} {@code times} times, each once its next park has lasted 250 ms;
     * {@code parked} counts the parks {@code t} has begun.
     */
    private static void wake(final Thread t, final AtomicInteger parked, final int times) {
        wake(t, parked, times, () -> LockSupport.unpark(t));
    }

    /** Wakes {@code t} with {@code how} each of {@code times} times it is waiting, 250 ms into the wait. */
    private static void wake(final Thread t, final AtomicInteger parked, final int times, final Runnable how) {
        for (int i = 0; i < times; i++) {
            while (parked.get() <= i || t.getState() != Thread.State.TIMED_WAITING) {
                Thread.onSpinWait();
            }
            JfrFixtures.sleep(250);
            how.run();
        }
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

    @Test
    void timerLoopsAreReadFromTheEventsOwnFields() throws Exception {
        // Each thread waits out its own timeout six times, by each of the three events: a
        // monitor wait says timedOut, a park carries its timeout, a sleep its time.
        final Object monitor = new Object();
        final Path file = JfrFixtures.record(dir, "timers", r -> {
            r.enable("jdk.JavaMonitorWait").withThreshold(Duration.ofMillis(10)).withStackTrace();
            r.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(10)).withStackTrace();
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ofMillis(10)).withStackTrace();
            r.enable("jdk.ThreadStart");
            r.enable("jdk.ThreadEnd");
        }, () -> {
            final Thread waiter = new Thread(() -> {
                for (int i = 0; i < 6; i++) {
                    synchronized (monitor) {
                        try {
                            monitor.wait(150);
                        } catch (final InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            }, "wait-timer");
            final Thread parker = new Thread(() -> {
                for (int i = 0; i < 6; i++) {
                    LockSupport.parkNanos(150_000_000L);
                }
            }, "park-timer");
            final Thread sleeper = new Thread(() -> {
                for (int i = 0; i < 6; i++) {
                    JfrFixtures.sleep(150);
                }
            }, "sleep-timer");
            final Thread deadliner = new Thread(() -> {
                for (int i = 0; i < 6; i++) {
                    final long deadline = System.currentTimeMillis() + 150;
                    while (System.currentTimeMillis() < deadline) {
                        LockSupport.parkUntil(deadline);
                    }
                }
            }, "until-timer");
            waiter.start();
            parker.start();
            sleeper.start();
            deadliner.start();
            waiter.join();
            parker.join();
            sleeper.join();
            deadliner.join();
        });
        final StallReport r = stalls(file, "*-timer");
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("24 waits totalling ")
                && w.contains("park-timer") && w.contains("sleep-timer") && w.contains("wait-timer")
                && w.contains("until-timer")), r.warnings().toString());

        final StallCollector none = new StallCollector(Glob.of("*-timer"), IdleMatcher.none(), IdleMatcher.none(), GAP);
        JfrReader.read(file, none);
        assertEquals(6, count(none.report(), Verdict.OBJECT_WAIT), none.report().stalls().toString());
        assertEquals(12, count(none.report(), Verdict.PARKED), none.report().stalls().toString());
        assertEquals(6, count(none.report(), Verdict.SLEEP), none.report().stalls().toString());
    }

    @Test
    void aSleepOrADeadlineCutShortIsAStallNotATimer() throws Exception {
        // The same waits as a timer loop's, from one place and repeated, but each ends well before
        // the time it asked for: the thread was woken, so the rule has nothing to set aside.
        // The threads' lives are recorded, so that their waits are most of them, as a timer's are.
        final Path file = JfrFixtures.record(dir, "cut-short", r -> {
            r.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(10)).withStackTrace();
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ofMillis(10)).withStackTrace();
            r.enable("jdk.ThreadStart");
            r.enable("jdk.ThreadEnd");
        }, () -> {
            final AtomicInteger slept = new AtomicInteger();
            final Thread sleeper = new Thread(() -> {
                for (int i = 0; i < 3; i++) {
                    slept.incrementAndGet();
                    try {
                        Thread.sleep(WOKEN_BEFORE_NANOS / 1_000_000L);
                    } catch (final InterruptedException e) {
                        // woken, as the test means it to be
                    }
                }
            }, "cut-sleeper");
            final AtomicInteger parked = new AtomicInteger();
            final Thread deadliner = new Thread(() -> {
                for (int i = 0; i < 3; i++) {
                    parked.incrementAndGet();
                    LockSupport.parkUntil(System.currentTimeMillis() + WOKEN_BEFORE_NANOS / 1_000_000L);
                }
            }, "cut-deadliner");
            sleeper.start();
            deadliner.start();
            wake(sleeper, slept, 3, sleeper::interrupt);
            wake(deadliner, parked, 3);
            sleeper.join();
            deadliner.join();
        });
        final StallReport r = stalls(file, "cut-*");
        assertEquals(3, count(r, Verdict.SLEEP), r.stalls().toString());
        assertEquals(3, count(r, Verdict.PARKED), r.stalls().toString());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("timer loops")), r.warnings().toString());
    }
}
