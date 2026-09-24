// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

/**
 * What the open-addressing tables share: capacity arithmetic and hash spreading.
 * The tables themselves do not share a base class: a probe loop inherited through
 * virtual hooks is a megamorphic call per probe once several table shapes are loaded,
 * so each shape carries its own loop (G-1.3).
 */
final class Hashes {

    static final int MIN_CAPACITY = 16;
    /** The largest power-of-two array a table can have. */
    static final int MAX_CAPACITY = 1 << 30;
    static final double LOAD_FACTOR = 0.5;

    private Hashes() {
    }

    /**
     * The power-of-two slot count that holds {@code entries} at or under the load factor,
     * so that many inserts into a new table never rehash.
     *
     * @throws IllegalArgumentException when that is more than {@link #MAX_CAPACITY} slots
     */
    static int capacityFor(final int entries) {
        final double slots = Math.max(MIN_CAPACITY, Math.ceil(entries / LOAD_FACTOR));
        if (slots > MAX_CAPACITY) {
            throw new IllegalArgumentException("a table cannot hold " + entries + " entries: the limit is "
                    + (int) (MAX_CAPACITY * LOAD_FACTOR));
        }
        return ceilPow2((int) slots);
    }

    /**
     * The slot count after a rehash of a table of {@code capacity} slots.
     *
     * @throws IllegalStateException when the table is already at {@link #MAX_CAPACITY}
     */
    static int grow(final int capacity) {
        if (capacity >= MAX_CAPACITY) {
            throw new IllegalStateException("table is full: it holds " + (int) (MAX_CAPACITY * LOAD_FACTOR)
                    + " entries, the most a table can");
        }
        return capacity << 1;
    }

    /** The smallest power of two at or above {@code n}. */
    static int ceilPow2(final int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    /**
     * The rehash countdown of a table of {@code capacity} slots: every insert decrements it
     * and the insert that takes it to zero rehashes, so the table holds
     * {@code capacity * LOAD_FACTOR} entries and the next one grows it.
     */
    static int freeFor(final int capacity) {
        return (int) (capacity * LOAD_FACTOR) + 1;
    }

    /** Spreads an object hash so masking does not discard the high bits. */
    static int spread(final int h) {
        return h ^ (h >>> 16);
    }

    /** Folds and spreads a {@code long} key. */
    static int spread(final long key) {
        return spread((int) (key ^ (key >>> 32)));
    }

    /**
     * Whether the entry at {@code next} may move into the hole at {@code slot} during a
     * backward-shift delete: it may unless its home slot lies in {@code (slot, next]}.
     */
    static boolean mayMove(final int slot, final int next, final int home) {
        final boolean homeAfterHole = slot <= next ? slot < home && home <= next : slot < home || home <= next;
        return !homeAfterHole;
    }
}
