// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import org.junit.jupiter.api.Test;

class AllocationTest {

    static final long S = 1_000_000_000L;
    static final Stack SITE_A = new Stack(List.of(new Frame("dev.app.A", "alloc", 1, "JIT compiled")), false);
    static final Stack SITE_B = new Stack(List.of(new Frame("dev.app.B", "alloc", 2, "JIT compiled")), false);

    static RecordingInfo info(String name, long seconds) {
        return new RecordingInfo(Path.of(name), new Interval(0, seconds * S), 1, Map.of(), Map.of(), Set.of(), List.of());
    }

    static AllocationReport report(String name, long seconds, Map<String, Long> byThread, Map<String, Long> byClass,
                                   Map<Stack, Long> bySite) {
        long total = byThread.values().stream().mapToLong(Long::longValue).sum();
        return new AllocationReport(info(name, seconds), "jdk.ObjectAllocationSample", total, 100, 100, Map.of(), byThread, byClass,
                bySite, Map.of("worker", byClass), Map.of("worker", bySite));
    }

    @Test
    void estimateIsMeasuredAgainstTheJvmCounters() {
        AllocationReport none = report("a.jfr", 1, Map.of("worker", 1000L), Map.of("[B", 1000L), Map.of());
        assertFalse(none.hasCounters());
        assertEquals(0.0, none.estimateError(), 0.0);
        assertTrue(none.counted("worker").isEmpty());
        // Only the counted threads take part in the comparison: the short-lived one is not held against it.
        AllocationReport some = new AllocationReport(info("a.jfr", 1), "jdk.ObjectAllocationSample", 1570, 100, 100,
                Map.of("worker", 1000L), Map.of("worker", 1070L, "short-lived", 500L), Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(some.hasCounters());
        assertEquals(1000, some.countedBytes());
        assertEquals(1070, some.estimatedOnCountedThreads());
        assertEquals(0.07, some.estimateError(), 1e-9);
        assertTrue(some.estimateErrorMaterial());
        assertEquals(1000L, some.counted("worker").orElseThrow());
        assertTrue(some.counted("short-lived").isEmpty());
        // A counter on a thread that barely allocates says nothing about the estimate.
        AllocationReport noise = new AllocationReport(info("a.jfr", 1), "jdk.ObjectAllocationSample", 1_000_000, 100, 100,
                Map.of("main", 20L), Map.of("main", 480L, "worker", 999_520L), Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(noise.hasCounters());
        assertFalse(noise.estimateErrorMaterial());
    }

    @Test
    void ranksAndShares() {
        AllocationReport r = report("a.jfr", 10,
                Map.of("worker", 800L, "loop", 200L),
                Map.of("[B", 900L, "java.lang.Long", 100L),
                Map.of(SITE_A, 700L, SITE_B, 300L));
        assertEquals(1000, r.totalBytes());
        assertEquals(100.0, r.rate());
        assertEquals(10.0, r.seconds());
        List<AllocationReport.Row<String>> threads = r.threads(10);
        assertEquals("worker", threads.getFirst().key());
        assertEquals(0.8, threads.getFirst().share(), 1e-9);
        assertEquals(800, threads.getFirst().bytes());
        assertEquals(1, r.threads(1).size());
        assertEquals("[B", r.classes(10).getFirst().key());
        assertEquals(SITE_A, r.sites(10).getFirst().key());
        assertEquals(SITE_A, r.sitesOf("worker", 1).getFirst().key());
        assertEquals("[B", r.classesOf("worker", 1).getFirst().key());
        assertTrue(r.classesOf("nobody", 5).isEmpty());
        assertTrue(r.sitesOf("nobody", 5).isEmpty());
    }

    @Test
    void zeroLengthRecordingDoesNotDivideByZero() {
        AllocationReport r = report("z.jfr", 0, Map.of("t", 10L), Map.of(), Map.of());
        assertTrue(r.rate() > 0);
        AllocationReport empty = report("e.jfr", 5, Map.of(), Map.of(), Map.of());
        assertEquals(0, empty.totalBytes());
        assertTrue(empty.threads(5).isEmpty());
    }

    @Test
    void diffComparesRatesNotTotals() {
        AllocationReport before = report("before.jfr", 10,
                Map.of("worker", 1000L, "gone", 500L),
                Map.of("[B", 1500L),
                Map.of(SITE_A, 1500L));
        AllocationReport after = report("after.jfr", 20,
                Map.of("worker", 1000L, "new", 400L),
                Map.of("[B", 1000L, "java.lang.Long", 400L),
                Map.of(SITE_A, 1000L, SITE_B, 400L));
        AllocationDiff diff = new AllocationDiff(before, after);

        AllocationDiff.Delta<String> total = diff.total();
        assertEquals(150.0, total.beforeRate(), 1e-9);
        assertEquals(70.0, total.afterRate(), 1e-9);
        assertEquals(-80.0, total.delta(), 1e-9);
        assertEquals(-80.0 / 150.0, total.ratio(), 1e-9);

        List<AllocationDiff.Delta<String>> threads = diff.threads(10);
        assertEquals(3, threads.size());
        // Sorted by absolute change: worker 100 -> 50 (-50), gone 50 -> 0 (-50), new 0 -> 20 (+20).
        assertEquals(20.0, threads.get(2).delta(), 1e-9);
        assertEquals("new", threads.get(2).key());
        assertEquals(Double.POSITIVE_INFINITY, threads.get(2).ratio());
        AllocationDiff.Delta<String> gone = threads.stream().filter(d -> d.key().equals("gone")).findFirst().orElseThrow();
        assertEquals(-1.0, gone.ratio(), 1e-9);
        assertEquals(2, diff.threads(2).size());

        assertEquals("[B", diff.classes(10).getFirst().key());
        assertEquals(SITE_A, diff.sites(10).getFirst().key());
        assertEquals(0.0, new AllocationDiff.Delta<>("x", 0.0, 0.0).ratio());
        assertEquals(before, diff.baseline());
        assertEquals(after, diff.current());
    }
}
