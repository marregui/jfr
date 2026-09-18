package dev.jfrq.core.stalls;

import java.util.List;

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
     * @param idle    whether the stack shows the thread at its idle point
     * @param inNative true for {@code jdk.NativeMethodSample} (thread in native code), false
     *                for {@code jdk.ExecutionSample} (thread executing Java). The two are
     *                sampled at different rates, so cadence is tracked per kind.
     */
    public record Sample(long time, Stack stack, boolean idle, boolean inNative) {
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

        BlockKind(String label) {
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
     * @param owner    for monitors, the thread that held the lock for the bulk of the wait;
     *                 otherwise {@code null}
     * @param via      for monitors, threads that held the lock briefly between {@code owner}
     *                 and this thread (JFR records only the last holder; the collector walks
     *                 back through their own waits to find who really held it)
     * @param bytes    for socket and file operations, the bytes moved; kept apart from
     *                 {@code detail} so that repeated reads from one peer group together
     */
    public record Block(Interval interval, BlockKind kind, String detail, Stack stack, ThreadRef owner,
                        List<ThreadRef> via, long bytes) {
        public Block {
            via = List.copyOf(via);
        }

        public Block(Interval interval, BlockKind kind, String detail, Stack stack, ThreadRef owner) {
            this(interval, kind, detail, stack, owner, List.of(), 0);
        }

        public Block(Interval interval, BlockKind kind, String detail, Stack stack, long bytes) {
            this(interval, kind, detail, stack, null, List.of(), bytes);
        }

        public long length() {
            return interval.length();
        }
    }

    /** A JVM-wide stop-the-world interval. */
    public enum PauseKind {
        GC("GC pause"),
        SAFEPOINT("safepoint");

        private final String label;

        PauseKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Pause(Interval interval, PauseKind kind, String detail) {
        public long length() {
            return interval.length();
        }
    }

    /**
     * Everything known about one thread.
     *
     * @param thread  the thread
     * @param samples sampler observations in time order
     * @param blocks  blocking events in start order
     */
    public record ThreadTimeline(ThreadRef thread, List<Sample> samples, List<Block> blocks) {
        public ThreadTimeline {
            samples = List.copyOf(samples);
            blocks = List.copyOf(blocks);
        }
    }
}
