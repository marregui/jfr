// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

/**
 * Byte-count formatting for tables: {@code 12.3 MB}, {@code 1.02 GB}, {@code 850 B}. The
 * unit is chosen after rounding to three digits, so 999,999 bytes is {@code 1.00 MB}.
 */
public final class Bytes {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Bytes() {
    }

    public static String format(final long bytes) {
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
        String num = Durations.threeDigits(v);
        if (num.equals("1000") && unit < UNITS.length - 1) {
            // Rounded up to 1000 of this unit (999.9 KB): that is 1.00 of the next.
            num = Durations.threeDigits(v / 1000);
            unit++;
        }
        return num + " " + UNITS[unit];
    }

    /** Signed form for diffs: {@code +12.3 MB}, {@code -850 B}, {@code 0 B}. */
    public static String signed(final long bytes) {
        if (bytes > 0) {
            return "+" + format(bytes);
        }
        return format(bytes);
    }

    /** Bytes per second, e.g. {@code 45.1 MB/s}. */
    public static String rate(final double bytesPerSecond) {
        final long rounded = Math.round(bytesPerSecond);
        return (bytesPerSecond < 0 && rounded == 0 ? "-0 B" : format(rounded)) + "/s";
    }

    /** Signed rate for diffs. */
    public static String signedRate(final double bytesPerSecond) {
        return (bytesPerSecond > 0 ? "+" : "") + rate(bytesPerSecond);
    }
}
