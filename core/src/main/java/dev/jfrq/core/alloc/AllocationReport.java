// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Stack;

/**
 * Estimated allocation pressure, by thread, class and site, from one recording.
 *
 * <p>Every number is an estimate derived from sampled events (see {@link #source()});
 * the estimates are statistically sound for ranking and for sizeable shares, not for
 * the last few percent.
 *
 * @param info       the recording the report was built from
 * @param source     the event type the estimate is based on, e.g. {@code jdk.ObjectAllocationSample}
 * @param totalBytes estimated bytes allocated over the recording
 * @param samples    number of allocation events in the estimate
 * @param events     number of allocation events seen, including each thread's discarded first sample
 * @param countedByThread per thread name, what the JVM's own counter ({@code jdk.ThreadAllocationStatistics})
 *                   grew by between the first and last time the thread was seen; only threads
 *                   seen at least twice, so a thread that lived between two counter events is absent
 * @param byThread   estimated bytes per thread name
 * @param byClass    estimated bytes per allocated class (JVM name)
 * @param bySite     estimated bytes per allocation stack
 * @param classByThread per thread name, bytes per class
 * @param siteByThread  per thread name, bytes per stack
 * @param support    how many samples stand behind each row: a rate built on a handful of
 *                   them is noise, and a percentage printed next to it reads as a finding
 */
public record AllocationReport(
        RecordingInfo info,
        String source,
        long totalBytes,
        long samples,
        long events,
        Map<String, Long> countedByThread,
        Map<String, Long> byThread,
        Map<String, Long> byClass,
        Map<Stack, Long> bySite,
        Map<String, Map<String, Long>> classByThread,
        Map<String, Map<Stack, Long>> siteByThread,
        Support support) {

    /**
     * Samples per key, alongside the bytes. The estimate weights every sample by the bytes
     * it stands for, so two rows of equal size can rest on 2 000 samples and on 3; only the
     * count says which.
     */
    public record Support(Map<String, Long> byThread, Map<String, Long> byClass, Map<Stack, Long> bySite) {
        public static final Support NONE = new Support(Map.of(), Map.of(), Map.of());

        public long thread(final String name) {
            return byThread.getOrDefault(name, 0L);
        }

        public long className(final String name) {
            return byClass.getOrDefault(name, 0L);
        }

        public long site(final Stack stack) {
            return bySite.getOrDefault(stack, 0L);
        }
    }

    /** A ranked row: key, bytes and share of the report total. */
    public record Row<K>(K key, long bytes, double share) {
    }

    /**
     * A ranked site: what the fold called it, the stack that stands for it, and the evidence
     * behind the whole row.
     *
     * @param label   the name the stacks were summed under, e.g. {@code com.example.Parser.parse}
     * @param stack   the biggest single stack in the row, printed under it
     * @param samples the samples behind every stack in the row, not only {@code stack}
     * @param stacks  how many distinct stacks were summed
     */
    public record SiteRow(String label, Stack stack, long bytes, double share, long samples, int stacks) {
    }

    public double seconds() {
        return Math.max(info.span().length(), 1) / 1e9;
    }

    /** Estimated bytes per second over the whole recording. */
    public double rate() {
        return totalBytes / seconds();
    }

    /** Whether the recording carries the JVM's own allocation counters for at least one thread. */
    public boolean hasCounters() {
        return !countedByThread.isEmpty();
    }

    /** The JVM's counters summed over the threads that have one. */
    public long countedBytes() {
        long total = 0;
        for (final long b : countedByThread.values()) {
            total += b;
        }
        return total;
    }

    /** The estimate restricted to the threads that have a counter, so the two compare like for like. */
    public long estimatedOnCountedThreads() {
        long total = 0;
        for (final String thread : countedByThread.keySet()) {
            total += byThread.getOrDefault(thread, 0L);
        }
        return total;
    }

    /**
     * The share of the estimate that the counters can speak for: the estimate on the
     * counted threads over the whole estimate. A thread that started and ended between two
     * counter events has no counter, so in a recording where short-lived pool threads did
     * most of the allocating, {@link #estimateError()} is a statement about a minority of
     * the report and reading it as the error of the whole would be wrong.
     */
    public double countedCoverage() {
        return totalBytes > 0 ? (double) estimatedOnCountedThreads() / totalBytes : 0;
    }

    /**
     * How far the estimate is from the JVM's counters on the threads that have one, as a
     * signed fraction of the counters ({@code 0.07} means the estimate is 7 % high); 0 when
     * nothing was counted.
     */
    public double estimateError() {
        final long counted = countedBytes();
        return counted > 0 ? (double) (estimatedOnCountedThreads() - counted) / counted : 0;
    }

    /**
     * Whether {@link #estimateError()} says anything: the counted threads must carry at
     * least 1 % of the estimate. Below that the two numbers differ by start-up noise (the
     * counters are read a few milliseconds after sampling begins) and a percentage would
     * only alarm.
     */
    public boolean estimateErrorMaterial() {
        final long counted = countedBytes();
        return counted > 0 && counted >= totalBytes / 100;
    }

    /** The JVM's counter for a thread, when it was seen at least twice. */
    public Optional<Long> counted(final String thread) {
        return Optional.ofNullable(countedByThread.get(thread));
    }

    public double rate(final long bytes) {
        return bytes / seconds();
    }

    public List<Row<String>> threads(final int top) {
        return rank(byThread, top);
    }

    public List<Row<String>> classes(final int top) {
        return rank(byClass, top);
    }

    public List<Row<Stack>> sites(final int top) {
        return rank(bySite, top);
    }

    /**
     * Allocation sites ranked by bytes, every stack that {@code key} names the same summed
     * into one row. A logical site reaches the sampler down many paths, and one row per path
     * turns a fifth of the heap into a dozen rows of two percent; the raw map keeps them
     * apart, because {@link AllocationDiff#sites(int)} compares them one stack at a time.
     *
     * <p>The samples are summed with the bytes. A row whose bytes are the sum of ten stacks
     * and whose support is one of them says the most important row in the report rests on
     * three samples when it rests on thousands.
     */
    public List<SiteRow> sites(final SiteKey key, final int top) {
        final Map<String, long[]> totals = new HashMap<>();
        final Map<String, Stack> shown = new HashMap<>();
        final Map<String, Long> largest = new HashMap<>();
        for (final Map.Entry<Stack, Long> e : bySite.entrySet()) {
            final String label = key.of(e.getKey());
            final long[] t = totals.computeIfAbsent(label, _ -> new long[3]);
            t[0] += e.getValue();
            t[1] += support.site(e.getKey());
            t[2]++;
            // The stack that stands for the row is its biggest contributor, so the lines
            // printed under a row are the ones most of its bytes came through.
            final Long best = largest.get(label);
            if (best == null || e.getValue() > best) {
                largest.put(label, e.getValue());
                shown.put(label, e.getKey());
            }
        }
        final double total = Math.max(totalBytes, 1);
        final List<SiteRow> rows = new ArrayList<>(totals.size());
        totals.forEach((label, t) -> rows.add(new SiteRow(label, shown.get(label), t[0], t[0] / total, t[1], (int) t[2])));
        rows.sort(Comparator.comparingLong(SiteRow::bytes).reversed().thenComparing(SiteRow::label));
        return rows.size() > top ? List.copyOf(rows.subList(0, top)) : List.copyOf(rows);
    }

    /**
     * The non-JDK package roots the allocation came from, by bytes: the first two segments of
     * each culprit's class, which is the form {@code --app} takes. A reader who has never
     * seen the application before learns from its own report what to point the option at.
     */
    public List<Row<String>> packageRoots(final int top) {
        final Map<String, Long> bytes = new HashMap<>();
        for (final Map.Entry<Stack, Long> e : bySite.entrySet()) {
            final Frame culprit = e.getKey().culpritOrNull();
            if (culprit != null && !culprit.isJdk()) {
                bytes.merge(root(culprit.type()), e.getValue(), Long::sum);
            }
        }
        return rank(bytes, top);
    }

    private static String root(final String type) {
        final int first = type.indexOf('.');
        if (first < 0) {
            return "(default package)";
        }
        final int second = type.indexOf('.', first + 1);
        return second < 0 ? type.substring(0, first) : type.substring(0, second);
    }

    public List<Row<String>> classesOf(final String thread, final int top) {
        return rank(classByThread.getOrDefault(thread, Map.of()), top);
    }

    public List<Row<Stack>> sitesOf(final String thread, final int top) {
        return rank(siteByThread.getOrDefault(thread, Map.of()), top);
    }

    /** Ranks a map by value; shares are relative to the report's total, not the map's. */
    <K> List<Row<K>> rank(final Map<K, Long> map, final int top) {
        final List<Row<K>> rows = new ArrayList<>(map.size());
        final double total = Math.max(totalBytes, 1);
        for (final Map.Entry<K, Long> e : map.entrySet()) {
            rows.add(new Row<>(e.getKey(), e.getValue(), e.getValue() / total));
        }
        rows.sort(Comparator.<Row<K>>comparingLong(Row::bytes).reversed());
        return rows.size() > top ? List.copyOf(rows.subList(0, top)) : List.copyOf(rows);
    }
}
