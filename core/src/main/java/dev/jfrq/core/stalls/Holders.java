// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.Comparator;
import java.util.List;

import dev.jfrq.core.coll.IdentityObjObjHashMap;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;

/**
 * Every thread's monitor waits, watched or not, and the walk that turns JFR's
 * {@code previousOwner} into the thread that really held a lock. {@code stalls} runs it on
 * {@link Timeline.Block}s and {@code locks} on its own waits; {@link Access} is how it reads
 * either.
 *
 * <p>{@code previousOwner} is the thread that released the monitor to the waiter, which under
 * contention is often another waiter that got it a moment earlier and held it for
 * microseconds. The walk rebuilds the chain of holds inside the victim's wait, from its end
 * backwards. The victim got the lock when its wait ended; each thread on the chain got it when
 * its own wait for that lock ended, taking the one of its waits that ended last at or before
 * its successor (the next thread toward the victim) got the lock, and held it from then, or
 * from the start of the victim's wait if that is later, until the successor got it. The owner
 * of that wait is the next thread back. The chain ends at a thread with no such wait, which
 * held the lock from before the victim's wait began; at an unknown owner; and at a cycle (the
 * victim cannot hold what it waits for, and a wait is not walked twice). The thread that held
 * the lock longest inside the wait, summed when it appears more than once, is the holder;
 * the others that held it are the threads it was handed on through, in the order they held
 * it. On a tie the thread nearer the victim is named.
 *
 * <p>Fill with {@link #add}, then {@link #seal} once, then {@link #resolve}.
 *
 * @param <W> the wait type
 */
public final class Holders<W> {

    /** How many of the threads a lock was handed on through are named; the rest are counted. */
    public static final int VIA_SHOWN = 4;

    /** How the walk reads a wait, and rebuilds one with its holder resolved. */
    public interface Access<W> {
        Interval interval(W wait);

        /** The thread JFR says released the lock to the waiter, or {@code null} when unknown. */
        ThreadRef owner(W wait);

        /** What identifies the lock waited for: equal for waits on the same lock. */
        Object lock(W wait);

        W withHolder(W wait, ThreadRef holder, List<ThreadRef> via);
    }

    private final Access<W> access;
    private final Comparator<W> byEnd;
    /** Per lock, per thread, its waits for that lock, in end order once sealed. */
    private final ObjObjHashMap<Object, ObjObjHashMap<ThreadRef, ObjList<W>>> byLock = new ObjObjHashMap<>(256);
    /** Scratch for the walk, cleared per wait (G-3.1): the chain newest first, each thread's hold, the waits used. */
    private final ObjList<ThreadRef> chain = new ObjList<>();
    private final ObjLongHashMap<ThreadRef> held = new ObjLongHashMap<>(16);
    private final IdentityObjObjHashMap<W, W> walked = new IdentityObjObjHashMap<>(16);
    private final ObjList<ThreadRef> via = new ObjList<>();

    public Holders(final Access<W> access) {
        this.access = access;
        this.byEnd = (a, b) -> Long.compare(access.interval(a).end(), access.interval(b).end());
    }

    public void add(final ThreadRef thread, final W wait) {
        final Object lock = access.lock(wait);
        final int li = byLock.keyIndex(lock);
        final ObjObjHashMap<ThreadRef, ObjList<W>> threads = li < 0 ? byLock.valueAtQuick(li)
                : byLock.putAt(li, lock, new ObjObjHashMap<>(4));
        final int ti = threads.keyIndex(thread);
        final ObjList<W> waits = ti < 0 ? threads.valueAtQuick(ti) : threads.putAt(ti, thread, new ObjList<>(4));
        waits.add(wait);
    }

    /** Sorts every thread's waits for every lock by their end, which is when the thread got it. */
    public void seal() {
        for (int s = 0, n = byLock.slots(); s < n; s++) {
            if (byLock.hasKeyAtSlot(s)) {
                final ObjObjHashMap<ThreadRef, ObjList<W>> threads = byLock.valueAtSlot(s);
                for (int t = 0, m = threads.slots(); t < m; t++) {
                    if (threads.hasKeyAtSlot(t)) {
                        threads.valueAtSlot(t).sort(byEnd);
                    }
                }
            }
        }
    }

    /** {@code wait} with its owner resolved to the thread that held the lock longest; the same object when nothing changes. */
    public W resolve(final ThreadRef waiter, final W wait) {
        final ThreadRef recorded = access.owner(wait);
        if (recorded == null) {
            return wait;
        }
        final ObjObjHashMap<ThreadRef, ObjList<W>> waitsFor = byLock.get(access.lock(wait));
        final Interval interval = access.interval(wait);
        final long start = interval.start();
        chain.clear();
        held.clear();
        walked.clear();
        ThreadRef thread = recorded;
        long handedOn = interval.end();
        // Each step: `thread` held the lock until `handedOn`, when its successor got it.
        while (!thread.equals(waiter)) {
            final W acquired = waitsFor == null ? null : lastAcquired(waitsFor.get(thread), start, handedOn);
            final long from = acquired == null ? start : access.interval(acquired).end();
            final int index = held.keyIndex(thread);
            if (index < 0) {
                held.increment(thread, handedOn - from);
            } else {
                held.putAt(index, thread, handedOn - from);
                chain.add(thread);
            }
            // Acquisitions only move back in time, so a wait comes round again only through
            // holds of no length: those are the steps a cycle can hide in, and the only ones
            // remembered.
            if (acquired == null || access.owner(acquired) == null
                    || (from == handedOn && walked.put(acquired, acquired) != null)) {
                break;
            }
            thread = access.owner(acquired);
            handedOn = from;
        }
        if (chain.isEmpty()) {
            return wait;
        }
        // The chain is newest first: the first thread with the largest hold is the one nearest the victim.
        ThreadRef holder = chain.getQuick(0);
        for (int i = 1, n = chain.size(); i < n; i++) {
            final ThreadRef t = chain.getQuick(i);
            if (held.get(t) > held.get(holder)) {
                holder = t;
            }
        }
        via.clear();
        for (int i = chain.size() - 1; i >= 0; i--) {
            final ThreadRef t = chain.getQuick(i);
            if (!t.equals(holder) && held.get(t) > 0) {
                via.add(t);
            }
        }
        if (holder.equals(recorded) && via.isEmpty()) {
            return wait;
        }
        return access.withHolder(wait, holder, via.toList());
    }

    /**
     * The wait in {@code waits} (one thread's, for one lock, in end order) that ended last in
     * {@code (after, atOrBefore]}, or {@code null}: a wait that ended at or before
     * {@code after} says the thread held the lock from before then, which is what no wait says
     * too.
     */
    private W lastAcquired(final ObjList<W> waits, final long after, final long atOrBefore) {
        if (waits == null) {
            return null;
        }
        // The first wait that ended after `atOrBefore`; the one before it is the candidate.
        int lo = 0;
        int hi = waits.size();
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (access.interval(waits.getQuick(mid)).end() <= atOrBefore) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo == 0) {
            return null;
        }
        final W last = waits.getQuick(lo - 1);
        return access.interval(last).end() > after ? last : null;
    }

    /**
     * Appends {@code  (handed on through a, b)} for a non-empty {@code via}, naming the first
     * {@link #VIA_SHOWN} and counting the rest: a hot lock on a loaded node hands on through
     * dozens of threads inside one wait, and a line of thirty names buries the holder.
     */
    public static StringBuilder appendVia(final StringBuilder sb, final List<ThreadRef> via) {
        if (via.isEmpty()) {
            return sb;
        }
        sb.append(" (handed on through ");
        final int shown = Math.min(via.size(), VIA_SHOWN);
        for (int i = 0; i < shown; i++) {
            sb.append(i > 0 ? ", " : "").append(via.get(i).name());
        }
        if (via.size() > shown) {
            sb.append(" and ").append(via.size() - shown).append(" more");
        }
        return sb.append(')');
    }
}
