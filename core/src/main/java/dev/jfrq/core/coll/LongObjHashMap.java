// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

import java.util.Arrays;

/**
 * {@code long}-keyed map of references (G-1.3): safepoint ids, event-type ids, lock
 * addresses. Open addressing over a power-of-two {@code long[]} with the
 * {@link #keyIndex} sign convention (G-1.5). The no-entry key marks a free slot; it is
 * {@code -1} unless the constructor says otherwise. Keys come from the recording, so any
 * {@code long} must be storable (G-5.5): an entry whose key equals the no-entry key lives
 * in one extra slot past the probed range, at index {@link #slots()}{@code - 1}, and is
 * found, iterated and rehashed like any other. Choosing a no-entry key the data rarely
 * holds keeps that slot empty; correctness does not depend on the choice.
 *
 * @param <V> the value type
 */
public final class LongObjHashMap<V> implements Mutable {

    private static final long DEFAULT_NO_ENTRY_KEY = -1L;

    // A field added below that holds contents must be reset in clear() too (G-3.2).
    private final long noEntryKey;
    /** {@code capacity + 1} slots: the probed range, then the no-entry key's own slot. */
    private long[] keys;
    private Object[] values;
    private int mask;
    private int free;
    private int size;
    private boolean hasNoEntryKey;

    public LongObjHashMap() {
        this(Hashes.MIN_CAPACITY);
    }

    public LongObjHashMap(final int initialCapacity) {
        this(initialCapacity, DEFAULT_NO_ENTRY_KEY);
    }

    /** @param noEntryKey the key value that marks a free slot; a key equal to it is stored in a slot of its own */
    public LongObjHashMap(final int initialCapacity, final long noEntryKey) {
        this.noEntryKey = noEntryKey;
        final int capacity = Hashes.capacityFor(initialCapacity);
        this.keys = new long[capacity + 1];
        this.values = new Object[capacity + 1];
        this.mask = capacity - 1;
        this.free = Hashes.freeFor(capacity);
        Arrays.fill(keys, noEntryKey);
    }

    @Override
    public void clear() {
        Arrays.fill(keys, noEntryKey);
        Arrays.fill(values, null);
        free = Hashes.freeFor(mask + 1);
        size = 0;
        hasNoEntryKey = false;
    }

    public boolean contains(final long key) {
        return keyIndex(key) < 0;
    }

    public boolean excludes(final long key) {
        return keyIndex(key) > -1;
    }

    /** The value for {@code key}, or {@code null} when absent. */
    public V get(final long key) {
        final int index = keyIndex(key);
        return index < 0 ? valueAtQuick(index) : null;
    }

    /** Whether {@code slot} (see {@link #slots()}) holds an entry. */
    public boolean hasKeyAtSlot(final int slot) {
        return slot > mask ? hasNoEntryKey : keys[slot] != noEntryKey;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** The key at {@code slot}; the no-entry key for a free slot, so test {@link #hasKeyAtSlot} first. */
    public long keyAtSlot(final int slot) {
        return keys[slot];
    }

    /** See {@link ObjObjHashMap#keyIndex}: negative means present at {@code -index - 1}. */
    public int keyIndex(final long key) {
        if (key == noEntryKey) {
            return hasNoEntryKey ? -(mask + 1) - 1 : mask + 1;
        }
        final int index = Hashes.spread(key) & mask;
        final long k = keys[index];
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
    public V put(final long key, final V value) {
        final int index = keyIndex(key);
        if (index < 0) {
            final V previous = valueAtQuick(index);
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
    public V putAt(final int index, final long key, final V value) {
        assert index >= 0 && keys[index] == noEntryKey;
        values[index] = value;
        size++;
        if (index > mask) {
            // The no-entry key's own slot: outside the probed range, so it uses no free slot.
            assert key == noEntryKey;
            hasNoEntryKey = true;
            return value;
        }
        keys[index] = key;
        if (--free == 0) {
            rehash();
        }
        return value;
    }

    public int size() {
        return size;
    }

    /** The number of slots to scan when iterating, the no-entry key's own slot included. */
    public int slots() {
        return keys.length;
    }

    /** The value a negative {@link #keyIndex} result denotes; throws on a non-negative one. */
    public V valueAt(final int index) {
        if (index >= 0) {
            throw new IllegalArgumentException("key is absent: keyIndex " + index);
        }
        return valueAtQuick(index);
    }

    /** {@link #valueAt} without the sign check (G-1.6). */
    @SuppressWarnings("unchecked")
    public V valueAtQuick(final int index) {
        assert index < 0;
        return (V) values[-index - 1];
    }

    /** The value at {@code slot} (see {@link #slots()}); {@code null} for a free slot. */
    @SuppressWarnings("unchecked")
    public V valueAtSlot(final int slot) {
        return (V) values[slot];
    }

    private int probe(final long key, int index) {
        do {
            index = (index + 1) & mask;
            final long k = keys[index];
            if (k == noEntryKey) {
                return index;
            }
            if (k == key) {
                return -index - 1;
            }
        } while (true);
    }

    private void rehash() {
        final long[] oldKeys = keys;
        final Object[] oldValues = values;
        final int oldCapacity = mask + 1;
        final int capacity = Hashes.grow(oldCapacity);
        keys = new long[capacity + 1];
        values = new Object[capacity + 1];
        Arrays.fill(keys, noEntryKey);
        values[capacity] = oldValues[oldCapacity];
        mask = capacity - 1;
        free = Hashes.freeFor(capacity) - (size - (hasNoEntryKey ? 1 : 0));
        for (int i = 0; i < oldCapacity; i++) {
            final long k = oldKeys[i];
            if (k != noEntryKey) {
                final int index = keyIndex(k);
                assert index >= 0;
                keys[index] = k;
                values[index] = oldValues[i];
            }
        }
    }
}
