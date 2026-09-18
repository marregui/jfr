// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.coll;

import java.util.Arrays;

/**
 * Reference-keyed open-addressing set (G-1.3), with the layout and conventions of
 * {@link ObjObjHashMap}. {@link #add} is one probe.
 *
 * @param <T> the element type
 */
public final class ObjHashSet<T> implements Mutable {

    private Object[] keys;
    private int mask;
    private int free;
    private int size;

    public ObjHashSet() {
        this(Hashing.MIN_CAPACITY);
    }

    public ObjHashSet(int initialCapacity) {
        int capacity = Hashing.capacityFor(initialCapacity);
        keys = new Object[capacity];
        mask = capacity - 1;
        free = Hashing.freeFor(capacity);
    }

    /** @return true when the element was not there before */
    public boolean add(T value) {
        int index = keyIndex(value);
        if (index < 0) {
            return false;
        }
        addAt(index, value);
        return true;
    }

    /** Stores a new element at the free slot a non-negative {@link #keyIndex} result named. */
    public void addAt(int index, T value) {
        assert index >= 0 && keys[index] == null;
        keys[index] = value;
        size++;
        if (--free == 0) {
            rehash();
        }
    }

    @Override
    public void clear() {
        Arrays.fill(keys, null);
        free = Hashing.freeFor(keys.length);
        size = 0;
    }

    public boolean contains(T value) {
        return keyIndex(value) < 0;
    }

    public boolean excludes(T value) {
        return keyIndex(value) > -1;
    }

    /** Whether {@code slot} (see {@link #slots()}) holds an element. */
    public boolean hasKeyAtSlot(int slot) {
        return keys[slot] != null;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** The element at {@code slot}, or {@code null} for a free slot. */
    @SuppressWarnings("unchecked")
    public T keyAtSlot(int slot) {
        return (T) keys[slot];
    }

    /** See {@link ObjObjHashMap#keyIndex}: negative means present at {@code -index - 1}. */
    public int keyIndex(T value) {
        int index = Hashing.spread(value.hashCode()) & mask;
        Object k = keys[index];
        if (k == null) {
            return index;
        }
        if (k == value || k.equals(value)) {
            return -index - 1;
        }
        return probe(value, index);
    }

    public boolean notEmpty() {
        return size > 0;
    }

    /** Removes {@code value} if present; re-homes the elements probed past it instead of leaving a tombstone. */
    public boolean remove(T value) {
        int index = keyIndex(value);
        if (index >= 0) {
            return false;
        }
        int slot = -index - 1;
        keys[slot] = null;
        size--;
        free++;
        int next = (slot + 1) & mask;
        while (keys[next] != null) {
            int home = Hashing.spread(keys[next].hashCode()) & mask;
            if (Hashing.mayMove(slot, next, home)) {
                keys[slot] = keys[next];
                keys[next] = null;
                slot = next;
            }
            next = (next + 1) & mask;
        }
        return true;
    }

    public int size() {
        return size;
    }

    /** The number of slots to scan when iterating. */
    public int slots() {
        return keys.length;
    }

    private int probe(T value, int index) {
        do {
            index = (index + 1) & mask;
            Object k = keys[index];
            if (k == null) {
                return index;
            }
            if (k == value || k.equals(value)) {
                return -index - 1;
            }
        } while (true);
    }

    private void rehash() {
        Object[] old = keys;
        int capacity = old.length << 1;
        keys = new Object[capacity];
        mask = capacity - 1;
        free = Hashing.freeFor(capacity) - size;
        for (Object k : old) {
            if (k != null) {
                int index = Hashing.spread(k.hashCode()) & mask;
                while (keys[index] != null) {
                    index = (index + 1) & mask;
                }
                keys[index] = k;
            }
        }
    }
}
