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

    public static Interval ofLength(final long start, final long length) {
        return new Interval(start, start + length);
    }

    public long length() {
        return end - start;
    }

    public boolean contains(final long t) {
        return t >= start && t < end;
    }

    /** Length of the overlap with {@code o}, zero when disjoint. */
    public long overlap(final Interval o) {
        final long s = Math.max(start, o.start);
        final long e = Math.min(end, o.end);
        return e > s ? e - s : 0;
    }

    public boolean overlaps(final Interval o) {
        return overlap(o) > 0;
    }

    /** The smallest interval covering both. */
    public Interval union(final Interval o) {
        return new Interval(Math.min(start, o.start), Math.max(end, o.end));
    }

    /**
     * This interval cut down to {@code window}, which is the part of it a recording can
     * speak for. A blocking event that began before the recording's span, or was still
     * running at its end, is written to the file whole: counted whole it puts more time
     * inside the window than the window holds. Returns {@code this} when nothing is cut,
     * so the common case allocates nothing, and a zero-length interval inside
     * {@code window} when the two are disjoint.
     */
    public Interval clampTo(final Interval window) {
        if (start >= window.start && end <= window.end) {
            return this;
        }
        final long s = Math.min(Math.max(start, window.start), window.end);
        final long e = Math.min(Math.max(end, window.start), window.end);
        return new Interval(s, e);
    }

    @Override
    public int compareTo(final Interval o) {
        final int c = Long.compare(start, o.start);
        return c != 0 ? c : Long.compare(end, o.end);
    }
}
