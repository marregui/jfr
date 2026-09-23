// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.LongObjHashMap;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjHashSet;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.EventKinds;
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
import dev.jfrq.core.util.Sorted;
import jdk.jfr.consumer.RecordedEvent;

/**
 * Builds {@link ThreadTimeline}s for the threads matching a filter, plus the JVM-wide
 * pauses, from one pass over a recording; then hands them to {@link StallAnalysis}.
 *
 * <p>Pauses come from three event families. {@code jdk.GCPhasePause} is the
 * stop-the-world part of a collection. Every other safepoint is assembled from
 * {@code jdk.SafepointBegin} (the time to bring the threads to a halt) and
 * {@code jdk.ExecuteVMOperation} (the operation that ran while they were halted, which
 * names it: a thread dump, a deoptimisation, a class redefinition), joined on the
 * safepoint id. {@code jdk.SafepointEnd} is used when present, but the JDK's own
 * {@code default} and {@code profile} settings disable it, which is why the VM operation
 * event is the one that matters.
 *
 * <p>Per event the collector does one probe per thread (the filter verdict is cached
 * with the thread), reuses the detail strings for locks and peers, and keeps GC pauses
 * and safepoints flat; the {@link Sample} and {@link Block} records the analysis reads
 * are the only per-event allocations left.
 */
public final class StallCollector implements JfrReader.Sink {

    private static final Set<String> TYPES = EventKinds.names(
            EventKinds.EXECUTION_SAMPLE, EventKinds.NATIVE_METHOD_SAMPLE,
            EventKinds.JAVA_MONITOR_ENTER, EventKinds.THREAD_PARK, EventKinds.JAVA_MONITOR_WAIT,
            EventKinds.THREAD_SLEEP, EventKinds.SOCKET_READ, EventKinds.SOCKET_WRITE, EventKinds.FILE_READ,
            EventKinds.FILE_WRITE, EventKinds.FILE_FORCE, EventKinds.GC_PHASE_PAUSE, EventKinds.SAFEPOINT_BEGIN,
            EventKinds.SAFEPOINT_END, EventKinds.EXECUTE_VM_OPERATION);

    private static final Comparator<Sample> BY_TIME = Comparator.comparingLong(Sample::time);
    private static final Comparator<Block> BY_INTERVAL = Comparator.comparing(Block::interval);
    private static final Comparator<Pause> PAUSE_BY_INTERVAL = Comparator.comparing(Pause::interval);

    /** GC pauses are kept flat until the end (G-1.8): four longs per pause. */
    private static final int GC_SLOT = 4;
    private static final int GC_START = 0;
    private static final int GC_END = 1;
    private static final int GC_ID = 2;

    private final Predicate<String> threadFilter;
    private final IdleMatcher idle;
    private final StallAnalysis analysis;
    /** Every thread seen, watched or not: the monitor waits of all of them resolve lock holders. */
    private final ObjObjHashMap<ThreadRef, ThreadEvents> threads = new ObjObjHashMap<>(256);
    private final LongList gcPauses = new LongList(64 * GC_SLOT);
    private final ObjList<String> gcNames = new ObjList<>(64);
    private final LongObjHashMap<Safepoint> safepoints = new LongObjHashMap<>(256, Long.MIN_VALUE);
    private final LockNames lockNames = new LockNames();
    private final PeerNames peerNames = new PeerNames();
    /** Scratch for the holder walk-back, cleared per block (G-3.3). */
    private final ObjList<ThreadRef> via = new ObjList<>();
    private final ObjHashSet<ThreadRef> seen = new ObjHashSet<>();
    private Interner interner = new Interner();
    private StallReport report;

    public StallCollector(final Predicate<String> threadFilter, final IdleMatcher idle, final long gapNanos) {
        this(threadFilter, idle, IdleMatcher.forWorkWaits(), gapNanos);
    }

    /**
     * @param idle      what an idle <em>sample</em> looks like: the wait is the top frame
     * @param workWaits what a park that is only "no work to do" looks like: the frame that
     *                  decides sits below the park, so the two lists are not the same list
     */
    public StallCollector(final Predicate<String> threadFilter, final IdleMatcher idle, final IdleMatcher workWaits,
                          final long gapNanos) {
        this.threadFilter = threadFilter;
        this.idle = idle;
        this.analysis = new StallAnalysis(gapNanos, workWaits);
    }

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
            case EventKinds.GC_PHASE_PAUSE -> {
                final Interval interval = Events.interval(e);
                gcPauses.add(interval.start());
                gcPauses.add(interval.end());
                gcPauses.add(Events.longOr(e, "gcId", -1));
                gcPauses.add(0);
                gcNames.add(Events.stringOr(e, "name", "GC"));
            }
            // Begin, end and operation may arrive in any order (delivery is file order); joined in finish().
            case EventKinds.SAFEPOINT_BEGIN -> safepoint(e).begin(Events.interval(e));
            case EventKinds.SAFEPOINT_END -> safepoint(e).end(Events.endNanos(e));
            case EventKinds.EXECUTE_VM_OPERATION -> {
                if (e.hasField("safepoint") && e.getBoolean("safepoint")) {
                    safepoint(e).operation(Events.interval(e), Events.stringOr(e, "operation", "?"));
                }
            }
            default -> acceptThreadEvent(e, kind);
        }
    }

    private Safepoint safepoint(final RecordedEvent e) {
        final long id = Events.longOr(e, "safepointId", -1);
        final int index = safepoints.keyIndex(id);
        return index < 0 ? safepoints.valueAtQuick(index) : safepoints.putAt(index, id, new Safepoint(id));
    }

    private void acceptThreadEvent(final RecordedEvent e, final int kind) {
        final ThreadRef thread = interner.thread(e);
        if (thread == null) {
            return;
        }
        final int index = threads.keyIndex(thread);
        final ThreadEvents t = index < 0 ? threads.valueAtQuick(index)
                : threads.putAt(index, thread, new ThreadEvents(threadFilter.test(thread.name())));
        if (kind == EventKinds.JAVA_MONITOR_ENTER) {
            // Kept for every thread; a watched thread's own wait carries the stack and is the same object.
            final Block wait = new Block(Events.interval(e), BlockKind.MONITOR, lockName(e, "monitorClass"),
                    t.watched ? Events.stack(e, interner) : Stack.EMPTY, Events.thread(e, "previousOwner", interner));
            t.monitorWaits.add(wait);
            if (t.watched) {
                t.blocks.add(wait);
            }
            return;
        }
        if (!t.watched) {
            return;
        }
        switch (kind) {
            case EventKinds.EXECUTION_SAMPLE, EventKinds.NATIVE_METHOD_SAMPLE -> {
                final Stack stack = Events.stack(e, interner);
                final boolean inNative = kind == EventKinds.NATIVE_METHOD_SAMPLE;
                t.samples.add(new Sample(Events.startNanos(e), stack, idle.isIdle(stack), inNative));
            }
            case EventKinds.THREAD_PARK -> block(t, e, BlockKind.PARK, parkName(e), 0);
            case EventKinds.JAVA_MONITOR_WAIT ->
                    block(t, e, BlockKind.OBJECT_WAIT, lockNames.on(lockName(e, "monitorClass")), 0);
            case EventKinds.THREAD_SLEEP -> block(t, e, BlockKind.SLEEP, "", 0);
            case EventKinds.SOCKET_READ -> block(t, e, BlockKind.SOCKET_READ, peerNames.from(peer(e)),
                    Events.longOr(e, "bytesRead", 0));
            case EventKinds.SOCKET_WRITE -> block(t, e, BlockKind.SOCKET_WRITE, peerNames.to(peer(e)),
                    Events.longOr(e, "bytesWritten", 0));
            case EventKinds.FILE_READ -> block(t, e, BlockKind.FILE_READ, Events.stringOr(e, "path", "?"),
                    Events.longOr(e, "bytesRead", 0));
            case EventKinds.FILE_WRITE -> block(t, e, BlockKind.FILE_WRITE, Events.stringOr(e, "path", "?"),
                    Events.longOr(e, "bytesWritten", 0));
            case EventKinds.FILE_FORCE -> block(t, e, BlockKind.FILE_FORCE, Events.stringOr(e, "path", "?"), 0);
            default -> {
            }
        }
    }

    private void block(final ThreadEvents t, final RecordedEvent e, final BlockKind kind, final String detail, final long bytes) {
        t.blocks.add(new Block(Events.interval(e), kind, detail, Events.stack(e, interner), bytes));
    }

    /** {@code dev.app.Registry@1f2e}: one string per (class, address), reused across events. */
    private String lockName(final RecordedEvent e, final String field) {
        return lockNames.name(Events.className(e, field, interner), Events.longOr(e, "address", 0));
    }

    private String parkName(final RecordedEvent e) {
        final String cls = Events.className(e, "parkedClass", interner);
        return cls == null ? "(no blocker object)" : lockNames.on(lockName(e, "parkedClass"));
    }

    /** {@code host:port}, or {@code address:port} when the host is unknown; one string per peer. */
    private String peer(final RecordedEvent e) {
        final String host = Events.stringOr(e, "host", "");
        final String where = host.isEmpty() ? Events.stringOr(e, "address", "?") : host;
        return peerNames.peer(where, Events.longOr(e, "port", 0));
    }

    @Override
    public void finish(final RecordingInfo info) {
        final ObjList<ThreadTimeline> timelines = new ObjList<>();
        for (int s = 0, n = threads.slots(); s < n; s++) {
            if (threads.hasKeyAtSlot(s)) {
                final ThreadEvents t = threads.valueAtSlot(s);
                t.monitorWaits.sort(BY_INTERVAL);
                t.longestMonitorWait = Sorted.maxLength(t.monitorWaits, Block::length);
            }
        }
        for (int s = 0, n = threads.slots(); s < n; s++) {
            if (!threads.hasKeyAtSlot(s)) {
                continue;
            }
            final ThreadEvents t = threads.valueAtSlot(s);
            if (!t.watched || (t.samples.isEmpty() && t.blocks.isEmpty())) {
                continue;
            }
            final ThreadRef thread = threads.keyAtSlot(s);
            t.samples.sort(BY_TIME);
            final ObjList<Block> blocks = new ObjList<>(t.blocks.size());
            for (int i = 0, m = t.blocks.size(); i < m; i++) {
                final Block block = t.blocks.getQuick(i);
                blocks.add(block.kind() == BlockKind.MONITOR ? resolveHolder(thread, block) : block);
            }
            blocks.sort(BY_INTERVAL);
            timelines.add(new ThreadTimeline(thread, t.samples.toList(), blocks.toList()));
        }
        report = analysis.analyse(info, timelines.toList(), pauses());
    }

    /**
     * GC pauses plus every other safepoint, each reported once. A safepoint spans from the
     * begin event's start to the end of its VM operation (or its end event, when recorded);
     * an operation whose begin event fell under the recording's threshold stands alone.
     * A safepoint that overlaps a GC pause is the GC's own and is dropped as a duplicate.
     */
    private List<Pause> pauses() {
        final ObjList<Pause> gcs = new ObjList<>(gcNames.size());
        for (int i = 0, n = gcNames.size(); i < n; i++) {
            final int slot = i * GC_SLOT;
            gcs.add(new Pause(new Interval(gcPauses.getQuick(slot + GC_START), gcPauses.getQuick(slot + GC_END)),
                    PauseKind.GC, gcNames.getQuick(i) + " (gcId " + gcPauses.getQuick(slot + GC_ID) + ")"));
        }
        gcs.sort(PAUSE_BY_INTERVAL);
        final long longestGc = Sorted.maxLength(gcs, Pause::length);

        final ObjList<Pause> pauses = new ObjList<>(gcs.size() + safepoints.size());
        pauses.addAll(gcs);
        for (int s = 0, n = safepoints.slots(); s < n; s++) {
            if (!safepoints.hasKeyAtSlot(s)) {
                continue;
            }
            final Pause sp = safepoints.valueAtSlot(s).pause();
            if (sp == null) {
                continue;
            }
            boolean isGc = false;
            final int from = Sorted.lowerBound(gcs, Pause::start, sp.start() - longestGc);
            for (int i = from, m = gcs.size(); i < m; i++) {
                final Pause gc = gcs.getQuick(i);
                if (gc.start() >= sp.interval().end()) {
                    break;
                }
                if (gc.interval().overlap(sp.interval()) >= StallAnalysis.COVER * sp.length()) {
                    isGc = true;
                    break;
                }
            }
            if (!isGc) {
                pauses.add(sp);
            }
        }
        return pauses.toList();
    }

    /**
     * JFR's {@code previousOwner} is the thread that released the monitor to the waiter,
     * which under contention is often another waiter that held it for microseconds. Walks
     * back: while the recorded owner was itself waiting for the same lock during this wait,
     * take its owner instead, and remember the intermediaries.
     */
    private Block resolveHolder(final ThreadRef waiter, final Block block) {
        ThreadRef owner = block.owner();
        if (owner == null) {
            return block;
        }
        via.clear();
        seen.clear();
        seen.add(waiter);
        seen.add(owner);
        while (true) {
            Block ownersWait = null;
            final ThreadEvents theirs = threads.get(owner);
            if (theirs != null) {
                final ObjList<Block> waits = theirs.monitorWaits;
                final int from = Sorted.lowerBound(waits, Block::start, block.start() - theirs.longestMonitorWait);
                for (int i = from, n = waits.size(); i < n; i++) {
                    final Block w = waits.getQuick(i);
                    if (w.start() >= block.interval().end()) {
                        break;
                    }
                    if (w.detail().equals(block.detail()) && w.interval().overlaps(block.interval())
                            && (ownersWait == null || w.length() > ownersWait.length())) {
                        ownersWait = w;
                    }
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
        return new Block(block.interval(), block.kind(), block.detail(), block.stack(), owner, via.toList(),
                block.bytes());
    }

    public StallReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }

    /** What one thread contributed. Lists are small at first; a busy watched thread grows them. */
    private static final class ThreadEvents {
        /** The filter's verdict for the thread's name, decided once. */
        final boolean watched;
        final ObjList<Sample> samples = new ObjList<>(16);
        final ObjList<Block> blocks = new ObjList<>(8);
        /** Monitor waits, watched or not, to trace who really held a lock. */
        final ObjList<Block> monitorWaits = new ObjList<>(4);
        /** The longest monitor wait: bounds the window a holder lookup scans. */
        long longestMonitorWait;

        ThreadEvents(final boolean watched) {
            this.watched = watched;
        }
    }

    /** The three events of one safepoint, assembled in whatever order the file delivers them. */
    private static final class Safepoint {
        final long id;
        long beginStart = Nulls.LONG_NULL;
        long beginEnd;
        long recordedEnd = Nulls.LONG_NULL;
        long operationStart = Nulls.LONG_NULL;
        long operationEnd;
        String operation;

        Safepoint(final long id) {
            this.id = id;
        }

        void begin(final Interval interval) {
            beginStart = interval.start();
            beginEnd = interval.end();
        }

        void end(final long endNanos) {
            recordedEnd = endNanos;
        }

        void operation(final Interval interval, final String name) {
            operationStart = interval.start();
            operationEnd = interval.end();
            operation = name;
        }

        /** The pause, or {@code null} when only an end event was seen. */
        Pause pause() {
            if (beginStart != Nulls.LONG_NULL) {
                long end = beginEnd;
                if (recordedEnd != Nulls.LONG_NULL) {
                    end = Math.max(end, recordedEnd);
                }
                if (operationStart != Nulls.LONG_NULL) {
                    end = Math.max(end, operationEnd);
                }
                final String what = operation == null ? "safepoint " + id : "VM operation " + operation;
                return new Pause(new Interval(beginStart, Math.max(beginStart, end)), PauseKind.SAFEPOINT, what);
            }
            if (operationStart != Nulls.LONG_NULL) {
                return new Pause(new Interval(operationStart, operationEnd), PauseKind.SAFEPOINT,
                        "VM operation " + operation);
            }
            return null;
        }
    }

    /**
     * {@code Class@address} strings and their {@code on } prefix, one per lock: a
     * recording holds a few locks and hundreds of thousands of waits (G-2.3). Keyed by
     * address, with the class checked; an address reused by another class (the object
     * moved) replaces the entry, and the earlier string stays alive through its blocks.
     */
    private static final class LockNames {
        private final LongObjHashMap<Entry> byAddress = new LongObjHashMap<>(64, Long.MIN_VALUE);
        private final ObjObjHashMap<String, String> prefixed = new ObjObjHashMap<>(64);

        private static final class Entry {
            final String cls;
            final String name;

            Entry(final String cls, final String name) {
                this.cls = cls;
                this.name = name;
            }
        }

        String name(final String cls, final long address) {
            final int index = byAddress.keyIndex(address);
            if (index < 0) {
                final Entry e = byAddress.valueAtQuick(index);
                if (e.cls == cls || (cls != null && cls.equals(e.cls))) {
                    return e.name;
                }
            }
            final String name = (cls == null ? "?" : ClassNames.pretty(cls)) + "@" + Long.toHexString(address);
            final Entry created = new Entry(cls, name);
            if (index < 0) {
                byAddress.put(address, created);
            } else {
                byAddress.putAt(index, address, created);
            }
            return created.name;
        }

        /** {@code "on " + name}, one per name. */
        String on(final String name) {
            final int index = prefixed.keyIndex(name);
            return index < 0 ? prefixed.valueAtQuick(index) : prefixed.putAt(index, name, "on " + name);
        }
    }

    /** {@code host:port} strings and their {@code from }/{@code to } prefixes, one per peer. */
    private static final class PeerNames {
        private final ObjObjHashMap<String, LongObjHashMap<String>> byHost = new ObjObjHashMap<>(16);
        private final ObjObjHashMap<String, String> from = new ObjObjHashMap<>(16);
        private final ObjObjHashMap<String, String> to = new ObjObjHashMap<>(16);

        String peer(final String where, final long port) {
            final int index = byHost.keyIndex(where);
            final LongObjHashMap<String> ports = index < 0 ? byHost.valueAtQuick(index)
                    : byHost.putAt(index, where, new LongObjHashMap<>(4, Long.MIN_VALUE));
            final int p = ports.keyIndex(port);
            return p < 0 ? ports.valueAtQuick(p) : ports.putAt(p, port, where + ":" + port);
        }

        String from(final String peer) {
            final int index = from.keyIndex(peer);
            return index < 0 ? from.valueAtQuick(index) : from.putAt(index, peer, "from " + peer);
        }

        String to(final String peer) {
            final int index = to.keyIndex(peer);
            return index < 0 ? to.valueAtQuick(index) : to.putAt(index, peer, "to " + peer);
        }
    }
}
