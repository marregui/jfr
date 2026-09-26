// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;

/**
 * Whether a lock is a thread's own perch — the place it waits when it has nothing to do —
 * decided from what the recording measured rather than from the name of a frame.
 *
 * <p>{@link IdleMatcher} answers the same question by recognising a pool's own idle frame,
 * which works only for the runtimes whose frames are in its list. A service with its own
 * worker loop is not in anyone's list: on one recording, eight of the eight most contended
 * locks were dispatcher threads parked on their own mailbox, and an operator who did not
 * already know the codebase had no way to know to name that frame.
 *
 * <p>A perch has a shape no list is needed to see. Exactly one thread ever waits on it, no
 * thread was ever found holding it, and that one thread spends most of the recording parked
 * there. Measured on the recording that raised this, the separation is wide: the mailboxes
 * covered 76.7 % to 99.9 % of the window while the busiest real queue in the same file — a
 * consumer that was genuinely waiting for data someone else had to produce — covered 9.8 %.
 * Half the window is the line, with five times the margin on either side of it.
 *
 * <p>Two parks are required for the same reason the share is: one park covering the window
 * is a thread that is stuck, which is the most important thing a report can say, and it must
 * never be filed away as idleness. A lock with a holder is contention whatever its shape.
 */
public final class Perch {

    /** A lock has to hold its thread for more than this share of the window to be its perch. */
    private static final long SHARE_DENOMINATOR = 2;
    /** One long park is a thread that is stuck, not a loop with nothing to do. */
    private static final int MIN_PARKS = 2;
    /**
     * How many frames of a stack name the loop that waits there: eight, deep enough to reach
     * below the park and the queue into the loop itself.
     */
    private static final int LOOP_FRAMES = 8;
    /** How unlikely chance has to make a moved lock's pauses: one in a thousand, as a power of ten. */
    private static final double CHANCE_LOG10 = -3;

    private Perch() {
    }

    /**
     * @param totalNanos      how long the lock held its waiters, inside the window
     * @param parks           how many times it did
     * @param distinctWaiters how many threads ever waited on it
     * @param owned           whether any thread was found holding it
     * @param windowNanos     the recording's span
     */
    public static boolean matches(final long totalNanos, final int parks, final int distinctWaiters,
                                  final boolean owned, final long windowNanos) {
        return distinctWaiters == 1 && !owned && parks >= MIN_PARKS
                && totalNanos * SHARE_DENOMINATOR > windowNanos;
    }

    /**
     * Whether the locks one thread waited on in turn from one loop are a single object that
     * collections moved. JFR names a lock by its address, and a collection that moves the
     * object gives it a new one: on one recording a dispatcher's mailbox had nine addresses
     * in three minutes, one per young collection, and no piece covered half the window.
     *
     * <p>An address changes under a moving collection only, so every change from one wait to
     * the next has to span a pause. That alone proves nothing when the waits are long against
     * the time between pauses: a consumer that waits 800 ms on a new future per request, in
     * a JVM collecting every 200 ms, has a pause inside every change. So the changes must
     * also be unlikely to have met a pause by chance: a change of length {@code L}, in a
     * stretch whose pauses come {@code k} to {@code T}, meets one by chance with odds of at
     * most {@code L k / T}, and the product over every change has to be one in a thousand
     * or less. Measured on the recordings this was built on, the moved locks scored between
     * 10<sup>-5</sup> (a monitor polling every 5 s, eight changes in eight collections) and
     * 10<sup>-19</sup>; the threads waiting on a new object per request either had changes
     * with no pause (20 of 31, 28 of 41) or scored 1. Two 60 s waits on two addresses score 1
     * as well: the recording cannot tell a moved lock from two objects there, and says so by
     * listing them.
     *
     * <p>A collector that moves objects outside its pauses (ZGC, Shenandoah) may move a lock
     * with no pause in the change, and that lock then stays split.
     *
     * @param locks  the lock of each wait, in the order the waits ended
     * @param ends   when each wait ended, ascending: one thread's waits do not overlap
     * @param pauses collection pauses as flat (start, end) pairs, ascending and disjoint
     * @return {@code true} when there are at least two locks, every change spans a pause, and
     * chance explains that at odds of one in a thousand or less
     */
    public static boolean moved(final LongList locks, final LongList ends, final LongList pauses) {
        final int n = locks.size();
        if (n < 2) {
            return false;
        }
        final long first = ends.getQuick(0);
        final long last = ends.getQuick(n - 1);
        if (last <= first) {
            return false;
        }
        final long pausesInStretch = pausesIn(pauses, first, last);
        double chance = 0;
        boolean changed = false;
        for (int i = 1; i < n; i++) {
            final long from = ends.getQuick(i - 1);
            final long to = ends.getQuick(i);
            if (to < from) {
                return false;
            }
            if (locks.getQuick(i) != locks.getQuick(i - 1)) {
                if (pausesIn(pauses, from, to) == 0) {
                    return false;
                }
                chance += Math.log10(Math.min(1.0, (double) (to - from) * pausesInStretch / (last - first)));
                changed = true;
            }
        }
        return changed && chance <= CHANCE_LOG10;
    }

    /** How many pauses overlap {@code (from, to]}: from the first one ending after {@code from} while they start by {@code to}. */
    private static long pausesIn(final LongList pauses, final long from, final long to) {
        final int count = pauses.size() / 2;
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (pauses.getQuick(2 * mid + 1) <= from) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int i = lo;
        while (i < count && pauses.getQuick(2 * i) <= to) {
            i++;
        }
        return i - lo;
    }

    /**
     * Where a thread waits: the thread and the loop, as {@link #loop} prints it. The pieces
     * of a moved lock are folded per place, so one idle thread's mailbox is found whether or
     * not other threads run the same loop.
     */
    public record Place(ThreadRef waiter, String loop) {
    }

    /**
     * The loop a stack waits in, as it prints: two stacks that print the same are one loop to
     * a reader, whatever the frame kinds or the objects behind them, so they are one loop
     * here. {@code null} for an empty stack, which names no loop: every stackless wait would
     * print the same, and none of them is evidence about another.
     */
    public static String loop(final Stack stack) {
        return stack.isEmpty() ? null : stack.pretty("", LOOP_FRAMES);
    }

    /**
     * What one lock did over a whole recording, gathered by whoever holds the waits: the
     * contention report from its {@code Wait}s, the stall analysis from its blocks. Both weigh
     * the lock the same way, so the rule and its evidence stay in one place.
     */
    public static final class Shape {

        private ThreadRef waiter;
        private boolean several;
        private boolean owned;
        private long total;
        private int parks;
        private long longest;
        private Stack stack = Stack.EMPTY;

        /**
         * @param nanos the part of this wait inside the window, which is what the share is of
         * @param owned whether a thread was found holding the lock for this wait
         */
        public void add(final ThreadRef waiter, final long nanos, final Stack stack, final boolean owned) {
            if (this.waiter == null) {
                this.waiter = waiter;
            } else if (!this.waiter.equals(waiter)) {
                several = true;
            }
            this.owned |= owned;
            total += nanos;
            parks++;
            if (nanos > longest && !stack.isEmpty()) {
                longest = nanos;
                this.stack = stack;
            }
        }

        /** Folds {@code other} in: the pieces of one lock that a collection split by moving it. */
        public void add(final Shape other) {
            if (other.waiter == null) {
                return;
            }
            if (waiter == null) {
                waiter = other.waiter;
            } else if (!waiter.equals(other.waiter)) {
                several = true;
            }
            several |= other.several;
            owned |= other.owned;
            total += other.total;
            parks += other.parks;
            if (other.longest > longest) {
                longest = other.longest;
                stack = other.stack;
            }
        }

        /** The one thread that waited here, when {@link #ownThread}; otherwise the first of them. */
        public ThreadRef waiter() {
            return waiter;
        }

        /** Whether one thread, and only one, waited here and nobody was seen holding it. */
        public boolean ownThread() {
            return waiter != null && !several && !owned;
        }

        public boolean matches(final long windowNanos) {
            return Perch.matches(total, parks, several ? 2 : 1, owned, windowNanos);
        }

        /** The stack of its longest wait: the loop that waits there, as the report shows it. */
        public Stack stack() {
            return stack;
        }
    }
}
