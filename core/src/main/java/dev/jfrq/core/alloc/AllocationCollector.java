// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.alloc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
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
 *
 * <p>Independently of either, {@code jdk.ThreadAllocationStatistics} (written at every
 * chunk boundary by the JDK's own settings) carries each thread's exact allocation
 * counter. For a thread seen at least twice, the difference between its last and first
 * counter is what it allocated in between; the report shows it next to the estimate, so
 * a reader knows how far the sampling is from the truth for the threads that matter.
 * A thread that started and ended between two counter events is never counted.
 *
 * <p>The aggregation runs on primitive-valued maps and the counters on a flat list
 * (G-1.3, G-1.8); the {@code java.util} maps the report exposes are built once at the end.
 */
public final class AllocationCollector implements JfrReader.Sink {

    public static final String SAMPLE = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_SAMPLE);
    public static final String IN_TLAB = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_IN_NEW_TLAB);
    public static final String OUTSIDE_TLAB = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_OUTSIDE_TLAB);
    public static final String THREAD_STATISTICS = EventKinds.nameOf(EventKinds.THREAD_ALLOCATION_STATISTICS);

    private static final Set<String> TYPES = Set.of(SAMPLE, IN_TLAB, OUTSIDE_TLAB, THREAD_STATISTICS);

    /** Per thread, three longs at a stride of four: the smallest and largest counter seen, and how often. */
    private static final int COUNTER_STRIDE = 4;
    private static final int COUNTER_MIN = 0;
    private static final int COUNTER_MAX = 1;
    private static final int COUNTER_SEEN = 2;

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
    public void accept(final RecordedEvent e) {
        accept(e, EventKinds.kindOf(e.getEventType().getName()));
    }

    @Override
    public void accept(final RecordedEvent e, final int kind) {
        switch (kind) {
            case EventKinds.OBJECT_ALLOCATION_SAMPLE -> sampled.add(e, Events.longOr(e, "weight", 0), interner);
            case EventKinds.OBJECT_ALLOCATION_IN_NEW_TLAB -> tlab.add(e, Events.longOr(e, "tlabSize", 0), interner);
            case EventKinds.OBJECT_ALLOCATION_OUTSIDE_TLAB ->
                    tlab.add(e, Events.longOr(e, "allocationSize", 0), interner);
            case EventKinds.THREAD_ALLOCATION_STATISTICS -> counter(e);
            default -> {
            }
        }
    }

    /**
     * Events arrive in file order, which is chunk order, and the counter only grows, so the
     * smallest and largest values seen are the first and last: no timestamps needed.
     */
    private void counter(final RecordedEvent e) {
        final ThreadRef thread = Events.thread(e, "thread", interner);
        if (thread == null) {
            return;
        }
        final long allocated = Events.longOr(e, "allocated", -1);
        if (allocated < 0) {
            return;
        }
        final int index = counterSlot.keyIndex(thread);
        int base;
        if (index < 0) {
            base = (int) counterSlot.valueAtQuick(index);
        } else {
            base = counters.size();
            counters.add(Long.MAX_VALUE);
            counters.add(Long.MIN_VALUE);
            counters.add(0);
            counters.add(0);
            counterSlot.putAt(index, thread, base);
        }
        counters.setQuick(base + COUNTER_MIN, Math.min(counters.getQuick(base + COUNTER_MIN), allocated));
        counters.setQuick(base + COUNTER_MAX, Math.max(counters.getQuick(base + COUNTER_MAX), allocated));
        counters.setQuick(base + COUNTER_SEEN, counters.getQuick(base + COUNTER_SEEN) + 1);
    }

    @Override
    public void finish(final RecordingInfo info) {
        sampled.dropFirsts();
        // A recording with sampled events is answered from them even if every thread had only one.
        final Accumulator chosen = sampled.events > 0 ? sampled : tlab;
        final String source = sampled.events > 0 ? SAMPLE : IN_TLAB + " + " + OUTSIDE_TLAB;
        report = new AllocationReport(info, source, chosen.total, chosen.samples, chosen.events, countedByThread(),
                toMap(chosen.byThread), toMap(chosen.byClass), toMap(chosen.bySite), toMaps(chosen.classByThread),
                toMaps(chosen.siteByThread), new AllocationReport.Support(toMap(chosen.countByThread),
                toMap(chosen.countByClass), toMap(chosen.countBySite)));
    }

    /**
     * Per thread name, what the JVM's counter grew by between its first and last event;
     * threads seen once are left out. */
    private Map<String, Long> countedByThread() {
        final Map<String, Long> counted = new HashMap<>();
        for (int s = 0, n = counterSlot.slots(); s < n; s++) {
            if (!counterSlot.hasKeyAtSlot(s)) {
                continue;
            }
            final int base = (int) counterSlot.valueAtSlot(s);
            if (counters.getQuick(base + COUNTER_SEEN) >= 2) {
                counted.merge(counterSlot.keyAtSlot(s).name(),
                        counters.getQuick(base + COUNTER_MAX) - counters.getQuick(base + COUNTER_MIN), Long::sum);
            }
        }
        return counted;
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
        final ObjLongHashMap<String> byThread = new ObjLongHashMap<>(64);
        final ObjLongHashMap<String> byClass = new ObjLongHashMap<>(1024);
        final ObjLongHashMap<Stack> bySite = new ObjLongHashMap<>(4096);
        /** Samples behind each of the three, so a row can say how much evidence it rests on. */
        final ObjLongHashMap<String> countByThread = new ObjLongHashMap<>(64);
        final ObjLongHashMap<String> countByClass = new ObjLongHashMap<>(1024);
        final ObjLongHashMap<Stack> countBySite = new ObjLongHashMap<>(4096);
        final ObjObjHashMap<String, ObjLongHashMap<String>> classByThread = new ObjObjHashMap<>(64);
        final ObjObjHashMap<String, ObjLongHashMap<Stack>> siteByThread = new ObjObjHashMap<>(64);
        /** Per thread, the earliest sample seen (delivery is file order, not time order). */
        private final ObjObjHashMap<ThreadRef, First> firsts;

        /** One thread's earliest sample, re-pointed when an earlier one turns up (G-3.1). */
        private static final class First {
            long time;
            long bytes;
            String threadName;
            String cls;
            Stack site;

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

        void add(final RecordedEvent e, final long bytes, final Interner interner) {
            if (bytes <= 0) {
                return;
            }
            final ThreadRef thread = interner.thread(e);
            final String threadName = thread == null ? NO_THREAD : thread.name();
            String cls = Events.className(e, "objectClass", interner);
            if (cls == null) {
                cls = NO_CLASS;
            }
            final Stack site = Events.stack(e, interner);
            add(bytes, threadName, cls, site);
            if (firsts != null && thread != null) {
                final long time = Events.startNanos(e);
                final int index = firsts.keyIndex(thread);
                if (index >= 0) {
                    firsts.putAt(index, thread, new First().of(time, bytes, threadName, cls, site));
                } else {
                    final First known = firsts.valueAtQuick(index);
                    if (time < known.time) {
                        known.of(time, bytes, threadName, cls, site);
                    }
                }
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

        /** Takes every thread's first sample back out of the totals. */
        void dropFirsts() {
            if (firsts == null) {
                return;
            }
            for (int s = 0, n = firsts.slots(); s < n; s++) {
                if (!firsts.hasKeyAtSlot(s)) {
                    continue;
                }
                final First f = firsts.valueAtSlot(s);
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
