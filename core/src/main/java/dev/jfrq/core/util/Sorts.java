// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.util.List;
import java.util.function.ToLongFunction;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.ObjList;

/**
 * Window lookups over lists sorted by a start key, and the order of a list of keys.
 * Interval lists in this library are sorted by start; to find every element overlapping
 * {@code [from, to)} it is enough to scan from the first element whose start is at least
 * {@code from - maxDuration} and stop at the first whose start is at least {@code to}.
 */
public final class Sorts {

    private Sorts() {
    }

    /** Index of the first element whose key is at least {@code value}; {@code list.size()} if none. */
    public static <T> int lowerBound(final List<T> list, final ToLongFunction<T> key, final long value) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (key.applyAsLong(list.get(mid)) < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** {@link #lowerBound(List, ToLongFunction, long)} over an {@link ObjList}. */
    public static <T> int lowerBound(final ObjList<T> list, final ToLongFunction<T> key, final long value) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (key.applyAsLong(list.getQuick(mid)) < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The longest {@code length} over the list, or 0 when empty. */
    public static <T> long maxDuration(final List<T> list, final ToLongFunction<T> length) {
        long max = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            max = Math.max(max, length.applyAsLong(list.get(i)));
        }
        return max;
    }

    /** {@link #maxDuration(List, ToLongFunction)} over an {@link ObjList}. */
    public static <T> long maxDuration(final ObjList<T> list, final ToLongFunction<T> length) {
        long max = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            max = Math.max(max, length.applyAsLong(list.getQuick(i)));
        }
        return max;
    }

    /**
     * The indexes of {@code keys} in ascending key order, equal keys in index order: parallel
     * lists read in time order without boxing an index per row (G-1.1). A bottom-up merge sort.
     */
    public static int[] order(final LongList keys) {
        final int n = keys.size();
        int[] from = new int[n];
        for (int i = 0; i < n; i++) {
            from[i] = i;
        }
        int[] to = new int[n];
        for (int width = 1; width < n; width <<= 1) {
            for (int lo = 0; lo < n; lo += width << 1) {
                final int mid = Math.min(lo + width, n);
                final int hi = Math.min(lo + (width << 1), n);
                int a = lo;
                int b = mid;
                for (int k = lo; k < hi; k++) {
                    to[k] = a < mid && (b >= hi || keys.getQuick(from[a]) <= keys.getQuick(from[b])) ? from[a++] : from[b++];
                }
            }
            final int[] swap = from;
            from = to;
            to = swap;
        }
        return from;
    }
}
