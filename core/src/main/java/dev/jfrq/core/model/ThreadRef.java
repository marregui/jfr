// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.model;

import jdk.jfr.consumer.RecordedThread;

/**
 * A thread as JFR identifies it: the Java thread id plus the name it had when the event
 * was written. Two events attributed to the same thread compare equal.
 *
 * @param id   the Java thread id, or the OS thread id for threads without a Java identity
 * @param name the Java thread name, falling back to the OS name, then {@code thread#<id>}
 */
public record ThreadRef(long id, String name) {

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
        return new ThreadRef(id, name);
    }

    @Override
    @SuppressWarnings("NullableProblems") // Record.toString() carries an external @NotNull; this never returns null
    public String toString() {
        return name;
    }
}
