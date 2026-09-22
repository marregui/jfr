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

    static RecordingInfo info(final String name, final long seconds) {
        return new RecordingInfo(Path.of(name), new Interval(0, seconds * S), 1, Map.of(), Map.of(), Set.of(), List.of());
    }

    static AllocationReport report(final String name, final long seconds, final Map<String, Long> byThread, final Map<String, Long> byClass,
                                   final Map<Stack, Long> bySite) {
        final long total = byThread.values().stream().mapToLong(Long::longValue).sum();
        return new AllocationReport(info(name, seconds), "jdk.ObjectAllocationSample", total, 100, 100, Map.of(), byThread, byClass,
                bySite, Map.of("worker", byClass), Map.of("worker", bySite), support(byThread, byClass, bySite));
    }

    /** One sample per key unless a test says otherwise: enough for the counts to be present. */
    static AllocationReport.Support support(final Map<String, Long> byThread, final Map<String, Long> byClass,
                                            final Map<Stack, Long> bySite) {
        final Map<String, Long> threads = new java.util.HashMap<>();
        byThread.forEach((k, _) -> threads.put(k, 1L));
        final Map<String, Long> classes = new java.util.HashMap<>();
        byClass.forEach((k, _) -> classes.put(k, 1L));
        final Map<Stack, Long> sites = new java.util.HashMap<>();
        bySite.forEach((k, _) -> sites.put(k, 1L));
        return new AllocationReport.Support(threads, classes, sites);
    }

    @Test
    void estimateIsMeasuredAgainstTheJvmCounters() {
        final AllocationReport none = report("a.jfr", 1, Map.of("worker", 1000L), Map.of("[B", 1000L), Map.of());
        assertFalse(none.hasCounters());
        assertEquals(0.0, none.estimateError(), 0.0);
        assertTrue(none.counted("worker").isEmpty());
        // Only the counted threads take part in the comparison: the short-lived one is not held against it.
        final AllocationReport some = new AllocationReport(info("a.jfr", 1), "jdk.ObjectAllocationSample", 1570, 100, 100,
                Map.of("worker", 1000L), Map.of("worker", 1070L, "short-lived", 500L), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        assertTrue(some.hasCounters());
        assertEquals(1000, some.countedBytes());
        assertEquals(1070, some.estimatedOnCountedThreads());
        assertEquals(0.07, some.estimateError(), 1e-9);
        assertTrue(some.estimateErrorMaterial());
        assertEquals(1000L, some.counted("worker").orElseThrow());
        assertTrue(some.counted("short-lived").isEmpty());
        // The +7 % is a statement about 68 % of the estimate: the short-lived thread has no counter.
        assertEquals(1070.0 / 1570.0, some.countedCoverage(), 1e-9);
        assertEquals(0.0, none.countedCoverage(), 0.0);
        // A counter on a thread that barely allocates says nothing about the estimate.
        final AllocationReport noise = new AllocationReport(info("a.jfr", 1), "jdk.ObjectAllocationSample", 1_000_000, 100, 100,
                Map.of("main", 20L), Map.of("main", 480L, "worker", 999_520L), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        assertTrue(noise.hasCounters());
        assertFalse(noise.estimateErrorMaterial());
    }

    @Test
    void everyPathThroughOneMethodIsOneRow() {
        // The same application method reached down three different library paths, and
        // allocating on two of its own lines: one logical site, which ranked one row per
        // path reads as three rows of a third the size.
        final AllocationReport r = report("a.jfr", 1, Map.of("worker", 600L), Map.of(),
                Map.of(parsePath("java.lang.String", "substring", 453), 300L,
                        parsePath("java.util.Arrays", "copyOfRange", 453), 200L,
                        parsePath("java.lang.String", "substring", 454), 100L));

        final List<AllocationReport.SiteRow> folded = r.sites(SiteKey.culpritMethod(), 10);
        assertEquals(1, folded.size());
        final AllocationReport.SiteRow row = folded.getFirst();
        assertEquals("org.lib.NodeId.parse", row.label());
        assertEquals(600, row.bytes());
        assertEquals(1.0, row.share(), 1e-9);
        assertEquals(3, row.stacks());
        // The support is the whole row's, not the representative stack's: one sample each
        // above, so a row that sums three stacks rests on three samples, not on one.
        assertEquals(3, row.samples());
        // The stack printed under the row is the biggest contributor, not an arbitrary one.
        assertEquals(300, r.bySite().get(row.stack()));
        // The raw map still keeps them apart, which is what --baseline matches on.
        assertEquals(3, r.sites(10).size());
    }

    @Test
    void appPrefixesAttributeToTheCallerThatOwnsThePath() {
        // Two library methods on the same application call site. Ranked by what allocated,
        // they are two rows in a library nobody here can change; ranked by --app they are
        // one row naming the line that called it.
        final Frame caller = new Frame("com.app.Node", "id", 99, "JIT compiled");
        final Stack parse = new Stack(List.of(new Frame("java.lang.String", "substring", 2904, "JIT compiled"),
                new Frame("org.lib.NodeId", "parse", 453, "JIT compiled"), caller), false);
        final Stack uint = new Stack(List.of(new Frame("org.lib.UInteger", "valueOf", 131, "JIT compiled"),
                new Frame("org.lib.NodeId", "parse", 459, "JIT compiled"), caller), false);
        final AllocationReport r = report("a.jfr", 1, Map.of("worker", 300L), Map.of(),
                Map.of(parse, 200L, uint, 100L));

        assertEquals(2, r.sites(SiteKey.culpritMethod(), 10).size());
        final List<AllocationReport.SiteRow> byApp = r.sites(SiteKey.inPackages(List.of("com.app")), 10);
        assertEquals(1, byApp.size());
        assertEquals("com.app.Node.id", byApp.getFirst().label());
        assertEquals(300, byApp.getFirst().bytes());
        assertEquals(2, byApp.getFirst().samples());
        // A stack that never enters the named packages keeps its own name rather than vanishing.
        final List<AllocationReport.SiteRow> noMatch = r.sites(SiteKey.inPackages(List.of("com.nothing")), 10);
        assertEquals(2, noMatch.size());
        assertEquals("org.lib.NodeId.parse", noMatch.getFirst().label());
    }

    @Test
    void packageRootsNameWhatAppCanBePointedAt() {
        final Stack app = new Stack(List.of(new Frame("com.app.Node", "id", 99, "JIT compiled")), false);
        final Stack lib = new Stack(List.of(new Frame("org.lib.NodeId", "parse", 453, "JIT compiled")), false);
        final Stack jdk = new Stack(List.of(new Frame("java.lang.String", "substring", 2904, "JIT compiled")), false);
        final AllocationReport r = report("a.jfr", 1, Map.of("worker", 600L), Map.of(),
                Map.of(app, 300L, lib, 200L, jdk, 100L));

        final List<AllocationReport.Row<String>> roots = r.packageRoots(10);
        // Ranked by bytes, and a stack that is JDK code all the way down names no package.
        assertEquals(List.of("com.app", "org.lib"), roots.stream().map(AllocationReport.Row::key).toList());
        assertEquals(300, roots.getFirst().bytes());
    }

    /** A stack allocating inside {@code org.lib.NodeId.parse}, reached through {@code through}. */
    private static Stack parsePath(final String type, final String method, final int line) {
        return new Stack(List.of(new Frame(type, method, 2904, "JIT compiled"),
                new Frame("org.lib.NodeId", "parse", line, "JIT compiled"),
                new Frame("com.app.Node", "id", 99, "JIT compiled")), false);
    }

    @Test
    void ranksAndShares() {
        final AllocationReport r = report("a.jfr", 10,
                Map.of("worker", 800L, "loop", 200L),
                Map.of("[B", 900L, "java.lang.Long", 100L),
                Map.of(SITE_A, 700L, SITE_B, 300L));
        assertEquals(1000, r.totalBytes());
        assertEquals(100.0, r.rate());
        assertEquals(10.0, r.seconds());
        final List<AllocationReport.Row<String>> threads = r.threads(10);
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
        final AllocationReport r = report("z.jfr", 0, Map.of("t", 10L), Map.of(), Map.of());
        assertTrue(r.rate() > 0);
        final AllocationReport empty = report("e.jfr", 5, Map.of(), Map.of(), Map.of());
        assertEquals(0, empty.totalBytes());
        assertTrue(empty.threads(5).isEmpty());
    }

    @Test
    void diffComparesRatesNotTotals() {
        final AllocationReport before = report("before.jfr", 10,
                Map.of("worker", 1000L, "gone", 500L),
                Map.of("[B", 1500L),
                Map.of(SITE_A, 1500L));
        final AllocationReport after = report("after.jfr", 20,
                Map.of("worker", 1000L, "new", 400L),
                Map.of("[B", 1000L, "java.lang.Long", 400L),
                Map.of(SITE_A, 1000L, SITE_B, 400L));
        final AllocationDiff diff = new AllocationDiff(before, after);

        final AllocationDiff.Delta<String> total = diff.total();
        assertEquals(150.0, total.beforeRate(), 1e-9);
        assertEquals(70.0, total.afterRate(), 1e-9);
        assertEquals(-80.0, total.delta(), 1e-9);
        assertEquals(-80.0 / 150.0, total.ratio(), 1e-9);

        final List<AllocationDiff.Delta<String>> threads = diff.threads(10);
        assertEquals(3, threads.size());
        // Sorted by absolute change: worker 100 -> 50 (-50), gone 50 -> 0 (-50), new 0 -> 20 (+20).
        assertEquals(20.0, threads.get(2).delta(), 1e-9);
        assertEquals("new", threads.get(2).key());
        assertEquals(Double.POSITIVE_INFINITY, threads.get(2).ratio());
        final AllocationDiff.Delta<String> gone = threads.stream().filter(d -> d.key().equals("gone")).findFirst().orElseThrow();
        assertEquals(-1.0, gone.ratio(), 1e-9);
        assertEquals(2, diff.threads(2).size());

        assertEquals("[B", diff.classes(10).getFirst().key());
        assertEquals(SITE_A, diff.sites(10).getFirst().key());
        assertEquals(0.0, new AllocationDiff.Delta<>("x", 0.0, 0.0).ratio());
        assertEquals(before, diff.baseline());
        assertEquals(after, diff.current());
    }
}
