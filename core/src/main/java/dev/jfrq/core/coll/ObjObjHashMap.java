// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

import java.util.Arrays;

/**
 * Reference-keyed, reference-valued open-addressing map: linear probing over a
 * power-of-two array, the {@link #keyIndex} sign convention and backward-shift deletion
 * (G-1.3, G-1.5). Keys are compared with {@code equals} and hashed with {@code hashCode};
 * a {@code null} key is the free slot, so keys are never null. The get-or-insert idiom is
 * one probe:
 * <pre>
 *   int i = map.keyIndex(key);
 *   V v = i &lt; 0 ? map.valueAtQuick(i) : map.putAt(i, key, newValue());
 * </pre>
 *
 * <p>A {@code keyIndex} result is valid only until the next mutation.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class ObjObjHashMap<K, V> implements Mutable {

    // A field added below that holds contents must be reset in clear() too (G-3.2).
    private Object[] keys;
    private Object[] values;
    private int mask;
    private int free;
    private int size;

    public ObjObjHashMap() {
        this(Hashes.MIN_CAPACITY);
    }

    public ObjObjHashMap(final int initialCapacity) {
        final int capacity = Hashes.capacityFor(initialCapacity);
        keys = new Object[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        free = Hashes.freeFor(capacity);
    }

    @Override
    public void clear() {
        Arrays.fill(keys, null);
        Arrays.fill(values, null);
        free = Hashes.freeFor(keys.length);
        size = 0;
    }

    public boolean contains(final K key) {
        return keyIndex(key) < 0;
    }

    public boolean excludes(final K key) {
        return keyIndex(key) > -1;
    }

    /** The value for {@code key}, or {@code null} when absent. */
    public V get(final K key) {
        final int index = keyIndex(key);
        return index < 0 ? valueAtQuick(index) : null;
    }

    /** Whether {@code slot} (see {@link #slots()}) holds an entry. */
    public boolean hasKeyAtSlot(final int slot) {
        return keys[slot] != null;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** The key at {@code slot}, or {@code null} for a free slot. */
    @SuppressWarnings("unchecked")
    public K keyAtSlot(final int slot) {
        return (K) keys[slot];
    }

    /**
     * One probe that answers both "is it there" and "where": a negative result
     * {@code -index - 1} means the key is present at {@code index}; a non-negative result
     * is the free slot an insert of this key would take.
     */
    public int keyIndex(final K key) {
        final int index = Hashes.spread(key.hashCode()) & mask;
        final Object k = keys[index];
        if (k == null) {
            return index;
        }
        if (k == key || k.equals(key)) {
            return -index - 1;
        }
        return probe(key, index);
    }

    public boolean notEmpty() {
        return size > 0;
    }

    /** Inserts or replaces; returns the previous value or {@code null}. */
    public V put(final K key, final V value) {
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
    public V putAt(final int index, final K key, final V value) {
        assert index >= 0 && keys[index] == null;
        keys[index] = key;
        values[index] = value;
        size++;
        if (--free == 0) {
            rehash();
        }
        return value;
    }

    /** Removes {@code key} if present; re-homes the keys probed past it instead of leaving a tombstone. */
    public boolean remove(final K key) {
        final int index = keyIndex(key);
        if (index < 0) {
            removeAt(index);
            return true;
        }
        return false;
    }

    /** Removes the entry a negative {@link #keyIndex} result denotes. */
    public void removeAt(final int index) {
        assert index < 0;
        int slot = -index - 1;
        keys[slot] = null;
        values[slot] = null;
        size--;
        free++;
        int next = (slot + 1) & mask;
        while (keys[next] != null) {
            final int home = Hashes.spread(keys[next].hashCode()) & mask;
            if (Hashes.mayMove(slot, next, home)) {
                keys[slot] = keys[next];
                values[slot] = values[next];
                keys[next] = null;
                values[next] = null;
                slot = next;
            }
            next = (next + 1) & mask;
        }
    }

    public int size() {
        return size;
    }

    /** The number of slots to scan when iterating: {@code for (int s = 0, n = slots(); s < n; s++)}. */
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

    private int probe(final K key, int index) {
        do {
            index = (index + 1) & mask;
            final Object k = keys[index];
            if (k == null) {
                return index;
            }
            if (k == key || k.equals(key)) {
                return -index - 1;
            }
        } while (true);
    }

    private void rehash() {
        final Object[] oldKeys = keys;
        final Object[] oldValues = values;
        final int capacity = Hashes.grow(oldKeys.length);
        keys = new Object[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        free = Hashes.freeFor(capacity) - size;
        for (int i = 0; i < oldKeys.length; i++) {
            final Object k = oldKeys[i];
            if (k != null) {
                int index = Hashes.spread(k.hashCode()) & mask;
                while (keys[index] != null) {
                    index = (index + 1) & mask;
                }
                keys[index] = k;
                values[index] = oldValues[i];
            }
        }
    }
}
