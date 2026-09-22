// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.stalls;

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

        public boolean matches(final long windowNanos) {
            return Perch.matches(total, parks, several ? 2 : 1, owned, windowNanos);
        }

        /** The stack of its longest wait: the loop that waits there, as the report shows it. */
        public Stack stack() {
            return stack;
        }
    }
}
