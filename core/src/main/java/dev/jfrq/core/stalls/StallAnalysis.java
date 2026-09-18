package dev.jfrq.core.stalls;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.jfrq.core.jfr.RecordingInfo;
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

    private static final List<String> THRESHOLDED_BLOCK_EVENTS = List.of(
            "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.JavaMonitorWait", "jdk.ThreadSleep",
            "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite", "jdk.FileForce");

    private final long gap;

    public StallAnalysis(long gapNanos) {
        if (gapNanos <= 0) {
            throw new IllegalArgumentException("gap must be positive");
        }
        this.gap = gapNanos;
    }

    public long gap() {
        return gap;
    }

    public StallReport analyse(RecordingInfo info, List<ThreadTimeline> timelines, List<Pause> pauses) {
        List<String> warnings = new ArrayList<>();
        warnRecording(info, warnings);
        long period = samplerPeriod(info);

        List<Pause> sortedPauses = new ArrayList<>(pauses);
        sortedPauses.sort(Comparator.comparing(Pause::interval));
        List<Pause> longPauses = new ArrayList<>();
        for (Pause p : sortedPauses) {
            if (p.length() >= gap) {
                longPauses.add(p);
            }
        }

        List<ThreadTimeline> ordered = new ArrayList<>(timelines);
        ordered.sort(Comparator.comparing(t -> t.thread().name()));
        List<Stall> stalls = new ArrayList<>();
        List<ThreadSummary> summaries = new ArrayList<>();
        List<String> cadenceWarnings = new ArrayList<>();
        for (ThreadTimeline tl : ordered) {
            Cadence cadence = Cadence.of(tl.samples(), period);
            List<Stall> found = analyseThread(tl, cadence, sortedPauses);
            stalls.addAll(found);
            long stalled = 0;
            long worst = 0;
            for (Stall s : found) {
                stalled += s.duration();
                worst = Math.max(worst, s.duration());
            }
            summaries.add(new ThreadSummary(tl.thread(), tl.samples().size(), cadence.java, cadence.inNative,
                    found.size(), stalled, worst));
            long absence = cadence.routineAbsence();
            if (absence > 0 && absence * CADENCE_FACTOR > gap) {
                cadenceWarnings.add(String.format(Locale.ROOT,
                        "%s: samples routinely up to %s apart; unexplained silences shorter than ~%s "
                                + "cannot be seen, only ones a blocking event or a JVM pause explains",
                        tl.thread().name(), Durations.format(absence),
                        Durations.format(absence * CADENCE_FACTOR)));
            }
        }
        if (cadenceWarnings.size() <= 3) {
            warnings.addAll(cadenceWarnings);
        } else {
            warnings.addAll(cadenceWarnings.subList(0, 3));
            warnings.add((cadenceWarnings.size() - 3) + " more threads with coarse sampling cadence");
        }
        return new StallReport(info, gap, summaries, markSimultaneous(stalls), longPauses, warnings);
    }

    /**
     * An unexplained silence that several watched threads share at the same moment is
     * far more likely to be the sampler not running, or a pause the recording did not
     * capture, than each thread independently blocking; say so in the detail.
     */
    static List<Stall> markSimultaneous(List<Stall> stalls) {
        List<Stall> unexplained = new ArrayList<>();
        for (Stall s : stalls) {
            if (s.verdict() == Verdict.UNEXPLAINED) {
                unexplained.add(s);
            }
        }
        if (unexplained.size() < 2) {
            return stalls;
        }
        unexplained.sort(Comparator.comparingLong(Stall::start));
        long longest = Sorted.maxLength(unexplained, Stall::duration);
        Map<Stall, Integer> others = new java.util.IdentityHashMap<>();
        for (Stall s : unexplained) {
            int count = 0;
            int from = Sorted.lowerBound(unexplained, Stall::start, s.start() - longest);
            for (int i = from; i < unexplained.size(); i++) {
                Stall o = unexplained.get(i);
                if (o.start() >= s.interval().end()) {
                    break;
                }
                if (o != s && !o.thread().equals(s.thread())
                        && o.interval().overlap(s.interval()) >= COVER * s.duration()) {
                    count++;
                }
            }
            if (count > 0) {
                others.put(s, count);
            }
        }
        if (others.isEmpty()) {
            return stalls;
        }
        List<Stall> out = new ArrayList<>(stalls.size());
        for (Stall s : stalls) {
            Integer count = others.get(s);
            if (count == null) {
                out.add(s);
            } else {
                out.add(new Stall(s.thread(), s.interval(), s.verdict(),
                        s.detail() + "; simultaneous on " + (count + 1)
                                + " watched threads, so more likely the sampler than this thread",
                        s.stack(), s.evidence(), s.samples()));
            }
        }
        return out;
    }

    /** The finer of the two sampler periods in the recording's settings, or 0 if unknown. */
    static long samplerPeriod(RecordingInfo info) {
        long period = 0;
        for (String type : new String[] {"jdk.ExecutionSample", "jdk.NativeMethodSample"}) {
            long p = info.period(type).map(Duration::toNanos).orElse(0L);
            if (p > 0 && (period == 0 || p < period)) {
                period = p;
            }
        }
        return period;
    }

    private void warnRecording(RecordingInfo info, List<String> warnings) {
        if (!info.has("jdk.ExecutionSample") && !info.has("jdk.NativeMethodSample")) {
            warnings.add("no sampler events in the recording: only event-based stalls can be found");
        }
        for (String type : THRESHOLDED_BLOCK_EVENTS) {
            if (!info.enabled(type)) {
                continue;
            }
            info.threshold(type).ifPresent(t -> {
                if (t.toNanos() > gap) {
                    warnings.add(type + " threshold " + Durations.format(t) + " exceeds gap "
                            + Durations.format(gap) + ": shorter blocks of this kind are not in the file");
                }
            });
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
        static Cadence of(List<Sample> samples, long configuredPeriod) {
            List<Long> javaDiffs = new ArrayList<>();
            List<Long> nativeDiffs = new ArrayList<>();
            List<Long> allDiffs = new ArrayList<>();
            for (int i = 1; i < samples.size(); i++) {
                Sample a = samples.get(i - 1);
                Sample b = samples.get(i);
                long d = b.time() - a.time();
                allDiffs.add(d);
                if (!a.inNative() && !b.inNative()) {
                    javaDiffs.add(d);
                } else if (a.inNative() && b.inNative()) {
                    nativeDiffs.add(d);
                }
            }
            List<Long> javaSpacing = javaDiffs.isEmpty() ? allDiffs : javaDiffs;
            List<Long> nativeSpacing = nativeDiffs.isEmpty() ? allDiffs : nativeDiffs;
            long java = percentile(javaSpacing, 0.5);
            long period = configuredPeriod > 0 ? configuredPeriod : java;
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

        static long percentile(List<Long> values, double p) {
            if (values.isEmpty()) {
                return 0;
            }
            List<Long> sorted = new ArrayList<>(values);
            sorted.sort(null);
            int index = (int) Math.min(sorted.size() - 1, Math.floor(p * sorted.size()));
            return sorted.get(index);
        }
    }

    private List<Stall> analyseThread(ThreadTimeline tl, Cadence cadence, List<Pause> pauses) {
        List<Stall> stalls = new ArrayList<>();

        // 1. Event-based stalls: precise, independent of sampling.
        List<Stall> eventStalls = new ArrayList<>();
        for (Block b : tl.blocks()) {
            if (b.length() >= gap) {
                eventStalls.add(new Stall(tl.thread(), b.interval(), verdictOf(b.kind()), describe(b), b.stack(),
                        Evidence.EVENT, 0));
            }
        }
        stalls.addAll(eventStalls);
        Windows windows = new Windows(tl.blocks(), pauses, eventStalls);

        // 2. Sample-based candidates.
        List<Sample> samples = tl.samples();
        long runLimit = Math.min(gap, RUN_FACTOR * Math.max(cadence.period, cadence.java));
        if (runLimit <= 0) {
            runLimit = gap;
        }
        List<Candidate> runs = new ArrayList<>();
        List<Candidate> silences = new ArrayList<>();
        int i = 0;
        while (i < samples.size()) {
            Sample first = samples.get(i);
            if (i > 0) {
                Sample prev = samples.get(i - 1);
                long d = first.time() - prev.time();
                if (d >= silentThreshold(cadence)) {
                    silences.add(new Candidate(new Interval(prev.time(), first.time()), List.of()));
                }
            }
            if (first.idle()) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < samples.size()) {
                Sample cur = samples.get(j);
                Sample next = samples.get(j + 1);
                if (next.idle() || next.time() - cur.time() > runLimit) {
                    break;
                }
                j++;
            }
            Sample last = samples.get(j);
            long end = last.time() + cadence.period;
            if (j + 1 < samples.size()) {
                end = Math.min(end, samples.get(j + 1).time());
            }
            end = Math.max(end, last.time());
            Interval run = new Interval(first.time(), end);
            if (run.length() >= gap && j + 1 - i >= RUN_MIN_SAMPLES) {
                runs.add(new Candidate(run, samples.subList(i, j + 1)));
            }
            // Pairs inside the run were within runLimit <= gap of each other, so none is a silence.
            i = j + 1;
        }

        for (Candidate silence : silences) {
            if (windows.coveredByEvent(silence.interval)) {
                continue;
            }
            Explanation ex = windows.explain(silence.interval, true);
            if (ex != null) {
                stalls.add(new Stall(tl.thread(), silence.interval, ex.verdict, ex.detail, ex.stack,
                        Evidence.SILENCE, 0));
            } else {
                stalls.add(new Stall(tl.thread(), silence.interval, Verdict.UNEXPLAINED,
                        "no samples and no blocking event: blocked below the recording's thresholds, "
                                + "or sampled too sparsely", Stack.EMPTY, Evidence.SILENCE, 0));
            }
        }

        for (Candidate run : runs) {
            if (windows.coveredByEvent(run.interval)) {
                continue;
            }
            Explanation ex = windows.explain(run.interval, false);
            if (ex != null) {
                stalls.add(new Stall(tl.thread(), run.interval, ex.verdict, ex.detail, ex.stack,
                        Evidence.SAMPLES, run.samples.size()));
            } else {
                Stall b = busy(tl, run);
                if (b != null) {
                    stalls.add(b);
                }
            }
        }
        return stalls;
    }

    private record Candidate(Interval interval, List<Sample> samples) {
    }

    private long silentThreshold(Cadence cadence) {
        return Math.max(gap, CADENCE_FACTOR * cadence.routineAbsence());
    }

    private record Explanation(Verdict verdict, String detail, Stack stack) {
    }

    /**
     * The sorted lists a candidate interval is checked against, with the longest element of
     * each remembered so a lookup scans only the elements that can overlap.
     */
    private static final class Windows {
        private final List<Block> blocks;
        private final long longestBlock;
        private final List<Pause> pauses;
        private final long longestPause;
        private final List<Stall> eventStalls;
        private final long longestEventStall;

        Windows(List<Block> blocks, List<Pause> pauses, List<Stall> eventStalls) {
            this.blocks = blocks;
            this.longestBlock = Sorted.maxLength(blocks, Block::length);
            this.pauses = pauses;
            this.longestPause = Sorted.maxLength(pauses, Pause::length);
            this.eventStalls = eventStalls;
            this.longestEventStall = Sorted.maxLength(eventStalls, Stall::duration);
        }

        boolean coveredByEvent(Interval candidate) {
            int from = Sorted.lowerBound(eventStalls, Stall::start, candidate.start() - longestEventStall);
            for (int i = from; i < eventStalls.size(); i++) {
                Stall s = eventStalls.get(i);
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
         * the most coverage wins if it covers at least {@link #COVER}. Failing that, and only
         * when {@code tryPauses}, JVM pauses are tried the same way.
         */
        Explanation explain(Interval interval, boolean tryPauses) {
            Explanation byBlock = explainByBlocks(interval);
            if (byBlock != null || !tryPauses) {
                return byBlock;
            }
            return explainByPauses(interval);
        }

        private Explanation explainByBlocks(Interval interval) {
            Map<String, long[]> coverage = new LinkedHashMap<>();
            Map<String, Block> representative = new HashMap<>();
            int from = Sorted.lowerBound(blocks, b -> b.interval().start(), interval.start() - longestBlock);
            for (int i = from; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                if (b.interval().start() >= interval.end()) {
                    break;
                }
                long overlap = b.interval().overlap(interval);
                if (overlap == 0) {
                    continue;
                }
                String key = b.kind() + "|" + b.detail();
                long[] c = coverage.computeIfAbsent(key, k -> new long[2]);
                c[0] += overlap;
                c[1]++;
                Block rep = representative.get(key);
                if (rep == null || b.length() > rep.length()) {
                    representative.put(key, b);
                }
            }
            String bestKey = null;
            long best = 0;
            for (Map.Entry<String, long[]> e : coverage.entrySet()) {
                if (e.getValue()[0] > best) {
                    best = e.getValue()[0];
                    bestKey = e.getKey();
                }
            }
            if (bestKey != null && best >= COVER * interval.length()) {
                Block rep = representative.get(bestKey);
                long count = coverage.get(bestKey)[1];
                String detail = count > 1 ? count + " × " + describe(rep) : describe(rep);
                return new Explanation(verdictOf(rep.kind()), detail, rep.stack());
            }
            return null;
        }

        private Explanation explainByPauses(Interval interval) {
            long gc = 0;
            long safepoint = 0;
            Pause gcRep = null;
            Pause spRep = null;
            int from = Sorted.lowerBound(pauses, p -> p.interval().start(), interval.start() - longestPause);
            for (int i = from; i < pauses.size(); i++) {
                Pause p = pauses.get(i);
                if (p.interval().start() >= interval.end()) {
                    break;
                }
                long overlap = p.interval().overlap(interval);
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
            if (gcRep != null && gc >= COVER * interval.length()) {
                return new Explanation(Verdict.GC_PAUSE, gcRep.kind().label() + ": " + gcRep.detail(), Stack.EMPTY);
            }
            if (spRep != null && safepoint >= COVER * interval.length()) {
                return new Explanation(Verdict.SAFEPOINT, spRep.kind().label() + ": " + spRep.detail(), Stack.EMPTY);
            }
            return null;
        }
    }

    private static Stall busy(ThreadTimeline tl, Candidate run) {
        Map<String, Integer> culprits = new LinkedHashMap<>();
        Map<String, Stack> stacks = new HashMap<>();
        int nativeTop = 0;
        for (Sample s : run.samples) {
            String name = s.stack().culprit().map(f -> f.qualifiedName()).orElse("<no stack>");
            culprits.merge(name, 1, Integer::sum);
            stacks.putIfAbsent(name, s.stack());
            if (s.inNative()) {
                nativeTop++;
            }
        }
        String top = null;
        int topCount = 0;
        for (Map.Entry<String, Integer> e : culprits.entrySet()) {
            if (e.getValue() > topCount) {
                topCount = e.getValue();
                top = e.getKey();
            }
        }
        int n = run.samples.size();
        double share = n == 0 ? 0 : (double) topCount / n;
        String pct = String.format(Locale.ROOT, "%.0f%%", share * 100);
        String nativeNote = nativeTop * 2 >= n && n > 0 ? " [mostly in native code]" : "";
        if (share >= DOMINANT) {
            return new Stall(tl.thread(), run.interval, Verdict.BUSY,
                    "busy in " + top + " (" + pct + " of " + n + " samples)" + nativeNote,
                    stacks.get(top), Evidence.SAMPLES, n);
        }
        if (n < SATURATED_MIN_SAMPLES) {
            // Too few samples to claim the thread never yielded; the run is not reported.
            return null;
        }
        return new Stall(tl.thread(), run.interval, Verdict.SATURATED,
                "no return to idle across " + n + " samples; " + culprits.size() + " distinct culprits, top "
                        + top + " " + pct + nativeNote,
                stacks.get(top), Evidence.SAMPLES, n);
    }

    static Verdict verdictOf(BlockKind kind) {
        return switch (kind) {
            case MONITOR -> Verdict.BLOCKED_MONITOR;
            case PARK -> Verdict.PARKED;
            case OBJECT_WAIT -> Verdict.OBJECT_WAIT;
            case SLEEP -> Verdict.SLEEP;
            case SOCKET_READ, SOCKET_WRITE, FILE_READ, FILE_WRITE, FILE_FORCE -> Verdict.BLOCKING_IO;
        };
    }

    static String describe(Block b) {
        StringBuilder sb = new StringBuilder(b.kind().label());
        if (b.detail() != null && !b.detail().isEmpty()) {
            sb.append(' ').append(b.detail());
        }
        if (b.kind() == BlockKind.MONITOR) {
            sb.append(" held by ").append(b.owner() == null ? "unknown" : b.owner().name());
            if (!b.via().isEmpty()) {
                sb.append(" (handed on through ");
                for (int i = 0; i < b.via().size(); i++) {
                    sb.append(i > 0 ? ", " : "").append(b.via().get(i).name());
                }
                sb.append(')');
            }
        }
        return sb.toString();
    }
}
