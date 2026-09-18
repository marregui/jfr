// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.coll;

/**
 * The domain's "no value" sentinels for primitives (G-1.1). They are distinct from the
 * collections' no-entry values (G-1.2): a {@code LongList} answers {@code -1} for a
 * missing element by default, a nanosecond timestamp that is absent is {@link #LONG_NULL}.
 */
public final class Nulls {

    public static final int INT_NULL = Integer.MIN_VALUE;
    public static final long LONG_NULL = Long.MIN_VALUE;
    public static final double DOUBLE_NULL = Double.NaN;

    private Nulls() {
    }

    /** Widening that preserves nullness. */
    public static long intToLong(int v) {
        return v == INT_NULL ? LONG_NULL : v;
    }
}
