// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.alloc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.Fields;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import jdk.jfr.consumer.RecordedEvent;

/**
 * Aggregates allocation events into an {@link AllocationReport}.
 *
 * <p>Two families of events can carry the estimate:
 * <ul>
 *   <li>{@code jdk.ObjectAllocationSample} (JDK 16+): throttled samples whose {@code weight}
 *       field is the number of bytes the sample stands for. Summing weights, never counting
 *       samples, gives the estimate.</li>
 *   <li>{@code jdk.ObjectAllocationInNewTLAB} / {@code OutsideTLAB} (older recordings, or
 *       profiles that enable them explicitly): one event per new TLAB, weighted by the TLAB
 *       size, plus one per large allocation, weighted by its size.</li>
 * </ul>
 * When both are present the sampled family is used, because it is the one the JDK tunes.
 *
 * <p>One sample per thread is discarded: the first. Its weight is the bytes the thread
 * allocated since it was <em>last sampled</em>, and for a thread that was never sampled
 * before, or not since an earlier recording hours ago, that is its lifetime allocation.
 * Left in, a main thread that allocated 100 MB at start-up and nothing during a one-minute
 * recording is reported as allocating 100 MB in that minute. Dropping the sample loses at
 * most the bytes between the previous sample and this one, which for a thread sampled at
 * hundreds of times a second is nothing, and for a thread sampled once is unknowable.
 * A platform thread whose {@code jdk.ThreadStart} is in the file keeps its first sample:
 * its lifetime began inside the recording, so the weight is all in the window, and
 * dropping it lost most of what short-lived pool threads allocated.
 *
 * <p>A virtual thread loses its first sample too, and the report says how many and how
 * heavy they were ({@link AllocationReport.Dropped}). The JVM counts allocation per
 * carrier, so a sample taken on a virtual thread is weighted by what its carrier allocated
 * since the carrier was last sampled, whichever virtual threads it ran meanwhile, and a
 * carrier never sampled before carries everything it allocated since it started: 3.35 GB
 * for 67 MB allocated inside the recording, on a JVM that had been running virtual threads
 * before it. The event names the virtual thread, not the carrier, so that sample cannot
 * be told apart from an honest one; dropping every virtual thread's first sample removes
 * it whenever it lands on one, at the price of most virtual-thread allocation (a virtual
 * thread is typically sampled once or never). It does not make the estimate a floor: a
 * virtual thread that resumed on a carrier not yet sampled carries that carrier's history
 * on a later sample, which is kept.
 *
 * <p>Independently of either, {@code jdk.ThreadAllocationStatistics} (written at every
 * chunk boundary by the JDK's own settings) carries each thread's exact allocation
 * counter. The difference between its last counter and its first is what the thread
 * allocated in between, and a thread that started in the file had a counter of zero at its
 * {@code jdk.ThreadStart}; the report shows that next to the estimate, so a reader knows how
 * far the sampling is from the truth for the threads that matter. The estimate it is set
 * against is the samples inside that same stretch, which is why every thread's sample times
 * are kept (two longs a sample): set against the whole file, a pool thread started after the
 * first chunk had its early allocation in the estimate and not in the counter, which read as
 * an estimate 28 % high on a file whose samples were right. A thread that started and ended
 * between two counter events is never counted.
 *
 * <p>The aggregation runs on primitive-valued maps and the counters on a flat list
 * (G-1.3, G-1.8); the {@code java.util} maps the report exposes are built once at the end.
 */
public final class AllocationCollector implements JfrReader.Sink {

    public static final String SAMPLE = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_SAMPLE);
    public static final String IN_TLAB = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_IN_NEW_TLAB);
    public static final String OUTSIDE_TLAB = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_OUTSIDE_TLAB);
    public static final String THREAD_STATISTICS = EventKinds.nameOf(EventKinds.THREAD_ALLOCATION_STATISTICS);

    public static final String THREAD_START = EventKinds.nameOf(EventKinds.THREAD_START);

    private static final Set<String> TYPES = Set.of(SAMPLE, IN_TLAB, OUTSIDE_TLAB, THREAD_STATISTICS, THREAD_START);

    /**
     * Per thread, at a stride of six: the smallest and largest counter seen, how often, when
     * the first and the last were read, and when the thread started ({@code Nulls.LONG_NULL}
     * when that is not in the file).
     */
    private static final int COUNTER_STRIDE = 6;
    private static final int COUNTER_MIN = 0;
    private static final int COUNTER_MAX = 1;
    private static final int COUNTER_SEEN = 2;
    private static final int COUNTER_FIRST_TIME = 3;
    private static final int COUNTER_LAST_TIME = 4;
    private static final int COUNTER_STARTED = 5;

    private final Accumulator sampled = new Accumulator(true);
    /** TLAB events each stand for one buffer, so the first one carries no history. */
    private final Accumulator tlab = new Accumulator(false);
    private final ObjLongHashMap<ThreadRef> counterSlot = new ObjLongHashMap<>(256);
    private final LongList counters = new LongList(256 * COUNTER_STRIDE);
    private Interner interner = new Interner();
    private AllocationReport report;

    @Override
    public void begin(final Interner interner) {
        this.interner = interner;
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public void accept(@Transient final RecordedEvent e) {
        accept(e, EventKinds.kindOf(e.getEventType().getName()));
    }

    @Override
    public void accept(@Transient final RecordedEvent e, final int kind) {
        switch (kind) {
            case EventKinds.OBJECT_ALLOCATION_SAMPLE -> sampled.add(e, Events.longOr(e, Fields.WEIGHT, 0, interner), interner);
            case EventKinds.OBJECT_ALLOCATION_IN_NEW_TLAB -> tlab.add(e, Events.longOr(e, Fields.TLAB_SIZE, 0, interner), interner);
            case EventKinds.OBJECT_ALLOCATION_OUTSIDE_TLAB ->
                    tlab.add(e, Events.longOr(e, Fields.ALLOCATION_SIZE, 0, interner), interner);
            case EventKinds.THREAD_ALLOCATION_STATISTICS -> counter(e);
            case EventKinds.THREAD_START -> started(e);
            default -> {
            }
        }
    }

    /**
     * A {@code jdk.ObjectAllocationSample} whose fields are already read: what
     * {@link #accept(RecordedEvent, int)} does after {@link Events}, for tests that build the
     * samples by hand. {@code cls} may be {@code null}.
     */
    void sample(final ThreadRef thread, final long time, final long weight, final String cls, final Stack site) {
        sampled.add(thread, time, weight, cls, site);
    }

    /**
     * The counter only grows, so the smallest value seen is the first reading and the largest
     * the last, and their times say which stretch the difference covers.
     */
    private void counter(@Transient final RecordedEvent e) {
        final ThreadRef thread = Events.thread(e, Fields.THREAD, interner);
        if (thread == null) {
            return;
        }
        final long allocated = Events.longOr(e, Fields.ALLOCATED, -1, interner);
        if (allocated < 0) {
            return;
        }
        counter(thread, Events.startNanos(e), allocated);
    }

    /** A counter reading whose fields are already read, for tests that build them by hand. */
    void counter(final ThreadRef thread, final long time, final long allocated) {
        final int base = counterSlot(thread);
        // A thread that stopped allocating reads the same value again: the stretch still runs
        // from the earliest reading of the smallest value to the latest of the largest.
        final long min = counters.getQuick(base + COUNTER_MIN);
        if (allocated < min || allocated == min && time < counters.getQuick(base + COUNTER_FIRST_TIME)) {
            counters.setQuick(base + COUNTER_MIN, allocated);
            counters.setQuick(base + COUNTER_FIRST_TIME, time);
        }
        final long max = counters.getQuick(base + COUNTER_MAX);
        if (allocated > max || allocated == max && time > counters.getQuick(base + COUNTER_LAST_TIME)) {
            counters.setQuick(base + COUNTER_MAX, allocated);
            counters.setQuick(base + COUNTER_LAST_TIME, time);
        }
        counters.setQuick(base + COUNTER_SEEN, counters.getQuick(base + COUNTER_SEEN) + 1);
    }

    /** A thread that started in the file: its counter was zero then. */
    private void started(@Transient final RecordedEvent e) {
        final ThreadRef thread = interner.thread(e);
        if (thread != null && !thread.isVirtual()) {
            started(thread, Events.startNanos(e));
        }
    }

    /** A {@code jdk.ThreadStart} whose fields are already read, for tests that build them by hand. */
    void started(final ThreadRef thread, final long time) {
        counters.setQuick(counterSlot(thread) + COUNTER_STARTED, time);
    }

    private int counterSlot(final ThreadRef thread) {
        final int index = counterSlot.keyIndex(thread);
        if (index < 0) {
            return (int) counterSlot.valueAtQuick(index);
        }
        final int base = counters.size();
        counters.add(Long.MAX_VALUE);
        counters.add(Long.MIN_VALUE);
        counters.add(0);
        counters.add(Nulls.LONG_NULL);
        counters.add(Nulls.LONG_NULL);
        counters.add(Nulls.LONG_NULL);
        counterSlot.putAt(index, thread, base);
        return base;
    }

    @Override
    public void finish(final RecordingInfo info) {
        // A recording with sampled events is answered from them even if every thread had only one.
        final Accumulator chosen = sampled.events > 0 ? sampled : tlab;
        final String source = sampled.events > 0 ? SAMPLE : IN_TLAB + " + " + OUTSIDE_TLAB;
        final Map<String, Long> counted = new HashMap<>();
        final Map<String, Long> estimated = new HashMap<>();
        counted(chosen, counted, estimated);
        sampled.dropFirsts(this::startedInFile);
        final Map<String, Long> byThread = toMap(chosen.byThread);
        if (chosen.firsts == null) {
            // The TLAB events' times are not read: their estimate is the whole file's.
            for (final String name : counted.keySet()) {
                estimated.put(name, byThread.getOrDefault(name, 0L));
            }
        }
        report = new AllocationReport(info, source, chosen.total, chosen.samples, chosen.events, counted,
                byThread, toMap(chosen.byClass), toMap(chosen.bySite), toMaps(chosen.classByThread),
                toMaps(chosen.siteByThread), new AllocationReport.Support(toMap(chosen.countByThread),
                toMap(chosen.countByClass), toMap(chosen.countBySite)),
                new AllocationReport.Dropped(chosen.virtualFirsts, chosen.virtualFirstBytes), estimated);
    }

    /**
     * Per thread name, what the JVM's counter grew by between its first reading, or the
     * thread's start, and its last reading, into {@code counted}; and what the samples the
     * estimate keeps say about that same stretch, into {@code estimated}. Called before
     * {@link Accumulator#dropFirsts}, which forgets the sample times.
     */
    private void counted(final Accumulator chosen, final Map<String, Long> counted, final Map<String, Long> estimated) {
        for (int s = 0, n = counterSlot.slots(); s < n; s++) {
            if (!counterSlot.hasKeyAtSlot(s)) {
                continue;
            }
            final ThreadRef thread = counterSlot.keyAtSlot(s);
            final int base = (int) counterSlot.valueAtSlot(s);
            final long seen = counters.getQuick(base + COUNTER_SEEN);
            final long started = counters.getQuick(base + COUNTER_STARTED);
            if (seen == 0 || seen == 1 && started == Nulls.LONG_NULL) {
                continue;
            }
            final boolean fromStart = started != Nulls.LONG_NULL;
            final long first = fromStart ? 0 : counters.getQuick(base + COUNTER_MIN);
            counted.merge(thread.name(), counters.getQuick(base + COUNTER_MAX) - first, Long::sum);
            estimated.merge(thread.name(), chosen.within(thread, fromStart,
                    fromStart ? started : counters.getQuick(base + COUNTER_FIRST_TIME),
                    counters.getQuick(base + COUNTER_LAST_TIME)), Long::sum);
        }
    }

    /** Whether the thread started in the file, which makes its first sample honest. */
    private boolean startedInFile(final ThreadRef thread) {
        final int index = counterSlot.keyIndex(thread);
        return index < 0 && counters.getQuick((int) counterSlot.valueAtQuick(index) + COUNTER_STARTED) != Nulls.LONG_NULL;
    }

    /** Available after {@link JfrReader#read}. */
    public AllocationReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }

    private static <K> Map<K, Long> toMap(final ObjLongHashMap<K> map) {
        final Map<K, Long> out = new HashMap<>(map.size() * 2);
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                out.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        return out;
    }

    private static <K> Map<String, Map<K, Long>> toMaps(final ObjObjHashMap<String, ObjLongHashMap<K>> maps) {
        final Map<String, Map<K, Long>> out = new HashMap<>(maps.size() * 2);
        for (int s = 0, n = maps.slots(); s < n; s++) {
            if (maps.hasKeyAtSlot(s)) {
                out.put(maps.keyAtSlot(s), toMap(maps.valueAtSlot(s)));
            }
        }
        return out;
    }

    private static final class Accumulator {
        private static final String NO_THREAD = "<no thread>";
        private static final String NO_CLASS = "?";

        long total;
        /** Samples in the totals. */
        long samples;
        /** Events seen, discarded or not. */
        long events;
        /** Virtual threads' first samples taken out of the totals, and their weight. */
        long virtualFirsts;
        long virtualFirstBytes;
        final ObjLongHashMap<String> byThread = new ObjLongHashMap<>(64);
        final ObjLongHashMap<String> byClass = new ObjLongHashMap<>(1024);
        final ObjLongHashMap<Stack> bySite = new ObjLongHashMap<>(4096);
        /** Samples behind each of the three, so a row can say how much evidence it rests on. */
        final ObjLongHashMap<String> countByThread = new ObjLongHashMap<>(64);
        final ObjLongHashMap<String> countByClass = new ObjLongHashMap<>(1024);
        final ObjLongHashMap<Stack> countBySite = new ObjLongHashMap<>(4096);
        final ObjObjHashMap<String, ObjLongHashMap<String>> classByThread = new ObjObjHashMap<>(64);
        final ObjObjHashMap<String, ObjLongHashMap<Stack>> siteByThread = new ObjObjHashMap<>(64);
        /**
         * Per thread, the earliest sample seen (delivery is file order, not time order), and when
         * the samples after it began and ended.
         */
        private final ObjObjHashMap<ThreadRef, First> firsts;

        /** One thread's earliest sample, re-pointed when an earlier one turns up (G-3.1). */
        private static final class First {
            long time;
            long bytes;
            String threadName;
            String cls;
            Stack site;
            /** Every sample of the thread, time and weight, for the estimate over its counter's stretch. */
            final LongList samples = new LongList(16);

            First of(final long time, final long bytes, final String threadName, final String cls, final Stack site) {
                this.time = time;
                this.bytes = bytes;
                this.threadName = threadName;
                this.cls = cls;
                this.site = site;
                return this;
            }
        }

        Accumulator(final boolean dropFirstPerThread) {
            this.firsts = dropFirstPerThread ? new ObjObjHashMap<>(64) : null;
        }

        void add(@Transient final RecordedEvent e, final long bytes, final Interner interner) {
            if (bytes <= 0) {
                return;
            }
            // The timestamp costs an Instant, and only the sampled family needs it.
            add(interner.thread(e), firsts != null ? Events.startNanos(e) : Nulls.LONG_NULL, bytes,
                    Events.className(e, Fields.OBJECT_CLASS, interner), Events.stack(e, interner));
        }

        /** One event, its fields already read; {@code thread} and {@code cls} may be {@code null}. */
        void add(final ThreadRef thread, final long time, final long bytes, final String cls, final Stack site) {
            if (bytes <= 0) {
                return;
            }
            final String threadName = thread == null ? NO_THREAD : thread.name();
            final String className = cls == null ? NO_CLASS : cls;
            add(bytes, threadName, className, site);
            if (firsts != null && thread != null) {
                final int index = firsts.keyIndex(thread);
                final First f;
                if (index >= 0) {
                    f = firsts.putAt(index, thread, new First().of(time, bytes, threadName, className, site));
                } else {
                    f = firsts.valueAtQuick(index);
                    if (time < f.time) {
                        f.of(time, bytes, threadName, className, site);
                    }
                }
                f.samples.add(time);
                f.samples.add(bytes);
            }
        }

        private void add(final long bytes, final String threadName, final String cls, final Stack site) {
            total += bytes;
            samples++;
            events++;
            byThread.increment(threadName, bytes);
            byClass.increment(cls, bytes);
            bySite.increment(site, bytes);
            countByThread.increment(threadName, 1);
            countByClass.increment(cls, 1);
            countBySite.increment(site, 1);
            perThread(classByThread, threadName).increment(cls, bytes);
            perThread(siteByThread, threadName).increment(site, bytes);
        }

        private static <K> ObjLongHashMap<K> perThread(final ObjObjHashMap<String, ObjLongHashMap<K>> maps, final String thread) {
            final int index = maps.keyIndex(thread);
            return index < 0 ? maps.valueAtQuick(index) : maps.putAt(index, thread, new ObjLongHashMap<>(64));
        }

        /**
         * The weight of the thread's samples the estimate keeps that fall in {@code [from, to]}:
         * all of them for a thread that started in the file, all but its earliest otherwise.
         */
        long within(final ThreadRef thread, final boolean startedInFile, final long from, final long to) {
            final First f = firsts == null ? null : firsts.get(thread);
            if (f == null) {
                return 0;
            }
            long sum = 0;
            boolean dropped = startedInFile;
            for (int i = 0, n = f.samples.size(); i < n; i += 2) {
                final long time = f.samples.getQuick(i);
                if (!dropped && time == f.time) {
                    dropped = true;
                    continue;
                }
                if (time >= from && time <= to) {
                    sum += f.samples.getQuick(i + 1);
                }
            }
            return sum;
        }

        /**
         * Takes every thread's first sample back out of the totals, counting the virtual ones,
         * except a platform thread's that {@code startedInFile}: its history is all in the window.
         */
        void dropFirsts(final Predicate<ThreadRef> startedInFile) {
            if (firsts == null) {
                return;
            }
            for (int s = 0, n = firsts.slots(); s < n; s++) {
                if (!firsts.hasKeyAtSlot(s) || startedInFile.test(firsts.keyAtSlot(s))) {
                    continue;
                }
                final First f = firsts.valueAtSlot(s);
                if (firsts.keyAtSlot(s).isVirtual()) {
                    virtualFirsts++;
                    virtualFirstBytes += f.bytes;
                }
                total -= f.bytes;
                samples--;
                subtract(byThread, f.threadName, f.bytes);
                subtract(byClass, f.cls, f.bytes);
                subtract(bySite, f.site, f.bytes);
                subtract(countByThread, f.threadName, 1);
                subtract(countByClass, f.cls, 1);
                subtract(countBySite, f.site, 1);
                // Two threads can share a name (a pool that recycles them); the per-name maps may
                // already be gone after the first of them.
                subtract(classByThread.get(f.threadName), f.cls, f.bytes);
                subtract(siteByThread.get(f.threadName), f.site, f.bytes);
                if (byThread.excludes(f.threadName)) {
                    classByThread.remove(f.threadName);
                    siteByThread.remove(f.threadName);
                }
            }
            firsts.clear();
        }

        private static <K> void subtract(final ObjLongHashMap<K> map, final K key, final long bytes) {
            if (map == null) {
                return;
            }
            final int index = map.keyIndex(key);
            final long left = (index < 0 ? map.valueAtQuick(index) : 0) - bytes;
            if (left > 0) {
                if (index < 0) {
                    map.put(key, left);
                } else {
                    map.putAt(index, key, left);
                }
            } else if (index < 0) {
                map.removeAt(index);
            }
        }
    }
}
