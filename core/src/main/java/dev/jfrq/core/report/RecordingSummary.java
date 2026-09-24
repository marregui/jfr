// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Durations;

/**
 * What {@code info} says about a recording besides its event counts, derived once for
 * both renderers so the text and the HTML report cannot drift apart: the settings that
 * decide what the file can show, and the thread names folded into the families that
 * {@code --thread} is written against.
 */
public final class RecordingSummary {

    private RecordingSummary() {
    }

    /**
     * Threads that differ only in the numbers a pool gives its workers.
     *
     * @param name    the family, {@code pool-N-thread-N}
     * @param count   how many distinct threads are in it
     * @param example the first of them in name order
     */
    public record Family(String name, int count, String example) {
    }

    /**
     * The setting in force for each of {@code types} that has one, as
     * {@code ExecutionSample 10.0 ms, NativeMethodSample 10.0 ms}: its threshold, else its
     * period, else its throttle. Empty when none of them has any, and when the recording
     * holds no settings at all ({@link RecordingInfo#hasSettings()} tells the two apart).
     */
    public static String settings(final RecordingInfo info, final String... types) {
        final StringBuilder sb = new StringBuilder();
        for (final String t : types) {
            final String value = info.threshold(t).map(Durations::format)
                    .or(() -> info.period(t).map(Durations::format))
                    .or(() -> info.setting(t, "throttle"))
                    .orElse(null);
            if (value == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(t.startsWith("jdk.") ? t.substring("jdk.".length()) : t).append(' ').append(value);
        }
        return sb.toString();
    }

    /**
     * Every enabled event type whose threshold suppresses something, in name order. A threshold
     * of zero lets every event through, so it is the absence of one: the JDK's own profiles set
     * it on dozens of types nobody chose, and listing those buried the handful that were chosen
     * under 41 entries. The zeroes are still in the per-type table, where they answer a
     * question about one event type rather than about the recording.
     */
    public static String[] thresholded(final RecordingInfo info) {
        final List<String> types = new ArrayList<>();
        for (final String type : info.settings().keySet()) {
            if (info.isEnabled(type) && info.threshold(type).filter(d -> !d.isZero()).isPresent()) {
                types.add(type);
            }
        }
        return sorted(types);
    }

    /** Every enabled event type that is throttled: those are sampled, so the file is not complete for them. */
    public static String[] throttled(final RecordingInfo info) {
        final List<String> types = new ArrayList<>();
        for (final String type : info.settings().keySet()) {
            if (info.isEnabled(type) && info.throttle(type).filter(v -> !v.isBlank()).isPresent()) {
                types.add(type);
            }
        }
        return sorted(types);
    }

    /** Name order, because the settings come out of a hash map and two reports have to diff. */
    private static String[] sorted(final List<String> types) {
        types.sort(Comparator.naturalOrder());
        return types.toArray(new String[0]);
    }

    /**
     * The threads in the file, folded into families by the numbers a pool varies per worker,
     * in family order. {@code stalls} and {@code locks} take a {@code --thread} glob and this
     * is the only place that can say what to pass them.
     */
    public static List<Family> threadFamilies(final RecordingInfo info) {
        final Map<String, List<String>> families = new TreeMap<>();
        for (final ThreadRef t : info.threads()) {
            families.computeIfAbsent(family(t.name()), _ -> new ArrayList<>()).add(t.name());
        }
        final List<Family> out = new ArrayList<>(families.size());
        for (final Map.Entry<String, List<String>> e : families.entrySet()) {
            final List<String> names = e.getValue();
            names.sort(Comparator.naturalOrder());
            out.add(new Family(e.getKey(), names.size(), names.getFirst()));
        }
        return out;
    }

    /**
     * {@code milo-shared-thread-pool-17} and {@code pool-36-thread-2} are one family each:
     * the trailing run of digits, and any digits between two separators, are what a pool
     * varies per worker.
     */
    public static String family(final String name) {
        final StringBuilder sb = new StringBuilder(name.length());
        boolean digits = false;
        for (int i = 0, n = name.length(); i < n; i++) {
            final char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                digits = true;
                continue;
            }
            if (digits) {
                sb.append('N');
                digits = false;
            }
            sb.append(c);
        }
        if (digits) {
            sb.append('N');
        }
        return sb.toString();
    }
}
