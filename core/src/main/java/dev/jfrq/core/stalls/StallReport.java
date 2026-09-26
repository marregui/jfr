// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.util.Durations;

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

    /** What both renderers say when no watched thread has anything to judge it by. */
    public static final String NO_THREAD = "No thread matched that has a sample or a blocking event. `jfrq info` "
            + "lists every thread seen in any event, the JVM's own included; stalls can judge only a thread the "
            + "sampler or a blocking event saw.";

    /**
     * What limits a thread's view: nothing, one of the sampler's two slots, or the thread itself.
     * A stall a blocking event or a JVM pause explains is seen whatever this says; this is about
     * the ones nothing explains, which only the samples around them can show.
     */
    public enum Sight {
        /** An unexplained stall of the gap's length would be seen. */
        CLEAR,
        /**
         * The thread was observable all along, but the sampler visits one thread in native code
         * per period, and this one's turn came too seldom: a shorter native period helps.
         */
        NATIVE_SAMPLER,
        /** The same for the Java slot, which takes at most five threads per period. */
        JAVA_SAMPLER,
        /**
         * Its silences are mostly the thread itself, parked, blocked or in the VM where the
         * sampler cannot see a thread, not the sampler's pace: no period helps much, and its
         * stalls are the ones blocking events explain.
         */
        OWN_ABSENCE
    }

    /**
     * Per-thread facts.
     *
     * @param thread             the thread
     * @param samples            sampler observations seen
     * @param javaCadenceNanos   median interval between consecutive Java samples; 0 if unknown
     * @param nativeCadenceNanos median interval between consecutive native samples (which is
     *                           how an idle event loop is seen); 0 if unknown
     * @param stalls             stalls attributed to the thread
     * @param stalledNanos       sum of stall lengths; a thread's stalls are disjoint, so this never
     *                           exceeds its life in the recording
     * @param worstNanos         the longest stall
     * @param unseenBelowNanos   the shortest stall no event explains that the samples can show
     *                           (three routine absences); 0 when the thread has no routine absence
     * @param sight              what limits that
     * @param roundTripNanos     for a sampler-limited thread, how long its slot takes to come round
     *                           to a thread that sits in its state all along; 0 otherwise
     */
    public record ThreadSummary(ThreadRef thread, int samples, long javaCadenceNanos, long nativeCadenceNanos,
                                int stalls, long stalledNanos, long worstNanos, long unseenBelowNanos, Sight sight,
                                long roundTripNanos) {

        /** A thread with nothing hidden from it. */
        public ThreadSummary(final ThreadRef thread, final int samples, final long javaCadenceNanos,
                             final long nativeCadenceNanos, final int stalls, final long stalledNanos,
                             final long worstNanos) {
            this(thread, samples, javaCadenceNanos, nativeCadenceNanos, stalls, stalledNanos, worstNanos, 0,
                    Sight.CLEAR, 0);
        }

        /**
         * The PER THREAD cell both renderers print: blank when nothing is hidden, a dash when nothing
         * is seen, which a bound past the recording's length also means.
         */
        public String unseenBelow(final long spanNanos) {
            if (sight == Sight.CLEAR) {
                return "";
            }
            return unseenBelowNanos > 0 && unseenBelowNanos <= spanNanos ? Durations.format(unseenBelowNanos) : "—";
        }
    }

    public StallReport {
        final List<Stall> sorted = new ArrayList<>(stalls);
        sorted.sort(Comparator.comparingLong(Stall::duration).reversed()
                .thenComparingLong(Stall::start));
        stalls = List.copyOf(sorted);
        threads = List.copyOf(threads);
        pauses = List.copyOf(pauses);
        warnings = List.copyOf(warnings);
    }

    /** The shortest period the JFR sampler takes: a shorter one samples no more often (measured on JDK 25). */
    static final long MIN_SAMPLER_PERIOD = 1_000_000L;
    /** Past this ratio between the worst and the best thread's bound, the headline names only the best. */
    private static final long WIDE_SPREAD = 10;
    /** How many threads in Java the sampler takes per period. */
    private static final int JAVA_PER_PERIOD = 5;

    /**
     * The verdict on the question itself, before any stall: which of the watched threads this
     * recording cannot show an unexplained stall of the gap's length on, why, and the setting
     * that would. One sentence per kind of limit, none when every thread's view is clear.
     * Events and pauses are exact whatever the sampling, so this is only about the stalls
     * nothing explains.
     */
    public List<String> unseen() {
        final List<String> out = new ArrayList<>(3);
        sampler(Sight.NATIVE_SAMPLER, out);
        sampler(Sight.JAVA_SAMPLER, out);
        final List<ThreadSummary> own = withSight(Sight.OWN_ABSENCE);
        if (!own.isEmpty()) {
            out.add(String.format(Locale.ROOT, "on %d of %d threads, a stall no event or pause explains is %s: "
                    + "their silences are mostly the thread itself, parked or blocked where the sampler cannot see "
                    + "it, not the sampler's pace, so no sampling period changes that much; blocking events are "
                    + "what show their stalls", own.size(), threads.size(), seen(own)));
        }
        return out;
    }

    /**
     * {@link #unseen()} in one line, before its reasons: how short an unexplained stall these
     * threads cannot show, and on how many of them. {@code null} when every thread's view is
     * clear. The {@link #unseen()} sentences explain it and name the setting that would help;
     * this is the line a reader who stops early still needs, since "0 found" means nothing
     * below it.
     */
    public String unseenHeadline() {
        final long span = info.span().duration();
        int limited = 0;
        int never = 0;
        long lo = Long.MAX_VALUE;
        long hi = 0;
        for (final ThreadSummary t : threads) {
            if (t.sight() == Sight.CLEAR) {
                continue;
            }
            limited++;
            final long u = t.unseenBelowNanos();
            if (u > span) {
                never++;
            } else if (u > 0) {
                lo = Math.min(lo, u);
                hi = Math.max(hi, u);
            }
        }
        if (limited == 0) {
            return null;
        }
        final String of = limited + " of " + threads.size() + " threads";
        final String exact = "; stalls a blocking event or pause explains are exact";
        if (hi == 0) {
            return "unexplained stalls are not observable at all on " + of + exact;
        }
        // The best thread's bound holds on every one of them; the worst's is how far it reaches,
        // unless the spread is so wide that one number says nothing (84.5 ms against 24m18s over
        // 434 threads with --thread '*'), and "far longer on some" is the true part of it.
        final String best = Durations.format(lo);
        final String worst = Durations.format(hi);
        final String reach = best.equals(worst) ? ""
                : hi <= WIDE_SPREAD * lo ? " (" + worst + " on the worst thread)"
                : " (far longer on some)";
        return "unexplained stalls shorter than " + best + reach
                + " are not observable on " + of + (never == 0 ? "" : ", and none at all on " + never + " of them")
                + exact;
    }

    private List<ThreadSummary> withSight(final Sight sight) {
        final List<ThreadSummary> out = new ArrayList<>();
        for (final ThreadSummary t : threads) {
            if (t.sight() == sight) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * {@code seen only from 1.08 s}, or {@code from 510 ms to 620 ms}, over the threads that have
     * a routine absence, one value when both ends print the same. A bound past the recording's
     * length is no bound: those threads are counted as not seen at all.
     */
    private String seen(final List<ThreadSummary> threads) {
        final long span = info.span().duration();
        final long[] unseen = new long[threads.size()];
        int n = 0;
        int never = 0;
        for (final ThreadSummary t : threads) {
            final long u = t.unseenBelowNanos();
            if (u > span) {
                never++;
            } else if (u > 0) {
                unseen[n++] = u;
            }
        }
        if (n == 0) {
            return "not seen at all";
        }
        Arrays.sort(unseen, 0, n);
        final String lo = Durations.format(unseen[0]);
        final String hi = Durations.format(unseen[n - 1]);
        return "seen only from " + (lo.equals(hi) ? lo : lo + " to " + hi)
                + (never == 0 ? "" : ", and not at all on " + never + " of them");
    }

    private void sampler(final Sight sight, final List<String> out) {
        final List<ThreadSummary> limited = withSight(sight);
        if (limited.isEmpty()) {
            return;
        }
        final boolean inNative = sight == Sight.NATIVE_SAMPLER;
        ThreadSummary worst = limited.getFirst();
        final long[] cadences = new long[limited.size()];
        int sampled = 0;
        for (final ThreadSummary t : limited) {
            if (t.unseenBelowNanos() > worst.unseenBelowNanos()) {
                worst = t;
            }
            final long c = inNative ? t.nativeCadenceNanos() : t.javaCadenceNanos();
            if (c > 0) {
                cadences[sampled++] = c;
            }
        }
        Arrays.sort(cadences, 0, sampled);
        final long cadence = sampled == 0 ? 0 : cadences[sampled / 2];
        final String type = inNative ? "jdk.NativeMethodSample" : "jdk.ExecutionSample";
        final long period = info.periodNanos(type);
        final StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "on %d of %d threads, a stall no event explains is %s", limited.size(), threads.size(), seen(limited)));
        if (cadence > 0) {
            sb.append(String.format(Locale.ROOT, ": each is sampled %s every ~%s", inNative ? "in native code" : "in Java",
                    Durations.format(cadence)));
            if (period > 0 && cadence >= 2 * period) {
                sb.append(inNative
                        ? String.format(Locale.ROOT, ", about %d threads in native code sharing the sampler's one "
                                + "native slot per %s period", Math.round((double) cadence / period), Durations.format(period))
                        : String.format(Locale.ROOT, ", about %d threads in Java sharing its %d Java slots per %s period",
                                Math.round((double) JAVA_PER_PERIOD * cadence / period), JAVA_PER_PERIOD,
                                Durations.format(period)));
            }
        }
        if (period > 0) {
            sb.append(". ").append(remedy(type, period, worst));
        }
        out.add(sb.toString());
    }

    /**
     * The period that brings the worst of them to the gap. A thread's routine absence is the
     * slot's round trip, which scales with the period, plus whatever the thread spent where no
     * slot sees it, which does not; the shortest visible stall is three of those absences.
     * Floored at the sampler's own minimum, below which the rest can only come from events.
     */
    private String remedy(final String type, final long period, final ThreadSummary worst) {
        if (period <= MIN_SAMPLER_PERIOD) {
            return "The sampler is at its shortest period already: only blocking events can show these stalls, "
                    + "so record them with low thresholds and no throttle";
        }
        final double absence = worst.unseenBelowNanos() / 3.0;
        final double roundTrip = Math.min(worst.roundTripNanos(), absence);
        final double own = absence - roundTrip;
        // own + roundTrip * p / period <= gap / 3, solved for p.
        final double wanted = (gapNanos / 3.0 - own) * period / Math.max(1, roundTrip);
        // Shorter than now by a whole millisecond where it can be, never under the sampler's floor.
        final long suggested = Math.max(MIN_SAMPLER_PERIOD, Math.min(period - MIN_SAMPLER_PERIOD,
                (long) wanted / MIN_SAMPLER_PERIOD * MIN_SAMPLER_PERIOD));
        final long reached = (long) (3 * (own + roundTrip * suggested / period));
        final String setting = type + "#period=" + suggested / MIN_SAMPLER_PERIOD + "ms";
        return reached <= gapNanos ? "Record with " + setting + " to see them from the gap"
                : "Record with " + setting + (suggested == MIN_SAMPLER_PERIOD ? ", the shortest the sampler takes," : "")
                        + " to see them from ~" + Durations.format(reached) + "; shorter ones only blocking events can show";
    }

    /** Whether no watched thread had anything to judge it by: both renderers then say {@link #NO_THREAD}. */
    public boolean isNoThreadMatched() {
        return threads.isEmpty() && stalls.isEmpty();
    }

    public List<Stall> top(final int n) {
        return top(stalls, n);
    }

    /**
     * The threads a per-thread table leaves out, as both renderers print it:
     * {@code 12 more that stalled, 110 threads with no stall}, leaving out a zero.
     */
    public static String notListed(final int moreStalled, final int clean) {
        final StringBuilder sb = new StringBuilder();
        if (moreStalled > 0) {
            sb.append(moreStalled).append(" more that stalled");
        }
        if (clean > 0) {
            sb.append(sb.isEmpty() ? "" : ", ").append(clean).append(clean == 1 ? " thread" : " threads")
                    .append(" with no stall");
        }
        return sb.toString();
    }

    /** The first {@code n} of a list already in the order it is to be read. */
    public static List<Stall> top(final List<Stall> stalls, final int n) {
        return stalls.size() > n ? stalls.subList(0, n) : stalls;
    }

    /**
     * The stalls the recording can explain, longest first. They are kept apart from
     * {@link #unexplained()} because ranking the two together puts the biggest number on the
     * row that says least: a 47 s gap with no evidence above a 17 s park with a stack under it.
     * A gap and a park are different kinds of claim, so they are different lists.
     */
    public List<Stall> explained() {
        return withVerdict(false);
    }

    /** The stalls with no blocking event and too few samples to say anything, longest first. */
    public List<Stall> unexplained() {
        return withVerdict(true);
    }

    private List<Stall> withVerdict(final boolean unexplained) {
        final List<Stall> out = new ArrayList<>();
        for (final Stall s : stalls) {
            if ((s.verdict() == Stall.Verdict.UNEXPLAINED) == unexplained) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /** Totals per verdict: count and summed duration, largest total first. */
    public record VerdictSummary(Stall.Verdict verdict, int count, long totalNanos, long worstNanos) {
    }

    /**
     * Totals per verdict, flat and indexed by ordinal (G-1.8, G-1.9): three longs per verdict
     * in a power-of-two stride, the fourth slot unused.
     */
    private static final int TOTALS_STRIDE = 4;
    private static final int COUNT = 0;
    private static final int TOTAL = 1;
    private static final int WORST = 2;

    public List<VerdictSummary> byVerdict() {
        final Stall.Verdict[] verdicts = Stall.Verdict.values();
        final long[] totals = new long[verdicts.length * TOTALS_STRIDE];
        for (final Stall s : stalls) {
            final int base = s.verdict().ordinal() * TOTALS_STRIDE;
            totals[base + COUNT]++;
            totals[base + TOTAL] += s.duration();
            totals[base + WORST] = Math.max(totals[base + WORST], s.duration());
        }
        final List<VerdictSummary> out = new ArrayList<>();
        for (final Stall.Verdict v : verdicts) {
            final int base = v.ordinal() * TOTALS_STRIDE;
            if (totals[base + COUNT] > 0) {
                out.add(new VerdictSummary(v, (int) totals[base + COUNT], totals[base + TOTAL], totals[base + WORST]));
            }
        }
        out.sort(Comparator.comparingLong(VerdictSummary::totalNanos).reversed());
        return out;
    }

    /**
     * The threads that stalled, most stalled first: the rows a reader acts on. The others are
     * counted, not listed, by both renderers; on a whole JVM they were most of a 235-line
     * report, and what they cannot show is the {@link #unseen()} line's to say.
     */
    public List<ThreadSummary> stalledThreads() {
        final List<ThreadSummary> out = new ArrayList<>();
        for (final ThreadSummary t : threads) {
            if (t.stalls() > 0) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparingLong(ThreadSummary::stalledNanos).reversed()
                .thenComparing(t -> t.thread().name()));
        return out;
    }

    public List<Stall> stallsOf(final ThreadRef thread) {
        final List<Stall> out = new ArrayList<>();
        for (final Stall s : stalls) {
            if (s.thread().equals(thread)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingLong(Stall::start));
        return out;
    }
}
