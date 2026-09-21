// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.stalls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall.Evidence;
import dev.jfrq.core.stalls.Stall.Verdict;
import dev.jfrq.core.stalls.Timeline.Block;
import dev.jfrq.core.stalls.Timeline.BlockKind;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.stalls.Timeline.ThreadTimeline;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link StallAnalysis} with hand-built timelines. Times are in milliseconds in the
 * helpers and converted to nanoseconds, the sampler period is 10 ms and the gap 50 ms
 * unless a test says otherwise.
 */
class StallAnalysisTest {

    static final long MS = 1_000_000L;
    static final ThreadRef LOOP = new ThreadRef(1, "event-loop-1");
    static final ThreadRef OTHER = new ThreadRef(2, "event-loop-2");
    static final ThreadRef HOLDER = new ThreadRef(3, "housekeeper");

    static final Stack IDLE = stack(new Frame("sun.nio.ch.KQueue", "poll", 0, "Native"),
            new Frame("sun.nio.ch.KQueueSelectorImpl", "doSelect", 100, "JIT compiled"));
    static final Stack BURN = stack(new Frame("dev.app.CpuWork", "burn", 14, "JIT compiled"),
            new Frame("dev.app.Handler", "channelRead0", 59, "JIT compiled"));
    static final Stack READ0 = stack(new Frame("sun.nio.ch.SocketDispatcher", "read0", 0, "Native"),
            new Frame("dev.app.Handler", "lookup", 80, "JIT compiled"));

    static Stack stack(final Frame... frames) {
        return new Stack(List.of(frames), false);
    }

    static LongList longs(final long... values) {
        final LongList list = new LongList(values.length);
        for (final long v : values) {
            list.add(v);
        }
        return list;
    }

    static RecordingInfo info(final long spanMillis, final Map<String, Map<String, String>> settings, final String... presentTypes) {
        final Map<String, Long> counts = new java.util.HashMap<>();
        for (final String t : presentTypes) {
            counts.put(t, 1L);
        }
        return new RecordingInfo(Path.of("test.jfr"), new Interval(0, spanMillis * MS), 1, counts, settings, Set.of(), List.of());
    }

    static RecordingInfo sampledInfo() {
        return info(10_000, Map.of("jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms")),
                "jdk.ExecutionSample", "jdk.NativeMethodSample");
    }

    /** Idle samples every {@code step} ms over [from, to). */
    static List<Sample> idle(final long from, final long to, final long step) {
        final List<Sample> out = new ArrayList<>();
        for (long t = from; t < to; t += step) {
            out.add(new Sample(t * MS, IDLE, true, true));
        }
        return out;
    }

    static List<Sample> busy(final long from, final long to, final long step, final Stack stack, final boolean inNative) {
        final List<Sample> out = new ArrayList<>();
        for (long t = from; t < to; t += step) {
            out.add(new Sample(t * MS, stack, false, inNative));
        }
        return out;
    }

    static Block block(final long fromMs, final long toMs, final BlockKind kind, final String detail, final ThreadRef owner) {
        return new Block(new Interval(fromMs * MS, toMs * MS), kind, detail, Stack.EMPTY, owner);
    }

    static StallReport analyse(final List<Sample> samples, final List<Block> blocks, final List<Pause> pauses) {
        final List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort(java.util.Comparator.comparingLong(Sample::time));
        return new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, sorted, blocks)), pauses);
    }

    @Test
    void busyRunOfSamplesIsAStallNamedAfterTheDominantCulprit() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(busy(200, 320, 10, BURN, false));
        samples.addAll(idle(320, 500, 10));
        final StallReport r = analyse(samples, List.of(), List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BUSY, s.verdict());
        assertEquals(Evidence.SAMPLES, s.evidence());
        assertEquals(12, s.samples());
        assertEquals(200 * MS, s.start());
        // Ends at the next observation, which was the idle sample at 320 ms.
        assertEquals(120 * MS, s.duration());
        assertTrue(s.detail().contains("dev.app.CpuWork.burn"), s.detail());
        assertTrue(s.detail().contains("100% of 12 samples"), s.detail());
        assertEquals(BURN, s.stack());
        assertEquals(1, r.threads().size());
        assertEquals(120 * MS, r.threads().getFirst().stalledNanos());
        assertEquals(120 * MS, r.threads().getFirst().worstNanos());
        assertEquals(10 * MS, r.threads().getFirst().javaCadenceNanos());
    }

    @Test
    void shortBusyRunIsBelowTheGap() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(busy(200, 230, 10, BURN, false)); // 3 samples, ~40 ms
        samples.addAll(idle(230, 400, 10));
        assertTrue(analyse(samples, List.of(), List.of()).stalls().isEmpty());
    }

    @Test
    void aSingleSampleIsNeverARun() {
        // With a 10 ms gap one sample spans exactly one period, which would pass the length test.
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.add(new Sample(100 * MS, BURN, false, false));
        samples.addAll(idle(110, 200, 10));
        final StallReport r = new StallAnalysis(10 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, samples, List.of())), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
    }

    @Test
    void sparseNonIdleSamplesDoNotChainIntoARun() {
        // Non-idle samples 60 ms apart: nothing proves the thread was busy between them.
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 400, 60, BURN, false));
        samples.addAll(idle(400, 500, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
    }

    @Test
    void busyRunWithoutADominantCulpritIsSaturatedOnlyWithEnoughSamples() {
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        for (int i = 0; i < 8; i++) {
            final Stack distinct = stack(new Frame("dev.app.Task" + i, "run", 1, "JIT compiled"));
            samples.add(new Sample((100 + i * 10) * MS, distinct, false, false));
        }
        samples.addAll(idle(180, 300, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        assertEquals(1, r.stalls().size());
        assertEquals(Verdict.SATURATED, r.stalls().getFirst().verdict());
        assertTrue(r.stalls().getFirst().detail().contains("8 distinct culprits"));

        // Four distinct samples over 60 ms: too little evidence to call it saturated.
        final List<Sample> few = new ArrayList<>(idle(0, 100, 10));
        for (int i = 0; i < 4; i++) {
            final Stack distinct = stack(new Frame("dev.app.Task" + i, "run", 1, "JIT compiled"));
            few.add(new Sample((100 + i * 20) * MS, distinct, false, false));
        }
        few.addAll(idle(180, 300, 10));
        assertTrue(analyse(few, List.of(), List.of()).stalls().isEmpty());
    }

    @Test
    void blockingEventLongerThanTheGapIsAStallAndSwallowsTheSilence() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(500, 700, 10)); // silent for 300 ms
        final Block monitor = block(205, 495, BlockKind.MONITOR, "dev.app.Registry@1", HOLDER);
        final StallReport r = analyse(samples, List.of(monitor), List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BLOCKED_MONITOR, s.verdict());
        assertEquals(Evidence.EVENT, s.evidence());
        assertEquals(monitor.interval(), s.interval());
        assertEquals("blocked on monitor dev.app.Registry@1 held by housekeeper", s.detail());
    }

    @Test
    void silenceExplainedByShortBlocksThatCoverMostOfIt() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(400, 600, 10)); // 200 ms silence
        final List<Block> sleeps = List.of(
                block(210, 250, BlockKind.SLEEP, "", null),
                block(260, 300, BlockKind.SLEEP, "", null),
                block(310, 350, BlockKind.SLEEP, "", null)); // 120 of 200 ms
        final StallReport r = analyse(samples, sleeps, List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.SLEEP, s.verdict());
        assertEquals(Evidence.SILENCE, s.evidence());
        assertEquals("3 × Thread.sleep", s.detail());
        assertEquals(new Interval(190 * MS, 400 * MS), s.interval());
    }

    @Test
    void silenceExplainedByAGcPause() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(400, 600, 10));
        final Pause gc = new Pause(new Interval(220 * MS, 380 * MS), PauseKind.GC, "G1 Young (gcId 4)");
        final StallReport r = analyse(samples, List.of(), List.of(gc));

        assertEquals(1, r.stalls().size());
        assertEquals(Verdict.GC_PAUSE, r.stalls().getFirst().verdict());
        assertEquals("GC pause: G1 Young (gcId 4)", r.stalls().getFirst().detail());
        assertEquals(List.of(gc), r.pauses());
        assertFalse(r.stalls().getFirst().verdict().isThreadLocal());
    }

    @Test
    void silenceExplainedByASafepointWhenNoGcCoversIt() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(400, 600, 10));
        final Pause gc = new Pause(new Interval(220 * MS, 240 * MS), PauseKind.GC, "small");
        final Pause sp = new Pause(new Interval(240 * MS, 390 * MS), PauseKind.SAFEPOINT, "safepoint 9");
        final StallReport r = analyse(samples, List.of(), List.of(gc, sp));
        assertEquals(Verdict.SAFEPOINT, r.stalls().getFirst().verdict());
        assertEquals(List.of(sp), r.pauses()); // only pauses >= gap are listed
    }

    @Test
    void unexplainedSilenceIsReportedAsSuch() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(600, 800, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.UNEXPLAINED, s.verdict());
        assertEquals(Evidence.SILENCE, s.evidence());
        assertEquals(Stack.EMPTY, s.stack());
        assertFalse(s.detail().contains("simultaneous"));
    }

    @Test
    void routineSamplingGapsAreNotSilence() {
        // Native samples routinely 100 ms apart: 250 ms is not evidence, 400 ms is.
        final List<Sample> samples = new ArrayList<>(idle(0, 1000, 100));
        samples.addAll(idle(1150, 2000, 100));
        assertTrue(analyse(samples, List.of(), List.of()).stalls().isEmpty());

        final List<Sample> longer = new ArrayList<>(idle(0, 1000, 100));
        longer.addAll(idle(1400, 2000, 100));
        final StallReport r = analyse(longer, List.of(), List.of());
        assertEquals(1, r.stalls().size());
        assertEquals(new Interval(900 * MS, 1400 * MS), r.stalls().getFirst().interval());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("routinely up to 100 ms apart")), r.warnings().toString());
    }

    @Test
    void busyRunCoveredByIoEventsIsBlockingIo() {
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 200, 10, READ0, true));
        samples.addAll(idle(200, 300, 10));
        final List<Block> reads = List.of(
                block(100, 130, BlockKind.SOCKET_READ, "from db:5432 (12 bytes)", null),
                block(140, 170, BlockKind.SOCKET_READ, "from db:5432 (12 bytes)", null));
        final StallReport r = analyse(samples, reads, List.of());
        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BLOCKING_IO, s.verdict());
        assertEquals(Evidence.SAMPLES, s.evidence());
        assertEquals("2 × blocking socket read from db:5432 (12 bytes)", s.detail());
    }

    @Test
    void busyRunMostlyInNativeCodeSaysSo() {
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 200, 10, READ0, true));
        samples.addAll(idle(200, 300, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BUSY, s.verdict());
        assertTrue(s.detail().contains("dev.app.Handler.lookup"));
        assertTrue(s.detail().endsWith("[mostly in native code]"));
    }

    @Test
    void simultaneousUnexplainedSilencesAreMarked() {
        final List<Sample> a = new ArrayList<>(idle(0, 200, 10));
        a.addAll(idle(600, 800, 10));
        final List<Sample> b = new ArrayList<>(idle(0, 210, 10));
        b.addAll(idle(590, 800, 10));
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, a, List.of()), new ThreadTimeline(OTHER, b, List.of())), List.of());
        assertEquals(2, r.stalls().size());
        for (final Stall s : r.stalls()) {
            assertEquals(Verdict.UNEXPLAINED, s.verdict());
            assertTrue(s.detail().contains("simultaneous on 2 watched threads"), s.detail());
        }
        assertEquals(List.of("event-loop-1", "event-loop-2"),
                r.threads().stream().map(t -> t.thread().name()).toList());
    }

    @Test
    void eventStallsAreFoundWithoutAnySamples() {
        final Block sleep = block(100, 400, BlockKind.SLEEP, "", null);
        final StallReport r = new StallAnalysis(50 * MS).analyse(info(1000, Map.of()),
                List.of(new ThreadTimeline(LOOP, List.of(), List.of(sleep))), List.of());
        assertEquals(1, r.stalls().size());
        assertEquals(Verdict.SLEEP, r.stalls().getFirst().verdict());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("no sampler events")));
        assertEquals(0, r.threads().getFirst().javaCadenceNanos());
    }

    @Test
    void thresholdAboveGapIsWarnedAbout() {
        final RecordingInfo info = info(1000, Map.of(
                "jdk.JavaMonitorEnter", Map.of("enabled", "true", "threshold", "100 ms"),
                "jdk.ThreadPark", Map.of("enabled", "false", "threshold", "100 ms"),
                "jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms")), "jdk.ExecutionSample");
        final StallReport r = new StallAnalysis(50 * MS).analyse(info, List.of(), List.of());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().getFirst().startsWith("jdk.JavaMonitorEnter threshold 100 ms exceeds gap 50.0 ms"));
    }

    @Test
    void reportOrdersStallsLongestFirstAndSummarisesByVerdict() {
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 220, 10, BURN, false));
        samples.addAll(idle(220, 400, 10));
        final Block sleep = block(500, 900, BlockKind.SLEEP, "", null);
        final StallReport r = analyse(samples, List.of(sleep), List.of());
        assertEquals(2, r.stalls().size());
        assertEquals(Verdict.SLEEP, r.stalls().getFirst().verdict());
        assertEquals(Verdict.BUSY, r.stalls().get(1).verdict());
        assertEquals(1, r.top(1).size());
        assertEquals(2, r.stallsOf(LOOP).size());
        assertEquals(Verdict.BUSY, r.stallsOf(LOOP).getFirst().verdict()); // time order
        final List<StallReport.VerdictSummary> byVerdict = r.byVerdict();
        assertEquals(Verdict.SLEEP, byVerdict.getFirst().verdict());
        assertEquals(400 * MS, byVerdict.getFirst().totalNanos());
        assertEquals(1, byVerdict.get(1).count());
        assertEquals(50 * MS, r.gapNanos());
    }

    @Test
    void describeIncludesTheHandOverChain() {
        final Block b = new Block(new Interval(0, MS), BlockKind.MONITOR, "Registry@1", Stack.EMPTY, HOLDER,
                List.of(OTHER, LOOP), 0);
        assertEquals("blocked on monitor Registry@1 held by housekeeper (handed on through event-loop-2, event-loop-1)",
                StallAnalysis.describe(b));
        assertEquals("blocked on monitor X held by unknown",
                StallAnalysis.describe(block(0, 1, BlockKind.MONITOR, "X", null)));
        assertEquals("blocking file read /tmp/x", StallAnalysis.describe(block(0, 1, BlockKind.FILE_READ, "/tmp/x", null)));
        assertEquals(Verdict.PARKED, StallAnalysis.verdictOf(BlockKind.PARK));
        assertEquals(Verdict.OBJECT_WAIT, StallAnalysis.verdictOf(BlockKind.OBJECT_WAIT));
        assertEquals(Verdict.BLOCKING_IO, StallAnalysis.verdictOf(BlockKind.FILE_FORCE));
        assertTrue(BlockKind.FILE_WRITE.isIo());
        assertFalse(BlockKind.MONITOR.isIo());
    }

    @Test
    void rejectsNonPositiveGap() {
        assertThrows(IllegalArgumentException.class, () -> new StallAnalysis(0));
        assertEquals(50 * MS, new StallAnalysis(50 * MS).gap());
    }

    @Test
    void cadenceStatistics() {
        final StallAnalysis.Cadence c = StallAnalysis.Cadence.of(List.of(), 10 * MS);
        assertEquals(0, c.java());
        assertEquals(10 * MS, c.period());
        assertEquals(0, StallAnalysis.Cadence.percentile(new LongList(), 0.9));
        assertEquals(20L, StallAnalysis.Cadence.percentile(longs(10L, 20L, 30L), 0.5));
        assertEquals(30L, StallAnalysis.Cadence.percentile(longs(10L, 20L, 30L), 0.9));
        final StallAnalysis.Cadence mixed = StallAnalysis.Cadence.of(List.of(
                new Sample(0, BURN, false, false), new Sample(10 * MS, BURN, false, false),
                new Sample(110 * MS, IDLE, true, true), new Sample(210 * MS, IDLE, true, true)), 0);
        assertEquals(10 * MS, mixed.java());
        assertEquals(100 * MS, mixed.inNative());
        assertEquals(10 * MS, mixed.period());
    }

    /**
     * The cadence threshold decides whether an unexplained silence is evidence; a silence a
     * pause or a group of blocks explains is reported whatever the cadence, as the warning
     * text promises ("only ones a blocking event or a JVM pause explains").
     */
    @Test
    void anExplainedSilenceIsReportedBelowTheCadenceThreshold() {
        // Samples every 100 ms: routine absence 100 ms, so an unexplained silence needs 300 ms.
        final List<Sample> samples = new ArrayList<>(idle(0, 1000, 100));
        samples.addAll(idle(1150, 2000, 100));
        final Pause gc = new Pause(new Interval(1000 * MS, 1140 * MS), PauseKind.GC, "G1 Young (gcId 7)");
        final StallReport r = analyse(samples, List.of(), List.of(gc));

        assertEquals(1, r.stalls().size(), r.stalls().toString());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.GC_PAUSE, s.verdict());
        assertEquals(Evidence.SILENCE, s.evidence());
        assertEquals(new Interval(900 * MS, 1150 * MS), s.interval());
        // The same silence with nothing to explain it is below the threshold: not reported.
        assertTrue(analyse(samples, List.of(), List.of()).stalls().isEmpty());
        // Below the threshold the explanation must cover a whole gap by itself: a 40 ms pause
        // is half of an 80 ms silence but not a stall on its own.
        final List<Sample> shortGap = new ArrayList<>(idle(0, 1000, 100));
        shortGap.addAll(idle(1080, 2000, 100));
        final Pause brief = new Pause(new Interval(1000 * MS, 1040 * MS), PauseKind.GC, "G1 Young (gcId 8)");
        assertTrue(analyse(shortGap, List.of(), List.of(brief)).stalls().isEmpty());
        // And a 400 ms unexplained silence is.
        final List<Sample> longer = new ArrayList<>(idle(0, 1000, 100));
        longer.addAll(idle(1400, 2000, 100));
        final StallReport u = analyse(longer, List.of(), List.of());
        assertEquals(1, u.stalls().size());
        assertEquals(Verdict.UNEXPLAINED, u.stalls().getFirst().verdict());
    }

    @Test
    void manyShortReadsFromOnePeerExplainASilenceTogether() {
        // The loop vanishes for 300 ms; the file holds twelve 20 ms socket reads from one
        // backend, each with a different byte count. They must add up to one answer.
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(500, 700, 10));
        final List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final long from = 200 + i * 25L;
            blocks.add(new Block(new Interval(from * MS, (from + 20) * MS), BlockKind.SOCKET_READ,
                    "from backend:9000", READ0, 100L + i));
        }
        final StallReport r = analyse(samples, blocks, List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BLOCKING_IO, s.verdict());
        assertEquals(Evidence.SILENCE, s.evidence());
        assertEquals("12 × blocking socket read from backend:9000 (1.27 KB)", s.detail());
        assertEquals(READ0, s.stack());
        // One read alone keeps its own byte count.
        assertEquals("blocking socket read from backend:9000 (100 B)", StallAnalysis.describe(blocks.getFirst()));
    }

    @Test
    void throttledBlockingEventsAreAWarning() {
        final RecordingInfo info = info(10_000, Map.of(
                "jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms"),
                "jdk.SocketRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "300/s"),
                "jdk.FileRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "off"),
                "jdk.SocketWrite", Map.of("enabled", "false", "threshold", "1 ms", "throttle", "300/s")),
                "jdk.ExecutionSample");
        final StallReport r = new StallAnalysis(50 * MS).analyse(info, List.of(new ThreadTimeline(LOOP, idle(0, 100, 10),
                List.of())), List.of());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        final String w = r.warnings().getFirst();
        assertTrue(w.startsWith("throttled events (jdk.SocketRead 300/s)"), w);
        assertFalse(w.contains("FileRead") || w.contains("SocketWrite"), w);
    }
}
