// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.model.Stack;

/**
 * Compares two allocation reports, a baseline and a candidate, by <em>rate</em>
 * (bytes per second) rather than by total, so that recordings of different lengths are
 * comparable. Threads are matched by name, classes by name, sites by full stack; a key
 * present on one side only is reported against zero on the other.
 *
 * @param baseline the "before" report
 * @param current  the "after" report
 */
public record AllocationDiff(AllocationReport baseline, AllocationReport current) {

    /**
     * One compared key.
     *
     * @param key         thread name, class name or stack
     * @param beforeRate  bytes per second in the baseline
     * @param afterRate   bytes per second in the current report
     */
    public record Delta<K>(K key, double beforeRate, double afterRate) {
        public double delta() {
            return afterRate - beforeRate;
        }

        /** Relative change, or {@code +Infinity} when the baseline had nothing. */
        public double ratio() {
            if (beforeRate == 0) {
                return afterRate == 0 ? 0 : Double.POSITIVE_INFINITY;
            }
            return delta() / beforeRate;
        }
    }

    public Delta<String> total() {
        return new Delta<>("total", baseline.rate(), current.rate());
    }

    public List<Delta<String>> threads(int top) {
        return compare(baseline.byThread(), current.byThread(), top);
    }

    public List<Delta<String>> classes(int top) {
        return compare(baseline.byClass(), current.byClass(), top);
    }

    public List<Delta<Stack>> sites(int top) {
        return compare(baseline.bySite(), current.bySite(), top);
    }

    /** Sorted by absolute rate change, largest first. */
    <K> List<Delta<K>> compare(Map<K, Long> before, Map<K, Long> after, int top) {
        Set<K> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<Delta<K>> deltas = new ArrayList<>(keys.size());
        for (K k : keys) {
            double b = baseline.rate(before.getOrDefault(k, 0L));
            double a = current.rate(after.getOrDefault(k, 0L));
            deltas.add(new Delta<>(k, b, a));
        }
        deltas.sort(Comparator.<Delta<K>>comparingDouble(d -> Math.abs(d.delta())).reversed());
        return deltas.size() > top ? List.copyOf(deltas.subList(0, top)) : List.copyOf(deltas);
    }
}
