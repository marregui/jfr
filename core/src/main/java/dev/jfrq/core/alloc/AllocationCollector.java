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
 */
public final class AllocationCollector implements JfrReader.Sink {

    public static final String SAMPLE = "jdk.ObjectAllocationSample";
    public static final String IN_TLAB = "jdk.ObjectAllocationInNewTLAB";
    public static final String OUTSIDE_TLAB = "jdk.ObjectAllocationOutsideTLAB";

    private final Accumulator sampled = new Accumulator();
    private final Accumulator tlab = new Accumulator();
    private Interner interner = new Interner();
    private AllocationReport report;

    @Override
    public void begin(Interner interner) {
        this.interner = interner;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(SAMPLE, IN_TLAB, OUTSIDE_TLAB);
    }

    @Override
    public void accept(RecordedEvent e) {
        switch (e.getEventType().getName()) {
            case SAMPLE -> sampled.add(e, Events.longOr(e, "weight", 0), interner);
            case IN_TLAB -> tlab.add(e, Events.longOr(e, "tlabSize", 0), interner);
            case OUTSIDE_TLAB -> tlab.add(e, Events.longOr(e, "allocationSize", 0), interner);
            default -> {
            }
        }
    }

    @Override
    public void finish(RecordingInfo info) {
        Accumulator chosen = sampled.samples > 0 ? sampled : tlab;
        String source = sampled.samples > 0 ? SAMPLE : IN_TLAB + " + " + OUTSIDE_TLAB;
        report = new AllocationReport(info, source, chosen.total, chosen.samples,
                chosen.byThread, chosen.byClass, chosen.bySite, chosen.classByThread, chosen.siteByThread);
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
        long samples;
        final Map<String, Long> byThread = new HashMap<>();
        final Map<String, Long> byClass = new HashMap<>();
        final Map<Stack, Long> bySite = new HashMap<>();
        final Map<String, Map<String, Long>> classByThread = new HashMap<>();
        final Map<String, Map<Stack, Long>> siteByThread = new HashMap<>();

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

            total += bytes;
            samples++;
            byThread.merge(threadName, bytes, Long::sum);
            byClass.merge(cls, bytes, Long::sum);
            bySite.merge(site, bytes, Long::sum);
            classByThread.computeIfAbsent(threadName, k -> new HashMap<>()).merge(cls, bytes, Long::sum);
            siteByThread.computeIfAbsent(threadName, k -> new HashMap<>()).merge(site, bytes, Long::sum);
        }
    }
}
