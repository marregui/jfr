// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A growable list of references over a flat array (G-1.4). {@link #getQuick} is
 * unchecked; {@link #clear()} nulls every slot so the references are released.
 * Iteration is an indexed loop over {@link #size()}.
 *
 * @param <T> the element type
 */
public final class ObjList<T> implements Mutable {

    private static final int DEFAULT_CAPACITY = 16;

    private Object[] buffer;
    private int pos;

    public ObjList() {
        this(DEFAULT_CAPACITY);
    }

    public ObjList(final int capacity) {
        buffer = new Object[Math.max(capacity, 1)];
    }

    public void add(final T value) {
        checkCapacity(pos + 1);
        buffer[pos++] = value;
    }

    public void addAll(final ObjList<? extends T> other) {
        final int n = other.pos;
        checkCapacity(pos + n);
        System.arraycopy(other.buffer, 0, buffer, pos, n);
        pos += n;
    }

    /** Grows the backing array to hold at least {@code capacity} elements, doubling (G-1.7). */
    public void checkCapacity(final int capacity) {
        final int len = buffer.length;
        if (capacity > len) {
            final long doubled = Math.max((long) len << 1, capacity);
            buffer = Arrays.copyOf(buffer, (int) Math.min(doubled, Integer.MAX_VALUE - 8));
        }
    }

    @Override
    public void clear() {
        Arrays.fill(buffer, 0, pos, null);
        pos = 0;
    }

    /** Bounds-checked read: throws on a bad index. */
    public T get(final int index) {
        if (index < 0 || index >= pos) {
            throw new IndexOutOfBoundsException("index " + index + " of " + pos);
        }
        return getQuick(index);
    }

    /** The last element; throws when empty. */
    public T getLast() {
        return get(pos - 1);
    }

    /** Unchecked read: the caller has proven {@code index < size()} (G-1.6). */
    @SuppressWarnings("unchecked")
    public T getQuick(final int index) {
        assert index >= 0 && index < pos;
        return (T) buffer[index];
    }

    public boolean isEmpty() {
        return pos == 0;
    }

    public boolean notEmpty() {
        return pos > 0;
    }

    /** Unchecked write to an existing slot. */
    public void setQuick(final int index, final T value) {
        assert index >= 0 && index < pos;
        buffer[index] = value;
    }

    public int size() {
        return pos;
    }

    @SuppressWarnings("unchecked")
    public void sort(final Comparator<? super T> comparator) {
        Arrays.sort((T[]) buffer, 0, pos, comparator);
    }

    /** An immutable snapshot for report objects; the list itself stays reusable. */
    @SuppressWarnings("unchecked")
    public List<T> toList() {
        return List.of((T[]) Arrays.copyOf(buffer, pos));
    }

    @Override
    public String toString() {
        return Arrays.toString(Arrays.copyOf(buffer, pos));
    }
}
