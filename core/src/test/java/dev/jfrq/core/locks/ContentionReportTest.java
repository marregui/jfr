// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.locks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.locks.Wait.Kind;
import dev.jfrq.core.locks.Wait.LockKey;
import org.junit.jupiter.api.Test;

class ContentionReportTest {

    static final long MS = 1_000_000L;
    static final ThreadRef LOOP1 = new ThreadRef(1, "event-loop-1");
    static final ThreadRef LOOP2 = new ThreadRef(2, "event-loop-2");
    static final ThreadRef HOUSEKEEPER = new ThreadRef(3, "housekeeper");
    static final ThreadRef FLUSHER = new ThreadRef(4, "flusher");
    static final LockKey REGISTRY = new LockKey("dev.app.Registry", 0xabc, Kind.MONITOR_ENTER);
    static final LockKey STORE = new LockKey("dev.app.Store", 0xdef, Kind.MONITOR_ENTER);
    static final LockKey QUEUE = new LockKey("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject",
            0x111, Kind.PARK);

    static Wait wait(final long fromMs, final long toMs, final ThreadRef waiter, final LockKey lock, final ThreadRef owner) {
        return new Wait(new Interval(fromMs * MS, toMs * MS), waiter, lock, owner, Stack.EMPTY);
    }

    /** A park on the queue lock, with the stack that decides whether it is contention. */
    static Wait park(final long fromMs, final long toMs, final ThreadRef waiter, final Stack stack) {
        return new Wait(new Interval(fromMs * MS, toMs * MS), waiter, QUEUE, null, stack);
    }

    static Stack stack(final Frame... frames) {
        return new Stack(List.of(frames), false);
    }

    /** A pool worker with nothing to do: the frame that says so is below the park and the queue. */
    static final Stack NO_WORK = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
            new Frame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", "await", 1761, "JIT compiled"),
            new Frame("java.util.concurrent.LinkedBlockingQueue", "take", 435, "JIT compiled"),
            new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1070, "JIT compiled"),
            new Frame("java.util.concurrent.ThreadPoolExecutor", "runWorker", 1130, "JIT compiled"));

    /** The same park, under an application call that is waiting for an answer. */
    static final Stack AWAITING_RESULT = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
            new Frame("java.util.concurrent.SynchronousQueue", "poll", 800, "JIT compiled"),
            new Frame("dev.app.Rpc", "call", 44, "JIT compiled"),
            new Frame("dev.app.Handler", "channelRead0", 59, "JIT compiled"));

    static RecordingInfo info() {
        return window(0, 10_000);
    }

    static RecordingInfo window(final long fromMs, final long toMs) {
        return new RecordingInfo(Path.of("t.jfr"), new Interval(fromMs * MS, toMs * MS), 1, Map.of(), Map.of(),
                Set.of(), List.of());
    }

    @Test
    void emptyReport() {
        final ContentionReport r = new ContentionReport(info(), List.of());
        assertTrue(r.isEmpty());
        assertEquals(0, r.totalNanos());
        assertTrue(r.locks(5).isEmpty());
        assertTrue(r.waiters(5).isEmpty());
        assertTrue(r.convoys(5, 5).isEmpty());
        assertTrue(r.longest(5).isEmpty());
        assertEquals(info(), r.info());
    }

    @Test
    void locksAndWaitersAreRankedByTotalTime() {
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(600, 650, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(700, 720, HOUSEKEEPER, STORE, FLUSHER),
                wait(800, 805, LOOP1, QUEUE, null)));

        assertEquals(275 * MS, r.totalNanos());
        final List<ContentionReport.LockStats> locks = r.locks(10);
        assertEquals(3, locks.size());
        assertEquals(REGISTRY, locks.getFirst().lock());
        assertEquals(250 * MS, locks.getFirst().totalNanos());
        assertEquals(2, locks.getFirst().count());
        assertEquals(200 * MS, locks.getFirst().maxNanos());
        assertEquals(Set.of(LOOP1, LOOP2), locks.getFirst().waiters());
        assertEquals(Set.of(HOUSEKEEPER), locks.getFirst().owners());
        assertEquals(QUEUE, locks.get(2).lock());
        assertTrue(locks.get(2).owners().isEmpty());
        assertEquals(1, r.locks(1).size());

        final List<ContentionReport.ThreadStats> waiters = r.waiters(10);
        assertEquals(LOOP1, waiters.getFirst().thread());
        assertEquals(205 * MS, waiters.getFirst().totalNanos());
        assertEquals(2, waiters.getFirst().count());
        assertEquals(200 * MS, waiters.getFirst().maxNanos());

        assertEquals(200 * MS, r.longest(1).getFirst().duration());
        assertEquals(List.of(100 * MS, 600 * MS, 700 * MS, 800 * MS),
                r.waits().stream().map(Wait::start).toList());
    }

    @Test
    void theRealHolderIsResolvedThroughCoWaiters() {
        // JFR says loop-1 got the lock from loop-2, but loop-2 was itself waiting for the
        // housekeeper the whole time: the housekeeper is who held it.
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 299, LOOP2, REGISTRY, HOUSEKEEPER)));
        final Wait resolved = r.waits().stream().filter(w -> w.waiter().equals(LOOP1)).findFirst().orElseThrow();
        assertEquals(HOUSEKEEPER, resolved.owner());
        assertEquals(List.of(LOOP2), resolved.via());
        assertEquals("held by housekeeper (handed on through event-loop-2)", resolved.heldBy());
        final Wait direct = r.waits().stream().filter(w -> w.waiter().equals(LOOP2)).findFirst().orElseThrow();
        assertEquals(HOUSEKEEPER, direct.owner());
        assertTrue(direct.via().isEmpty());
        assertEquals("held by housekeeper", direct.heldBy());
        assertEquals(Set.of(HOUSEKEEPER), r.locks(1).getFirst().owners());
    }

    @Test
    void resolutionStopsAtCyclesAndAtUnknownOwners() {
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 300, LOOP2, REGISTRY, LOOP1)));
        for (final Wait w : r.waits()) {
            assertTrue(w.via().isEmpty(), w.toString());
        }
        final ContentionReport parks = new ContentionReport(info(), List.of(wait(0, 10, LOOP1, QUEUE, null)));
        assertNull(parks.waits().getFirst().owner());
        assertEquals("", parks.waits().getFirst().heldBy());
    }

    @Test
    void convoysFollowTheHolderIntoADifferentLock() {
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 299, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(150, 200, HOUSEKEEPER, STORE, FLUSHER),
                wait(900, 950, LOOP1, REGISTRY, HOUSEKEEPER)));
        final List<ContentionReport.Convoy> convoys = r.convoys(5, 10);
        assertEquals(2, convoys.size());
        final ContentionReport.Convoy first = convoys.getFirst();
        assertEquals(2, first.depth());
        assertEquals(LOOP1, first.head().waiter());
        assertEquals(HOUSEKEEPER, first.head().owner());
        assertEquals(STORE, first.links().get(1).lock());
        assertEquals(FLUSHER, first.links().get(1).owner());
        assertEquals(LOOP2, convoys.get(1).head().waiter());
        assertEquals(1, r.convoys(5, 1).size());
        // A depth limit of one link means no convoy can form.
        assertTrue(r.convoys(1, 10).isEmpty());
    }

    @Test
    void waitsAreCountedOnlyForThePartInsideTheWindow() {
        // A one-second window: event-loop-1's wait began before it, event-loop-2's runs past
        // its end. Counted whole they put 2.3 s of blocking inside a 1 s window.
        final RecordingInfo info = window(1_000, 2_000);
        final ContentionReport r = new ContentionReport(info, List.of(
                wait(400, 1_900, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(1_800, 2_600, LOOP2, STORE, HOUSEKEEPER)));

        assertEquals(1_100 * MS, r.totalNanos());
        assertEquals(2, r.clippedCount());
        assertEquals(900 * MS, r.locks(10).getFirst().totalNanos());
        assertEquals(900 * MS, r.locks(10).getFirst().maxNanos());
        assertEquals(900 * MS, r.waiters(10).getFirst().totalNanos());
        assertEquals(900 * MS, r.longest(1).getFirst().duration());
        // The clipped wait keeps everything but its interval.
        assertEquals(HOUSEKEEPER, r.longest(1).getFirst().owner());
        assertEquals(REGISTRY, r.longest(1).getFirst().lock());
        // The invariant the 112.5 % in the report broke: no thread is blocked for longer than the window.
        for (final ContentionReport.ThreadStats t : r.waiters(10)) {
            assertTrue(t.totalNanos() <= info.span().length(), t.thread() + " " + t.totalNanos());
        }
        assertEquals(1_000 * MS, r.longest(2).getFirst().start());
        assertEquals(2_000 * MS, r.longest(2).get(1).end());
    }

    @Test
    void aWaitWhollyOutsideTheWindowIsNotReported() {
        final ContentionReport r = new ContentionReport(window(1_000, 2_000), List.of(
                wait(100, 900, LOOP1, REGISTRY, HOUSEKEEPER)));
        assertTrue(r.isEmpty());
        assertEquals(0, r.totalNanos());
        assertEquals(0, r.clippedCount());
    }

    @Test
    void theMinFilterAppliesToTheInWindowPart() {
        // 800 ms of waiting, 50 ms of it inside the window: --min 100ms leaves nothing.
        final List<Wait> straddling = List.of(wait(200, 1_050, LOOP1, REGISTRY, HOUSEKEEPER));
        assertTrue(new ContentionReport(window(1_000, 2_000), straddling, 100 * MS, _ -> true).isEmpty());
        assertEquals(50 * MS, new ContentionReport(window(1_000, 2_000), straddling, 10 * MS, _ -> true).totalNanos());
    }

    @Test
    void convoysAreFollowedOnTheTrueIntervalsAndReportedOnTheClippedOnes() {
        // The housekeeper's own wait began before the window; the walk-back still finds it.
        final ContentionReport r = new ContentionReport(window(1_000, 2_000), List.of(
                wait(1_100, 1_400, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(900, 1_300, HOUSEKEEPER, STORE, FLUSHER)));
        final List<ContentionReport.Convoy> convoys = r.convoys(5, 10);
        assertEquals(1, convoys.size());
        assertEquals(LOOP1, convoys.getFirst().head().waiter());
        assertEquals(STORE, convoys.getFirst().links().get(1).lock());
        // The link is printed with its in-window duration: 1 000 ms .. 1 300 ms, not 900 ms .. 1 300 ms.
        assertEquals(300 * MS, convoys.getFirst().links().get(1).duration());
    }

    @Test
    void parksWaitingForWorkAreReportedApartFromContention() {
        // The shape of a real server: one 200 ms lock fight against two idle pool threads
        // parked for the whole recording. Ranked together, the lock never reaches the top.
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                park(0, 9_000, FLUSHER, NO_WORK),
                park(0, 9_500, HOUSEKEEPER, NO_WORK)));

        assertEquals(1, r.waits().size());
        assertEquals(200 * MS, r.totalNanos());
        assertEquals(REGISTRY, r.locks(10).getFirst().lock());
        assertEquals(1, r.locks(10).size());
        assertEquals(1, r.waiters(10).size());

        assertEquals(2, r.workWaits().size());
        assertEquals(2, r.workWaitThreads());
        assertEquals(18_500 * MS, r.workWaitNanos());
        assertEquals(QUEUE, r.workWaitLocks(10).getFirst().lock());
    }

    @Test
    void aParkWaitingForAResultIsStillContention() {
        // Same queue class, but the frames below the park are an application call.
        final ContentionReport r = new ContentionReport(info(), List.of(park(100, 5_000, LOOP1, AWAITING_RESULT)));
        assertEquals(1, r.waits().size());
        assertEquals(4_900 * MS, r.totalNanos());
        assertTrue(r.workWaits().isEmpty());
    }

    @Test
    void theSplitCanBeTurnedOff() {
        final List<Wait> waits = List.of(park(0, 9_000, FLUSHER, NO_WORK));
        final ContentionReport off = new ContentionReport(info(), waits, 0, _ -> true, IdleMatcher.none());
        assertEquals(1, off.waits().size());
        assertTrue(off.workWaits().isEmpty());
    }

    @Test
    void anIdleWorkerIsNeverAConvoyLink() {
        // The housekeeper holds the registry and is itself parked for work: that is not a convoy,
        // it is a thread doing nothing. Only a real second lock makes a chain.
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                park(50, 400, HOUSEKEEPER, NO_WORK)));
        assertTrue(r.convoys(5, 10).isEmpty(), r.convoys(5, 10).toString());
    }

    @Test
    void lockKeyPrettyPrinting() {
        assertEquals("dev.app.Registry@abc", REGISTRY.pretty());
        assertEquals("dev.app.Registry", REGISTRY.prettyClass());
        assertEquals("byte[]@1", new LockKey("[B", 1, Kind.PARK).pretty());
        assertEquals("monitor", Kind.MONITOR_ENTER.label());
        assertEquals("park", Kind.PARK.label());
        final Wait w = wait(1, 2, LOOP1, QUEUE, null);
        assertEquals(Kind.PARK, w.kind());
        assertEquals(MS, w.start());
        assertEquals(2 * MS, w.end());
    }

    @Test
    void filtersApplyAfterHolderResolutionSoNarrowingTheQuestionKeepsTheAnswer() {
        final List<Wait> all = List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 299, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(150, 200, HOUSEKEEPER, STORE, FLUSHER),
                wait(400, 405, LOOP1, QUEUE, null));
        // Only event-loop-1 asked about, only waits of 10 ms and more.
        final ContentionReport r = new ContentionReport(info(), all, 10 * MS, name -> name.equals("event-loop-1"));

        assertEquals(1, r.waits().size());
        final Wait w = r.waits().getFirst();
        assertEquals(LOOP1, w.waiter());
        // The walk-back went through event-loop-2's wait, which the filter does not report.
        assertEquals(HOUSEKEEPER, w.owner());
        assertEquals(List.of(LOOP2), w.via());
        assertEquals(1, r.waiters(10).size());
        assertEquals(LOOP1, r.waiters(10).getFirst().thread());
        assertEquals(200 * MS, r.totalNanos());
        // The convoy still follows the housekeeper into the store lock it was itself waiting for.
        final List<ContentionReport.Convoy> convoys = r.convoys(5, 10);
        assertEquals(1, convoys.size());
        assertEquals(2, convoys.getFirst().depth());
        assertEquals(STORE, convoys.getFirst().links().get(1).lock());
        // The same waits with no filter report everything.
        assertEquals(4, new ContentionReport(info(), all).waits().size());
    }
}
