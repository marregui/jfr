// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.locks;

import java.util.List;

import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.ClassNames;

/**
 * One blocking wait for a lock.
 *
 * @param interval when the waiter was blocked
 * @param waiter   the thread that waited
 * @param lock     what it waited for
 * @param owner    the thread that held the monitor for the bulk of the wait; {@code null}
 *                 for {@code java.util.concurrent} parks, where JFR does not know the owner
 * @param stack    where the waiter was
 * @param via      co-waiters that held the lock briefly between {@code owner} and the
 *                 waiter (JFR records only the thread that released the monitor to the
 *                 waiter; {@link ContentionReport} walks back through their own waits)
 */
public record Wait(Interval interval, ThreadRef waiter, LockKey lock, ThreadRef owner, Stack stack,
                   List<ThreadRef> via) {

    public Wait {
        via = List.copyOf(via);
    }

    public Wait(final Interval interval, final ThreadRef waiter, final LockKey lock, final ThreadRef owner, final Stack stack) {
        this(interval, waiter, lock, owner, stack, List.of());
    }

    /** {@code held by housekeeper (handed on through event-loop-2)}, or empty for parks. */
    public String heldBy() {
        if (owner == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder("held by ").append(owner.name());
        if (!via.isEmpty()) {
            sb.append(" (handed on through ");
            for (int i = 0; i < via.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(via.get(i).name());
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** How the wait was recorded. */
    public enum Kind {
        /** {@code jdk.JavaMonitorEnter}: contended {@code synchronized}. Knows the previous owner. */
        MONITOR_ENTER("monitor"),
        /** {@code jdk.ThreadPark}: {@code LockSupport.park}, which every j.u.c lock ends in. */
        PARK("park");

        private final String label;

        Kind(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Identity of a lock: the class of the object and its address. Addresses are only
     * stable between garbage collections that move the object, which is why the class is
     * always shown and the address only disambiguates.
     */
    public record LockKey(String className, long address, Kind kind) {
        public String pretty() {
            return ClassNames.pretty(className) + "@" + Long.toHexString(address);
        }

        public String prettyClass() {
            return ClassNames.pretty(className);
        }
    }

    public long start() {
        return interval.start();
    }

    public long end() {
        return interval.end();
    }

    public long duration() {
        return interval.length();
    }

    public Kind kind() {
        return lock.kind();
    }
}
