package dev.jfrq.core.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

/**
 * A stack trace with the innermost frame first. Value semantics, so stacks can key maps
 * (allocation sites, busy-run histograms); the hash is computed once, because a stack is
 * looked up far more often than it is created and the same stack recurs thousands of
 * times in a recording. {@link Interner} makes recurring stacks share one instance.
 */
public final class Stack {

    public static final Stack EMPTY = new Stack(new Frame[0], false);

    private final Frame[] frames;
    private final boolean truncated;
    private final int hash;

    Stack(Frame[] frames, boolean truncated) {
        this.frames = frames;
        this.truncated = truncated;
        this.hash = 31 * Arrays.hashCode(frames) + (truncated ? 1 : 0);
    }

    /**
     * @param frames    innermost first; may be empty when JFR could not walk the stack
     * @param truncated true when JFR cut the stack at its depth limit (default 64)
     */
    public Stack(List<Frame> frames, boolean truncated) {
        this(frames.toArray(new Frame[0]), truncated);
    }

    /** Builds a stack without interning; {@link Interner#stack(RecordedStackTrace)} is preferred in bulk. */
    public static Stack of(RecordedStackTrace trace) {
        if (trace == null) {
            return EMPTY;
        }
        List<RecordedFrame> recorded = trace.getFrames();
        Frame[] frames = new Frame[recorded.size()];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = Frame.of(recorded.get(i));
        }
        return new Stack(frames, trace.isTruncated());
    }

    /** Innermost first. */
    public List<Frame> frames() {
        return List.of(frames);
    }

    public boolean truncated() {
        return truncated;
    }

    public int depth() {
        return frames.length;
    }

    public boolean isEmpty() {
        return frames.length == 0;
    }

    public Optional<Frame> top() {
        return frames.length == 0 ? Optional.empty() : Optional.of(frames[0]);
    }

    /**
     * The innermost frame that is not JDK code: the best single name for "what the
     * application was doing". Falls back to the top frame when the whole stack is JDK code.
     */
    public Optional<Frame> culprit() {
        for (Frame f : frames) {
            if (!f.isJdk()) {
                return Optional.of(f);
            }
        }
        return top();
    }

    /** The first {@code n} frames, innermost first, as a new stack. */
    public Stack head(int n) {
        if (frames.length <= n) {
            return this;
        }
        return new Stack(Arrays.copyOf(frames, n), true);
    }

    /**
     * Multi-line rendering in stack-trace style with the given indent: the innermost
     * {@code maxFrames} frames, and if the {@linkplain #culprit() culprit} lies deeper, an
     * elision followed by the culprit's own line, so the application frame is always visible.
     */
    public String pretty(String indent, int maxFrames) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(maxFrames, frames.length);
        for (int i = 0; i < shown; i++) {
            sb.append(indent).append("at ").append(frames[i].pretty()).append('\n');
        }
        int culpritIndex = -1;
        for (int i = 0; i < frames.length; i++) {
            if (!frames[i].isJdk()) {
                culpritIndex = i;
                break;
            }
        }
        if (culpritIndex >= shown) {
            if (culpritIndex > shown) {
                sb.append(indent).append("... ").append(culpritIndex - shown).append(" more").append('\n');
            }
            sb.append(indent).append("at ").append(frames[culpritIndex].pretty()).append('\n');
            shown = culpritIndex + 1;
        }
        if (shown < frames.length || (truncated && shown == frames.length)) {
            sb.append(indent).append("... ").append(frames.length - shown).append(" more").append('\n');
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Stack s && hash == s.hash && truncated == s.truncated && Arrays.equals(frames, s.frames);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "Stack" + Arrays.toString(frames) + (truncated ? "..." : "");
    }
}
