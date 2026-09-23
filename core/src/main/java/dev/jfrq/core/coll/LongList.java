// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

import java.util.Arrays;

/**
 * A growable list of {@code long}s over a flat array (G-1.3). {@link #clear()} only
 * resets the position; stale values past it are revealed by {@link #setPos}, so callers
 * that pre-size for index writes fill what they need. The no-entry value is what
 * {@link #getLast()} answers when empty (G-1.2); {@code -1} unless the constructor says
 * otherwise.
 */
public final class LongList implements Mutable {

    private static final int DEFAULT_CAPACITY = 16;
    private static final long DEFAULT_NO_ENTRY_VALUE = -1L;

    private final long noEntryValue;
    private long[] data;
    private int pos;

    public LongList() {
        this(DEFAULT_CAPACITY, DEFAULT_NO_ENTRY_VALUE);
    }

    public LongList(final int capacity) {
        this(capacity, DEFAULT_NO_ENTRY_VALUE);
    }

    public LongList(final int capacity, final long noEntryValue) {
        this.data = new long[Math.max(capacity, 1)];
        this.noEntryValue = noEntryValue;
    }

    public void add(final long value) {
        checkCapacity(pos + 1);
        data[pos++] = value;
    }

    /** Grows the backing array to hold at least {@code capacity} elements, doubling (G-1.7). */
    public void checkCapacity(final int capacity) {
        final int len = data.length;
        if (capacity > len) {
            final long doubled = Math.max((long) len << 1, capacity);
            data = Arrays.copyOf(data, (int) Math.min(doubled, Integer.MAX_VALUE - 8));
        }
    }

    @Override
    public void clear() {
        pos = 0;
    }

    /** Sparse write: grows the list to {@code index + 1} when needed; slots skipped over are stale. */
    public void extendAndSet(final int index, final long value) {
        checkCapacity(index + 1);
        if (index >= pos) {
            pos = index + 1;
        }
        data[index] = value;
    }

    /** Bounds-checked read: throws on a bad index. */
    public long get(final int index) {
        if (index < 0 || index >= pos) {
            throw new IndexOutOfBoundsException("index " + index + " of " + pos);
        }
        return data[index];
    }

    /** The last element, or the no-entry value when empty. */
    public long getLast() {
        return pos > 0 ? data[pos - 1] : noEntryValue;
    }

    /** Unchecked read: the caller has proven {@code index < size()} (G-1.6). */
    public long getQuick(final int index) {
        assert index >= 0 && index < pos;
        return data[index];
    }

    public boolean isEmpty() {
        return pos == 0;
    }

    /**
     * On a sorted list, the index of the first element at or above {@code value};
     * {@link #size()} when there is none.
     */
    public int lowerBound(final long value) {
        int lo = 0;
        int hi = pos;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (data[mid] < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public boolean notEmpty() {
        return pos > 0;
    }

    /** Pre-sizes for index writes; slots between the old and new size hold stale values. */
    public void setPos(final int size) {
        checkCapacity(size);
        pos = size;
    }

    /** Unchecked write to an existing slot. */
    public void setQuick(final int index, final long value) {
        assert index >= 0 && index < pos;
        data[index] = value;
    }

    public int size() {
        return pos;
    }

    public void sort() {
        Arrays.sort(data, 0, pos);
    }

    @Override
    public String toString() {
        return Arrays.toString(Arrays.copyOf(data, pos));
    }
}
