// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.model;

import jdk.jfr.consumer.RecordedThread;

/**
 * A thread as JFR identifies it: the Java thread id plus the name JFR recorded for it.
 * Two events attributed to the same thread compare equal.
 *
 * @param id      the Java thread id, or the OS thread id for threads without a Java identity
 * @param name    the Java thread name, falling back to the OS name, then {@code thread#<id>}
 * @param isVirtual whether it is a virtual thread; an allocation sample on one is weighted by
 *                what its carrier allocated, not by what it did ({@code AllocationCollector})
 */
public record ThreadRef(long id, String name, boolean isVirtual) {

    /** A platform thread. */
    public ThreadRef(final long id, final String name) {
        this(id, name, false);
    }

    public static ThreadRef of(final RecordedThread t) {
        if (t == null) {
            return null;
        }
        final long id = t.getJavaThreadId() > 0 ? t.getJavaThreadId() : t.getOSThreadId();
        String name = t.getJavaName();
        if (name == null || name.isEmpty()) {
            name = t.getOSName();
        }
        if (name == null || name.isEmpty()) {
            name = "thread#" + id;
        }
        return new ThreadRef(id, name, t.isVirtual());
    }

    @Override
    @SuppressWarnings("NullableProblems") // Record.toString() carries an external @NotNull; this never returns null
    public String toString() {
        return name;
    }
}
