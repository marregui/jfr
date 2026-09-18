// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.coll;

import java.util.Arrays;

/**
 * Reference-keyed map of {@code long} values (G-1.3): the shape of every "bytes per
 * key" aggregation on the per-event path. Same layout and conventions as
 * {@link ObjObjHashMap}; {@link #increment} is one probe.
 *
 * @param <K> the key type
 */
public final class ObjLongHashMap<K> implements Mutable {

    private static final long DEFAULT_NO_ENTRY_VALUE = -1L;

    private final long noEntryValue;
    private Object[] keys;
    private long[] values;
    private int mask;
    private int free;
    private int size;

    public ObjLongHashMap() {
        this(Hashing.MIN_CAPACITY);
    }

    public ObjLongHashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_NO_ENTRY_VALUE);
    }

    /** @param noEntryValue what {@link #get} answers for an absent key (G-1.2) */
    public ObjLongHashMap(int initialCapacity, long noEntryValue) {
        this.noEntryValue = noEntryValue;
        int capacity = Hashing.capacityFor(initialCapacity);
        keys = new Object[capacity];
        values = new long[capacity];
        mask = capacity - 1;
        free = Hashing.freeFor(capacity);
    }

    @Override
    public void clear() {
        // Free slots' values are never read; the keys array is the source of truth.
        Arrays.fill(keys, null);
        free = Hashing.freeFor(keys.length);
        size = 0;
    }

    public boolean contains(K key) {
        return keyIndex(key) < 0;
    }

    public boolean excludes(K key) {
        return keyIndex(key) > -1;
    }

    /** The value for {@code key}, or the no-entry value. */
    public long get(K key) {
        int index = keyIndex(key);
        return index < 0 ? valueAtQuick(index) : noEntryValue;
    }

    /** Whether {@code slot} (see {@link #slots()}) holds an entry. */
    public boolean hasKeyAtSlot(int slot) {
        return keys[slot] != null;
    }

    /** Adds {@code delta} to the key's value, inserting {@code delta} for a new key; returns the new value. */
    public long increment(K key, long delta) {
        int index = keyIndex(key);
        if (index < 0) {
            return values[-index - 1] += delta;
        }
        putAt(index, key, delta);
        return delta;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** The key at {@code slot}, or {@code null} for a free slot. */
    @SuppressWarnings("unchecked")
    public K keyAtSlot(int slot) {
        return (K) keys[slot];
    }

    /** See {@link ObjObjHashMap#keyIndex}: negative means present at {@code -index - 1}. */
    public int keyIndex(K key) {
        int index = Hashing.spread(key.hashCode()) & mask;
        Object k = keys[index];
        if (k == null) {
            return index;
        }
        if (k == key || k.equals(key)) {
            return -index - 1;
        }
        return probe(key, index);
    }

    public long noEntryValue() {
        return noEntryValue;
    }

    public boolean notEmpty() {
        return size > 0;
    }

    /** Inserts or replaces. */
    public void put(K key, long value) {
        int index = keyIndex(key);
        if (index < 0) {
            values[-index - 1] = value;
        } else {
            putAt(index, key, value);
        }
    }

    /** Stores a new entry at the free slot a non-negative {@link #keyIndex} result named. */
    public void putAt(int index, K key, long value) {
        assert index >= 0 && keys[index] == null;
        keys[index] = key;
        values[index] = value;
        size++;
        if (--free == 0) {
            rehash();
        }
    }

    /** Removes {@code key} if present; re-homes the keys probed past it instead of leaving a tombstone. */
    public boolean remove(K key) {
        int index = keyIndex(key);
        if (index < 0) {
            removeAt(index);
            return true;
        }
        return false;
    }

    /** Removes the entry a negative {@link #keyIndex} result denotes. */
    public void removeAt(int index) {
        assert index < 0;
        int slot = -index - 1;
        keys[slot] = null;
        size--;
        free++;
        int next = (slot + 1) & mask;
        while (keys[next] != null) {
            int home = Hashing.spread(keys[next].hashCode()) & mask;
            if (Hashing.mayMove(slot, next, home)) {
                keys[slot] = keys[next];
                values[slot] = values[next];
                keys[next] = null;
                slot = next;
            }
            next = (next + 1) & mask;
        }
    }

    public int size() {
        return size;
    }

    /** The number of slots to scan when iterating. */
    public int slots() {
        return keys.length;
    }

    /** The value a negative {@link #keyIndex} result denotes; throws on a non-negative one. */
    public long valueAt(int index) {
        if (index >= 0) {
            throw new IllegalArgumentException("key is absent: keyIndex " + index);
        }
        return valueAtQuick(index);
    }

    /** {@link #valueAt} without the sign check (G-1.6). */
    public long valueAtQuick(int index) {
        assert index < 0;
        return values[-index - 1];
    }

    /** The value at {@code slot} (see {@link #slots()}); meaningless for a free slot. */
    public long valueAtSlot(int slot) {
        return values[slot];
    }

    private int probe(K key, int index) {
        do {
            index = (index + 1) & mask;
            Object k = keys[index];
            if (k == null) {
                return index;
            }
            if (k == key || k.equals(key)) {
                return -index - 1;
            }
        } while (true);
    }

    private void rehash() {
        Object[] oldKeys = keys;
        long[] oldValues = values;
        int capacity = oldKeys.length << 1;
        keys = new Object[capacity];
        values = new long[capacity];
        mask = capacity - 1;
        free = Hashing.freeFor(capacity) - size;
        for (int i = 0; i < oldKeys.length; i++) {
            Object k = oldKeys[i];
            if (k != null) {
                int index = Hashing.spread(k.hashCode()) & mask;
                while (keys[index] != null) {
                    index = (index + 1) & mask;
                }
                keys[index] = k;
                values[index] = oldValues[i];
            }
        }
    }
}
