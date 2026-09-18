// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.model;

/**
 * A half-open time interval {@code [start, end)} in nanoseconds since the epoch, which is
 * how every timestamp in this library is carried.
 */
public record Interval(long start, long end) implements Comparable<Interval> {

    public Interval {
        if (end < start) {
            throw new IllegalArgumentException("end " + end + " before start " + start);
        }
    }

    public static Interval ofLength(long start, long length) {
        return new Interval(start, start + length);
    }

    public long length() {
        return end - start;
    }

    public boolean contains(long t) {
        return t >= start && t < end;
    }

    /** Length of the overlap with {@code o}, zero when disjoint. */
    public long overlap(Interval o) {
        long s = Math.max(start, o.start);
        long e = Math.min(end, o.end);
        return e > s ? e - s : 0;
    }

    public boolean overlaps(Interval o) {
        return overlap(o) > 0;
    }

    /** The smallest interval covering both. */
    public Interval union(Interval o) {
        return new Interval(Math.min(start, o.start), Math.max(end, o.end));
    }

    @Override
    public int compareTo(Interval o) {
        int c = Long.compare(start, o.start);
        return c != 0 ? c : Long.compare(end, o.end);
    }
}
