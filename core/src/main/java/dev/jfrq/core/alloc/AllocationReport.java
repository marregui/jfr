package dev.jfrq.core.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
 * @param samples    number of allocation events consumed
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

    public double rate(long bytes) {
        return bytes / seconds();
    }

    public List<Row<String>> threads(int top) {
        return rank(byThread, top);
    }

    public List<Row<String>> classes(int top) {
        return rank(byClass, top);
    }

    public List<Row<Stack>> sites(int top) {
        return rank(bySite, top);
    }

    public List<Row<String>> classesOf(String thread, int top) {
        return rank(classByThread.getOrDefault(thread, Map.of()), top);
    }

    public List<Row<Stack>> sitesOf(String thread, int top) {
        return rank(siteByThread.getOrDefault(thread, Map.of()), top);
    }

    /** Ranks a map by value; shares are relative to the report's total, not the map's. */
    <K> List<Row<K>> rank(Map<K, Long> map, int top) {
        List<Row<K>> rows = new ArrayList<>(map.size());
        double total = Math.max(totalBytes, 1);
        for (Map.Entry<K, Long> e : map.entrySet()) {
            rows.add(new Row<>(e.getKey(), e.getValue(), e.getValue() / total));
        }
        rows.sort(Comparator.<Row<K>>comparingLong(Row::bytes).reversed());
        return rows.size() > top ? List.copyOf(rows.subList(0, top)) : List.copyOf(rows);
    }
}
