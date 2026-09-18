package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Timeline.Pause;

/**
 * The outcome of a stall analysis.
 *
 * @param info      the recording
 * @param gapNanos  the stall threshold that was applied
 * @param threads   one summary per watched thread, in name order
 * @param stalls    every stall found, longest first
 * @param pauses    JVM-wide pauses of at least the gap, in time order
 * @param warnings  things that limit what the analysis could see
 */
public record StallReport(RecordingInfo info, long gapNanos, List<ThreadSummary> threads, List<Stall> stalls,
                          List<Pause> pauses, List<String> warnings) {

    /**
     * Per-thread facts.
     *
     * @param thread            the thread
     * @param samples           sampler observations seen
     * @param javaCadenceNanos   median interval between consecutive Java samples; 0 if unknown
     * @param nativeCadenceNanos median interval between consecutive native samples (which is
     *                           how an idle event loop is seen); 0 if unknown
     * @param stalls             stalls attributed to the thread
     * @param stalledNanos       sum of stall lengths (stalls may nest, so this can exceed wall time)
     * @param worstNanos         the longest stall
     */
    public record ThreadSummary(ThreadRef thread, int samples, long javaCadenceNanos, long nativeCadenceNanos,
                                int stalls, long stalledNanos, long worstNanos) {
    }

    public StallReport {
        List<Stall> sorted = new ArrayList<>(stalls);
        sorted.sort(Comparator.comparingLong(Stall::duration).reversed()
                .thenComparingLong(Stall::start));
        stalls = List.copyOf(sorted);
        threads = List.copyOf(threads);
        pauses = List.copyOf(pauses);
        warnings = List.copyOf(warnings);
    }

    public List<Stall> top(int n) {
        return stalls.size() > n ? stalls.subList(0, n) : stalls;
    }

    /** Totals per verdict: count and summed duration, largest total first. */
    public record VerdictSummary(Stall.Verdict verdict, int count, long totalNanos, long worstNanos) {
    }

    public List<VerdictSummary> byVerdict() {
        java.util.EnumMap<Stall.Verdict, long[]> totals = new java.util.EnumMap<>(Stall.Verdict.class);
        for (Stall s : stalls) {
            long[] t = totals.computeIfAbsent(s.verdict(), _ -> new long[3]);
            t[0]++;
            t[1] += s.duration();
            t[2] = Math.max(t[2], s.duration());
        }
        List<VerdictSummary> out = new ArrayList<>();
        totals.forEach((v, t) -> out.add(new VerdictSummary(v, (int) t[0], t[1], t[2])));
        out.sort(Comparator.comparingLong(VerdictSummary::totalNanos).reversed());
        return out;
    }

    public List<Stall> stallsOf(ThreadRef thread) {
        List<Stall> out = new ArrayList<>();
        for (Stall s : stalls) {
            if (s.thread().equals(thread)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingLong(Stall::start));
        return out;
    }
}
