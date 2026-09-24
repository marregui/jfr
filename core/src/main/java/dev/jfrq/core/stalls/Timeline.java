// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.List;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;

/**
 * The raw material of a stall analysis: what the sampler and the blocking events say
 * about one thread, plus the JVM-wide pauses that stop every thread.
 */
public final class Timeline {

    private Timeline() {
    }

    /**
     * One sampler observation of a thread.
     *
     * @param time    when the sample was taken
     * @param stack   what the thread was executing
     * @param isIdle    whether the stack shows the thread at its idle point
     * @param isInNative true for {@code jdk.NativeMethodSample} (thread in native code), false
     *                for {@code jdk.ExecutionSample} (thread executing Java). The two are
     *                sampled at different rates, so cadence is tracked per kind.
     */
    public record Sample(long time, Stack stack, boolean isIdle, boolean isInNative) {
    }

    /** Why a thread was blocked, from an event that says so. */
    public enum BlockKind {
        MONITOR("blocked on monitor"),
        PARK("parked"),
        OBJECT_WAIT("Object.wait()"),
        SLEEP("Thread.sleep"),
        SOCKET_READ("blocking socket read"),
        SOCKET_WRITE("blocking socket write"),
        FILE_READ("blocking file read"),
        FILE_WRITE("blocking file write"),
        FILE_FORCE("blocking file sync");

        private final String label;

        BlockKind(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isIo() {
            return this == SOCKET_READ || this == SOCKET_WRITE || this == FILE_READ
                    || this == FILE_WRITE || this == FILE_FORCE;
        }
    }

    /**
     * A blocking event attributed to a thread.
     *
     * @param interval when the thread was blocked
     * @param kind     what kind of block
     * @param detail   the lock class, the peer address, the file path
     * @param stack    where the thread was
     * @param owner    for monitors, the thread that held the lock longest during the wait
     *                 ({@link Holders}); otherwise {@code null}
     * @param via      for monitors, the other threads that held the lock during the wait, in
     *                 the order they held it (JFR records only the last holder; the chain
     *                 before it comes from their own waits)
     * @param bytes    for socket and file operations, the bytes moved; kept apart from
     *                 {@code detail} so that repeated reads from one peer group together
     */
    public record Block(Interval interval, BlockKind kind, String detail, Stack stack, ThreadRef owner,
                        List<ThreadRef> via, long bytes) {
        public Block {
            via = List.copyOf(via);
        }

        public Block(final Interval interval, final BlockKind kind, final String detail, final Stack stack, final ThreadRef owner) {
            this(interval, kind, detail, stack, owner, List.of(), 0);
        }

        public Block(final Interval interval, final BlockKind kind, final String detail, final Stack stack, final long bytes) {
            this(interval, kind, detail, stack, null, List.of(), bytes);
        }

        public long start() {
            return interval.start();
        }

        public long duration() {
            return interval.duration();
        }
    }

    /** A JVM-wide stop-the-world interval. */
    public enum PauseKind {
        GC("GC pause"),
        SAFEPOINT("safepoint");

        private final String label;

        PauseKind(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Pause(Interval interval, PauseKind kind, String detail) {
        public long start() {
            return interval.start();
        }

        public long duration() {
            return interval.duration();
        }
    }

    /**
     * Everything known about one thread.
     *
     * @param thread    the thread
     * @param samples   sampler observations in time order
     * @param blocks    blocking events in start order
     * @param lifeStart when the thread's life inside the recording began: the span's start, or
     *                  its {@code jdk.ThreadStart} when that is later; {@link Nulls#LONG_NULL}
     *                  when the recording cannot say, and then the stretches before the first
     *                  sample and after the last are not judged
     * @param lifeEnd   when it ended, likewise
     */
    public record ThreadTimeline(ThreadRef thread, List<Sample> samples, List<Block> blocks, long lifeStart,
                                 long lifeEnd) {
        public ThreadTimeline {
            samples = List.copyOf(samples);
            blocks = List.copyOf(blocks);
        }

        /** A thread whose life inside the recording is not known. */
        public ThreadTimeline(final ThreadRef thread, final List<Sample> samples, final List<Block> blocks) {
            this(thread, samples, blocks, Nulls.LONG_NULL, Nulls.LONG_NULL);
        }

        public boolean isLifeKnown() {
            return lifeStart != Nulls.LONG_NULL && lifeEnd != Nulls.LONG_NULL;
        }
    }
}
