package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Timeline.Block;
import dev.jfrq.core.stalls.Timeline.BlockKind;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.stalls.Timeline.ThreadTimeline;
import dev.jfrq.core.util.ClassNames;
import jdk.jfr.consumer.RecordedEvent;

/**
 * Builds {@link ThreadTimeline}s for the threads matching a filter, plus the JVM-wide
 * pauses, from one pass over a recording; then hands them to {@link StallAnalysis}.
 */
public final class StallCollector implements JfrReader.Sink {

    private static final Set<String> TYPES = Set.of(
            "jdk.ExecutionSample", "jdk.NativeMethodSample",
            "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.JavaMonitorWait", "jdk.ThreadSleep",
            "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite", "jdk.FileForce",
            "jdk.GCPhasePause", "jdk.SafepointBegin", "jdk.SafepointEnd");

    private final Predicate<String> threadFilter;
    private final IdleMatcher idle;
    private final StallAnalysis analysis;

    private final Map<ThreadRef, List<Sample>> samples = new HashMap<>();
    private final Map<ThreadRef, List<Block>> blocks = new HashMap<>();
    /** Monitor waits of every thread, watched or not, to trace who really held a lock. */
    private final Map<ThreadRef, List<Block>> monitorWaits = new HashMap<>();
    private final List<Pause> gcPauses = new ArrayList<>();
    private final Map<Long, Interval> safepointBegins = new HashMap<>();
    private final Map<Long, Long> safepointEnds = new HashMap<>();
    private Interner interner = new Interner();
    private StallReport report;

    @Override
    public void begin(Interner interner) {
        this.interner = interner;
    }

    public StallCollector(Predicate<String> threadFilter, IdleMatcher idle, long gapNanos) {
        this.threadFilter = threadFilter;
        this.idle = idle;
        this.analysis = new StallAnalysis(gapNanos);
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public void accept(RecordedEvent e) {
        String type = e.getEventType().getName();
        switch (type) {
            case "jdk.GCPhasePause" -> gcPauses.add(new Pause(Events.interval(e), PauseKind.GC,
                    Events.stringOr(e, "name", "GC") + " (gcId " + Events.longOr(e, "gcId", -1) + ")"));
            // Begin and end may arrive in any order (delivery is file order); joined in finish().
            case "jdk.SafepointBegin" -> safepointBegins.put(Events.longOr(e, "safepointId", -1), Events.interval(e));
            case "jdk.SafepointEnd" -> safepointEnds.put(Events.longOr(e, "safepointId", -1), Events.endNanos(e));
            default -> acceptThreadEvent(e, type);
        }
    }

    private void acceptThreadEvent(RecordedEvent e, String type) {
        ThreadRef thread = interner.thread(e);
        if (thread == null) {
            return;
        }
        if ("jdk.JavaMonitorEnter".equals(type)) {
            monitorWaits.computeIfAbsent(thread, k -> new ArrayList<>()).add(new Block(Events.interval(e),
                    BlockKind.MONITOR, lockName(e, "monitorClass"), Stack.EMPTY,
                    Events.thread(e, "previousOwner", interner)));
        }
        if (!threadFilter.test(thread.name())) {
            return;
        }
        switch (type) {
            case "jdk.ExecutionSample", "jdk.NativeMethodSample" -> {
                var stack = Events.stack(e, interner);
                boolean inNative = "jdk.NativeMethodSample".equals(type);
                samples.computeIfAbsent(thread, k -> new ArrayList<>())
                        .add(new Sample(Events.startNanos(e), stack, idle.isIdle(stack), inNative));
            }
            case "jdk.JavaMonitorEnter" -> block(thread, e, BlockKind.MONITOR,
                    lockName(e, "monitorClass"), Events.thread(e, "previousOwner", interner));
            case "jdk.ThreadPark" -> block(thread, e, BlockKind.PARK, parkName(e), null);
            case "jdk.JavaMonitorWait" -> block(thread, e, BlockKind.OBJECT_WAIT,
                    "on " + lockName(e, "monitorClass"), null);
            case "jdk.ThreadSleep" -> block(thread, e, BlockKind.SLEEP, "", null);
            case "jdk.SocketRead" -> block(thread, e, BlockKind.SOCKET_READ, "from " + peer(e)
                    + " (" + Events.longOr(e, "bytesRead", 0) + " bytes)", null);
            case "jdk.SocketWrite" -> block(thread, e, BlockKind.SOCKET_WRITE, "to " + peer(e)
                    + " (" + Events.longOr(e, "bytesWritten", 0) + " bytes)", null);
            case "jdk.FileRead" -> block(thread, e, BlockKind.FILE_READ, Events.stringOr(e, "path", "?"), null);
            case "jdk.FileWrite" -> block(thread, e, BlockKind.FILE_WRITE, Events.stringOr(e, "path", "?"), null);
            case "jdk.FileForce" -> block(thread, e, BlockKind.FILE_FORCE, Events.stringOr(e, "path", "?"), null);
            default -> {
            }
        }
    }

    private void block(ThreadRef thread, RecordedEvent e, BlockKind kind, String detail, ThreadRef owner) {
        blocks.computeIfAbsent(thread, k -> new ArrayList<>())
                .add(new Block(Events.interval(e), kind, detail, Events.stack(e, interner), owner));
    }

    private String lockName(RecordedEvent e, String field) {
        String cls = Events.className(e, field, interner);
        long address = Events.longOr(e, "address", 0);
        return (cls == null ? "?" : ClassNames.pretty(cls)) + "@" + Long.toHexString(address);
    }

    private String parkName(RecordedEvent e) {
        String cls = Events.className(e, "parkedClass", interner);
        return cls == null ? "(no blocker object)" : "on " + lockName(e, "parkedClass");
    }

    private static String peer(RecordedEvent e) {
        String host = Events.stringOr(e, "host", "");
        String address = Events.stringOr(e, "address", "?");
        long port = Events.longOr(e, "port", 0);
        String where = host.isEmpty() ? address : host;
        return where + ":" + port;
    }

    @Override
    public void finish(RecordingInfo info) {
        List<ThreadTimeline> timelines = new ArrayList<>();
        for (ThreadRef thread : samples.keySet()) {
            blocks.putIfAbsent(thread, new ArrayList<>());
        }
        for (List<Block> waits : monitorWaits.values()) {
            waits.sort(Comparator.comparing(Block::interval));
        }
        for (Map.Entry<ThreadRef, List<Block>> e : blocks.entrySet()) {
            List<Sample> s = new ArrayList<>(samples.getOrDefault(e.getKey(), List.of()));
            s.sort(Comparator.comparingLong(Sample::time));
            List<Block> b = new ArrayList<>(e.getValue().size());
            for (Block block : e.getValue()) {
                b.add(block.kind() == BlockKind.MONITOR ? resolveHolder(e.getKey(), block) : block);
            }
            b.sort(Comparator.comparing(Block::interval));
            timelines.add(new ThreadTimeline(e.getKey(), s, b));
        }
        List<Pause> safepoints = new ArrayList<>(safepointBegins.size());
        safepointBegins.forEach((id, begin) -> {
            Long end = safepointEnds.get(id);
            Interval whole = end == null ? begin : new Interval(begin.start(), Math.max(begin.start(), end));
            safepoints.add(new Pause(whole, PauseKind.SAFEPOINT, "safepoint " + id));
        });
        List<Pause> pauses = new ArrayList<>(gcPauses);
        // A GC pause is also a safepoint; report it once, as the GC.
        for (Pause sp : safepoints) {
            boolean isGc = false;
            for (Pause gc : gcPauses) {
                if (gc.interval().overlap(sp.interval()) >= StallAnalysis.COVER * sp.length()) {
                    isGc = true;
                    break;
                }
            }
            if (!isGc) {
                pauses.add(sp);
            }
        }
        report = analysis.analyse(info, timelines, pauses);
    }

    /**
     * JFR's {@code previousOwner} is the thread that released the monitor to the waiter,
     * which under contention is often another waiter that held it for microseconds. Walks
     * back: while the recorded owner was itself waiting for the same lock during this wait,
     * take its owner instead, and remember the intermediaries.
     */
    private Block resolveHolder(ThreadRef waiter, Block block) {
        ThreadRef owner = block.owner();
        if (owner == null) {
            return block;
        }
        List<ThreadRef> via = new ArrayList<>();
        java.util.Set<ThreadRef> seen = new java.util.HashSet<>();
        seen.add(waiter);
        seen.add(owner);
        while (true) {
            Block ownersWait = null;
            for (Block w : monitorWaits.getOrDefault(owner, List.of())) {
                if (w.interval().start() >= block.interval().end()) {
                    break;
                }
                if (w.detail().equals(block.detail()) && w.interval().overlaps(block.interval())
                        && (ownersWait == null || w.length() > ownersWait.length())) {
                    ownersWait = w;
                }
            }
            // Stop at an unknown owner, and at a cycle (a thread cannot hold what it waits for).
            if (ownersWait == null || ownersWait.owner() == null || !seen.add(ownersWait.owner())) {
                break;
            }
            via.add(owner);
            owner = ownersWait.owner();
        }
        if (via.isEmpty()) {
            return block;
        }
        return new Block(block.interval(), block.kind(), block.detail(), block.stack(), owner, via);
    }

    public StallReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }
}
