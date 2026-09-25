// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.util.Bytes;
import dev.jfrq.core.util.Durations;

/**
 * What {@code health} found: what the JVM reported about itself as trouble, the garbage
 * collector's account, how memory, threads and CPU moved across the window, and which
 * throwables were created where.
 *
 * <p>A finding is only ever one of {@link Finding.Kind}, each read from the JVM's own events:
 * an out-of-memory error Java code created, a collection that failed or took the whole heap, a line it draws for
 * itself (its GC time goal, its pause target) and crossed, or a collection forced by
 * something other than a full young generation. A trend is not a finding: whether 3 MB of
 * resident growth in four minutes matters is the reader's call, so the numbers are reported
 * and no verdict is attached to them.
 *
 * @param info       the recording
 * @param findings   in {@link Finding.Kind} order, the most serious first
 * @param gc         the collector's account
 * @param trends     heap after GC, resident set, live threads, JVM and machine CPU; a series the
 *                   recording has no events for is left out
 * @param threads    thread starts over the window
 * @param throwables the throwables created, by class and by site
 */
public record HealthReport(RecordingInfo info, List<Finding> findings, Gc gc, List<Series> trends, Threads threads,
                           Throwables throwables) {

    /** G1's concurrent marking cycle, reported as a collection of its own. */
    public static final String CONCURRENT_CYCLE = "G1Old";

    /** What both renderers say when the recording has none of the events a trend is read from. */
    public static final String NO_TRENDS = "no jdk.GCHeapSummary, jdk.ResidentSetSize, jdk.JavaThreadStatistics or "
            + "jdk.CPULoad events in the recording";

    /** What both renderers say when there is no finding. */
    public static final String NO_FINDINGS = "no OutOfMemoryError Java code created, no failed evacuation or full "
            + "collection, GC time and pauses within the JVM's goals, and no collection forced by a humongous "
            + "allocation, metaspace or System.gc()";

    /** What a throwable's site is, in the words both renderers print above the sites. */
    public static final String SITE_RULE = "the first frame outside the JDK below the throwable's own construction";

    /** {@code G1New 37, G1Old 17}: a count per name, in the map's order. */
    public static String counts(final Map<String, Long> counts) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, Long> e : counts.entrySet()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * When a class's throwables were created, as both renderers print it under {@link #WHEN}:
     * {@code +5.111s, +5.972s, +605.594s} for the first, the median and the last, one offset when
     * there was one. The per-second rate is over the whole window, so this is what tells a
     * start-up burst from a steady trickle.
     */
    public static String when(final ClassRow c, final long startNanos) {
        final String first = Durations.offset(c.firstNanos() - startNanos);
        return c.firstNanos() == c.lastNanos() ? first : first + ", " + Durations.offset(c.medianNanos() - startNanos)
                + ", " + Durations.offset(c.lastNanos() - startNanos);
    }

    /** The column {@link #when} fills. */
    public static final String WHEN = "First, median, last";

    /** What both renderers say when no throwable is in the file. */
    public static final String NO_THROWS = "no jdk.JavaExceptionThrow events: none was created, or the event was off "
            + "(both JDK 25 settings files enable it)";

    public HealthReport {
        findings = List.copyOf(findings);
        trends = List.copyOf(trends);
    }

    /**
     * One condition the JVM reports about itself: code ran out of direct memory, it found no
     * room in the heap to copy live objects to, collected the whole heap in a pause, missed its own goals,
     * or was made to collect by something other than a full young generation.
     *
     * @param count      how many times it happened in the window
     * @param firstNanos when it first happened, epoch nanoseconds; {@code Nulls.LONG_NULL} for a
     *                   finding about the window as a whole
     * @param lastNanos  when it last happened, likewise
     * @param text       the sentence both renderers print
     */
    public record Finding(Kind kind, long count, long firstNanos, long lastNanos, String text) {

        /**
         * The order is the ranking: an error the application saw before a GC it paid for, and
         * a heap with no room left before a heap merely collected whole. The JVM's own
         * {@code OutOfMemoryError} (the heap, metaspace) and {@code StackOverflowError} are
         * never recorded, as the JVM makes them without running their constructors; an
         * evacuation failure is the step before the first.
         */
        public enum Kind {
            OUT_OF_MEMORY,
            EVACUATION_FAILED,
            FULL_GC,
            GC_TIME_OVER_GOAL,
            PAUSE_OVER_TARGET,
            HUMONGOUS_ALLOCATION,
            METASPACE_GC,
            SYSTEM_GC
        }
    }

    /**
     * The collector's account of the window.
     *
     * @param collections      per collector name ({@code G1New}, {@code G1Old}, {@code G1Full}), most first
     * @param causes           per cause ({@code G1 Evacuation Pause}), most first; a G1 concurrent cycle
     *                         ({@code G1Old}) is left out, as it carries the cause of the pause that started it
     * @param oldCycles        old-generation collections ({@code jdk.OldGarbageCollection}): for G1 the
     *                         concurrent marking cycles, which are routine, and every full collection
     * @param pauseNanos       the stop-the-world time, summed over every collection
     * @param longestPauseNanos the longest single pause
     * @param gcTimeRatio      the JVM's {@code GCTimeRatio}: it aims to spend at most
     *                         {@code 1 / (1 + ratio)} of the time collecting; {@code Nulls.INT_NULL} unknown
     * @param pauseTargetNanos {@code MaxGCPauseMillis}, when the collector has one; {@code Nulls.LONG_NULL} unset
     * @param maxHeapBytes     the heap's maximum size; {@code Nulls.LONG_NULL} unknown
     */
    public record Gc(Map<String, Long> collections, Map<String, Long> causes, long oldCycles, long pauseNanos,
                     long longestPauseNanos, int gcTimeRatio, long pauseTargetNanos, long maxHeapBytes) {

        public Gc {
            collections = ordered(collections);
            causes = ordered(causes);
        }

        /** Why the causes add up to fewer than the collections, when they do; empty otherwise. */
        public String causesNote() {
            return collections.containsKey(CONCURRENT_CYCLE) ? " (a G1 concurrent cycle carries the cause of the pause "
                    + "that started it and is not counted again)" : "";
        }

        public long count() {
            long n = 0;
            for (final long c : collections.values()) {
                n += c;
            }
            return n;
        }
    }

    /**
     * How one quantity moved across the window. The floor of a third is its lowest value
     * there: a heap after GC that leaks has a floor that rises from the first third to the
     * last, while one that is merely busy has peaks that come and go.
     *
     * @param name       {@code Heap after GC}
     * @param unit       what the values are
     * @param points     how many observations
     * @param floorFirst the lowest value in the first third of the observations; NaN under three
     * @param floorLast  the lowest value in the last third; NaN under three
     */
    public record Series(String name, Unit unit, int points, double start, double end, double min, double max,
                         double mean, double floorFirst, double floorLast) {

        public enum Unit {
            BYTES,
            COUNT,
            /** A share of the machine's CPU, 0 to 1. */
            FRACTION
        }

        /** One value as both renderers print it; a dash for a value the series does not have. */
        public String format(final double value) {
            if (Double.isNaN(value)) {
                return "—";
            }
            return switch (unit) {
                case BYTES -> Bytes.format((long) value);
                case COUNT -> String.format(Locale.ROOT, "%.0f", value);
                case FRACTION -> String.format(Locale.ROOT, "%.1f%%", value * 100);
            };
        }
    }

    /**
     * @param started threads started over the window, from the JVM's own running total
     *                ({@code jdk.JavaThreadStatistics}); {@code Nulls.LONG_NULL} unknown
     * @param peak    the most threads alive at once since the JVM started; {@code Nulls.LONG_NULL} unknown
     */
    public record Threads(long started, long peak) {

        /**
         * {@code 54 threads started in the window; at most 113 alive at once since the JVM
         * started}, as both renderers print it; empty when the starts are unknown.
         */
        public String sentence() {
            if (started == Nulls.LONG_NULL) {
                return "";
            }
            return started + (started == 1 ? " thread" : " threads") + " started in the window"
                    + (peak == Nulls.LONG_NULL ? "" : "; at most " + peak + " alive at once since the JVM started");
        }
    }

    /**
     * The throwables created in the window. JFR emits {@code jdk.JavaExceptionThrow} in the
     * {@code Throwable} constructor, so this counts creations: an object built only to capture
     * a stack trace counts, and a rethrow does not count again.
     *
     * @param created      every throwable created in the window, from {@code jdk.ExceptionStatistics}'
     *                     running total, with the second count the JDK gives each {@code Error} taken
     *                     back out; {@code Nulls.LONG_NULL} unknown
     * @param createdNanos the stretch that total covers, between its first and last reading
     * @param samples      {@code jdk.JavaExceptionThrow} events, one per throwable (an {@code Error}'s
     *                     second is skipped): every throwable when not throttled
     * @param throttle     the event's throttle ({@code 300/s}), or {@code null}: a throttled event is a sample
     * @param byClass      most first
     * @param bySite       most first
     * @param errors       {@code jdk.JavaErrorThrow} per class: the {@code Error}s, which are the ones
     *                     that end requests or threads
     */
    public record Throwables(long created, long createdNanos, long samples, String throttle, List<ClassRow> byClass,
                             List<SiteRow> bySite, Map<String, Long> errors) {

        public Throwables {
            byClass = List.copyOf(byClass);
            bySite = List.copyOf(bySite);
            errors = ordered(errors);
        }

        /** Throwables created per second, exact; NaN when the running total was not recorded twice. */
        public double rate() {
            return created == Nulls.LONG_NULL || createdNanos <= 0 ? Double.NaN
                    : created * 1e9 / createdNanos;
        }
    }

    /**
     * @param className the JVM name of the throwable's class
     * @param samples   the events of that class
     * @param share     of all the events
     * @param message   the message of the first event of the class, as an example; {@code null} when that
     *                  one had none
     * @param firstNanos  when the first of them was created, epoch nanoseconds
     * @param medianNanos when half of them had been: a start-up burst has it near the first, a
     *                    steady rate near the middle of the window, whatever straggler comes last
     * @param lastNanos   when the last was
     */
    public record ClassRow(String className, long samples, double share, String message, long firstNanos,
                           long medianNanos, long lastNanos) {
    }

    /**
     * @param site      the first frame outside the JDK below the throwable's own construction, as
     *                  {@code package.Class.method}; the first frame there when every one is the JDK's
     * @param className the throwable's class: one site can create several
     * @param stack     the first stack seen there, from the end of the throwable's construction down
     */
    public record SiteRow(String site, String className, long samples, double share, Stack stack) {
    }

    /** An unmodifiable copy that keeps the order it was given: most first. */
    private static Map<String, Long> ordered(final Map<String, Long> counts) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
}
