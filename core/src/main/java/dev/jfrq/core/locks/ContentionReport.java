package dev.jfrq.core.locks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final RecordingInfo info;
    private final List<Wait> waits;
    private final Map<ThreadRef, List<Wait>> byWaiter = new HashMap<>();
    /** Per waiter, the longest wait: bounds the window a lookup by start has to scan. */
    private final Map<ThreadRef, Long> longestByWaiter = new HashMap<>();

    public ContentionReport(RecordingInfo info, List<Wait> waits) {
        this.info = info;
        List<Wait> sorted = new ArrayList<>(waits);
        sorted.sort(Comparator.comparing(Wait::interval));
        Map<ThreadRef, List<Wait>> raw = new HashMap<>();
        Map<ThreadRef, Long> rawLongest = new HashMap<>();
        for (Wait w : sorted) {
            raw.computeIfAbsent(w.waiter(), k -> new ArrayList<>()).add(w);
            rawLongest.merge(w.waiter(), w.duration(), Math::max);
        }
        List<Wait> resolved = new ArrayList<>(sorted.size());
        for (Wait w : sorted) {
            resolved.add(resolveHolder(w, raw, rawLongest));
        }
        this.waits = List.copyOf(resolved);
        for (Wait w : this.waits) {
            byWaiter.computeIfAbsent(w.waiter(), k -> new ArrayList<>()).add(w);
            longestByWaiter.merge(w.waiter(), w.duration(), Math::max);
        }
    }

    /**
     * JFR's {@code previousOwner} is the thread that released the monitor to the waiter,
     * which under contention is often another waiter that held it for microseconds. Walks
     * back: while the recorded owner was itself waiting for the same lock during this wait,
     * take its owner instead and remember the intermediary.
     */
    private static Wait resolveHolder(Wait wait, Map<ThreadRef, List<Wait>> byWaiter, Map<ThreadRef, Long> longest) {
        ThreadRef owner = wait.owner();
        if (owner == null) {
            return wait;
        }
        List<ThreadRef> via = new ArrayList<>();
        Set<ThreadRef> seen = new LinkedHashSet<>();
        seen.add(wait.waiter());
        seen.add(owner);
        while (true) {
            Wait ownersWait = null;
            List<Wait> theirs = byWaiter.getOrDefault(owner, List.of());
            int from = Sorted.lowerBound(theirs, Wait::start, wait.start() - longest.getOrDefault(owner, 0L));
            for (int i = from; i < theirs.size(); i++) {
                Wait w = theirs.get(i);
                if (w.start() >= wait.end()) {
                    break;
                }
                if (w.lock().equals(wait.lock()) && w.interval().overlaps(wait.interval())
                        && (ownersWait == null || w.duration() > ownersWait.duration())) {
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
            return wait;
        }
        return new Wait(wait.interval(), wait.waiter(), wait.lock(), owner, wait.stack(), via);
    }

    public RecordingInfo info() {
        return info;
    }

    /** All waits, in start order. */
    public List<Wait> waits() {
        return waits;
    }

    public boolean isEmpty() {
        return waits.isEmpty();
    }

    public long totalNanos() {
        long total = 0;
        for (Wait w : waits) {
            total += w.duration();
        }
        return total;
    }

    /** Locks ranked by total time threads spent waiting for them. */
    public List<LockStats> locks(int top) {
        Map<Wait.LockKey, long[]> totals = new LinkedHashMap<>();
        Map<Wait.LockKey, Set<ThreadRef>> waiters = new HashMap<>();
        Map<Wait.LockKey, Set<ThreadRef>> owners = new HashMap<>();
        for (Wait w : waits) {
            long[] t = totals.computeIfAbsent(w.lock(), k -> new long[3]);
            t[0] += w.duration();
            t[1]++;
            t[2] = Math.max(t[2], w.duration());
            waiters.computeIfAbsent(w.lock(), k -> new LinkedHashSet<>()).add(w.waiter());
            if (w.owner() != null) {
                owners.computeIfAbsent(w.lock(), k -> new LinkedHashSet<>()).add(w.owner());
            }
        }
        List<LockStats> stats = new ArrayList<>();
        totals.forEach((lock, t) -> stats.add(new LockStats(lock, t[0], (int) t[1], t[2],
                waiters.getOrDefault(lock, Set.of()), owners.getOrDefault(lock, Set.of()))));
        stats.sort(Comparator.comparingLong(LockStats::totalNanos).reversed());
        return limit(stats, top);
    }

    /** Threads ranked by total time blocked. */
    public List<ThreadStats> waiters(int top) {
        List<ThreadStats> stats = new ArrayList<>();
        byWaiter.forEach((thread, ws) -> {
            long total = 0;
            long max = 0;
            for (Wait w : ws) {
                total += w.duration();
                max = Math.max(max, w.duration());
            }
            stats.add(new ThreadStats(thread, total, ws.size(), max));
        });
        stats.sort(Comparator.comparingLong(ThreadStats::totalNanos).reversed());
        return limit(stats, top);
    }

    /** The longest individual waits. */
    public List<Wait> longest(int top) {
        List<Wait> sorted = new ArrayList<>(waits);
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
    public List<Convoy> convoys(int maxDepth, int top) {
        List<Convoy> found = new ArrayList<>();
        // Heads in descending duration: once `top` convoys exist, no later head can outrank them.
        for (Wait head : longest(waits.size())) {
            if (found.size() >= top) {
                break;
            }
            List<Wait> links = new ArrayList<>();
            links.add(head);
            Set<ThreadRef> seen = new LinkedHashSet<>();
            seen.add(head.waiter());
            Wait current = head;
            while (links.size() < maxDepth && current.owner() != null && seen.add(current.owner())) {
                Wait next = longestOverlapping(current.owner(), current.interval(), current.lock());
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

    private Wait longestOverlapping(ThreadRef thread, Interval during, Wait.LockKey notThisLock) {
        Wait best = null;
        long bestOverlap = 0;
        List<Wait> theirs = byWaiter.getOrDefault(thread, List.of());
        int from = Sorted.lowerBound(theirs, Wait::start, during.start() - longestByWaiter.getOrDefault(thread, 0L));
        for (int i = from; i < theirs.size(); i++) {
            Wait w = theirs.get(i);
            if (w.start() >= during.end()) {
                break;
            }
            if (w.lock().equals(notThisLock)) {
                continue;
            }
            long overlap = w.interval().overlap(during);
            if (overlap > bestOverlap) {
                best = w;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    private static <T> List<T> limit(List<T> list, int top) {
        return list.size() > top ? List.copyOf(list.subList(0, top)) : List.copyOf(list);
    }
}
