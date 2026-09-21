// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class SortedTest {

    @Test
    void lowerBoundFindsTheFirstElementAtOrAboveTheValue() {
        final List<Long> keys = List.of(10L, 20L, 20L, 30L);
        assertEquals(0, Sorted.lowerBound(keys, Long::longValue, 5));
        assertEquals(0, Sorted.lowerBound(keys, Long::longValue, 10));
        assertEquals(1, Sorted.lowerBound(keys, Long::longValue, 15));
        assertEquals(1, Sorted.lowerBound(keys, Long::longValue, 20));
        assertEquals(3, Sorted.lowerBound(keys, Long::longValue, 21));
        assertEquals(4, Sorted.lowerBound(keys, Long::longValue, 31));
        assertEquals(0, Sorted.lowerBound(List.of(), Long::longValue, 0));
    }

    @Test
    void maxLength() {
        assertEquals(0, Sorted.maxLength(List.of(), Long::longValue));
        assertEquals(30, Sorted.maxLength(List.of(10L, 30L, 20L), Long::longValue));
    }
}
