// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

/**
 * What the open-addressing tables share: capacity arithmetic and hash spreading.
 * The tables themselves do not share a base class: a probe loop inherited through
 * virtual hooks is a megamorphic call per probe once several table shapes are loaded,
 * so each shape carries its own loop (G-1.3).
 */
final class Hashing {

    static final int MIN_CAPACITY = 16;
    static final double LOAD_FACTOR = 0.5;

    private Hashing() {
    }

    /** The power-of-two slot count that keeps {@code entries} at or under the load factor. */
    static int capacityFor(final int entries) {
        return ceilPow2((int) Math.max(MIN_CAPACITY, Math.ceil(entries / LOAD_FACTOR)));
    }

    /** The smallest power of two at or above {@code n}. */
    static int ceilPow2(final int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    /** Inserts allowed before a rehash for a table of {@code capacity} slots. */
    static int freeFor(final int capacity) {
        return (int) (capacity * LOAD_FACTOR);
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
