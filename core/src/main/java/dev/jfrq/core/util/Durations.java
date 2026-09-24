// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.time.Duration;
import java.util.Locale;

import dev.jfrq.core.coll.Nulls;

/**
 * Parsing and formatting of human durations in the forms JFR itself uses
 * ({@code "10 ms"}, {@code "20 ms"}, {@code "1 s"}, {@code "infinity"}), the forms people
 * type on a command line ({@code "50ms"}, {@code "1.5s"}, {@code "2m"}) and the forms
 * {@link #format(long)} prints ({@code "2m18s"}, {@code "3d04h"}), so every duration the
 * tool shows can be pasted back into an option. Parsing is by hand, with
 * a {@code Quiet} variant that answers a sentinel instead of throwing (G-2.3, G-1.6):
 * a setting value that is not a duration ({@code "everyChunk"}) is a normal case, not
 * an exception.
 */
public final class Durations {

    /** What {@code "infinity"} parses to and what formats as {@code "infinity"}: never. */
    public static final long INFINITE = Long.MAX_VALUE;
    private static final long NOT_A_DURATION = -1;
    private static final long UNKNOWN_UNIT = -2;
    private static final long OUT_OF_RANGE = -3;
    /**
     * Mantissas up to this many digits are exact in a double (below 2^53), so dividing by
     * an exact power of ten is one correctly rounded operation; longer ones go through
     * {@link Double#parseDouble}, which rounds correctly on its own.
     */
    private static final int EXACT_DIGITS = 15;
    /** 2^63: the first double a {@code long} cannot hold. */
    private static final double LONG_LIMIT = 0x1p63;
    private static final String[] UNITS = {"ns", "µs", "ms", "s"};
    private static final double[] POW10 = new double[EXACT_DIGITS + 1];

    static {
        POW10[0] = 1;
        for (int i = 1; i < POW10.length; i++) {
            POW10[i] = POW10[i - 1] * 10;
        }
    }

    private Durations() {
    }

    /**
     * Parses {@code "50ms"}, {@code "10 ms"}, {@code "1.5s"}, {@code "2m"}, {@code "100us"},
     * {@code "100µs"}, {@code "5ns"}, {@code "1h"}, {@code "2d"}, the compound forms
     * {@link #format(long)} prints ({@code "2m18s"}, {@code "13h11m"}, {@code "3d04h"}) and
     * {@code "infinity"} ({@link #INFINITE}). A bare number is nanoseconds, matching JFR's
     * own unit-less values.
     *
     * @throws IllegalArgumentException when the text is not a duration, or is one longer
     *                                  than a {@code long} of nanoseconds holds (about 292
     *                                  years; {@code infinity} says "never")
     */
    public static Duration parse(final String text) {
        return Duration.ofNanos(parseNanos(text));
    }

    /** Nanoseconds of {@link #parse(String)}. */
    public static long parseNanos(final String text) {
        final long nanos = parse0(text);
        if (nanos == UNKNOWN_UNIT) {
            throw new IllegalArgumentException("unknown duration unit in '" + text + "'");
        }
        if (nanos == NOT_A_DURATION) {
            throw new IllegalArgumentException("not a duration: '" + text + "' (expected e.g. 50ms, 1.5s, 2m)");
        }
        if (nanos == OUT_OF_RANGE) {
            throw new IllegalArgumentException("duration out of range: '" + text + "' (at most " + format(INFINITE - 1)
                    + "; 'infinity' means never)");
        }
        return nanos;
    }

    /** {@link #parseNanos(String)}, answering {@link Nulls#LONG_NULL} instead of throwing. */
    public static long parseNanosQuiet(final CharSequence text) {
        final long nanos = parse0(text);
        return nanos < 0 ? Nulls.LONG_NULL : nanos;
    }

    /**
     * Formats nanoseconds compactly for tables: {@code 312 ms}, {@code 1.42 s}, {@code 850 µs},
     * {@code 2m18s}, {@code 13h11m}, {@code 3d04h}. Each tier carries two units, so the number
     * is read rather than divided: a warning that says {@code 790m55s} is one the reader has to
     * convert, and the durations that reach these tiers are exactly the ones in the warnings.
     * Below a minute the unit is chosen after rounding to three digits, so 999.9 µs is
     * {@code 1.00 ms} and 59.97 s is {@code 1m00s}; above it the smaller unit is truncated,
     * like a clock. {@link #INFINITE} is {@code infinity}; negative values are formatted
     * with a leading minus.
     */
    public static String format(final long nanos) {
        if (nanos == Long.MIN_VALUE) {
            return "-" + format(INFINITE);
        }
        if (nanos < 0) {
            return "-" + format(-nanos);
        }
        if (nanos == INFINITE) {
            return "infinity";
        }
        if (nanos < 1_000L) {
            return nanos + " ns";
        }
        long seconds = nanos / 1_000_000_000L;
        if (nanos < 60_000_000_000L) {
            double v = nanos / 1_000.0;
            int unit = 1;
            while (v >= 1_000 && unit < UNITS.length - 1) {
                v /= 1_000;
                unit++;
            }
            String digits = threeDigits(v);
            if (unit < UNITS.length - 1 && digits.equals("1000")) {
                // Rounded up to 1000 of this unit: that is 1.00 of the next.
                v /= 1_000;
                unit++;
                digits = threeDigits(v);
            }
            if (unit < UNITS.length - 1 || Double.parseDouble(digits) < 60) {
                return digits + " " + UNITS[unit];
            }
            seconds = 60;
        }
        if (seconds < 3_600L) {
            return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
        }
        final long minutes = seconds / 60;
        if (minutes < 1_440L) {
            return String.format(Locale.ROOT, "%dh%02dm", minutes / 60, minutes % 60);
        }
        final long hours = minutes / 60;
        return String.format(Locale.ROOT, "%dd%02dh", hours / 24, hours % 24);
    }

    /** {@link #format(long)} for a {@link Duration}. */
    public static String format(final Duration duration) {
        return format(duration.toNanos());
    }

    /**
     * {@link #format(long)}, except that a zero is the "not measured" zero rather than a
     * duration: a sampling cadence needs two samples, and a thread with fewer has none. A
     * column that answers {@code 0 ns} claims a measurement that was never taken.
     */
    public static String formatOrDash(final long nanos) {
        return nanos == 0 ? "—" : format(nanos);
    }

    /** Formats an offset from the recording start as {@code +3.412s}. */
    public static String offset(final long nanos) {
        return String.format(Locale.ROOT, "%+.3fs", nanos / 1_000_000_000.0);
    }

    /**
     * The worker behind both parse entry points: nanoseconds, or a negative code. Accepts
     * optional whitespace, then {@code infinity} or one or more terms, then optional
     * whitespace. A term is digits with an optional fraction, optional whitespace and a unit
     * of ASCII letters or {@code µ}; the unit may be left out only when the term is the
     * whole text (a bare number is nanoseconds). Terms add up; a total a {@code long}
     * cannot hold is out of range rather than clamped.
     */
    private static long parse0(final CharSequence text) {
        if (text == null) {
            return NOT_A_DURATION;
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi && isSpace(text.charAt(lo))) {
            lo++;
        }
        while (hi > lo && isSpace(text.charAt(hi - 1))) {
            hi--;
        }
        if (isInfinity(text, lo, hi)) {
            return INFINITE;
        }
        if (lo == hi) {
            return NOT_A_DURATION;
        }
        long total = 0;
        int p = lo;
        while (p < hi) {
            final int start = p;
            while (p < hi && isDigit(text.charAt(p))) {
                p++;
            }
            if (p == start) {
                return NOT_A_DURATION;
            }
            final int fractionStart = p;
            if (p < hi && text.charAt(p) == '.') {
                int q = p + 1;
                while (q < hi && isDigit(text.charAt(q))) {
                    q++;
                }
                if (q == p + 1) {
                    return NOT_A_DURATION;
                }
                p = q;
            }
            final double value = number(text, start, fractionStart, p);
            while (p < hi && isSpace(text.charAt(p))) {
                p++;
            }
            final int unitStart = p;
            while (p < hi && (isAsciiLetter(text.charAt(p)) || text.charAt(p) == 'µ')) {
                p++;
            }
            if (unitStart == p && (start != lo || p != hi)) {
                // A number with no unit is nanoseconds only on its own; inside a compound, or
                // followed by anything but a unit, it is not a duration.
                return NOT_A_DURATION;
            }
            final long unitNanos = unitNanos(text, unitStart, p);
            if (unitNanos < 0) {
                return UNKNOWN_UNIT;
            }
            final double product = value * unitNanos;
            if (product >= LONG_LIMIT) {
                return OUT_OF_RANGE;
            }
            total += Math.round(product);
            if (total < 0) {
                return OUT_OF_RANGE;
            }
            while (p < hi && isSpace(text.charAt(p))) {
                p++;
            }
        }
        return total == INFINITE ? OUT_OF_RANGE : total;
    }

    /** Whether {@code [lo, hi)} is {@code infinity}, in any case, without allocating (G-2.2). */
    private static boolean isInfinity(final CharSequence text, final int lo, final int hi) {
        final String word = "infinity";
        if (hi - lo != word.length()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (fold(text.charAt(lo + i)) != word.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** The decimal number in {@code [lo, hi)} whose fraction, if any, starts at {@code dot}. */
    private static double number(final CharSequence text, final int lo, final int dot, final int hi) {
        final int digits = hi - lo - (dot < hi ? 1 : 0);
        if (digits > EXACT_DIGITS) {
            return Double.parseDouble(text.subSequence(lo, hi).toString());
        }
        long mantissa = 0;
        for (int i = lo; i < dot; i++) {
            mantissa = mantissa * 10 + (text.charAt(i) - '0');
        }
        int fractionDigits = 0;
        for (int i = dot + 1; i < hi; i++) {
            mantissa = mantissa * 10 + (text.charAt(i) - '0');
            fractionDigits++;
        }
        // One correctly rounded division, as the JDK's parser would produce.
        return mantissa / POW10[fractionDigits];
    }

    /** Nanoseconds per unit, matched case-insensitively without allocating (G-2.2); -1 for an unknown unit. */
    private static long unitNanos(final CharSequence text, final int lo, final int hi) {
        final int n = hi - lo;
        if (n == 0) {
            return 1L;
        }
        final char c0 = fold(text.charAt(lo));
        if (n == 1) {
            return switch (c0) {
                case 's' -> 1_000_000_000L;
                case 'm' -> 60_000_000_000L;
                case 'h' -> 3_600_000_000_000L;
                case 'd' -> 86_400_000_000_000L;
                default -> -1;
            };
        }
        final char c1 = fold(text.charAt(lo + 1));
        if (n == 2 && c1 == 's') {
            return switch (c0) {
                case 'n' -> 1L;
                case 'u', 'µ' -> 1_000L;
                case 'm' -> 1_000_000L;
                default -> -1;
            };
        }
        if (n == 3 && c0 == 'm' && c1 == 'i' && fold(text.charAt(lo + 2)) == 'n') {
            return 60_000_000_000L;
        }
        return -1;
    }

    /** Lower-cases an ASCII letter; leaves {@code µ} and the rest alone. */
    private static char fold(final char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c | 32) : c;
    }

    private static boolean isAsciiLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    /** The regex {@code \s} class: space, tab, newline, vertical tab, form feed, return. */
    private static boolean isSpace(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    /**
     * {@code v} to three significant digits ({@code 1.42}, {@code 12.3}, {@code 312}), the
     * precision chosen after rounding: 9.996 is {@code 10.0}, not {@code 10.00}, and 99.96
     * is {@code 100}. A value that rounds up to 1000 comes back as {@code "1000"} for the
     * caller to carry into the next unit; one already at or above 1000 keeps its digits.
     */
    static String threeDigits(final double v) {
        int decimals = v >= 100 ? 0 : v >= 10 ? 1 : 2;
        String digits = fixed(v, decimals);
        if (decimals > 0 && Double.parseDouble(digits) >= (decimals == 2 ? 10 : 100)) {
            decimals--;
            digits = fixed(v, decimals);
        }
        return digits;
    }

    private static String fixed(final double v, final int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }
}
