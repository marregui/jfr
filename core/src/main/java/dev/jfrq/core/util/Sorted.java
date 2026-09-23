// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.util.List;
import java.util.function.ToLongFunction;

import dev.jfrq.core.coll.ObjList;

/**
 * Window lookups over lists sorted by a start key. Interval lists in this library are
 * sorted by start; to find every element overlapping {@code [from, to)} it is enough to
 * scan from the first element whose start is at least {@code from - maxLength} and stop
 * at the first whose start is at least {@code to}.
 */
public final class Sorted {

    private Sorted() {
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
    public static <T> long maxLength(final List<T> list, final ToLongFunction<T> length) {
        long max = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            max = Math.max(max, length.applyAsLong(list.get(i)));
        }
        return max;
    }

    /** {@link #maxLength(List, ToLongFunction)} over an {@link ObjList}. */
    public static <T> long maxLength(final ObjList<T> list, final ToLongFunction<T> length) {
        long max = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            max = Math.max(max, length.applyAsLong(list.getQuick(i)));
        }
        return max;
    }
}
