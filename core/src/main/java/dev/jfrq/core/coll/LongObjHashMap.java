// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.coll;

import java.util.Arrays;

/**
 * {@code long}-keyed map of references (G-1.3): safepoint ids, event-type ids, lock
 * addresses. Open addressing over a power-of-two {@code long[]} with the
 * {@link #keyIndex} sign convention (G-1.5). The no-entry key marks a free slot and can
 * therefore never be stored; it is {@code -1} unless the constructor says otherwise.
 *
 * @param <V> the value type
 */
public final class LongObjHashMap<V> implements Mutable {

    private static final long DEFAULT_NO_ENTRY_KEY = -1L;

    private final long noEntryKey;
    private long[] keys;
    private Object[] values;
    private int mask;
    private int free;
    private int size;

    public LongObjHashMap() {
        this(Hashing.MIN_CAPACITY);
    }

    public LongObjHashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_NO_ENTRY_KEY);
    }

    /** @param noEntryKey the key value that marks a free slot; a key equal to it cannot be stored */
    public LongObjHashMap(int initialCapacity, long noEntryKey) {
        this.noEntryKey = noEntryKey;
        int capacity = Hashing.capacityFor(initialCapacity);
        this.keys = new long[capacity];
        this.values = new Object[capacity];
        this.mask = capacity - 1;
        this.free = Hashing.freeFor(capacity);
        Arrays.fill(keys, noEntryKey);
    }

    @Override
    public void clear() {
        Arrays.fill(keys, noEntryKey);
        Arrays.fill(values, null);
        free = Hashing.freeFor(keys.length);
        size = 0;
    }

    public boolean contains(long key) {
        return keyIndex(key) < 0;
    }

    public boolean excludes(long key) {
        return keyIndex(key) > -1;
    }

    /** The value for {@code key}, or {@code null} when absent. */
    public V get(long key) {
        int index = keyIndex(key);
        return index < 0 ? valueAtQuick(index) : null;
    }

    /** Whether {@code slot} (see {@link #slots()}) holds an entry. */
    public boolean hasKeyAtSlot(int slot) {
        return keys[slot] != noEntryKey;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** The key at {@code slot}, or the no-entry key for a free slot. */
    public long keyAtSlot(int slot) {
        return keys[slot];
    }

    /** See {@link ObjObjHashMap#keyIndex}: negative means present at {@code -index - 1}. */
    public int keyIndex(long key) {
        assert key != noEntryKey;
        int index = Hashing.spread(key) & mask;
        long k = keys[index];
        if (k == noEntryKey) {
            return index;
        }
        if (k == key) {
            return -index - 1;
        }
        return probe(key, index);
    }

    public boolean notEmpty() {
        return size > 0;
    }

    /** Inserts or replaces; returns the previous value or {@code null}. */
    public V put(long key, V value) {
        int index = keyIndex(key);
        if (index < 0) {
            V previous = valueAtQuick(index);
            values[-index - 1] = value;
            return previous;
        }
        putAt(index, key, value);
        return null;
    }

    /**
     * Stores a new entry at the free slot a non-negative {@link #keyIndex} result named.
     *
     * @return {@code value}, so the get-or-insert idiom is one expression
     */
    public V putAt(int index, long key, V value) {
        assert index >= 0 && keys[index] == noEntryKey;
        keys[index] = key;
        values[index] = value;
        size++;
        if (--free == 0) {
            rehash();
        }
        return value;
    }

    public int size() {
        return size;
    }

    /** The number of slots to scan when iterating. */
    public int slots() {
        return keys.length;
    }

    /** The value a negative {@link #keyIndex} result denotes; throws on a non-negative one. */
    public V valueAt(int index) {
        if (index >= 0) {
            throw new IllegalArgumentException("key is absent: keyIndex " + index);
        }
        return valueAtQuick(index);
    }

    /** {@link #valueAt} without the sign check (G-1.6). */
    @SuppressWarnings("unchecked")
    public V valueAtQuick(int index) {
        assert index < 0;
        return (V) values[-index - 1];
    }

    /** The value at {@code slot} (see {@link #slots()}); {@code null} for a free slot. */
    @SuppressWarnings("unchecked")
    public V valueAtSlot(int slot) {
        return (V) values[slot];
    }

    private int probe(long key, int index) {
        do {
            index = (index + 1) & mask;
            long k = keys[index];
            if (k == noEntryKey) {
                return index;
            }
            if (k == key) {
                return -index - 1;
            }
        } while (true);
    }

    private void rehash() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        int capacity = oldKeys.length << 1;
        keys = new long[capacity];
        values = new Object[capacity];
        Arrays.fill(keys, noEntryKey);
        mask = capacity - 1;
        free = Hashing.freeFor(capacity) - size;
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k != noEntryKey) {
                int index = keyIndex(k);
                assert index >= 0;
                keys[index] = k;
                values[index] = oldValues[i];
            }
        }
    }
}
