// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;

/**
 * What every lock parked on did across the whole recording, gathered for {@link Perch}.
 *
 * <p>The shape rule weighs a lock by every thread that ever waited on it, so it has to see
 * every thread's parks, not only the watched ones: a lock that two threads share is not
 * either thread's perch, and which threads {@code --thread} selected must not change that.
 * {@code locks} decides the same rule before any filter, which is why the two commands agree.
 *
 * <p>A park with no blocker object never counts: every {@code LockSupport.parkNanos} in the
 * JVM would share the one "lock", and a thread's own backoff or pacing loop would be filed
 * away as its idle point.
 *
 * <p>Kept flat (G-1.8): a lock is a number, and a park is three longs (the lock, its two
 * ends), its thread and its stack, appended per event. A server with one lock per request
 * has as many locks as parks, so nothing is allocated per lock until {@link #perchStacks},
 * and then only for the few that parked long enough to be a perch at all.
 */
final class ParkShapes {

    /** The detail of a park that names no blocker object. */
    static final String NO_BLOCKER = "(no blocker object)";

    private static final int SLOT = 3;
    private static final int LOCK = 0;
    private static final int START = 1;
    private static final int END = 2;

    private final ObjLongHashMap<String> lockIds = new ObjLongHashMap<>(64);
    private final LongList parks = new LongList(64 * SLOT);
    private final ObjList<ThreadRef> waiters = new ObjList<>(64);
    private final ObjList<Stack> stacks = new ObjList<>(64);

    /**
     * @param lock  the lock's name as the collector details it; {@link #NO_BLOCKER} is ignored
     * @param stack the park's stack, which is what a perch identifies
     */
    void add(final ThreadRef waiter, final String lock, final long start, final long end, final Stack stack) {
        if (NO_BLOCKER.equals(lock)) {
            return;
        }
        final int index = lockIds.keyIndex(lock);
        final long id;
        if (index < 0) {
            id = lockIds.valueAtQuick(index);
        } else {
            id = lockIds.size();
            lockIds.putAt(index, lock, id);
        }
        parks.add(id);
        parks.add(start);
        parks.add(end);
        waiters.add(waiter);
        stacks.add(stack);
    }

    /**
     * The stack of every lock {@link Perch} recognises in {@code span}: the loop that waits
     * there, which then answers for every other park from the same place. Each park counts
     * only for the part inside the span, as it does in {@code locks}.
     */
    ObjList<Stack> perchStacks(final Interval span) {
        final int locks = lockIds.size();
        final int n = waiters.size();
        // First the total per lock: a lock whose total fails the rule even with one waiter and
        // any number of parks is no perch, whoever they were, and is not looked at again.
        final long[] inside = new long[locks];
        for (int i = 0; i < n; i++) {
            inside[(int) parks.getQuick(i * SLOT + LOCK)] += inside(i, span);
        }
        final Perch.Shape[] shapes = new Perch.Shape[locks];
        for (int i = 0; i < n; i++) {
            final int lock = (int) parks.getQuick(i * SLOT + LOCK);
            final long nanos = inside(i, span);
            if (nanos > 0 && Perch.matches(inside[lock], Integer.MAX_VALUE, 1, false, span.duration())) {
                if (shapes[lock] == null) {
                    shapes[lock] = new Perch.Shape();
                }
                // A park has no holder to record, so the lock is never owned.
                shapes[lock].add(waiters.getQuick(i), nanos, stacks.getQuick(i), false);
            }
        }
        final ObjList<Stack> found = new ObjList<>();
        for (final Perch.Shape shape : shapes) {
            if (shape != null && shape.matches(span.duration()) && !shape.stack().isEmpty()) {
                found.add(shape.stack());
            }
        }
        return found;
    }

    private long inside(final int park, final Interval span) {
        final long start = Math.max(parks.getQuick(park * SLOT + START), span.start());
        final long end = Math.min(parks.getQuick(park * SLOT + END), span.end());
        return Math.max(0, end - start);
    }
}
