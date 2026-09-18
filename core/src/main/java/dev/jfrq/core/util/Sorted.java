package dev.jfrq.core.util;

import java.util.List;
import java.util.function.ToLongFunction;

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
    public static <T> int lowerBound(List<T> list, ToLongFunction<T> key, long value) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (key.applyAsLong(list.get(mid)) < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The longest {@code length} over the list, or 0 when empty. */
    public static <T> long maxLength(List<T> list, ToLongFunction<T> length) {
        long max = 0;
        for (T t : list) {
            max = Math.max(max, length.applyAsLong(t));
        }
        return max;
    }
}
