// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Stack;

/**
 * Decides whether a sampled stack shows a thread at its idle point: the selector wait of
 * an event loop, or a park. The decision looks at the innermost few frames, because the
 * native wait is always at the top of the stack and the selector method just below it.
 *
 * <p>The defaults cover the JDK selectors on every platform and Netty's native
 * transports. Other loops (a hand-written poll loop, a queue take) are described with
 * {@link #of(String)} and a comma-separated list of regular expressions matched against
 * {@code declaring.type.methodName}.
 */
public final class IdleMatcher {

    /** Innermost frames examined for a sampled stack: the wait is at the top. */
    static final int DEPTH = 3;
    /**
     * Innermost frames examined for a blocking event. The pool's own idle frame sits under
     * the park and the queue: {@code Unsafe.park}, {@code LockSupport.park},
     * {@code ConditionObject.await}, {@code LinkedBlockingQueue.take},
     * {@code ThreadPoolExecutor.getTask} is already five deep.
     */
    static final int BLOCK_DEPTH = 8;

    /**
     * Stacks that mean "this thread is parked because there is no work", as opposed to
     * parked waiting for something a caller needs. Each names the <em>pool's own</em> idle
     * frame, never the queue it parks on and never the worker loop: a thread running a task
     * still has {@code ThreadPoolExecutor.runWorker} beneath it, and a request thread
     * waiting for a reply on a {@code SynchronousQueue} is a real wait, so neither can be
     * used to tell the two apart.
     */
    public static final List<String> WORK_WAIT_PATTERNS = List.of(
            "java\\.util\\.concurrent\\.ThreadPoolExecutor\\.getTask",
            "java\\.util\\.concurrent\\.ForkJoinPool\\.awaitWork",
            "java\\.util\\.concurrent\\.ForkJoinPool\\.managedBlock\\w*",
            "java\\.util\\.concurrent\\.ScheduledThreadPoolExecutor\\$DelayedWorkQueue\\.take",
            "io\\.netty\\.util\\.concurrent\\.SingleThreadEventExecutor\\.takeTask",
            "ch\\.qos\\.logback\\.core\\.AsyncAppenderBase\\$Worker\\.run");

    public static final List<String> DEFAULT_PATTERNS = List.of(
            "sun\\.nio\\.ch\\.(KQueue|EPoll|WEPoll|Poll|DevPoll)\\w*\\.(poll|wait|epollWait|kevent)\\w*",
            "sun\\.nio\\.ch\\.\\w*SelectorImpl\\.doSelect",
            "sun\\.nio\\.ch\\.SelectorImpl\\.(select|lockAndDoSelect)",
            "io\\.netty\\.channel\\.epoll\\.Native\\.epollWait\\w*",
            "io\\.netty\\.channel\\.kqueue\\.Native\\.keventWait",
            "io\\.netty\\.channel\\.uring\\.Native\\.\\w*(Wait|Enter)\\w*",
            "jdk\\.internal\\.misc\\.Unsafe\\.park",
            "java\\.util\\.concurrent\\.locks\\.LockSupport\\.park\\w*",
            "java\\.lang\\.Object\\.wait\\w*");

    private static final long IDLE = 1;
    private static final long BUSY = 0;

    private final Pattern[] patterns;
    private final String source;
    private final int depth;
    /** Frames recur across every sample; regex matching runs once per distinct frame (G-2.2). */
    private final ObjLongHashMap<Frame> decided = new ObjLongHashMap<>(1024);

    private IdleMatcher(final String source, final Pattern[] patterns, final int depth) {
        this.source = source;
        this.patterns = patterns;
        this.depth = depth;
    }

    public static IdleMatcher defaults() {
        return of(String.join(",", DEFAULT_PATTERNS));
    }

    /**
     * The matcher for blocking events rather than samples: whether a park or an
     * {@code Object.wait} is a worker with nothing to do. Looks deeper than
     * {@link #defaults()}, because the frame that decides is below the park.
     */
    public static IdleMatcher forWorkWaits() {
        return workWaits(String.join(",", WORK_WAIT_PATTERNS));
    }

    /** {@link #forWorkWaits()} with the caller's patterns in place of the defaults. */
    public static IdleMatcher workWaits(final String spec) {
        return new IdleMatcher(spec, compile(spec), BLOCK_DEPTH);
    }

    /** Compiles a comma-separated list of regular expressions; replaces the defaults. */
    public static IdleMatcher of(final String spec) {
        return new IdleMatcher(spec, compile(spec), DEPTH);
    }

    /** Matches nothing: the escape hatch for a report that should classify no stack at all. */
    public static IdleMatcher none() {
        return new IdleMatcher("none", new Pattern[0], 0);
    }

    /**
     * True for {@link #none()}. A caller that asks for no idle classification is asking for
     * all of it to stop, {@link Perch} included: the escape hatch is only an escape hatch if
     * nothing else puts a wait aside behind its back.
     */
    public boolean matchesNothing() {
        return patterns.length == 0;
    }

    private static Pattern[] compile(final String spec) {
        final List<Pattern> compiled = new ArrayList<>();
        for (final String p : spec.split(",")) {
            final String t = p.trim();
            if (!t.isEmpty()) {
                compiled.add(Pattern.compile(t));
            }
        }
        if (compiled.isEmpty()) {
            throw new IllegalArgumentException("idle pattern list is empty");
        }
        return compiled.toArray(new Pattern[0]);
    }

    public boolean isIdle(final Stack stack) {
        final int depth = Math.min(this.depth, stack.depth());
        for (int i = 0; i < depth; i++) {
            if (isIdle(stack.frameQuick(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isIdle(final Frame frame) {
        final int index = decided.keyIndex(frame);
        if (index < 0) {
            return decided.valueAtQuick(index) == IDLE;
        }
        long verdict = BUSY;
        final String name = frame.qualifiedName();
        for (final Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                verdict = IDLE;
                break;
            }
        }
        decided.putAt(index, frame, verdict);
        return verdict == IDLE;
    }

    @Override
    public String toString() {
        return source;
    }
}
