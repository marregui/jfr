// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.health.HealthReport;
import dev.jfrq.core.health.ThreadCpu;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Durations;
import dev.jfrq.core.util.Glob;

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
     * @param virtual      how many of them are virtual threads, which no census covers
     * @param aliveAtStart how many were alive when the recording began
     * @param started      how many starts there were inside it (a thread can start more than once)
     * @param ended        how many ends
     * @param aliveAtEnd   how many were alive when it ended
     * @param attached     how many native code attached to the JVM ({@link ThreadCensus}); {@link Nulls#INT_NULL}
     *                     when the recording cannot say
     * @param cpu          the family's share of the JVM's CPUs across the window ({@link ThreadCpu}); NaN when the
     *                     recording cannot say, which it cannot for a thread outside the census either
     * @param example      the first of them in name order
     */
    public record Family(String name, int count, int seen, int virtual, int aliveAtStart, int started, int ended,
                         int aliveAtEnd, int attached, double cpu, String example) {
    }

    /**
     * The setting in force for each of {@code types} that has one, as
     * {@code ExecutionSample 10.0 ms, NativeMethodSample 10.0 ms}: its threshold, else its
     * period, else its throttle. Empty when none of them has any, and when the recording
     * holds no settings at all ({@link RecordingInfo#hasSettings()} tells the two apart).
     */
    public static String settings(final RecordingInfo info, final String... types) {
        return list(info, false, types);
    }

    /**
     * The throttle in force for each of {@code types} that has one, as
     * {@code JavaExceptionThrow 300/s}, for the {@code Throttled} line: a type that is both
     * thresholded and throttled (a file read) has its threshold on the other line.
     */
    public static String throttles(final RecordingInfo info, final String... types) {
        return list(info, true, types);
    }

    private static String list(final RecordingInfo info, final boolean throttles, final String... types) {
        final StringBuilder sb = new StringBuilder();
        for (final String t : types) {
            final String value = throttles ? info.throttle(t).orElse(null)
                    : info.threshold(t).map(Durations::format)
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
            threads.sort(ThreadRef.ORDER);
            boolean outside = false;
            int virtual = 0;
            double cpu = 0;
            boolean measured = false;
            for (final ThreadRef t : threads) {
                outside |= t.isVirtual() || census.covered() != null && !census.covered().contains(t);
                virtual += t.isVirtual() ? 1 : 0;
                final double share = census.cpu().of(t);
                measured |= !Double.isNaN(share);
                cpu += Double.isNaN(share) ? 0 : share;
            }
            out.add(new Family(e.getKey(), threads.size(), count(threads, info.threads()), virtual,
                    outside ? Nulls.INT_NULL : count(threads, census.aliveAtStart()),
                    outside ? Nulls.INT_NULL : events(threads, census.started()),
                    outside ? Nulls.INT_NULL : events(threads, census.ended()),
                    outside ? Nulls.INT_NULL : count(threads, census.aliveAtEnd()),
                    outside ? Nulls.INT_NULL : count(threads, census.attached()),
                    // The readings stand on their own, census or not. A Java thread with none used under a
                    // millisecond a period; a family outside the census with none (a collector's threads,
                    // virtual threads) is not measured at all.
                    !census.cpu().isKnown() || outside && !measured ? Double.NaN : cpu, threads.getFirst().name()));
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
     * say, and none it cannot; how many are virtual when any family has one, since their life
     * counts are dashes for that reason and a dash alone reads like a VM thread's; how many
     * native code attached when any was; the CPU when the recording has the threads' readings.
     */
    public static List<String> familyHeaders(final ThreadCensus.Result census, final List<Family> families) {
        final List<String> headers = new ArrayList<>(List.of("Family", "Threads", "Seen"));
        if (hasVirtual(families)) {
            headers.add("Virtual");
        }
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
        if (hasAttached(families)) {
            headers.add("Attached");
        }
        if (census.cpu().isKnown()) {
            headers.add("CPU");
        }
        headers.add("Example");
        return headers;
    }

    /**
     * One family's row under {@link #familyHeaders}. A family of one is named by its thread,
     * since {@code event-loop-N} is not a name {@code --thread} can match; a larger one by its
     * pattern with a {@code *}, and its first thread as the example.
     */
    public static Object[] familyCells(final Family f, final ThreadCensus.Result census, final List<Family> families) {
        final List<Object> cells = new ArrayList<>(11);
        cells.add(f.count() > 1 ? f.name() + "*" : f.example());
        cells.add(f.count());
        cells.add(f.seen());
        if (hasVirtual(families)) {
            cells.add(f.virtual() == 0 ? "" : f.virtual());
        }
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
        if (hasAttached(families)) {
            cells.add(f.attached() == 0 ? "" : cell(f.attached()));
        }
        if (census.cpu().isKnown()) {
            cells.add(Double.isNaN(f.cpu()) ? "—" : String.format(Locale.ROOT, "%.1f%%", f.cpu() * 100));
        }
        cells.add(f.count() > 1 ? f.example() : "");
        return cells.toArray();
    }

    /** Whether any family has a thread native code attached: the table then has a column for them. */
    public static boolean hasAttached(final List<Family> families) {
        for (final Family f : families) {
            if (f.attached() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the families table's own columns mean, as both renderers print them under it: one
     * line per column that needs one, none when the table has neither column.
     */
    public static List<String> familyNotes(final ThreadCensus.Result census, final List<Family> families) {
        final List<String> notes = new ArrayList<>(2);
        if (hasAttached(families)) {
            notes.add("Attached: threads native code attached to the JVM (JNI AttachCurrentThread, an upcall); their "
                    + "starts carry no stack and no parent thread. Each attach is a thread of its own, so a library "
                    + "that attaches for every callback starts one each time. The java launcher attaches main.");
        }
        if (census.cpu().isKnown()) {
            notes.add("CPU: the share of the JVM's CPUs (the machine's, unless -XX:ActiveProcessorCount, a container "
                    + "limit or a CPU affinity mask sets fewer) across the window, from jdk.ThreadCPULoad"
                    + HealthReport.leftOut(census.cpu().leftOut()) + ". A dash is a family the JVM does not measure "
                    + "(the collector's threads, virtual threads).");
        }
        return notes;
    }

    /** The windows {@link #starts} reports the busiest of: a burst, and a sustained second. */
    static final long BURST_NANOS = 100_000_000L;
    static final long SECOND_NANOS = 1_000_000_000L;

    /**
     * How the threads {@code glob} matches were started: when, how fast at the busiest, and by
     * which code. A pool that makes one thread per task when none is idle shows as a count of
     * starts far above its size and a burst of them in a hundred milliseconds; the code that
     * submitted the tasks is the creator. The creator is the innermost frame outside the JDK in
     * the starting thread's stack, the same rule {@code alloc --sites} uses, since the frames
     * above it ({@code Thread.start}, a pool's {@code addWorker}) are the same for every pool.
     *
     * @param top creators listed, the most starts first
     */
    public static Starts starts(final ThreadCensus.Result census, final Glob glob, final int top) {
        final List<Long> times = new ArrayList<>();
        final Map<String, Creator.Builder> creators = new LinkedHashMap<>();
        final Set<ThreadRef> threads = new HashSet<>();
        int attached = 0;
        for (final ThreadCensus.Start s : census.startEvents()) {
            if (!glob.test(s.thread().name())) {
                continue;
            }
            times.add(s.time());
            threads.add(s.thread());
            attached += s.isAttached() ? 1 : 0;
            final Stack stack = s.stack();
            int from = 0;
            while (from < stack.depth() && stack.frameQuick(from).isJdk()) {
                from++;
            }
            if (from == stack.depth()) {
                from = 0;
            }
            final String site = s.isAttached() ? ATTACHED : stack.isEmpty() ? NO_STACK : stack.frameQuick(from).stableName();
            final int below = from;
            creators.computeIfAbsent(site, k -> new Creator.Builder(k, below == 0 ? stack
                    : new Stack(stack.frames().subList(below, stack.depth()), stack.isTruncated()))).add(s.parent());
        }
        final int n = times.size();
        final List<Creator> rows = new ArrayList<>(creators.size());
        for (final Creator.Builder b : creators.values()) {
            rows.add(b.build(n));
        }
        rows.sort(Comparator.comparingInt(Creator::starts).reversed().thenComparing(Creator::site));
        return new Starts(glob.toString(), threads.size(), n, attached, n == 0 ? Nulls.LONG_NULL : times.getFirst(),
                n == 0 ? Nulls.LONG_NULL : times.getLast(), peak(times, BURST_NANOS), peak(times, SECOND_NANOS),
                rows.size() > top ? List.copyOf(rows.subList(0, top)) : rows, rows.size());
    }

    /**
     * A name for each recording that tells it from the others: its file name, or as many of the
     * directories above it as it takes. Three nodes' recordings are often all {@code node.jfr},
     * in a directory per node.
     */
    public static List<String> labels(final List<Path> files) {
        int depth = 1;
        while (true) {
            final List<String> labels = new ArrayList<>(files.size());
            final Set<String> seen = new HashSet<>();
            boolean distinct = true;
            boolean deeper = false;
            for (final Path f : files) {
                final Path p = f.toAbsolutePath().normalize();
                final int n = p.getNameCount();
                final String label = p.subpath(Math.max(0, n - depth), n).toString();
                labels.add(label);
                distinct &= seen.add(label);
                deeper |= n > depth;
            }
            if (distinct) {
                return labels;
            }
            if (!deeper) {
                // The same file given twice cannot be told apart by a suffix; its whole path can.
                final List<String> whole = new ArrayList<>(files.size());
                for (final Path f : files) {
                    whole.add(f.toAbsolutePath().normalize().toString());
                }
                return whole;
            }
            depth++;
        }
    }

    /** What a creator is, in the words both renderers print above them. */
    public static final String CREATOR_RULE = "the innermost frame outside the JDK in the stack of the thread that "
            + "started it";

    /**
     * {@code 2155 starts of 2155 threads from +5.200s 11:37:19.748Z to +1056.0s ...}, as both
     * renderers print it; says so when there were none.
     */
    public static String startsLine(final Starts s, final long recordingStart) {
        if (s.starts() == 0) {
            return "no jdk.ThreadStart of a thread matching " + s.glob() + " in the recording";
        }
        return s.starts() + (s.starts() == 1 ? " start" : " starts") + " of " + s.threads()
                + (s.threads() == 1 ? " thread" : " threads") + ", from " + Durations.at(s.firstNanos(), recordingStart)
                + " to " + Durations.at(s.lastNanos(), recordingStart)
                + (s.attached() == 0 ? "" : "; " + s.attached() + " attached from native code");
    }

    /** {@code 470 in 100 ms from +301.285s 12:41:00.064Z; 571 in 1.00 s from ...}, as both renderers print it. */
    public static String peaksLine(final Starts s, final long recordingStart) {
        return peak(s.burst(), recordingStart) + "; " + peak(s.second(), recordingStart);
    }

    private static String peak(final Peak p, final long recordingStart) {
        return p.count() + " in " + Durations.format(p.windowNanos()) + " from " + Durations.at(p.startNanos(), recordingStart);
    }

    /** What a creator row says for a thread native code attached: its start has no stack to name a site. */
    public static final String ATTACHED = "<attached from native code>";
    /** What a creator row says for a start recorded without its stack. */
    public static final String NO_STACK = "<no stack>";

    /** The most starts in any window of {@code width}, and when that window began; times ascending. */
    static Peak peak(final List<Long> times, final long width) {
        int best = 0;
        long at = Nulls.LONG_NULL;
        for (int i = 0, j = 0, n = times.size(); j < n; j++) {
            while (times.get(j) - times.get(i) >= width) {
                i++;
            }
            if (j - i + 1 > best) {
                best = j - i + 1;
                at = times.get(i);
            }
        }
        return new Peak(width, best, at);
    }

    /**
     * {@link #starts}' answer.
     *
     * @param glob         the {@code --thread} patterns, as given
     * @param threads      the distinct threads started
     * @param starts       the starts; a thread can start more than once (the JVM's compiler threads)
     * @param attached     how many of them were native code attaching a thread
     * @param firstNanos   the first start, epoch nanoseconds; {@link Nulls#LONG_NULL} with none
     * @param lastNanos    the last
     * @param burst        the most starts in a hundred milliseconds
     * @param second       the most in a second
     * @param creators     at most {@code top} of them, the most starts first
     * @param creatorsFound how many there were
     */
    public record Starts(String glob, int threads, int starts, int attached, long firstNanos, long lastNanos,
                         Peak burst, Peak second, List<Creator> creators, int creatorsFound) {

        public Starts {
            creators = List.copyOf(creators);
        }
    }

    /**
     * @param windowNanos how wide the window is
     * @param count       the most starts in one window of that width
     * @param startNanos  when the first such window began, epoch nanoseconds; {@link Nulls#LONG_NULL} with none
     */
    public record Peak(long windowNanos, int count, long startNanos) {
    }

    /**
     * @param site    the innermost frame outside the JDK in the starting thread's stack, as
     *                {@code package.Class.method}; {@link #ATTACHED} or {@link #NO_STACK} without one
     * @param starts  how many starts it made
     * @param share   of all the starts
     * @param parents the threads that ran it, in {@link ThreadRef#ORDER}: the renderers print and fold
     *                them in the order they iterate, so a set salted per JVM printed differently on
     *                every run. The order is fixed here, not in {@link #threadNames}, because lock
     *                waiters and owners reach that method in the order they were seen, which it keeps
     * @param stack   the first stack seen there, from the site down: the frames above it start a thread
     *                the same way for every pool
     */
    public record Creator(String site, int starts, double share, Set<ThreadRef> parents, Stack stack) {

        public Creator {
            parents = ThreadRef.ordered(parents);
        }

        private static final class Builder {
            private final String site;
            private final Stack stack;
            private final Set<ThreadRef> parents = new HashSet<>();
            private int starts;

            Builder(final String site, final Stack stack) {
                this.site = site;
                this.stack = stack;
            }

            void add(final ThreadRef parent) {
                starts++;
                if (parent != null) {
                    parents.add(parent);
                }
            }

            Creator build(final int total) {
                return new Creator(site, starts, total == 0 ? 0 : (double) starts / total, parents, stack);
            }
        }
    }

    /** Whether any family has a virtual thread: the table then has a column for them. */
    public static boolean hasVirtual(final List<Family> families) {
        for (final Family f : families) {
            if (f.virtual() > 0) {
                return true;
            }
        }
        return false;
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
     * Thread names for a table cell or a line, as every renderer prints them: all of them when
     * they are at most {@code shown}; otherwise the threads of one pool are one entry,
     * {@code ForkJoinPool.commonPool-worker-N* (11 threads)}, named as the families table names
     * it, and the entries past {@code shown} are counted. Four workers of one pool by name made a
     * 251-character row that still said "+7 more"; two event loops folded into a pattern lost
     * the names for nothing.
     */
    public static String threadNames(final Collection<ThreadRef> threads, final int shown) {
        final List<String> entries = new ArrayList<>(threads.size());
        if (threads.size() <= shown) {
            for (final ThreadRef t : threads) {
                entries.add(t.name());
            }
        } else {
            final Map<String, List<String>> families = new LinkedHashMap<>();
            for (final ThreadRef t : threads) {
                families.computeIfAbsent(family(t.name()), _ -> new ArrayList<>()).add(t.name());
            }
            for (final Map.Entry<String, List<String>> f : families.entrySet()) {
                final List<String> members = f.getValue();
                entries.add(members.size() == 1 ? members.getFirst()
                        : (f.getKey().equals(members.getFirst()) ? f.getKey() : f.getKey() + "*")
                                + " (" + members.size() + " threads)");
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0, n = entries.size(); i < n; i++) {
            if (i == shown) {
                sb.append(" (+").append(n - i).append(" more)");
                break;
            }
            sb.append(i > 0 ? ", " : "").append(entries.get(i));
        }
        return sb.toString();
    }

    /**
     * {@code milo-shared-thread-pool-17} and {@code pool-36-thread-2} are one family each:
     * the trailing run of digits, and any digits between two separators, are what a pool
     * varies per worker.
     */
    public static String family(final String name) {
        return fold(name, 'N');
    }

    /**
     * The {@code --thread} glob that matches every thread of {@code name}'s family:
     * {@code pool-*-thread-*}. The same fold as {@link #family}, with a wildcard where the
     * family has {@code N}, so a name that has a capital N of its own keeps it, over the
     * name made {@link #literal}.
     */
    public static String glob(final String name) {
        return fold(literal(name), '*');
    }

    /**
     * {@code name} as a glob that matches it and nothing else: its metacharacters and commas
     * escaped, and whitespace at either end put in a class ({@code [ ]}), since a glob's parts
     * are trimmed.
     */
    public static String literal(final String name) {
        final StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0, n = name.length(); i < n; i++) {
            final char c = name.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\' || c == ',') {
                sb.append('\\').append(c);
            } else if (Character.isWhitespace(c) && (i == 0 || i == n - 1)) {
                sb.append('[').append(c).append(']');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String fold(final String name, final char wildcard) {
        final StringBuilder sb = new StringBuilder(name.length());
        boolean digits = false;
        for (int i = 0, n = name.length(); i < n; i++) {
            final char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                digits = true;
                continue;
            }
            if (digits) {
                sb.append(wildcard);
                digits = false;
            }
            sb.append(c);
        }
        if (digits) {
            sb.append(wildcard);
        }
        return sb.toString();
    }
}
