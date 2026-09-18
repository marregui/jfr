// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import dev.jfrq.core.coll.Nulls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DurationsTest {

    @ParameterizedTest
    @CsvSource({
            "50ms, 50000000",
            "10 ms, 10000000",
            "1.5s, 1500000000",
            "2m, 120000000000",
            "1h, 3600000000000",
            "100us, 100000",
            "100µs, 100000",
            "5ns, 5",
            "42, 42",
            " 20 MS , 20000000",
    })
    void parsesJfrAndCommandLineForms(String text, long nanos) {
        assertEquals(nanos, Durations.parseNanos(text));
        assertEquals(Duration.ofNanos(nanos), Durations.parse(text));
    }

    @ParameterizedTest
    @CsvSource({"abc", "10 parsecs", "''", "ms", "1..2s"})
    void rejectsNonDurations(String text) {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(text));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(null));
    }

    @Test
    void quietVariantAnswersTheSentinelInsteadOfThrowing() {
        assertEquals(50_000_000L, Durations.parseNanosQuiet("50ms"));
        assertEquals(20_000_000L, Durations.parseNanosQuiet(new StringBuilder(" 20 MS ")));
        assertEquals(1_050_000_000L, Durations.parseNanosQuiet("1.05s"));
        assertEquals(120_000_000_000L, Durations.parseNanosQuiet("2min"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet("everyChunk"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet("10 parsecs"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet("1.s"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet("5 m s"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet(null));
        // Past eighteen digits the mantissa is parsed as a double rather than overflowing a long.
        assertEquals(Math.round(1e19), Durations.parseNanosQuiet("10000000000000000000"));
        String unit = assertThrows(IllegalArgumentException.class, () -> Durations.parse("10 parsecs")).getMessage();
        assertTrue(unit.startsWith("unknown duration unit"), unit);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0 ns",
            "999, 999 ns",
            "1500, 1.50 µs",
            "312000000, 312 ms",
            "12345678, 12.3 ms",
            "1420000000, 1.42 s",
            "59900000000, 59.9 s",
            "67000000000, 1m07s",
            "-312000000, -312 ms",
    })
    void formatsCompactly(long nanos, String expected) {
        assertEquals(expected, Durations.format(nanos));
    }

    @Test
    void formatsDurationsAndOffsets() {
        assertEquals("2.00 s", Durations.format(Duration.ofSeconds(2)));
        assertEquals("+3.412s", Durations.offset(3_412_000_000L));
        assertEquals("-0.500s", Durations.offset(-500_000_000L));
    }
}
