// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.coll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/**
 * The open-addressing tables against {@code java.util}: the same random sequence of
 * inserts, overwrites, deletes and lookups must leave both in the same state, through
 * several rehashes. The seed is printed and taken back through {@code -Djfrq.test.seed}
 * so a failing run can be replayed (G-11.4).
 */
class HashTablesTest {

    static final long SEED = Long.getLong("jfrq.test.seed", System.nanoTime());
    static final int OPERATIONS = 200_000;
    /** Few distinct keys, so deletes and re-inserts hit occupied neighbourhoods. */
    static final int KEY_SPACE = 4_000;

    static {
        System.out.println("HashTablesTest seed: " + SEED + " (replay with -Djfrq.test.seed=" + SEED + ")");
    }

    /** A key whose hash collides in bands, so linear probing runs long and deletes have to shift. */
    record Key(int id) {
        @Override
        public int hashCode() {
            return id >>> 3;
        }
    }

    @Test
    void objObjHashMapMatchesHashMap() {
        final SplittableRandom rnd = new SplittableRandom(SEED);
        final ObjObjHashMap<Key, String> map = new ObjObjHashMap<>(4);
        final Map<Key, String> reference = new HashMap<>();
        for (int op = 0; op < OPERATIONS; op++) {
            final Key k = new Key(rnd.nextInt(KEY_SPACE));
            switch (rnd.nextInt(4)) {
                case 0, 1 -> {
                    final String v = "v" + op;
                    assertEquals(reference.put(k, v), map.put(k, v));
                }
                case 2 -> assertEquals(reference.remove(k) != null, map.remove(k));
                default -> {
                    assertEquals(reference.get(k), map.get(k));
                    assertEquals(reference.containsKey(k), map.contains(k));
                    assertEquals(!reference.containsKey(k), map.excludes(k));
                }
            }
            assertEquals(reference.size(), map.size());
        }
        assertEquals(reference, slotsOf(map));
        assertEquals(reference.isEmpty(), map.isEmpty());
        assertEquals(!reference.isEmpty(), map.notEmpty());
        map.clear();
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertNull(map.get(new Key(1)));
    }

    @Test
    void objObjHashMapGetOrInsertIdiom() {
        final ObjObjHashMap<String, int[]> map = new ObjObjHashMap<>();
        final int i = map.keyIndex("a");
        assertTrue(i >= 0);
        final int[] counter = map.putAt(i, "a", new int[1]);
        counter[0]++;
        final int again = map.keyIndex("a");
        assertTrue(again < 0);
        assertEquals(1, map.valueAtQuick(again)[0]);
        assertEquals(1, map.valueAt(again)[0]);
        assertThrows(IllegalArgumentException.class, () -> map.valueAt(map.keyIndex("b")));
        final int slot = -again - 1;
        assertTrue(map.hasKeyAtSlot(slot));
        assertEquals("a", map.keyAtSlot(slot));
        assertEquals(counter, map.valueAtSlot(slot));
        // The result of putAt survives a rehash triggered by that very insert.
        final ObjObjHashMap<Integer, Integer> small = new ObjObjHashMap<>(1);
        for (int k = 0; k < 100; k++) {
            final int index = small.keyIndex(k);
            assertEquals(k, small.putAt(index, k, k));
            assertEquals(k, small.get(k));
        }
        assertEquals(100, small.size());
    }

    @Test
    void identityMapUsesReferencesNotEquals() {
        final IdentityObjObjHashMap<String, Integer> map = new IdentityObjObjHashMap<>(2);
        final String a = new String("key");
        final String b = new String("key");
        assertEquals(a, b);
        map.put(a, 1);
        assertEquals(1, map.get(a));
        assertNull(map.get(b));
        assertNull(map.put(b, 2));
        assertEquals(1, map.put(a, 3));
        assertEquals(2, map.size());
        assertEquals(3, map.get(a));
        assertEquals(2, map.get(b));
        // Growth through several rehashes keeps every entry reachable.
        final Object[] many = new Object[500];
        final IdentityObjObjHashMap<Object, Integer> grown = new IdentityObjObjHashMap<>(1);
        for (int i = 0; i < many.length; i++) {
            many[i] = new Object();
            final int index = grown.keyIndex(many[i]);
            assertTrue(index >= 0);
            assertEquals(i, grown.putAt(index, many[i], i));
            assertEquals(i + 1, grown.size());
        }
        for (int i = 0; i < many.length; i++) {
            final int index = grown.keyIndex(many[i]);
            assertTrue(index < 0);
            assertEquals(i, grown.valueAtQuick(index));
        }
        assertFalse(grown.isEmpty());
        grown.clear();
        assertTrue(grown.isEmpty());
        assertNull(grown.get(many[0]));
    }

    @Test
    void objLongHashMapMatchesHashMap() {
        final SplittableRandom rnd = new SplittableRandom(SEED);
        final ObjLongHashMap<Key> map = new ObjLongHashMap<>(4, Long.MIN_VALUE);
        final Map<Key, Long> reference = new HashMap<>();
        for (int op = 0; op < OPERATIONS; op++) {
            final Key k = new Key(rnd.nextInt(KEY_SPACE));
            switch (rnd.nextInt(5)) {
                case 0 -> {
                    reference.put(k, (long) op);
                    map.put(k, op);
                }
                case 1 -> {
                    final long delta = rnd.nextInt(100) - 50;
                    final long expected = reference.merge(k, delta, Long::sum);
                    assertEquals(expected, map.increment(k, delta));
                }
                case 2 -> assertEquals(reference.remove(k) != null, map.remove(k));
                default -> {
                    assertEquals(reference.getOrDefault(k, Long.MIN_VALUE), map.get(k));
                    assertEquals(reference.containsKey(k), map.contains(k));
                }
            }
            assertEquals(reference.size(), map.size());
        }
        final Map<Key, Long> seen = new HashMap<>();
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                seen.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        assertEquals(reference, seen);
        assertEquals(Long.MIN_VALUE, map.noEntryValue());
        final int absent = map.keyIndex(new Key(KEY_SPACE + 1));
        assertTrue(absent >= 0);
        assertThrows(IllegalArgumentException.class, () -> map.valueAt(absent));
        map.putAt(absent, new Key(KEY_SPACE + 1), 7);
        final int present = map.keyIndex(new Key(KEY_SPACE + 1));
        assertEquals(7, map.valueAt(present));
        assertTrue(map.excludes(new Key(KEY_SPACE + 2)));
        assertTrue(map.notEmpty());
        map.clear();
        assertTrue(map.isEmpty());
        assertEquals(-1, new ObjLongHashMap<String>().get("nothing"));
    }

    @Test
    void objHashSetMatchesHashSet() {
        final SplittableRandom rnd = new SplittableRandom(SEED);
        final ObjHashSet<Key> set = new ObjHashSet<>(4);
        final Set<Key> reference = new HashSet<>();
        for (int op = 0; op < OPERATIONS; op++) {
            final Key k = new Key(rnd.nextInt(KEY_SPACE));
            switch (rnd.nextInt(3)) {
                case 0 -> assertEquals(reference.add(k), set.add(k));
                case 1 -> assertEquals(reference.remove(k), set.remove(k));
                default -> {
                    assertEquals(reference.contains(k), set.contains(k));
                    assertEquals(!reference.contains(k), set.excludes(k));
                }
            }
            assertEquals(reference.size(), set.size());
        }
        final Set<Key> seen = new HashSet<>();
        for (int s = 0, n = set.slots(); s < n; s++) {
            if (set.hasKeyAtSlot(s)) {
                seen.add(set.keyAtSlot(s));
            }
        }
        assertEquals(reference, seen);
        final int index = set.keyIndex(new Key(KEY_SPACE + 5));
        assertTrue(index >= 0);
        set.addAt(index, new Key(KEY_SPACE + 5));
        assertTrue(set.contains(new Key(KEY_SPACE + 5)));
        assertTrue(set.notEmpty());
        set.clear();
        assertTrue(set.isEmpty());
        assertTrue(new ObjHashSet<String>().isEmpty());
    }

    @Test
    void longObjHashMapMatchesHashMap() {
        final SplittableRandom rnd = new SplittableRandom(SEED);
        final LongObjHashMap<String> map = new LongObjHashMap<>(4, Long.MIN_VALUE);
        final Map<Long, String> reference = new HashMap<>();
        for (int op = 0; op < OPERATIONS; op++) {
            // Negative keys too: the default no-entry key of -1 would forbid them, this map's does not.
            final long k = rnd.nextInt(KEY_SPACE) - KEY_SPACE / 2;
            if (rnd.nextBoolean()) {
                final String v = "v" + op;
                assertEquals(reference.put(k, v), map.put(k, v));
            } else {
                assertEquals(reference.get(k), map.get(k));
                assertEquals(reference.containsKey(k), map.contains(k));
                assertEquals(!reference.containsKey(k), map.excludes(k));
            }
            assertEquals(reference.size(), map.size());
        }
        final Map<Long, String> seen = new HashMap<>();
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                seen.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        assertEquals(reference, seen);
        final int absent = map.keyIndex(Long.MAX_VALUE);
        assertTrue(absent >= 0);
        assertThrows(IllegalArgumentException.class, () -> map.valueAt(absent));
        assertEquals("x", map.putAt(absent, Long.MAX_VALUE, "x"));
        assertEquals("x", map.valueAt(map.keyIndex(Long.MAX_VALUE)));
        assertTrue(map.notEmpty());
        map.clear();
        assertTrue(map.isEmpty());
        assertNull(map.get(Long.MAX_VALUE));
        final LongObjHashMap<String> defaults = new LongObjHashMap<>();
        assertNull(defaults.put(0, "zero"));
        assertEquals("zero", defaults.put(0, "nil"));
        assertEquals("nil", defaults.get(0));
    }

    @Test
    void capacityArithmetic() {
        assertEquals(1, Hashing.ceilPow2(0));
        assertEquals(1, Hashing.ceilPow2(1));
        assertEquals(2, Hashing.ceilPow2(2));
        assertEquals(4, Hashing.ceilPow2(3));
        assertEquals(1024, Hashing.ceilPow2(1000));
        assertEquals(1024, Hashing.ceilPow2(1024));
        assertEquals(16, Hashing.capacityFor(0));
        assertEquals(32, Hashing.capacityFor(16));
        assertEquals(8, Hashing.freeFor(16));
        // A key at its home slot never moves; one probed past the hole does.
        assertTrue(Hashing.mayMove(3, 5, 2));
        assertFalse(Hashing.mayMove(3, 5, 4));
        assertFalse(Hashing.mayMove(3, 5, 5));
        assertTrue(Hashing.mayMove(14, 1, 12));
        assertFalse(Hashing.mayMove(14, 1, 15));
        assertFalse(Hashing.mayMove(14, 1, 0));
    }

    private static <K, V> Map<K, V> slotsOf(final ObjObjHashMap<K, V> map) {
        final Map<K, V> out = new HashMap<>();
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                out.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        return out;
    }
}
