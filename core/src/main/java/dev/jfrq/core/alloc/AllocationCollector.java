package dev.jfrq.core.alloc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
 */
public final class AllocationCollector implements JfrReader.Sink {

    public static final String SAMPLE = "jdk.ObjectAllocationSample";
    public static final String IN_TLAB = "jdk.ObjectAllocationInNewTLAB";
    public static final String OUTSIDE_TLAB = "jdk.ObjectAllocationOutsideTLAB";
    public static final String THREAD_STATISTICS = "jdk.ThreadAllocationStatistics";

    private final Accumulator sampled = new Accumulator(true);
    /** TLAB events each stand for one buffer, so the first one carries no history. */
    private final Accumulator tlab = new Accumulator(false);
    /** Per thread: the smallest and largest {@code allocated} counter seen, and how many times it was seen. */
    private final Map<ThreadRef, long[]> counters = new HashMap<>();
    private Interner interner = new Interner();
    private AllocationReport report;

    @Override
    public void begin(Interner interner) {
        this.interner = interner;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(SAMPLE, IN_TLAB, OUTSIDE_TLAB, THREAD_STATISTICS);
    }

    @Override
    public void accept(RecordedEvent e) {
        switch (e.getEventType().getName()) {
            case SAMPLE -> sampled.add(e, Events.longOr(e, "weight", 0), interner);
            case IN_TLAB -> tlab.add(e, Events.longOr(e, "tlabSize", 0), interner);
            case OUTSIDE_TLAB -> tlab.add(e, Events.longOr(e, "allocationSize", 0), interner);
            case THREAD_STATISTICS -> counter(e);
            default -> {
            }
        }
    }

    /**
     * Events arrive in file order, which is chunk order, and the counter only grows, so the
     * smallest and largest values seen are the first and last: no timestamps needed.
     */
    private void counter(RecordedEvent e) {
        ThreadRef thread = Events.thread(e, "thread", interner);
        if (thread == null) {
            return;
        }
        long allocated = Events.longOr(e, "allocated", -1);
        if (allocated < 0) {
            return;
        }
        long[] c = counters.computeIfAbsent(thread, _ -> new long[] {Long.MAX_VALUE, Long.MIN_VALUE, 0});
        c[0] = Math.min(c[0], allocated);
        c[1] = Math.max(c[1], allocated);
        c[2]++;
    }

    @Override
    public void finish(RecordingInfo info) {
        sampled.dropFirsts();
        // A recording with sampled events is answered from them even if every thread had only one.
        Accumulator chosen = sampled.events > 0 ? sampled : tlab;
        String source = sampled.events > 0 ? SAMPLE : IN_TLAB + " + " + OUTSIDE_TLAB;
        report = new AllocationReport(info, source, chosen.total, chosen.samples, chosen.events, countedByThread(),
                chosen.byThread, chosen.byClass, chosen.bySite, chosen.classByThread, chosen.siteByThread);
    }

    /** Per thread name, what the JVM's counter grew by between its first and last event; threads seen once are left out. */
    private Map<String, Long> countedByThread() {
        Map<String, Long> counted = new HashMap<>();
        counters.forEach((thread, c) -> {
            if (c[2] >= 2) {
                counted.merge(thread.name(), c[1] - c[0], Long::sum);
            }
        });
        return counted;
    }

    /** Available after {@link JfrReader#read}. */
    public AllocationReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }

    private static final class Accumulator {
        long total;
        /** Samples in the totals. */
        long samples;
        /** Events seen, discarded or not. */
        long events;
        final Map<String, Long> byThread = new HashMap<>();
        final Map<String, Long> byClass = new HashMap<>();
        final Map<Stack, Long> bySite = new HashMap<>();
        final Map<String, Map<String, Long>> classByThread = new HashMap<>();
        final Map<String, Map<Stack, Long>> siteByThread = new HashMap<>();
        /** Per thread, the earliest sample seen (delivery is file order, not time order). */
        private final Map<ThreadRef, First> firsts;

        private record First(long time, long bytes, String threadName, String cls, Stack site) {
        }

        Accumulator(boolean dropFirstPerThread) {
            this.firsts = dropFirstPerThread ? new HashMap<>() : null;
        }

        void add(RecordedEvent e, long bytes, Interner interner) {
            if (bytes <= 0) {
                return;
            }
            ThreadRef thread = interner.thread(e);
            String threadName = thread == null ? "<no thread>" : thread.name();
            String cls = Events.className(e, "objectClass", interner);
            if (cls == null) {
                cls = "?";
            }
            Stack site = Events.stack(e, interner);
            add(bytes, threadName, cls, site);
            if (firsts != null && thread != null) {
                long time = Events.startNanos(e);
                First known = firsts.get(thread);
                if (known == null || time < known.time()) {
                    firsts.put(thread, new First(time, bytes, threadName, cls, site));
                }
            }
        }

        private void add(long bytes, String threadName, String cls, Stack site) {
            total += bytes;
            samples++;
            events++;
            byThread.merge(threadName, bytes, Long::sum);
            byClass.merge(cls, bytes, Long::sum);
            bySite.merge(site, bytes, Long::sum);
            classByThread.computeIfAbsent(threadName, _ -> new HashMap<>()).merge(cls, bytes, Long::sum);
            siteByThread.computeIfAbsent(threadName, _ -> new HashMap<>()).merge(site, bytes, Long::sum);
        }

        /** Takes every thread's first sample back out of the totals. */
        void dropFirsts() {
            if (firsts == null) {
                return;
            }
            for (First f : firsts.values()) {
                total -= f.bytes();
                samples--;
                subtract(byThread, f.threadName(), f.bytes());
                subtract(byClass, f.cls(), f.bytes());
                subtract(bySite, f.site(), f.bytes());
                // Two threads can share a name (a pool that recycles them); the per-name maps may
                // already be gone after the first of them.
                subtract(classByThread.get(f.threadName()), f.cls(), f.bytes());
                subtract(siteByThread.get(f.threadName()), f.site(), f.bytes());
                if (!byThread.containsKey(f.threadName())) {
                    classByThread.remove(f.threadName());
                    siteByThread.remove(f.threadName());
                }
            }
            firsts.clear();
        }

        private static <K> void subtract(Map<K, Long> map, K key, long bytes) {
            if (map == null) {
                return;
            }
            long left = map.getOrDefault(key, 0L) - bytes;
            if (left > 0) {
                map.put(key, left);
            } else {
                map.remove(key);
            }
        }
    }
}
