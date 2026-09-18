package dev.jfrq.core.locks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
import jdk.jfr.consumer.RecordedEvent;

/**
 * Collects contended monitor enters and parks into a {@link ContentionReport}.
 *
 * <p>Two things are deliberately not collected. {@code jdk.JavaMonitorWait}
 * ({@code Object.wait()}): a thread in {@code wait()} chose to wait for a notification, it
 * is not contending for the lock. And parks without a blocker object: those are
 * {@code LockSupport.parkNanos} sleeps and pacing loops, not locks. A park with a blocker
 * is kept, though a blocker of {@code AbstractQueuedSynchronizer$ConditionObject} usually
 * means a worker waiting for work on a queue rather than a lock fight; the class is shown
 * so the reader can tell.
 */
public final class ContentionCollector implements JfrReader.Sink {

    public static final String MONITOR_ENTER = "jdk.JavaMonitorEnter";
    public static final String THREAD_PARK = "jdk.ThreadPark";

    private final long minNanos;
    private final Predicate<String> waiterFilter;
    private final List<Wait> waits = new ArrayList<>();
    private Interner interner = new Interner();
    private ContentionReport report;

    @Override
    public void begin(Interner interner) {
        this.interner = interner;
    }

    /**
     * @param minNanos     waits shorter than this are left out of the report
     * @param waiterFilter only waits by threads whose name passes are reported
     */
    public ContentionCollector(long minNanos, Predicate<String> waiterFilter) {
        this.minNanos = minNanos;
        this.waiterFilter = waiterFilter;
    }

    public ContentionCollector() {
        this(0, _ -> true);
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(MONITOR_ENTER, THREAD_PARK);
    }

    /**
     * Every wait is kept, filtered or not: the holder of a lock is found by walking through
     * the waits of <em>other</em> threads, and a short wait by the intermediary is exactly
     * the one that says who really held it. The filters apply in the report.
     */
    @Override
    public void accept(RecordedEvent e) {
        Interval interval = Events.interval(e);
        ThreadRef waiter = interner.thread(e);
        if (waiter == null) {
            return;
        }
        boolean monitor = MONITOR_ENTER.equals(e.getEventType().getName());
        Wait.Kind kind = monitor ? Wait.Kind.MONITOR_ENTER : Wait.Kind.PARK;
        String cls = Events.className(e, monitor ? "monitorClass" : "parkedClass", interner);
        if (!monitor && cls == null) {
            return;
        }
        Wait.LockKey lock = new Wait.LockKey(cls, Events.longOr(e, "address", 0), kind);
        ThreadRef owner = monitor ? Events.thread(e, "previousOwner", interner) : null;
        waits.add(new Wait(interval, waiter, lock, owner, Events.stack(e, interner)));
    }

    @Override
    public void finish(RecordingInfo info) {
        report = new ContentionReport(info, waits, minNanos, waiterFilter);
    }

    public ContentionReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }
}
