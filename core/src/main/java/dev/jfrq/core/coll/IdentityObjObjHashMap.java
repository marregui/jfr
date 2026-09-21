// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.coll;

import java.util.Arrays;

/**
 * {@link ObjObjHashMap} with keys compared by reference and hashed by identity, for the
 * pool objects the JFR parser shares between events, where identity is both the cheapest
 * and the right notion of equality. A separate class rather than a subclass, so the probe
 * loop stays monomorphic (G-1.3). No delete: these are caches that are cleared whole.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class IdentityObjObjHashMap<K, V> implements Mutable {

    private Object[] keys;
    private Object[] values;
    private int mask;
    private int free;
    private int size;

    public IdentityObjObjHashMap(final int initialCapacity) {
        final int capacity = Hashing.capacityFor(initialCapacity);
        keys = new Object[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        free = Hashing.freeFor(capacity);
    }

    @Override
    public void clear() {
        Arrays.fill(keys, null);
        Arrays.fill(values, null);
        free = Hashing.freeFor(keys.length);
        size = 0;
    }

    /** The value for {@code key}, or {@code null} when absent. */
    public V get(final K key) {
        final int index = keyIndex(key);
        return index < 0 ? valueAtQuick(index) : null;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** See {@link ObjObjHashMap#keyIndex}: negative means present at {@code -index - 1}. */
    public int keyIndex(final K key) {
        final int index = Hashing.spread(System.identityHashCode(key)) & mask;
        final Object k = keys[index];
        if (k == null) {
            return index;
        }
        if (k == key) {
            return -index - 1;
        }
        return probe(key, index);
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

    public int size() {
        return size;
    }

    /** {@link ObjObjHashMap#valueAt} without the sign check (G-1.6). */
    @SuppressWarnings("unchecked")
    public V valueAtQuick(final int index) {
        assert index < 0;
        return (V) values[-index - 1];
    }

    private int probe(final K key, int index) {
        do {
            index = (index + 1) & mask;
            final Object k = keys[index];
            if (k == null) {
                return index;
            }
            if (k == key) {
                return -index - 1;
            }
        } while (true);
    }

    private void rehash() {
        final Object[] oldKeys = keys;
        final Object[] oldValues = values;
        final int capacity = oldKeys.length << 1;
        keys = new Object[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        free = Hashing.freeFor(capacity) - size;
        for (int i = 0; i < oldKeys.length; i++) {
            final Object k = oldKeys[i];
            if (k != null) {
                int index = Hashing.spread(System.identityHashCode(k)) & mask;
                while (keys[index] != null) {
                    index = (index + 1) & mask;
                }
                keys[index] = k;
                values[index] = oldValues[i];
            }
        }
    }
}
