// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.jfrq.core.coll.Nulls;
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
     * Threads that differ only in the numbers a pool gives its workers. The four life counts
     * are {@link Nulls#INT_NULL} when the recording cannot say ({@link ThreadCensus}).
     *
     * @param name         the family, {@code pool-N-thread-N}
     * @param count        how many distinct threads are in it, seen in events or in the census
     * @param seen         how many of them appear as the thread of some event
     * @param aliveAtStart how many were alive when the recording began
     * @param started      how many starts there were inside it (a thread can start more than once)
     * @param ended        how many ends
     * @param aliveAtEnd   how many were alive when it ended
     * @param example      the first of them in name order
     */
    public record Family(String name, int count, int seen, int aliveAtStart, int started, int ended, int aliveAtEnd,
                         String example) {
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
     * is the only place that can say what to pass them. The threads are those seen in events
     * and those only the census names: a thread parked through the whole window is in no
     * event, and is alive all the same. A family with a thread the census does not cover
     * ({@link ThreadCensus.Result#covered}: a virtual thread, a GC worker) has no life counts,
     * because zero would be a claim the recording does not make; without a census, a virtual
     * thread is the one kind known to be outside it.
     */
    public static List<Family> threadFamilies(final RecordingInfo info, final ThreadCensus.Result census) {
        final Set<ThreadRef> all = new HashSet<>(info.threads());
        addAll(all, census.aliveAtStart());
        addAll(all, census.started() == null ? null : census.started().keySet());
        addAll(all, census.ended() == null ? null : census.ended().keySet());
        addAll(all, census.aliveAtEnd());
        final Map<String, List<ThreadRef>> families = new TreeMap<>();
        for (final ThreadRef t : all) {
            families.computeIfAbsent(family(t.name()), _ -> new ArrayList<>()).add(t);
        }
        final List<Family> out = new ArrayList<>(families.size());
        for (final Map.Entry<String, List<ThreadRef>> e : families.entrySet()) {
            final List<ThreadRef> threads = e.getValue();
            threads.sort(Comparator.comparing(ThreadRef::name));
            final boolean outside = threads.stream().anyMatch(t -> t.isVirtual()
                    || census.covered() != null && !census.covered().contains(t));
            out.add(new Family(e.getKey(), threads.size(), count(threads, info.threads()),
                    outside ? Nulls.INT_NULL : count(threads, census.aliveAtStart()),
                    outside ? Nulls.INT_NULL : events(threads, census.started()),
                    outside ? Nulls.INT_NULL : events(threads, census.ended()),
                    outside ? Nulls.INT_NULL : count(threads, census.aliveAtEnd()), threads.getFirst().name()));
        }
        return out;
    }

    private static void addAll(final Set<ThreadRef> to, final Set<ThreadRef> from) {
        if (from != null) {
            to.addAll(from);
        }
    }

    /** How many of {@code threads} are in {@code set}; {@link Nulls#INT_NULL} when the set is unknown. */
    private static int count(final List<ThreadRef> threads, final Set<ThreadRef> set) {
        if (set == null) {
            return Nulls.INT_NULL;
        }
        int n = 0;
        for (final ThreadRef t : threads) {
            if (set.contains(t)) {
                n++;
            }
        }
        return n;
    }

    /** How many events {@code threads} have in {@code events}; {@link Nulls#INT_NULL} when unknown. */
    private static int events(final List<ThreadRef> threads, final Map<ThreadRef, Long> events) {
        if (events == null) {
            return Nulls.INT_NULL;
        }
        long n = 0;
        for (final ThreadRef t : threads) {
            n += events.getOrDefault(t, 0L);
        }
        return (int) n;
    }

    /**
     * The columns of the families table, both renderers': the life counts the recording can
     * say, and none it cannot.
     */
    public static List<String> familyHeaders(final ThreadCensus.Result census) {
        final List<String> headers = new ArrayList<>(List.of("Family", "Threads", "Seen"));
        if (census.aliveAtStart() != null) {
            headers.add("At start");
        }
        if (census.started() != null) {
            headers.add("Started");
            headers.add("Ended");
        }
        if (census.aliveAtEnd() != null) {
            headers.add("At end");
        }
        headers.add("Example");
        return headers;
    }

    /**
     * One family's row under {@link #familyHeaders}. A family of one is named by its thread,
     * since {@code event-loop-N} is not a name {@code --thread} can match; a larger one by its
     * pattern with a {@code *}, and its first thread as the example.
     */
    public static Object[] familyCells(final Family f, final ThreadCensus.Result census) {
        final List<Object> cells = new ArrayList<>(8);
        cells.add(f.count() > 1 ? f.name() + "*" : f.example());
        cells.add(f.count());
        cells.add(f.seen());
        if (census.aliveAtStart() != null) {
            cells.add(cell(f.aliveAtStart()));
        }
        if (census.started() != null) {
            cells.add(cell(f.started()));
            cells.add(cell(f.ended()));
        }
        if (census.aliveAtEnd() != null) {
            cells.add(cell(f.aliveAtEnd()));
        }
        cells.add(f.count() > 1 ? f.example() : "");
        return cells.toArray();
    }

    /** An unknown count prints as a dash, as an unknown cadence does. */
    private static Object cell(final int value) {
        return value == Nulls.INT_NULL ? "—" : value;
    }

    /**
     * The census as one line, {@code platform threads: 117 alive at start, 58 started, 58 ended,
     * 117 alive at end}, leaving out what the recording cannot say; empty when it can say none of it.
     */
    public static String lives(final ThreadCensus.Result census) {
        final StringBuilder sb = new StringBuilder();
        life(sb, census.aliveAtStart() == null ? Nulls.LONG_NULL : census.aliveAtStart().size(), "alive at start");
        life(sb, census.starts(), "started");
        life(sb, census.ends(), "ended");
        life(sb, census.aliveAtEnd() == null ? Nulls.LONG_NULL : census.aliveAtEnd().size(), "alive at end");
        return sb.isEmpty() ? "" : "platform threads: " + sb;
    }

    private static void life(final StringBuilder sb, final long count, final String label) {
        if (count != Nulls.LONG_NULL) {
            sb.append(sb.isEmpty() ? "" : ", ").append(count).append(' ').append(label);
        }
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
