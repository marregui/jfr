// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.jfrq.core.jfr.RecordingInfo;
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
        Map<String, Map<Stack, Long>> siteByThread) {

    /** A ranked row: key, bytes and share of the report total. */
    public record Row<K>(K key, long bytes, double share) {
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
