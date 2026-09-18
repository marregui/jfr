package dev.jfrq.core.locks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
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

    static Wait wait(long fromMs, long toMs, ThreadRef waiter, LockKey lock, ThreadRef owner) {
        return new Wait(new Interval(fromMs * MS, toMs * MS), waiter, lock, owner, Stack.EMPTY);
    }

    static RecordingInfo info() {
        return new RecordingInfo(Path.of("t.jfr"), new Interval(0, 10_000 * MS), Map.of(), Map.of(), Set.of());
    }

    @Test
    void emptyReport() {
        ContentionReport r = new ContentionReport(info(), List.of());
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
        ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(600, 650, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(700, 720, HOUSEKEEPER, STORE, FLUSHER),
                wait(800, 805, LOOP1, QUEUE, null)));

        assertEquals(275 * MS, r.totalNanos());
        List<ContentionReport.LockStats> locks = r.locks(10);
        assertEquals(3, locks.size());
        assertEquals(REGISTRY, locks.get(0).lock());
        assertEquals(250 * MS, locks.get(0).totalNanos());
        assertEquals(2, locks.get(0).count());
        assertEquals(200 * MS, locks.get(0).maxNanos());
        assertEquals(Set.of(LOOP1, LOOP2), locks.get(0).waiters());
        assertEquals(Set.of(HOUSEKEEPER), locks.get(0).owners());
        assertEquals(QUEUE, locks.get(2).lock());
        assertTrue(locks.get(2).owners().isEmpty());
        assertEquals(1, r.locks(1).size());

        List<ContentionReport.ThreadStats> waiters = r.waiters(10);
        assertEquals(LOOP1, waiters.get(0).thread());
        assertEquals(205 * MS, waiters.get(0).totalNanos());
        assertEquals(2, waiters.get(0).count());
        assertEquals(200 * MS, waiters.get(0).maxNanos());

        assertEquals(200 * MS, r.longest(1).getFirst().duration());
        assertEquals(List.of(100 * MS, 600 * MS, 700 * MS, 800 * MS),
                r.waits().stream().map(Wait::start).toList());
    }

    @Test
    void theRealHolderIsResolvedThroughCoWaiters() {
        // JFR says loop-1 got the lock from loop-2, but loop-2 was itself waiting for the
        // housekeeper the whole time: the housekeeper is who held it.
        ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 299, LOOP2, REGISTRY, HOUSEKEEPER)));
        Wait resolved = r.waits().stream().filter(w -> w.waiter().equals(LOOP1)).findFirst().orElseThrow();
        assertEquals(HOUSEKEEPER, resolved.owner());
        assertEquals(List.of(LOOP2), resolved.via());
        assertEquals("held by housekeeper (handed on through event-loop-2)", resolved.heldBy());
        Wait direct = r.waits().stream().filter(w -> w.waiter().equals(LOOP2)).findFirst().orElseThrow();
        assertEquals(HOUSEKEEPER, direct.owner());
        assertTrue(direct.via().isEmpty());
        assertEquals("held by housekeeper", direct.heldBy());
        assertEquals(Set.of(HOUSEKEEPER), r.locks(1).getFirst().owners());
    }

    @Test
    void resolutionStopsAtCyclesAndAtUnknownOwners() {
        ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 300, LOOP2, REGISTRY, LOOP1)));
        for (Wait w : r.waits()) {
            assertTrue(w.via().isEmpty(), w.toString());
        }
        ContentionReport parks = new ContentionReport(info(), List.of(wait(0, 10, LOOP1, QUEUE, null)));
        assertNull(parks.waits().getFirst().owner());
        assertEquals("", parks.waits().getFirst().heldBy());
    }

    @Test
    void convoysFollowTheHolderIntoADifferentLock() {
        ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 299, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(150, 200, HOUSEKEEPER, STORE, FLUSHER),
                wait(900, 950, LOOP1, REGISTRY, HOUSEKEEPER)));
        List<ContentionReport.Convoy> convoys = r.convoys(5, 10);
        assertEquals(2, convoys.size());
        ContentionReport.Convoy first = convoys.getFirst();
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
    void lockKeyPrettyPrinting() {
        assertEquals("dev.app.Registry@abc", REGISTRY.pretty());
        assertEquals("dev.app.Registry", REGISTRY.prettyClass());
        assertEquals("byte[]@1", new LockKey("[B", 1, Kind.PARK).pretty());
        assertEquals("monitor", Kind.MONITOR_ENTER.label());
        assertEquals("park", Kind.PARK.label());
        Wait w = wait(1, 2, LOOP1, QUEUE, null);
        assertEquals(Kind.PARK, w.kind());
        assertEquals(MS, w.start());
        assertEquals(2 * MS, w.end());
    }
}
