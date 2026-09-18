package dev.jfrq.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

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
