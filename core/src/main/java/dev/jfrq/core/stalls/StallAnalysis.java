// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.jfrq.core.coll.IdentityObjObjHashMap;
import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.stalls.Stall.Evidence;
import dev.jfrq.core.stalls.Stall.Verdict;
import dev.jfrq.core.stalls.StallReport.ThreadSummary;
import dev.jfrq.core.stalls.Timeline.Block;
import dev.jfrq.core.stalls.Timeline.BlockKind;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.stalls.Timeline.ThreadTimeline;
import dev.jfrq.core.util.Bytes;
import dev.jfrq.core.util.Durations;
import dev.jfrq.core.util.Sorted;

/**
 * Finds stalls in per-thread timelines. Pure logic over {@link Timeline} values, so it is
 * testable without a recording.
 *
 * <p>A stall is an interval of at least the configured gap during which the thread did
 * not return to its idle point. Three detectors find candidates:
 * <ol>
 *   <li><b>Events.</b> A blocking event (monitor, park, sleep, socket, file) at least the
 *       gap long is a stall on its own, with the event as the explanation.</li>
 *   <li><b>Sample runs.</b> Consecutive non-idle samples, each within a few sampler
 *       periods of the previous one, spanning at least the gap: the thread kept executing
 *       without reaching the selector. The explanation is the dominant application frame
 *       across the run, or "saturated" when there is none. Samples further apart than a
 *       few periods do not chain, because nothing proves the thread was busy in between.</li>
 *   <li><b>Silence.</b> Consecutive samples further apart than the thread's own sampling
 *       cadence allows: the thread was in a state the sampler cannot see (blocked, in the
 *       VM, at a safepoint). Explained by whichever blocking event or JVM pause covers most
 *       of the silence, otherwise reported as unexplained.</li>
 * </ol>
 *
 * <p>Sampling cadence is measured, not assumed. The JFR sampler visits at most five
 * threads executing Java and one thread in native code per period, round-robin, so a
 * thread's effective cadence depends on how many threads compete for each slot. Threads
 * sitting in a selector are in native code and share the single native slot with every
 * thread blocked in a socket read, which is why native and Java cadences are tracked
 * separately per thread.
 *
 * <p>One instance analyses one recording at a time: the scratch collections it reuses
 * between candidates are not shared between threads.
 */
public final class StallAnalysis {

    /** A silence must exceed this many cadences to count as evidence. */
    static final int CADENCE_FACTOR = 3;
    /** Non-idle samples chain into a run only when closer than this many sampler periods. */
    static final int RUN_FACTOR = 3;
    /** An explanation must cover this share of a candidate interval. */
    static final double COVER = 0.5;
    /** A single culprit must own this share of a busy run to be named. */
    static final double DOMINANT = 0.5;
    /** A run needs at least this many samples before it can be a stall at all. */
    static final int RUN_MIN_SAMPLES = 2;
    /** A "saturated" verdict (no dominant culprit) needs at least this many samples. */
    static final int SATURATED_MIN_SAMPLES = 5;
    /** How many per-thread cadence warnings are spelled out before the rest are counted. */
    private static final int CADENCE_WARNINGS_SHOWN = 3;

    private static final int[] THRESHOLDED_BLOCK_EVENTS = {
            EventKinds.JAVA_MONITOR_ENTER, EventKinds.THREAD_PARK, EventKinds.JAVA_MONITOR_WAIT,
            EventKinds.THREAD_SLEEP, EventKinds.SOCKET_READ, EventKinds.SOCKET_WRITE, EventKinds.FILE_READ,
            EventKinds.FILE_WRITE, EventKinds.FILE_FORCE};
    private static final int[] SAMPLER_EVENTS = {EventKinds.EXECUTION_SAMPLE, EventKinds.NATIVE_METHOD_SAMPLE};

    private static final Comparator<Pause> PAUSE_BY_INTERVAL = Comparator.comparing(Pause::interval);
    private static final Comparator<ThreadTimeline> BY_THREAD_NAME = Comparator.comparing(t -> t.thread().name());
    private static final Comparator<Stall> BY_START = Comparator.comparingLong(Stall::start);

    private final long gap;
    /** Which parks are a worker with nothing to do rather than a wait someone is paying for. */
    private final IdleMatcher workWaits;
    /** A culprit's qualified name, built once per distinct frame (G-2.3). */
    private final ObjObjHashMap<Frame, String> culpritNames = new ObjObjHashMap<>(1024);
    /** Scratch for {@link #busy}: culprit counts in first-seen order, and a stack per culprit. */
    private final ObjList<Culprit> culprits = new ObjList<>();
    private final ObjObjHashMap<String, Culprit> culpritByName = new ObjObjHashMap<>(64);
    /** Event stalls cut down to the recording's span in the current analysis; reset per {@link #analyse}. */
    private int clippedStalls;
    /** Blocks left out as "waiting for work" in the current analysis, and their total. */
    private int workWaitCount;
    private long workWaitNanos;

    /** Whether a block is a worker parked on its own empty queue rather than a wait that costs someone. */
    private boolean isWaitingForWork(final Block b) {
        return isWaitingForWork(verdictOf(b.kind()), b.stack());
    }

    /** The same question from a candidate's explanation, which carries the representative stack. */
    private boolean isWaitingForWork(final Verdict verdict, final Stack stack) {
        return (verdict == Verdict.PARKED || verdict == Verdict.OBJECT_WAIT) && workWaits.isIdle(stack);
    }

    public StallAnalysis(final long gapNanos) {
        this(gapNanos, IdleMatcher.forWorkWaits());
    }

    public StallAnalysis(final long gapNanos, final IdleMatcher workWaits) {
        if (gapNanos <= 0) {
            throw new IllegalArgumentException("gap must be positive");
        }
        this.gap = gapNanos;
        this.workWaits = workWaits;
    }

    public long gap() {
        return gap;
    }

    public StallReport analyse(final RecordingInfo info, final List<ThreadTimeline> timelines, final List<Pause> pauses) {
        final List<String> warnings = new ArrayList<>();
        warnRecording(info, warnings);
        final long period = samplerPeriod(info);
        clippedStalls = 0;
        workWaitCount = 0;
        workWaitNanos = 0;

        final ObjList<Pause> sortedPauses = new ObjList<>(pauses.size());
        for (int i = 0, n = pauses.size(); i < n; i++) {
            sortedPauses.add(pauses.get(i));
        }
        sortedPauses.sort(PAUSE_BY_INTERVAL);
        final ObjList<Pause> longPauses = new ObjList<>();
        for (int i = 0, n = sortedPauses.size(); i < n; i++) {
            final Pause p = sortedPauses.getQuick(i);
            if (p.length() >= gap) {
                longPauses.add(p);
            }
        }

        final ObjList<ThreadTimeline> ordered = new ObjList<>(timelines.size());
        for (int i = 0, n = timelines.size(); i < n; i++) {
            ordered.add(timelines.get(i));
        }
        ordered.sort(BY_THREAD_NAME);
        final ObjList<Stall> stalls = new ObjList<>();
        final ObjList<ThreadSummary> summaries = new ObjList<>(ordered.size());
        final ObjList<String> cadenceWarnings = new ObjList<>();
        final Windows windows = new Windows(sortedPauses);
        for (int i = 0, n = ordered.size(); i < n; i++) {
            final ThreadTimeline tl = ordered.getQuick(i);
            final Cadence cadence = Cadence.of(tl.samples(), period);
            final int before = stalls.size();
            analyseThread(tl, cadence, windows, stalls, info.span());
            long stalled = 0;
            long worst = 0;
            for (int s = before, m = stalls.size(); s < m; s++) {
                final long duration = stalls.getQuick(s).duration();
                stalled += duration;
                worst = Math.max(worst, duration);
            }
            summaries.add(new ThreadSummary(tl.thread(), tl.samples().size(), cadence.java, cadence.inNative,
                    stalls.size() - before, stalled, worst));
            final long absence = cadence.routineAbsence();
            if (absence > 0 && absence * CADENCE_FACTOR > gap) {
                cadenceWarnings.add(String.format(Locale.ROOT,
                        "%s: samples routinely up to %s apart; unexplained silences shorter than ~%s "
                                + "cannot be seen, only ones a blocking event or a JVM pause explains",
                        tl.thread().name(), Durations.format(absence),
                        Durations.format(absence * CADENCE_FACTOR)));
            }
        }
        if (workWaitCount > 0) {
            warnings.add(workWaitCount + (workWaitCount == 1 ? " park totalling " : " parks totalling ")
                    + Durations.format(workWaitNanos) + " were workers waiting for their own queue and are not "
                    + "stalls; --idle replaces the patterns that decide this");
        }
        if (clippedStalls > 0) {
            warnings.add(clippedStalls == 1
                    ? "1 stall extends beyond the recording's span and is counted only for the part inside it"
                    : clippedStalls + " stalls extend beyond the recording's span and are counted only for the "
                            + "part inside it");
        }
        final int shown = Math.min(cadenceWarnings.size(), CADENCE_WARNINGS_SHOWN);
        for (int i = 0; i < shown; i++) {
            warnings.add(cadenceWarnings.getQuick(i));
        }
        if (cadenceWarnings.size() > shown) {
            warnings.add((cadenceWarnings.size() - shown) + " more threads with coarse sampling cadence");
        }
        return new StallReport(info, gap, summaries.toList(), markSimultaneous(stalls.toList()), longPauses.toList(),
                warnings);
    }

    /**
     * An unexplained silence that several watched threads share at the same moment is
     * far more likely to be the sampler not running, or a pause the recording did not
     * capture, than each thread independently blocking; say so in the detail.
     */
    static List<Stall> markSimultaneous(final List<Stall> stalls) {
        final ObjList<Stall> unexplained = new ObjList<>();
        for (int i = 0, n = stalls.size(); i < n; i++) {
            final Stall s = stalls.get(i);
            if (s.verdict() == Verdict.UNEXPLAINED) {
                unexplained.add(s);
            }
        }
        if (unexplained.size() < 2) {
            return stalls;
        }
        unexplained.sort(BY_START);
        final long longest = Sorted.maxLength(unexplained, Stall::duration);
        final IdentityObjObjHashMap<Stall, Stall> marked = new IdentityObjObjHashMap<>(unexplained.size());
        for (int u = 0, n = unexplained.size(); u < n; u++) {
            final Stall s = unexplained.getQuick(u);
            int count = 0;
            final int from = Sorted.lowerBound(unexplained, Stall::start, s.start() - longest);
            for (int i = from; i < n; i++) {
                final Stall o = unexplained.getQuick(i);
                if (o.start() >= s.interval().end()) {
                    break;
                }
                if (o != s && !o.thread().equals(s.thread())
                        && o.interval().overlap(s.interval()) >= COVER * s.duration()) {
                    count++;
                }
            }
            if (count > 0) {
                marked.put(s, new Stall(s.thread(), s.interval(), s.verdict(),
                        s.detail() + "; simultaneous on " + (count + 1)
                                + " watched threads, so more likely the sampler than this thread",
                        s.stack(), s.evidence(), s.samples()));
            }
        }
        if (marked.isEmpty()) {
            return stalls;
        }
        final ObjList<Stall> out = new ObjList<>(stalls.size());
        for (int i = 0, n = stalls.size(); i < n; i++) {
            final Stall s = stalls.get(i);
            final Stall replacement = marked.get(s);
            out.add(replacement == null ? s : replacement);
        }
        return out.toList();
    }

    /** The finer of the two sampler periods in the recording's settings, or 0 if unknown. */
    static long samplerPeriod(final RecordingInfo info) {
        long period = 0;
        for (final int kind : SAMPLER_EVENTS) {
            final long p = info.periodNanos(EventKinds.nameOf(kind));
            if (p != Nulls.LONG_NULL && p > 0 && (period == 0 || p < period)) {
                period = p;
            }
        }
        return period;
    }

    private void warnRecording(final RecordingInfo info, final List<String> warnings) {
        if (!info.has(EventKinds.nameOf(EventKinds.EXECUTION_SAMPLE))
                && !info.has(EventKinds.nameOf(EventKinds.NATIVE_METHOD_SAMPLE))) {
            warnings.add("no sampler events in the recording: only event-based stalls can be found");
        }
        final StringBuilder throttled = new StringBuilder();
        for (final int kind : THRESHOLDED_BLOCK_EVENTS) {
            final String type = EventKinds.nameOf(kind);
            if (!info.enabled(type)) {
                continue;
            }
            final long threshold = info.thresholdNanos(type);
            if (threshold != Nulls.LONG_NULL && threshold > gap) {
                warnings.add(type + " threshold " + Durations.format(threshold) + " exceeds gap "
                        + Durations.format(gap) + ": shorter blocks of this kind are not in the file");
            }
            final String throttle = info.throttle(type).orElse(null);
            if (throttle != null) {
                throttled.append(throttled.isEmpty() ? "" : ", ").append(type).append(' ').append(throttle);
            }
        }
        if (!throttled.isEmpty()) {
            warnings.add("throttled events (" + throttled + "): not every blocking call is in the file, "
                    + "so a silence made of many short ones may stay unexplained");
        }
    }

    /**
     * Sampling intervals for a thread, split by sample kind. The median says how often
     * the thread is normally seen; the 90th percentile says how long a routine absence
     * can last (the native slot is round-robin, so a thread's native samples come in
     * bursts with long regular gaps between). Silence is judged against the percentile,
     * run chaining against the median.
     *
     * @param java       median spacing between consecutive Java samples
     * @param inNative   median spacing between consecutive native samples
     * @param javaP90    90th percentile of the Java spacing
     * @param nativeP90  90th percentile of the native spacing
     * @param period     the configured sampler period, or the Java median if unknown
     */
    record Cadence(long java, long inNative, long javaP90, long nativeP90, long period) {
        static Cadence of(final List<Sample> samples, final long configuredPeriod) {
            final int n = samples.size();
            final LongList javaDiffs = new LongList(n);
            final LongList nativeDiffs = new LongList(n);
            final LongList allDiffs = new LongList(n);
            for (int i = 1; i < n; i++) {
                final Sample a = samples.get(i - 1);
                final Sample b = samples.get(i);
                final long d = b.time() - a.time();
                allDiffs.add(d);
                if (!a.inNative() && !b.inNative()) {
                    javaDiffs.add(d);
                } else if (a.inNative() && b.inNative()) {
                    nativeDiffs.add(d);
                }
            }
            final LongList javaSpacing = javaDiffs.isEmpty() ? allDiffs : javaDiffs;
            final LongList nativeSpacing = nativeDiffs.isEmpty() ? allDiffs : nativeDiffs;
            // Sorting in place is fine: only the order statistics are read from here on.
            javaSpacing.sort();
            nativeSpacing.sort();
            final long java = percentile(javaSpacing, 0.5);
            final long period = configuredPeriod > 0 ? configuredPeriod : java;
            return new Cadence(java, percentile(nativeSpacing, 0.5), percentile(javaSpacing, 0.9),
                    percentile(nativeSpacing, 0.9), period);
        }

        /**
         * The longest routine absence between two observations. Between any two samples the
         * thread may have passed through the other state unseen (a burst of Java work, then
         * back to the selector before the native slot came round), so the worse of the two
         * percentiles applies whatever the neighbouring samples show.
         */
        long routineAbsence() {
            return Math.max(javaP90, nativeP90);
        }

        /** The {@code p}-th order statistic of a <em>sorted</em> list; 0 when empty. */
        static long percentile(final LongList sorted, final double p) {
            if (sorted.isEmpty()) {
                return 0;
            }
            final int index = (int) Math.min(sorted.size() - 1, Math.floor(p * sorted.size()));
            return sorted.getQuick(index);
        }
    }

    private void analyseThread(final ThreadTimeline tl, final Cadence cadence, final Windows windows,
                               final ObjList<Stall> stalls, final Interval span) {
        // 1. Event-based stalls: precise, independent of sampling.
        final List<Block> blocks = tl.blocks();
        final ObjList<Stall> eventStalls = new ObjList<>();
        for (int i = 0, n = blocks.size(); i < n; i++) {
            final Block b = blocks.get(i);
            // A block that began before the recording, or was still running at its end, is in
            // the file whole; only the part inside the span happened in the window the report
            // is about, and the gap applies to that part.
            final Interval inside = b.interval().clampTo(span);
            if (inside.length() >= gap) {
                // A worker parked on its own empty queue is not stalled, it is unemployed. The
                // block stays in the timeline below, because it is still what explains the
                // silence in the samples; it just does not become a stall of its own.
                if (isWaitingForWork(b)) {
                    workWaitNanos += inside.length();
                    workWaitCount++;
                    continue;
                }
                if (inside != b.interval()) {
                    clippedStalls++;
                }
                eventStalls.add(new Stall(tl.thread(), inside, verdictOf(b.kind()), describe(b), b.stack(),
                        Evidence.EVENT, 0));
            }
        }
        stalls.addAll(eventStalls);
        windows.of(blocks, eventStalls);

        // 2. Sample-based candidates.
        final List<Sample> samples = tl.samples();
        long runLimit = Math.min(gap, RUN_FACTOR * Math.max(cadence.period, cadence.java));
        if (runLimit <= 0) {
            runLimit = gap;
        }
        final ObjList<Candidate> runs = new ObjList<>();
        final ObjList<Interval> silences = new ObjList<>();
        int i = 0;
        final int n = samples.size();
        while (i < n) {
            final Sample first = samples.get(i);
            if (i > 0) {
                // Every gap of at least the stall length is a candidate; whether an unexplained
                // one is evidence of anything is decided against the cadence below.
                final Sample prev = samples.get(i - 1);
                final long d = first.time() - prev.time();
                if (d >= gap) {
                    silences.add(new Interval(prev.time(), first.time()));
                }
            }
            if (first.idle()) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n) {
                final Sample cur = samples.get(j);
                final Sample next = samples.get(j + 1);
                if (next.idle() || next.time() - cur.time() > runLimit) {
                    break;
                }
                j++;
            }
            final Sample last = samples.get(j);
            // The run reaches one sampler period past its last sample, or to the next
            // observation, whichever comes first; never past the end of the recording, since
            // that period is an estimate and the file says nothing beyond its span.
            long end = last.time() + cadence.period;
            if (j + 1 < n) {
                end = Math.min(end, samples.get(j + 1).time());
            }
            end = Math.max(Math.min(end, span.end()), last.time());
            final Interval run = new Interval(first.time(), end);
            if (run.length() >= gap && j + 1 - i >= RUN_MIN_SAMPLES) {
                runs.add(new Candidate(run, samples.subList(i, j + 1)));
            }
            // Pairs inside the run were within runLimit <= gap of each other, so none is a silence.
            i = j + 1;
        }

        final long unexplainedThreshold = silentThreshold(cadence);
        for (int s = 0, m = silences.size(); s < m; s++) {
            final Interval silence = silences.getQuick(s);
            if (windows.coveredByEvent(silence)) {
                continue;
            }
            // A silence shorter than the routine absence is not evidence by itself, so what
            // explains it must cover a whole gap on its own; a longer one is, and half is enough.
            final long minCover = silence.length() < unexplainedThreshold ? Math.max(gap, cover(silence)) : cover(silence);
            final Explanation ex = windows.explain(silence, true, minCover);
            if (ex != null) {
                // The same rule as above, at the other door: a silence whose explanation is a
                // worker's own empty queue is not a stall either, and must not fall through to
                // UNEXPLAINED, which would be a worse answer than the one just rejected.
                if (!isWaitingForWork(ex.verdict, ex.stack)) {
                    stalls.add(new Stall(tl.thread(), silence, ex.verdict, ex.detail, ex.stack, Evidence.SILENCE, 0));
                }
            } else if (silence.length() >= unexplainedThreshold) {
                // Longer than the thread's routine absence: the sampler would have seen it otherwise.
                stalls.add(new Stall(tl.thread(), silence, Verdict.UNEXPLAINED,
                        "no samples and no blocking event: blocked below the recording's thresholds, "
                                + "or sampled too sparsely", Stack.EMPTY, Evidence.SILENCE, 0));
            }
        }

        for (int r = 0, m = runs.size(); r < m; r++) {
            final Candidate run = runs.getQuick(r);
            if (windows.coveredByEvent(run.interval)) {
                continue;
            }
            final Explanation ex = windows.explain(run.interval, false, cover(run.interval));
            if (ex != null) {
                stalls.add(new Stall(tl.thread(), run.interval, ex.verdict, ex.detail, ex.stack,
                        Evidence.SAMPLES, run.samples.size()));
            } else {
                final Stall b = busy(tl, run);
                if (b != null) {
                    stalls.add(b);
                }
            }
        }
    }

    private record Candidate(Interval interval, List<Sample> samples) {
    }

    /** The coverage an explanation needs for an interval that is evidence in its own right. */
    private static long cover(final Interval interval) {
        return (long) Math.ceil(COVER * interval.length());
    }

    /** The shortest silence that means anything on its own: above the gap and above the routine absence. */
    private long silentThreshold(final Cadence cadence) {
        return Math.max(gap, CADENCE_FACTOR * cadence.routineAbsence());
    }

    private record Explanation(Verdict verdict, String detail, Stack stack) {
    }

    /** Coverage of one (kind, detail) group of blocks over a candidate interval; reused across candidates. */
    private static final class Group {
        BlockKind kind;
        String detail;
        long overlap;
        long count;
        long bytes;
        /** The longest block in the group: its stack stands for the group. */
        Block representative;

        Group of(final BlockKind kind, final String detail) {
            this.kind = kind;
            this.detail = detail;
            this.overlap = 0;
            this.count = 0;
            this.bytes = 0;
            this.representative = null;
            return this;
        }
    }

    /**
     * The sorted lists a candidate interval is checked against, with the longest element of
     * each remembered so a lookup scans only the elements that can overlap. The pauses are
     * fixed for the analysis; the blocks and event stalls are re-pointed per thread
     * ({@link #of}), and the grouping scratch is cleared per lookup (G-3.1, G-3.3).
     */
    private static final class Windows {
        private final ObjList<Pause> pauses;
        private final long longestPause;
        private List<Block> blocks = List.of();
        private long longestBlock;
        private ObjList<Stall> eventStalls = new ObjList<>();
        private long longestEventStall;
        /** Groups in first-seen order, so a tie in coverage goes to the earliest group, and their pool. */
        private final ObjList<Group> groups = new ObjList<>();
        private final ObjList<Group> pool = new ObjList<>();
        private final ObjObjHashMap<String, Group>[] groupByDetail;

        @SuppressWarnings({"unchecked", "rawtypes"}) // an array of a generic type has no other spelling
        Windows(final ObjList<Pause> pauses) {
            this.pauses = pauses;
            this.longestPause = Sorted.maxLength(pauses, Pause::length);
            final BlockKind[] kinds = BlockKind.values();
            this.groupByDetail = new ObjObjHashMap[kinds.length];
            for (int i = 0; i < kinds.length; i++) {
                groupByDetail[i] = new ObjObjHashMap<>(16);
            }
        }

        void of(final List<Block> blocks, final ObjList<Stall> eventStalls) {
            this.blocks = blocks;
            this.longestBlock = Sorted.maxLength(blocks, Block::length);
            this.eventStalls = eventStalls;
            this.longestEventStall = Sorted.maxLength(eventStalls, Stall::duration);
        }

        boolean coveredByEvent(final Interval candidate) {
            final int from = Sorted.lowerBound(eventStalls, Stall::start, candidate.start() - longestEventStall);
            for (int i = from, n = eventStalls.size(); i < n; i++) {
                final Stall s = eventStalls.getQuick(i);
                if (s.start() >= candidate.end()) {
                    break;
                }
                if (s.interval().overlap(candidate) >= COVER * candidate.length()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Groups blocking events overlapping the interval by kind and detail; the group with
         * the most coverage wins if it covers at least {@code minCover} nanoseconds. Failing
         * that, and only when {@code tryPauses}, JVM pauses are tried the same way.
         */
        Explanation explain(final Interval interval, final boolean tryPauses, final long minCover) {
            final Explanation byBlock = explainByBlocks(interval, minCover);
            if (byBlock != null || !tryPauses) {
                return byBlock;
            }
            return explainByPauses(interval, minCover);
        }

        /**
         * Blocks are grouped by kind and detail, which for I/O is the peer or the path and
         * not the byte count, so that many short reads from one peer add up to one answer.
         */
        private Explanation explainByBlocks(final Interval interval, final long minCover) {
            clearGroups();
            final int from = Sorted.lowerBound(blocks, Block::start, interval.start() - longestBlock);
            for (int i = from, n = blocks.size(); i < n; i++) {
                final Block b = blocks.get(i);
                if (b.start() >= interval.end()) {
                    break;
                }
                final long overlap = b.interval().overlap(interval);
                if (overlap == 0) {
                    continue;
                }
                final Group g = group(b.kind(), b.detail());
                g.overlap += overlap;
                g.count++;
                g.bytes += b.bytes();
                if (g.representative == null || b.length() > g.representative.length()) {
                    g.representative = b;
                }
            }
            Group best = null;
            for (int i = 0, n = groups.size(); i < n; i++) {
                final Group g = groups.getQuick(i);
                if (best == null || g.overlap > best.overlap) {
                    best = g;
                }
            }
            if (best != null && best.overlap >= minCover) {
                final Block rep = best.representative;
                final String detail = best.count > 1 ? best.count + " × " + describe(rep, best.bytes) : describe(rep);
                return new Explanation(verdictOf(rep.kind()), detail, rep.stack());
            }
            return null;
        }

        private Group group(final BlockKind kind, final String detail) {
            // A block without a detail groups under the literal "null", as the old string key did.
            final String key = detail == null ? "null" : detail;
            final ObjObjHashMap<String, Group> byDetail = groupByDetail[kind.ordinal()];
            final int index = byDetail.keyIndex(key);
            if (index < 0) {
                return byDetail.valueAtQuick(index);
            }
            final Group g = groups.size() < pool.size() ? pool.getQuick(groups.size()) : allocate();
            groups.add(g.of(kind, key));
            return byDetail.putAt(index, key, g);
        }

        private Group allocate() {
            final Group g = new Group();
            pool.add(g);
            return g;
        }

        private void clearGroups() {
            for (int i = 0, n = groups.size(); i < n; i++) {
                final Group g = groups.getQuick(i);
                groupByDetail[g.kind.ordinal()].remove(g.detail);
                g.representative = null;
            }
            groups.clear();
        }

        private Explanation explainByPauses(final Interval interval, final long minCover) {
            long gc = 0;
            long safepoint = 0;
            Pause gcRep = null;
            Pause spRep = null;
            final int from = Sorted.lowerBound(pauses, Pause::start, interval.start() - longestPause);
            for (int i = from, n = pauses.size(); i < n; i++) {
                final Pause p = pauses.getQuick(i);
                if (p.start() >= interval.end()) {
                    break;
                }
                final long overlap = p.interval().overlap(interval);
                if (overlap == 0) {
                    continue;
                }
                if (p.kind() == PauseKind.GC) {
                    gc += overlap;
                    if (gcRep == null || p.length() > gcRep.length()) {
                        gcRep = p;
                    }
                } else {
                    safepoint += overlap;
                    if (spRep == null || p.length() > spRep.length()) {
                        spRep = p;
                    }
                }
            }
            if (gcRep != null && gc >= minCover) {
                return new Explanation(Verdict.GC_PAUSE, gcRep.kind().label() + ": " + gcRep.detail(), Stack.EMPTY);
            }
            if (spRep != null && safepoint >= minCover) {
                return new Explanation(Verdict.SAFEPOINT, spRep.kind().label() + ": " + spRep.detail(), Stack.EMPTY);
            }
            return null;
        }
    }

    /** One culprit of a busy run: how many samples named it, and the first stack that did. */
    private static final class Culprit {
        String name;
        Stack stack;
        int count;

        Culprit of(final String name, final Stack stack) {
            this.name = name;
            this.stack = stack;
            this.count = 0;
            return this;
        }
    }

    private Stall busy(final ThreadTimeline tl, final Candidate run) {
        culprits.clear();
        culpritByName.clear();
        int nativeTop = 0;
        final List<Sample> samples = run.samples;
        final int n = samples.size();
        for (int i = 0; i < n; i++) {
            final Sample s = samples.get(i);
            final String name = culpritName(s.stack());
            final int index = culpritByName.keyIndex(name);
            Culprit c;
            if (index < 0) {
                c = culpritByName.valueAtQuick(index);
            } else {
                c = culpritByName.putAt(index, name, new Culprit().of(name, s.stack()));
                culprits.add(c);
            }
            c.count++;
            if (s.inNative()) {
                nativeTop++;
            }
        }
        Culprit top = null;
        for (int i = 0, m = culprits.size(); i < m; i++) {
            final Culprit c = culprits.getQuick(i);
            if (top == null || c.count > top.count) {
                top = c;
            }
        }
        final double share = top == null ? 0 : (double) top.count / n;
        final String pct = String.format(Locale.ROOT, "%.0f%%", share * 100);
        final String nativeNote = nativeTop * 2 >= n && n > 0 ? " [mostly in native code]" : "";
        if (share >= DOMINANT) {
            return new Stall(tl.thread(), run.interval, Verdict.BUSY,
                    "busy in " + top.name + " (" + pct + " of " + n + " samples)" + nativeNote,
                    top.stack, Evidence.SAMPLES, n);
        }
        if (n < SATURATED_MIN_SAMPLES) {
            // Too few samples to claim the thread never yielded; the run is not reported.
            return null;
        }
        return new Stall(tl.thread(), run.interval, Verdict.SATURATED,
                "no return to idle across " + n + " samples; " + culprits.size() + " distinct culprits, top "
                        + top.name + " " + pct + nativeNote,
                top.stack, Evidence.SAMPLES, n);
    }

    /** The culprit frame's {@code type.method}, or {@code <no stack>}; the name is built once per frame. */
    private String culpritName(final Stack stack) {
        final Frame culprit = stack.culpritOrNull();
        if (culprit == null) {
            return "<no stack>";
        }
        final int index = culpritNames.keyIndex(culprit);
        return index < 0 ? culpritNames.valueAtQuick(index)
                : culpritNames.putAt(index, culprit, culprit.qualifiedName());
    }

    static Verdict verdictOf(final BlockKind kind) {
        return switch (kind) {
            case MONITOR -> Verdict.BLOCKED_MONITOR;
            case PARK -> Verdict.PARKED;
            case OBJECT_WAIT -> Verdict.OBJECT_WAIT;
            case SLEEP -> Verdict.SLEEP;
            case SOCKET_READ, SOCKET_WRITE, FILE_READ, FILE_WRITE, FILE_FORCE -> Verdict.BLOCKING_IO;
        };
    }

    static String describe(final Block b) {
        return describe(b, b.bytes());
    }

    /** {@link #describe(Block)} with the byte count of a whole group of I/O blocks. */
    static String describe(final Block b, final long bytes) {
        final StringBuilder sb = new StringBuilder(b.kind().label());
        if (b.detail() != null && !b.detail().isEmpty()) {
            sb.append(' ').append(b.detail());
        }
        if (b.kind().isIo() && bytes > 0) {
            sb.append(" (").append(Bytes.format(bytes)).append(')');
        }
        if (b.kind() == BlockKind.MONITOR) {
            sb.append(" held by ").append(b.owner() == null ? "unknown" : b.owner().name());
            if (!b.via().isEmpty()) {
                sb.append(" (handed on through ");
                for (int i = 0, n = b.via().size(); i < n; i++) {
                    sb.append(i > 0 ? ", " : "").append(b.via().get(i).name());
                }
                sb.append(')');
            }
        }
        return sb.toString();
    }
}
