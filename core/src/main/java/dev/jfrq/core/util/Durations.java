package dev.jfrq.core.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing and formatting of human durations in the forms JFR itself uses
 * ({@code "10 ms"}, {@code "20 ms"}, {@code "1 s"}) and the forms people type on a
 * command line ({@code "50ms"}, {@code "1.5s"}, {@code "2m"}).
 */
public final class Durations {

    private static final Pattern FORM = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Zµ]*)\\s*$");

    private Durations() {
    }

    /**
     * Parses {@code "50ms"}, {@code "10 ms"}, {@code "1.5s"}, {@code "2m"}, {@code "100us"},
     * {@code "100µs"}, {@code "5ns"}, {@code "1h"}. A bare number is nanoseconds, matching
     * JFR's own unit-less values.
     *
     * @throws IllegalArgumentException when the text is not a duration
     */
    public static Duration parse(String text) {
        Matcher m = FORM.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a duration: '" + text + "' (expected e.g. 50ms, 1.5s, 2m)");
        }
        double value = Double.parseDouble(m.group(1));
        long unitNanos = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "", "ns" -> 1L;
            case "us", "µs" -> 1_000L;
            case "ms" -> 1_000_000L;
            case "s" -> 1_000_000_000L;
            case "m", "min" -> 60_000_000_000L;
            case "h" -> 3_600_000_000_000L;
            default -> throw new IllegalArgumentException("unknown duration unit in '" + text + "'");
        };
        return Duration.ofNanos(Math.round(value * unitNanos));
    }

    /** Nanoseconds of {@link #parse(String)}. */
    public static long parseNanos(String text) {
        return parse(text).toNanos();
    }

    /**
     * Formats nanoseconds compactly for tables: {@code 312 ms}, {@code 1.42 s}, {@code 850 µs}.
     * Negative values are formatted with a leading minus.
     */
    public static String format(long nanos) {
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
        long seconds = nanos / 1_000_000_000L;
        return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
    }

    /** {@link #format(long)} for a {@link Duration}. */
    public static String format(Duration duration) {
        return format(duration.toNanos());
    }

    /** Formats an offset from the recording start as {@code +3.412s}. */
    public static String offset(long nanos) {
        return String.format(Locale.ROOT, "%+.3fs", nanos / 1_000_000_000.0);
    }

    private static String trim(double v) {
        if (v >= 100) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        if (v >= 10) {
            return String.format(Locale.ROOT, "%.1f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
