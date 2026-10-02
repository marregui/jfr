// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.health;

import java.util.HashMap;
import java.util.Map;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.ThreadRef;

/**
 * The CPU each Java thread used, from {@code jdk.ThreadCPULoad}. Once a period the JVM
 * evaluates every Java thread at one instant, and writes a reading for each that used at
 * least a millisecond of CPU since it last kept the thread's CPU time: that CPU as a share of
 * the JVM's CPUs over the wall-clock time since the thread was last evaluated, capped at one
 * core. The JVM's CPUs are its active processor count, the machine's unless
 * {@code -XX:ActiveProcessorCount}, a container limit or a CPU affinity mask sets fewer; the
 * share passes 100 % when the process uses more CPUs than it counts. A thread that ends
 * writes one more, over the stretch since the last evaluation. A reading times the stretch
 * it covers is the thread's CPU in it, as a share of the JVM's CPUs;
 * summed over the window and divided by it, the thread's share of the JVM's CPUs across it.
 * Summed over every thread it is the Java threads' share, which the JVM's own figure
 * ({@code jdk.CPULoad}) must not fall below where the two divide by the same count (see
 * {@link HealthCollector}): that figure covers the whole process, the collector's threads
 * too, which are not Java threads and have no readings.
 *
 * <p>The stretch a reading covers is not in the event. The JVM keeps a thread's wall-clock
 * time from its start, and from each evaluation after it; the file shows an evaluation as an
 * instant two or more readings share, or as the thread's own previous reading. So a reading
 * covers the time since the latest of: the start of its thread's life (a thread name the JVM
 * reuses, a compiler thread's, starts many times), the thread's previous reading, and the
 * latest instant before it that two or more readings share. That is the time between
 * evaluations as it ran, not as it was meant to: on a loaded broker 22 of 104 periods of 10 s
 * ran over 10.5 s, up to 22.7 s. Weighing every reading as a period overstated the threads
 * that end between periods: six threads that each ran a second read 5.6 % of a 12-core
 * machine for 0.6 %. An evaluation at which only another single thread had a reading is not
 * seen, and a reading after it is weighed from the instant before: the one stretch the file
 * cannot bound, and an overstatement.
 *
 * <p>A thread's first reading covers the time since its start when the start is in the file,
 * and counts like any other, unless native code attached the thread ({@link #start}). Its CPU
 * time then counts from the native thread's creation and its wall-clock time from the attach,
 * so its readings also hold the CPU the native thread used before the attach, which is not in
 * the file: a library thread that ran for hours, or the thread the {@code java} launcher
 * attaches again as {@code DestroyJavaVM} when {@code main} returns, which holds all the CPU
 * {@code main} used. The JVM caps a reading at one core and carries what is above the cap into
 * the thread's next reading, so that CPU comes out at one core a reading until it is spent. The
 * attached life's readings are left out and counted up to and including its first reading below
 * the cap, the one that holds the rest; the readings after it count. The cap is one core of the
 * JVM's CPUs, exactly 1/N: the largest reading in the file when that is 1/N, and none when it is
 * not, which leaves out only the first reading after an attach. A thread spinning a whole core
 * reads just under the cap (8.296 % and 8.307 % of 12 CPUs, an attached spinner's first two),
 * so its own CPU stops the leaving out within a reading or two. The VM's
 * own {@code main}, the thread that created the VM, is attached too, but its native thread did
 * only the launcher's work before that, so its start counts as a start.
 *
 * <p>A reading with no start and no earlier reading of its thread in the file is weighed from
 * the shared instant before it when the recording has {@code jdk.ThreadStart} on: the thread
 * was then alive when the recording began, and each shared instant evaluated it. Without such
 * an instant (a thread alive before the recording, at the first evaluation), or without
 * {@code jdk.ThreadStart} (the thread may have started after the instant), the reading covers
 * a stretch the file does not hold: it is left out and counted. Whether
 * {@code jdk.ThreadStart} was on is the last chunk's setting: a recording that turned it on
 * partway through weighs the threads started before that as alive from the beginning, and
 * overstates the short-lived among them.
 *
 * <p>Readings and starts arrive in file order, not time order, so they are kept flat (G-1.8)
 * and weighed once at the end.
 */
public final class ThreadCpu {

    private final LongList times = new LongList(256);
    private final LongList shares = new LongList(256);
    private final ObjList<ThreadRef> threads = new ObjList<>(256);
    /** Every start in the file, of every life of every thread. */
    private final LongList startTimes = new LongList(64);
    private final ObjList<ThreadRef> startThreads = new ObjList<>(64);
    /** The starts that were native code attaching a thread, a subset of the above. */
    private final LongList attachTimes = new LongList(16);
    private final ObjList<ThreadRef> attachThreads = new ObjList<>(16);

    /**
     * One reading.
     *
     * @param share the thread's user and system CPU as a share of the JVM's CPUs over the stretch it covers
     */
    public void add(final ThreadRef thread, final long time, final double share) {
        times.add(time);
        shares.add(Double.doubleToRawLongBits(share));
        threads.add(thread);
    }

    /** The name HotSpot gives the thread that created the VM, the one attach that brings no CPU. */
    static final String VM_CREATOR = "main";
    /** How close to one core, 1/N, a reading is taken as capped: above two floats' rounding, far below a real gap. */
    private static final double CAP_TOLERANCE = 1e-6;

    /**
     * {@code thread} started at {@code time}; {@code attach} when native code attached it to the
     * JVM, whose readings after that then hold CPU the file does not.
     */
    public void start(final ThreadRef thread, final long time, final boolean attach) {
        startTimes.add(time);
        startThreads.add(thread);
        if (attach && !VM_CREATOR.equals(thread.name())) {
            attachTimes.add(time);
            attachThreads.add(thread);
        }
    }

    /**
     * What the readings say, over {@code info}'s window; {@link Result#UNKNOWN} when there are
     * none.
     */
    public Result result(final RecordingInfo info) {
        final long window = info.span().duration();
        final int n = times.size();
        if (n == 0 || window <= 0) {
            return Result.UNKNOWN;
        }
        final LongList batches = sharedInstants();
        final Map<ThreadRef, LongList> readingsOf = byThread(times, threads);
        final Map<ThreadRef, LongList> startsOf = byThread(startTimes, startThreads);
        final Map<ThreadRef, LongList> attachesOf = byThread(attachTimes, attachThreads);
        final Map<ThreadRef, LongList> belowCapOf = belowCap();
        // With jdk.ThreadStart on, a thread with no start in the file was alive when the recording
        // began, so every shared instant in the file evaluated it. Without it, a thread first read
        // after a shared instant may not have existed then.
        final boolean startsRecorded = info.isEnabled(EventKinds.nameOf(EventKinds.THREAD_START));
        final Map<ThreadRef, Double> byThread = new HashMap<>(readingsOf.size() * 2);
        double total = 0;
        long leftOut = 0;
        for (int i = 0; i < n; i++) {
            final ThreadRef t = threads.getQuick(i);
            final long time = times.getQuick(i);
            final long previous = before(readingsOf.get(t), time);
            final long start = atOrBefore(startsOf.get(t), time);
            final long known = Math.max(previous, start);
            // Since an attach, the native thread's earlier CPU comes out in readings at the cap,
            // and its rest in the first one below it.
            final boolean draining = start != Nulls.LONG_NULL && atOrBefore(attachesOf.get(t), time) == start
                    && before(belowCapOf.get(t), time) < start;
            final long from = draining ? Nulls.LONG_NULL : known != Nulls.LONG_NULL || startsRecorded
                    ? Math.max(before(batches, time), known) : Nulls.LONG_NULL;
            final Double sofar = byThread.get(t);
            if (from == Nulls.LONG_NULL) {
                leftOut++;
                if (sofar == null) {
                    byThread.put(t, 0.0);
                }
                continue;
            }
            final double cpu = Double.longBitsToDouble(shares.getQuick(i)) * (time - from) / window;
            byThread.put(t, (sofar == null ? 0 : sofar) + cpu);
            total += cpu;
        }
        return new Result(byThread, total, n - leftOut, leftOut);
    }

    /**
     * Each thread's readings below the cap, by instant ascending. A capped reading is one core
     * of the JVM's CPUs, exactly 1/N; the largest reading is the cap when it is 1/N, and when it
     * is not, no reading reached the cap and every one is below it.
     */
    private Map<ThreadRef, LongList> belowCap() {
        double largest = 0;
        for (int i = 0, n = shares.size(); i < n; i++) {
            largest = Math.max(largest, Double.longBitsToDouble(shares.getQuick(i)));
        }
        final long cpus = largest > 0 ? Math.round(1 / largest) : 0;
        final double cap = cpus > 0 && Math.abs(largest * cpus - 1) < CAP_TOLERANCE ? largest : Double.POSITIVE_INFINITY;
        final LongList below = new LongList(times.size());
        final ObjList<ThreadRef> owners = new ObjList<>(times.size());
        for (int i = 0, n = times.size(); i < n; i++) {
            if (Double.longBitsToDouble(shares.getQuick(i)) < cap * (1 - CAP_TOLERANCE)) {
                below.add(times.getQuick(i));
                owners.add(threads.getQuick(i));
            }
        }
        return byThread(below, owners);
    }

    /** The instants two or more readings share, ascending: the periodic evaluations. */
    private LongList sharedInstants() {
        final LongList sorted = new LongList(times.size());
        for (int i = 0, n = times.size(); i < n; i++) {
            sorted.add(times.getQuick(i));
        }
        sorted.sort();
        final LongList shared = new LongList(64);
        for (int i = 1, n = sorted.size(); i < n; i++) {
            final long t = sorted.getQuick(i);
            if (t == sorted.getQuick(i - 1) && (shared.isEmpty() || shared.getLast() != t)) {
                shared.add(t);
            }
        }
        return shared;
    }

    /** Each thread's instants, ascending. */
    private static Map<ThreadRef, LongList> byThread(final LongList instants, final ObjList<ThreadRef> owners) {
        final Map<ThreadRef, LongList> out = new HashMap<>(64);
        for (int i = 0, n = instants.size(); i < n; i++) {
            final ThreadRef t = owners.getQuick(i);
            LongList list = out.get(t);
            if (list == null) {
                list = new LongList(16);
                out.put(t, list);
            }
            list.add(instants.getQuick(i));
        }
        for (final LongList list : out.values()) {
            list.sort();
        }
        return out;
    }

    /** The latest of the ascending {@code instants} strictly before {@code time}; LONG_NULL when none. */
    static long before(final LongList instants, final long time) {
        if (instants == null) {
            return Nulls.LONG_NULL;
        }
        final int at = instants.lowerBound(time);
        return at == 0 ? Nulls.LONG_NULL : instants.getQuick(at - 1);
    }

    /** The latest of the ascending {@code instants} at or before {@code time}; LONG_NULL when none. */
    static long atOrBefore(final LongList instants, final long time) {
        return before(instants, time + 1);
    }

    /**
     * @param byThread each thread's share of the JVM's CPUs across the window; empty when unknown
     * @param share    their sum: the Java threads' share of the JVM's CPUs; NaN when unknown
     * @param readings the readings counted
     * @param leftOut  the readings left out: an attached thread's, up to its first below the cap,
     *                 which hold CPU from before the attach; or one with no start and no earlier
     *                 reading of its thread in the file, and no shared instant before it that
     *                 evaluated the thread, so the stretch it covers is unknown
     */
    public record Result(Map<ThreadRef, Double> byThread, double share, long readings, long leftOut) {

        /** No {@code jdk.ThreadCPULoad} readings, or a recording with no window. */
        public static final Result UNKNOWN = new Result(Map.of(), Double.NaN, 0, 0);

        public Result {
            byThread = Map.copyOf(byThread);
        }

        public boolean isKnown() {
            return !Double.isNaN(share);
        }

        /** The share of {@code thread}, or NaN when it has no reading. */
        public double of(final ThreadRef thread) {
            final Double s = byThread.get(thread);
            return s == null ? Double.NaN : s;
        }
    }
}
