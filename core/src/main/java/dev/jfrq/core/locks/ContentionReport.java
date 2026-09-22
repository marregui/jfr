// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.locks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import dev.jfrq.core.coll.ObjHashSet;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Sorted;

/**
 * Lock contention in one recording: per-lock and per-thread totals, the longest
 * individual waits in time order, and <em>convoys</em>: chains where the thread that held
 * the lock was itself blocked on another lock at the time.
 */
public final class ContentionReport {

    /** Totals for one lock. */
    public record LockStats(Wait.LockKey lock, long totalNanos, int count, long maxNanos,
                            Set<ThreadRef> waiters, Set<ThreadRef> owners) {
    }

    /** Totals for one waiting thread. */
    public record ThreadStats(ThreadRef thread, long totalNanos, int count, long maxNanos) {
    }

    /**
     * A wait whose owner was itself waiting: {@code links.get(0)} is the outermost wait,
     * {@code links.get(1)} is the owner's own overlapping wait, and so on.
     */
    public record Convoy(List<Wait> links) {
        public Wait head() {
            return links.getFirst();
        }

        public int depth() {
            return links.size();
        }
    }

    private static final Comparator<Wait> BY_INTERVAL = Comparator.comparing(Wait::interval);
    private static final long PASSES = 1;
    private static final long FAILS = 0;

    private final RecordingInfo info;
    /** The reported waits, after the filters, in start order. */
    private final List<Wait> waits;
    /** How many waits the recording holds before the filters. */
    private final int unfilteredCount;
    /** How many reported waits were cut down to the recording's span. */
    private final int clippedCount;
    /** Every thread's waits, filters or not, so that a convoy can be followed into any thread. */
    private final ObjObjHashMap<ThreadRef, Waits> byWaiter = new ObjObjHashMap<>(256);

    /** One thread's waits in start order, and the longest of them: bounds the window a lookup by start scans. */
    private static final class Waits {
        final ObjList<Wait> list = new ObjList<>(8);
        long longest;

        void add(final Wait w) {
            list.add(w);
            longest = Math.max(longest, w.duration());
        }
    }

    public ContentionReport(final RecordingInfo info, final List<Wait> waits) {
        this(info, waits, 0, _ -> true);
    }

    /**
     * @param waits        every wait in the recording; holders are resolved across all of them
     * @param minNanos     waits shorter than this are left out of the report
     * @param waiterFilter only waits by threads whose name passes are reported
     */
    public ContentionReport(final RecordingInfo info, final List<Wait> waits, final long minNanos, final Predicate<String> waiterFilter) {
        this.info = info;
        final ObjList<Wait> sorted = new ObjList<>(waits.size());
        for (int i = 0, n = waits.size(); i < n; i++) {
            sorted.add(waits.get(i));
        }
        sorted.sort(BY_INTERVAL);
        final ObjObjHashMap<ThreadRef, Waits> raw = new ObjObjHashMap<>(256);
        for (int i = 0, n = sorted.size(); i < n; i++) {
            final Wait w = sorted.getQuick(i);
            waitsOf(raw, w.waiter()).add(w);
        }
        // The filter runs once per thread, not once per wait (G-2.2).
        final ObjLongHashMap<ThreadRef> filterVerdict = new ObjLongHashMap<>(256);
        final ObjList<ThreadRef> via = new ObjList<>();
        final ObjHashSet<ThreadRef> seen = new ObjHashSet<>();
        final ObjList<Wait> reported = new ObjList<>(sorted.size());
        final Interval window = info.span();
        int clipped = 0;
        for (int i = 0, n = sorted.size(); i < n; i++) {
            final Wait w = sorted.getQuick(i);
            final Wait resolved = resolveHolder(w, raw, via, seen);
            // Holders are resolved on the true intervals; everything counted is the part
            // inside the window, so the totals and the shares cannot exceed it. Clipping
            // keeps the order: both ends move by a monotone function of themselves.
            final Wait inside = clip(resolved, window);
            // Every thread's waits stay reachable for convoy following; the report lists the filtered ones.
            waitsOf(byWaiter, w.waiter()).add(inside);
            if (inside.duration() > 0 && inside.duration() >= minNanos
                    && passes(filterVerdict, waiterFilter, w.waiter())) {
                reported.add(inside);
                if (inside != resolved) {
                    clipped++;
                }
            }
        }
        this.waits = reported.toList();
        this.unfilteredCount = sorted.size();
        this.clippedCount = clipped;
    }

    /** A wait counted only for the part inside the window; the same object when it is wholly inside. */
    private static Wait clip(final Wait w, final Interval window) {
        final Interval inside = w.interval().clampTo(window);
        return inside == w.interval() ? w : new Wait(inside, w.waiter(), w.lock(), w.owner(), w.stack(), w.via());
    }

    private static Waits waitsOf(final ObjObjHashMap<ThreadRef, Waits> map, final ThreadRef thread) {
        final int index = map.keyIndex(thread);
        return index < 0 ? map.valueAtQuick(index) : map.putAt(index, thread, new Waits());
    }

    private static boolean passes(final ObjLongHashMap<ThreadRef> verdicts, final Predicate<String> filter, final ThreadRef thread) {
        final int index = verdicts.keyIndex(thread);
        if (index < 0) {
            return verdicts.valueAtQuick(index) == PASSES;
        }
        final boolean passes = filter.test(thread.name());
        verdicts.putAt(index, thread, passes ? PASSES : FAILS);
        return passes;
    }

    /** Whether the filters left anything out. */
    public boolean filtered() {
        return unfilteredCount != waits.size();
    }

    public int unfilteredCount() {
        return unfilteredCount;
    }

    /**
     * How many reported waits began before the recording's span or were still running at
     * its end, and are therefore counted only for the part inside it. A {@code jfrq-live}
     * delta window slices waits at both ends by construction, so this is normal rather
     * than a defect in the file; it is reported because it is why a wait's total here can
     * be smaller than the same wait elsewhere.
     */
    public int clippedCount() {
        return clippedCount;
    }

    /**
     * JFR's {@code previousOwner} is the thread that released the monitor to the waiter,
     * which under contention is often another waiter that held it for microseconds. Walks
     * back: while the recorded owner was itself waiting for the same lock during this wait,
     * take its owner instead and remember the intermediary.
     */
    private static Wait resolveHolder(final Wait wait, final ObjObjHashMap<ThreadRef, Waits> byWaiter, final ObjList<ThreadRef> via,
                                      final ObjHashSet<ThreadRef> seen) {
        ThreadRef owner = wait.owner();
        if (owner == null) {
            return wait;
        }
        via.clear();
        seen.clear();
        seen.add(wait.waiter());
        seen.add(owner);
        while (true) {
            Wait ownersWait = null;
            final Waits theirs = byWaiter.get(owner);
            if (theirs != null) {
                final ObjList<Wait> list = theirs.list;
                final int from = Sorted.lowerBound(list, Wait::start, wait.start() - theirs.longest);
                for (int i = from, n = list.size(); i < n; i++) {
                    final Wait w = list.getQuick(i);
                    if (w.start() >= wait.end()) {
                        break;
                    }
                    if (w.lock().equals(wait.lock()) && w.interval().overlaps(wait.interval())
                            && (ownersWait == null || w.duration() > ownersWait.duration())) {
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
            return wait;
        }
        return new Wait(wait.interval(), wait.waiter(), wait.lock(), owner, wait.stack(), via.toList());
    }

    public RecordingInfo info() {
        return info;
    }

    /** The reported waits (those passing the filters), in start order. */
    public List<Wait> waits() {
        return waits;
    }

    public boolean isEmpty() {
        return waits.isEmpty();
    }

    public long totalNanos() {
        long total = 0;
        for (final Wait w : waits) {
            total += w.duration();
        }
        return total;
    }

    /** Locks ranked by total time threads spent waiting for them. */
    public List<LockStats> locks(final int top) {
        final Map<Wait.LockKey, long[]> totals = new LinkedHashMap<>();
        final Map<Wait.LockKey, Set<ThreadRef>> waiters = new HashMap<>();
        final Map<Wait.LockKey, Set<ThreadRef>> owners = new HashMap<>();
        for (final Wait w : waits) {
            final long[] t = totals.computeIfAbsent(w.lock(), _ -> new long[3]);
            t[0] += w.duration();
            t[1]++;
            t[2] = Math.max(t[2], w.duration());
            waiters.computeIfAbsent(w.lock(), _ -> new LinkedHashSet<>()).add(w.waiter());
            if (w.owner() != null) {
                owners.computeIfAbsent(w.lock(), _ -> new LinkedHashSet<>()).add(w.owner());
            }
        }
        final List<LockStats> stats = new ArrayList<>();
        totals.forEach((lock, t) -> stats.add(new LockStats(lock, t[0], (int) t[1], t[2],
                waiters.getOrDefault(lock, Set.of()), owners.getOrDefault(lock, Set.of()))));
        stats.sort(Comparator.comparingLong(LockStats::totalNanos).reversed());
        return limit(stats, top);
    }

    /** Threads ranked by total time blocked. */
    public List<ThreadStats> waiters(final int top) {
        final Map<ThreadRef, long[]> totals = new LinkedHashMap<>();
        for (final Wait w : waits) {
            final long[] t = totals.computeIfAbsent(w.waiter(), _ -> new long[3]);
            t[0] += w.duration();
            t[1]++;
            t[2] = Math.max(t[2], w.duration());
        }
        final List<ThreadStats> stats = new ArrayList<>();
        totals.forEach((thread, t) -> stats.add(new ThreadStats(thread, t[0], (int) t[1], t[2])));
        stats.sort(Comparator.comparingLong(ThreadStats::totalNanos).reversed());
        return limit(stats, top);
    }

    /** The longest individual waits. */
    public List<Wait> longest(final int top) {
        final List<Wait> sorted = new ArrayList<>(waits);
        sorted.sort(Comparator.comparingLong(Wait::duration).reversed());
        return limit(sorted, top);
    }

    /**
     * Finds convoys: for each monitor wait whose owner is known, follows the owner into
     * its own wait for a <em>different</em> lock overlapping the same time, up to
     * {@code maxDepth} links (co-waiters for the same lock are already folded into
     * {@link Wait#via()}). Only chains of two or more links are returned, longest head
     * wait first.
     */
    public List<Convoy> convoys(final int maxDepth, final int top) {
        final List<Convoy> found = new ArrayList<>();
        // Heads in descending duration: once `top` convoys exist, no later head can outrank them.
        for (final Wait head : longest(waits.size())) {
            if (found.size() >= top) {
                break;
            }
            final List<Wait> links = new ArrayList<>();
            links.add(head);
            final Set<ThreadRef> seen = new LinkedHashSet<>();
            seen.add(head.waiter());
            Wait current = head;
            while (links.size() < maxDepth && current.owner() != null && seen.add(current.owner())) {
                final Wait next = longestOverlapping(current.owner(), current.interval(), current.lock());
                if (next == null) {
                    break;
                }
                links.add(next);
                current = next;
            }
            if (links.size() >= 2) {
                found.add(new Convoy(List.copyOf(links)));
            }
        }
        return List.copyOf(found);
    }

    private Wait longestOverlapping(final ThreadRef thread, final Interval during, final Wait.LockKey notThisLock) {
        Wait best = null;
        long bestOverlap = 0;
        final Waits theirs = byWaiter.get(thread);
        if (theirs == null) {
            return null;
        }
        final ObjList<Wait> list = theirs.list;
        final int from = Sorted.lowerBound(list, Wait::start, during.start() - theirs.longest);
        for (int i = from, n = list.size(); i < n; i++) {
            final Wait w = list.getQuick(i);
            if (w.start() >= during.end()) {
                break;
            }
            if (w.lock().equals(notThisLock)) {
                continue;
            }
            final long overlap = w.interval().overlap(during);
            if (overlap > bestOverlap) {
                best = w;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    private static <T> List<T> limit(final List<T> list, final int top) {
        return list.size() > top ? List.copyOf(list.subList(0, top)) : List.copyOf(list);
    }
}
