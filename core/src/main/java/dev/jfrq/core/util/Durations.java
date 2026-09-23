// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.time.Duration;
import java.util.Locale;

import dev.jfrq.core.coll.Nulls;

/**
 * Parsing and formatting of human durations in the forms JFR itself uses
 * ({@code "10 ms"}, {@code "20 ms"}, {@code "1 s"}) and the forms people type on a
 * command line ({@code "50ms"}, {@code "1.5s"}, {@code "2m"}). Parsing is by hand, with
 * a {@code Quiet} variant that answers a sentinel instead of throwing (G-2.3, G-1.6):
 * a setting value that is not a duration ({@code "everyChunk"}) is a normal case, not
 * an exception.
 */
public final class Durations {

    private static final long NOT_A_DURATION = -1;
    private static final long UNKNOWN_UNIT = -2;
    /** Mantissas longer than this are parsed as doubles so a long cannot overflow. */
    private static final int EXACT_DIGITS = 18;
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
     * {@code "100µs"}, {@code "5ns"}, {@code "1h"}. A bare number is nanoseconds, matching
     * JFR's own unit-less values.
     *
     * @throws IllegalArgumentException when the text is not a duration
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
        return nanos;
    }

    /** {@link #parseNanos(String)}, answering {@link Nulls#LONG_NULL} instead of throwing. */
    public static long parseNanosQuiet(final CharSequence text) {
        final long nanos = parse0(text);
        return nanos < 0 ? Nulls.LONG_NULL : nanos;
    }

    /**
     * Formats nanoseconds compactly for tables: {@code 312 ms}, {@code 1.42 s}, {@code 850 µs}.
     * Negative values are formatted with a leading minus.
     */
    public static String format(final long nanos) {
        if (nanos == Long.MIN_VALUE) {
            return "-" + format(Long.MAX_VALUE);
        }
        if (nanos < 0) {
            return "-" + format(-nanos);
        }
        if (nanos < 1_000L) {
            return nanos + " ns";
        }
        if (nanos < 1_000_000L) {
            return trim(nanos / 1_000.0) + " µs";
        }
        if (nanos < 1_000_000_000L) {
            return trim(nanos / 1_000_000.0) + " ms";
        }
        if (nanos < 60_000_000_000L) {
            return trim(nanos / 1_000_000_000.0) + " s";
        }
        final long seconds = nanos / 1_000_000_000L;
        return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
    }

    /** {@link #format(long)} for a {@link Duration}. */
    public static String format(final Duration duration) {
        return format(duration.toNanos());
    }

    /** Formats an offset from the recording start as {@code +3.412s}. */
    public static String offset(final long nanos) {
        return String.format(Locale.ROOT, "%+.3fs", nanos / 1_000_000_000.0);
    }

    /**
     * The worker behind both parse entry points: nanoseconds, or a negative code. Accepts
     * optional whitespace, digits with an optional fraction, optional whitespace, an
     * optional unit of ASCII letters or {@code µ}, optional whitespace.
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
        int p = lo;
        while (p < hi && isDigit(text.charAt(p))) {
            p++;
        }
        if (p == lo) {
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
        final double value = number(text, lo, fractionStart, p);
        while (p < hi && isSpace(text.charAt(p))) {
            p++;
        }
        for (int i = p; i < hi; i++) {
            final char c = text.charAt(i);
            if (!isAsciiLetter(c) && c != 'µ') {
                return NOT_A_DURATION;
            }
        }
        final long unitNanos = unitNanos(text, p, hi);
        if (unitNanos < 0) {
            return UNKNOWN_UNIT;
        }
        return Math.round(value * unitNanos);
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

    private static String trim(final double v) {
        if (v >= 100) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        if (v >= 10) {
            return String.format(Locale.ROOT, "%.1f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
