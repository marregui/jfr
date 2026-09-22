// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.locks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.stalls.Perch;
import dev.jfrq.core.util.Sorted;

/**
 * Lock contention in one recording: per-lock and per-thread totals, the longest
 * individual waits in time order, and <em>convoys</em>: chains where the thread that held
 * the lock was itself blocked on another lock at the time.
 */
public final class ContentionReport {

    /**
     * Totals for one lock.
     *
     * @param longest the longest single wait for it, whose stack stands for the row: ranking
     *                by duration means a hot lock of many short waits never reaches
     *                {@code LONGEST WAITS}, and a row without a stack is a name a reader
     *                cannot act on
     */
    public record LockStats(Wait.LockKey lock, long totalNanos, int count, long maxNanos,
                            Set<ThreadRef> waiters, Set<ThreadRef> owners, Wait longest) {
    }

    /** Totals for one waiting thread. */
    public record ThreadStats(ThreadRef thread, long totalNanos, int count, long maxNanos) {
    }

    /**
     * One lock site: the locks whose longest wait prints the same stack, and their summed
     * evidence. Fifteen queues of the same kind are one site with fifteen instances, not
     * fifteen rows a reader has to recognise as one and add up.
     *
     * @param longest    the longest wait at this site, whose stack stands for it
     * @param locks      the lock instances folded into the row, in rank order
     * @param totalNanos the wait over every one of them
     * @param count      how many waits that was
     * @param maxNanos   the longest single wait
     */
    public record SiteStats(Wait longest, List<Wait.LockKey> locks, long totalNanos, int count, long maxNanos,
                            Set<ThreadRef> waiters, Set<ThreadRef> owners) {

        public Wait.Kind kind() {
            return longest.lock().kind();
        }
    }

    /**
     * The locks whose longest wait prints the same stack. A server with one mailbox per worker
     * has as many locks as workers and a single stack between them: printed once per lock it
     * was 66 lines of a 177-line report, and the locks are the information the repetition hid.
     *
     * @param locks   the locks this stack stands for, in the order they were ranked
     * @param longest the longest wait across them, whose stack is the one to print
     */
    public record StackGroup(List<Wait.LockKey> locks, Wait longest) {
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
    /** Reported parks that were workers waiting for their own queue, in start order. */
    private final List<Wait> workWaits;
    /** How many waits the recording holds before the filters. */
    private final int unfilteredCount;
    /** How many reported waits were cut down to the recording's span. */
    private final int clippedCount;
    /** How many locks were set aside by their shape rather than by a frame in the idle list. */
    private final int perchCount;
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

    public ContentionReport(final RecordingInfo info, final List<Wait> waits, final long minNanos, final Predicate<String> waiterFilter) {
        this(info, waits, minNanos, waiterFilter, IdleMatcher.forWorkWaits());
    }

    /**
     * @param waits        every wait in the recording; holders are resolved across all of them
     * @param minNanos     waits shorter than this are left out of the report
     * @param waiterFilter only waits by threads whose name passes are reported
     * @param workWaits    which parks are a worker waiting for its own queue rather than
     *                     contention; those are reported apart, because on a server they
     *                     outnumber and outrank every real lock
     */
    public ContentionReport(final RecordingInfo info, final List<Wait> waits, final long minNanos,
                            final Predicate<String> waiterFilter, final IdleMatcher workWaits) {
        this(info, waits, minNanos, waiterFilter, workWaits, _ -> true);
    }

    /**
     * @param lockFilter only waits for locks that pass are reported; a lock is named by its
     *                   class, its address, or both, and the filter runs after holder
     *                   resolution like the others
     */
    public ContentionReport(final RecordingInfo info, final List<Wait> waits, final long minNanos,
                            final Predicate<String> waiterFilter, final IdleMatcher workWaits,
                            final Predicate<Wait.LockKey> lockFilter) {
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
        final ObjList<Wait> idling = new ObjList<>();
        final Interval window = info.span();

        // First pass: resolve the holders and clip, and weigh each lock while every wait is
        // still in hand. Holders are resolved on the true intervals; everything counted is
        // the part inside the window, so the totals and the shares cannot exceed it. Clipping
        // keeps the order: both ends move by a monotone function of themselves.
        final ObjList<Wait> insides = new ObjList<>(sorted.size());
        final Map<Wait.LockKey, Perch.Shape> shapes = new LinkedHashMap<>();
        for (int i = 0, n = sorted.size(); i < n; i++) {
            final Wait w = sorted.getQuick(i);
            final Wait inside = clip(resolveHolder(w, raw, via, seen), window);
            insides.add(inside);
            if (inside.duration() > 0) {
                shapes.computeIfAbsent(inside.lock(), _ -> new Perch.Shape())
                        .add(inside.waiter(), inside.duration(), inside.stack(), inside.owner() != null);
            }
        }
        // The shape verdict is reached before any filter narrows the report, so that --min or
        // --thread cannot turn a perch into contention by hiding the waits that prove it is not.
        final Set<Wait.LockKey> perches = new HashSet<>();
        if (!workWaits.matchesNothing()) {
            final Set<Stack> perchStacks = new HashSet<>();
            shapes.forEach((lock, s) -> {
                if (lock.kind() == Wait.Kind.PARK && s.matches(window.length())) {
                    perches.add(lock);
                    perchStacks.add(s.stack());
                }
            });
            // What shape finds is a lock; what it identifies is the loop above it. One worker
            // out of thirteen that was busy for two thirds of the recording parks on its own
            // mailbox exactly like the other twelve, and a rule that let a threshold decide
            // between them would put that one lock, alone, at the top of the contention it is
            // not part of. The measurement names the frame the idle list was missing; the
            // frame then answers for every lock that waits there.
            shapes.forEach((lock, s) -> {
                if (lock.kind() == Wait.Kind.PARK && perchStacks.contains(s.stack())) {
                    perches.add(lock);
                }
            });
        }

        int clipped = 0;
        for (int i = 0, n = sorted.size(); i < n; i++) {
            final Wait w = sorted.getQuick(i);
            if (!lockFilter.test(w.lock())) {
                continue;
            }
            final Wait inside = insides.getQuick(i);
            final boolean idle = w.kind() == Wait.Kind.PARK
                    && (workWaits.isIdle(w.stack()) || perches.contains(w.lock()));
            // A worker parked on its own queue holds nothing and blocks nobody, so it is not a
            // convoy link either; the rest stay reachable so a convoy can be followed into any
            // thread. The report lists the filtered ones.
            if (!idle) {
                waitsOf(byWaiter, w.waiter()).add(inside);
            }
            if (inside.duration() > 0 && inside.duration() >= minNanos
                    && passes(filterVerdict, waiterFilter, w.waiter())) {
                if (idle) {
                    idling.add(inside);
                } else {
                    reported.add(inside);
                    if (inside.duration() < w.duration()) {
                        clipped++;
                    }
                }
            }
        }
        this.waits = reported.toList();
        this.workWaits = idling.toList();
        this.unfilteredCount = sorted.size();
        this.clippedCount = clipped;
        this.perchCount = perches.size();
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

    /**
     * Parks that were a worker waiting for its own queue: not contention, and on a server
     * they are most of the blocking time in the file. Reported apart so that ranking by
     * duration does not bury the one lock that matters under a dozen idle pools.
     */
    public List<Wait> workWaits() {
        return workWaits;
    }

    public long workWaitNanos() {
        return sum(workWaits);
    }

    /** {@link #locks(int)} over the waiting-for-work parks instead of the contended waits. */
    public List<LockStats> workWaitLocks(final int top) {
        return locksOf(workWaits, top);
    }

    /**
     * How many of the locks under {@link #workWaits()} were recognised by their shape rather
     * than by a frame in the idle list: one thread, no holder, most of the recording parked
     * there. The report says so, because a reader who does not know the rule cannot tell why
     * a lock they can see in the file is missing from the contention above.
     */
    public int perchCount() {
        return perchCount;
    }

    /** How many distinct threads were waiting for work. */
    public int workWaitThreads() {
        final Set<ThreadRef> threads = new LinkedHashSet<>();
        for (final Wait w : workWaits) {
            threads.add(w.waiter());
        }
        return threads.size();
    }

    public boolean isEmpty() {
        return waits.isEmpty();
    }

    public long totalNanos() {
        return sum(waits);
    }

    private static long sum(final List<Wait> of) {
        long total = 0;
        for (final Wait w : of) {
            total += w.duration();
        }
        return total;
    }

    /** Locks ranked by total time threads spent waiting for them. */
    public List<LockStats> locks(final int top) {
        return locksOf(waits, top);
    }

    /**
     * The ranked locks of {@link #locks(int)} grouped by the stack that stands for them, in
     * rank order.
     *
     * @param frames how many frames the caller will print: two stacks that differ only below
     *               that print the same, so they are one group. Keyed on the rendering rather
     *               than on the frames, because an elided stack also shows the first frame
     *               of the application's own code wherever it lies, and two stacks that differ
     *               there are two a reader can tell apart
     */
    public List<StackGroup> lockStacks(final int top, final int frames) {
        final Map<String, List<Wait.LockKey>> byStack = new LinkedHashMap<>();
        final Map<String, Wait> longest = new HashMap<>();
        for (final LockStats l : locks(top)) {
            if (l.longest() == null) {
                continue;
            }
            final String rendering = l.longest().stack().pretty("", frames);
            byStack.computeIfAbsent(rendering, _ -> new ArrayList<>()).add(l.lock());
            final Wait best = longest.get(rendering);
            if (best == null || l.longest().duration() > best.duration()) {
                longest.put(rendering, l.longest());
            }
        }
        final List<StackGroup> out = new ArrayList<>(byStack.size());
        byStack.forEach((rendering, locks) -> out.add(new StackGroup(List.copyOf(locks), longest.get(rendering))));
        return List.copyOf(out);
    }

    /**
     * Lock sites ranked by total wait: every lock whose longest wait prints the same stack
     * summed into one row, over all locks rather than over the top {@code top} of them, so a
     * site spread across forty instances outranks one big lock instead of being lost below it.
     *
     * <p>Fifteen queues of the same kind are fifteen rows in {@link #locks(int)} and one line
     * of work to the reader; the addresses stay reachable through a {@code --lock} query.
     *
     * @param frames how many frames the caller will print, as in {@link #lockStacks(int, int)}
     */
    public List<SiteStats> lockSites(final int top, final int frames) {
        final Map<String, List<LockStats>> byStack = new LinkedHashMap<>();
        for (final LockStats l : locksOf(waits, Integer.MAX_VALUE)) {
            if (l.longest() != null) {
                byStack.computeIfAbsent(l.longest().stack().pretty("", frames), _ -> new ArrayList<>()).add(l);
            }
        }
        final List<SiteStats> sites = new ArrayList<>(byStack.size());
        byStack.forEach((_, group) -> {
            final List<Wait.LockKey> locks = new ArrayList<>(group.size());
            final Set<ThreadRef> waiters = new LinkedHashSet<>();
            final Set<ThreadRef> owners = new LinkedHashSet<>();
            long total = 0;
            long max = 0;
            int count = 0;
            Wait longest = null;
            for (final LockStats l : group) {
                locks.add(l.lock());
                waiters.addAll(l.waiters());
                owners.addAll(l.owners());
                total += l.totalNanos();
                max = Math.max(max, l.maxNanos());
                count += l.count();
                if (longest == null || l.longest().duration() > longest.duration()) {
                    longest = l.longest();
                }
            }
            sites.add(new SiteStats(longest, List.copyOf(locks), total, count, max, Set.copyOf(waiters),
                    Set.copyOf(owners)));
        });
        sites.sort(Comparator.comparingLong(SiteStats::totalNanos).reversed());
        return limit(sites, top);
    }

    private List<LockStats> locksOf(final List<Wait> from, final int top) {
        final Map<Wait.LockKey, long[]> totals = new LinkedHashMap<>();
        final Map<Wait.LockKey, Set<ThreadRef>> waiters = new HashMap<>();
        final Map<Wait.LockKey, Set<ThreadRef>> owners = new HashMap<>();
        final Map<Wait.LockKey, Wait> longest = new HashMap<>();
        for (final Wait w : from) {
            final long[] t = totals.computeIfAbsent(w.lock(), _ -> new long[3]);
            t[0] += w.duration();
            t[1]++;
            t[2] = Math.max(t[2], w.duration());
            waiters.computeIfAbsent(w.lock(), _ -> new LinkedHashSet<>()).add(w.waiter());
            if (w.owner() != null) {
                owners.computeIfAbsent(w.lock(), _ -> new LinkedHashSet<>()).add(w.owner());
            }
            final Wait best = longest.get(w.lock());
            if (best == null || w.duration() > best.duration()) {
                longest.put(w.lock(), w);
            }
        }
        final List<LockStats> stats = new ArrayList<>();
        totals.forEach((lock, t) -> stats.add(new LockStats(lock, t[0], (int) t[1], t[2],
                waiters.getOrDefault(lock, Set.of()), owners.getOrDefault(lock, Set.of()), longest.get(lock))));
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
