// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.time.Instant;

import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * Field access on {@link RecordedEvent} with the JFR quirks handled in one place: the
 * sampler events name their thread {@code sampledThread} while every other event uses
 * {@code eventThread}; timestamps are exposed as epoch nanoseconds; missing values become
 * {@code null} or a sentinel rather than an exception.
 */
public final class Events {

    private Events() {
    }

    public static long nanos(final Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    public static long startNanos(final RecordedEvent e) {
        return nanos(e.getStartTime());
    }

    public static long endNanos(final RecordedEvent e) {
        return nanos(e.getEndTime());
    }

    public static Interval interval(final RecordedEvent e) {
        final long start = startNanos(e);
        final long end = endNanos(e);
        return new Interval(start, Math.max(start, end));
    }

    /** The thread an event belongs to, or {@code null} for VM-level events without one. */
    public static ThreadRef thread(final RecordedEvent e) {
        final RecordedThread t = e.hasField("sampledThread") ? e.getThread("sampledThread") : e.getThread();
        return ThreadRef.of(t);
    }

    /** A thread-valued field such as {@code previousOwner}, or {@code null}. */
    public static ThreadRef thread(final RecordedEvent e, final String field) {
        if (!e.hasField(field)) {
            return null;
        }
        return ThreadRef.of(e.getThread(field));
    }

    /** {@link #thread(RecordedEvent, String)} resolved through the interner's identity cache. */
    public static ThreadRef thread(final RecordedEvent e, final String field, final Interner interner) {
        if (!e.hasField(field)) {
            return null;
        }
        return interner.thread(e.getThread(field));
    }

    /** The event's stack without interning; collectors use {@link #stack(RecordedEvent, Interner)}. */
    public static Stack stack(final RecordedEvent e) {
        return Stack.of(e.getStackTrace());
    }

    /** The event's stack, canonicalised through {@code interner}. */
    public static Stack stack(final RecordedEvent e, final Interner interner) {
        return interner.stack(e.getStackTrace());
    }

    /**
     * The JVM name of a class-valued field ({@code [B}, {@code java.lang.Object}) resolved
     * through the interner's identity cache, or {@code null}.
     */
    public static String className(final RecordedEvent e, final String field, final Interner interner) {
        if (!e.hasField(field)) {
            return null;
        }
        return interner.className(e.getClass(field));
    }

    public static long longOr(final RecordedEvent e, final String field, final long fallback) {
        return e.hasField(field) ? e.getLong(field) : fallback;
    }

    public static String stringOr(final RecordedEvent e, final String field, final String fallback) {
        if (!e.hasField(field)) {
            return fallback;
        }
        final String v = e.getString(field);
        return v == null ? fallback : v;
    }
}
