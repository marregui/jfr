package dev.jfrq.core.jfr;

import java.time.Instant;

import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import jdk.jfr.consumer.RecordedClass;
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

    public static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    public static long startNanos(RecordedEvent e) {
        return nanos(e.getStartTime());
    }

    public static long endNanos(RecordedEvent e) {
        return nanos(e.getEndTime());
    }

    public static Interval interval(RecordedEvent e) {
        long start = startNanos(e);
        long end = endNanos(e);
        return new Interval(start, Math.max(start, end));
    }

    /** The thread an event belongs to, or {@code null} for VM-level events without one. */
    public static ThreadRef thread(RecordedEvent e) {
        RecordedThread t = e.hasField("sampledThread") ? e.getThread("sampledThread") : e.getThread();
        return ThreadRef.of(t);
    }

    /** A thread-valued field such as {@code previousOwner}, or {@code null}. */
    public static ThreadRef thread(RecordedEvent e, String field) {
        if (!e.hasField(field)) {
            return null;
        }
        return ThreadRef.of(e.getThread(field));
    }

    /** {@link #thread(RecordedEvent, String)} resolved through the interner's identity cache. */
    public static ThreadRef thread(RecordedEvent e, String field, Interner interner) {
        if (!e.hasField(field)) {
            return null;
        }
        return interner.thread(e.getThread(field));
    }

    /** The event's stack without interning; collectors use {@link #stack(RecordedEvent, Interner)}. */
    public static Stack stack(RecordedEvent e) {
        return Stack.of(e.getStackTrace());
    }

    /** The event's stack, canonicalised through {@code interner}. */
    public static Stack stack(RecordedEvent e, Interner interner) {
        return interner.stack(e.getStackTrace());
    }

    /** The JVM name of a class-valued field ({@code [B}, {@code java.lang.Object}), or {@code null}. */
    public static String className(RecordedEvent e, String field) {
        if (!e.hasField(field)) {
            return null;
        }
        RecordedClass c = e.getClass(field);
        return c == null ? null : c.getName();
    }

    /** {@link #className(RecordedEvent, String)} resolved through the interner's identity cache. */
    public static String className(RecordedEvent e, String field, Interner interner) {
        if (!e.hasField(field)) {
            return null;
        }
        return interner.className(e.getClass(field));
    }

    public static long longOr(RecordedEvent e, String field, long fallback) {
        return e.hasField(field) ? e.getLong(field) : fallback;
    }

    public static String stringOr(RecordedEvent e, String field, String fallback) {
        if (!e.hasField(field)) {
            return fallback;
        }
        String v = e.getString(field);
        return v == null ? fallback : v;
    }
}
