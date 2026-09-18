package dev.jfrq.core.util;

import java.util.Locale;

/** Byte-count formatting for tables: {@code 12.3 MB}, {@code 1.02 GB}, {@code 850 B}. */
public final class Bytes {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Bytes() {
    }

    public static String format(long bytes) {
        if (bytes == Long.MIN_VALUE) {
            return "-" + format(Long.MAX_VALUE);
        }
        if (bytes < 0) {
            return "-" + format(-bytes);
        }
        double v = bytes;
        int unit = 0;
        while (v >= 1000 && unit < UNITS.length - 1) {
            v /= 1000;
            unit++;
        }
        if (unit == 0) {
            return bytes + " B";
        }
        String num = v >= 100 ? String.format(Locale.ROOT, "%.0f", v)
                : v >= 10 ? String.format(Locale.ROOT, "%.1f", v)
                : String.format(Locale.ROOT, "%.2f", v);
        return num + " " + UNITS[unit];
    }

    /** Signed form for diffs: {@code +12.3 MB}, {@code -850 B}, {@code 0 B}. */
    public static String signed(long bytes) {
        if (bytes > 0) {
            return "+" + format(bytes);
        }
        return format(bytes);
    }

    /** Bytes per second, e.g. {@code 45.1 MB/s}. */
    public static String rate(double bytesPerSecond) {
        long rounded = Math.round(bytesPerSecond);
        return (bytesPerSecond < 0 && rounded == 0 ? "-0 B" : format(rounded)) + "/s";
    }

    /** Signed rate for diffs. */
    public static String signedRate(double bytesPerSecond) {
        return (bytesPerSecond > 0 ? "+" : "") + rate(bytesPerSecond);
    }
}
