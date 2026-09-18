package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    /** Innermost frames examined. */
    static final int DEPTH = 3;

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

    private final List<Pattern> patterns;
    private final String source;
    /** Frames recur across every sample; regex matching runs once per distinct frame. */
    private final Map<Frame, Boolean> decided = new HashMap<>(1024);

    private IdleMatcher(String source, List<Pattern> patterns) {
        this.source = source;
        this.patterns = patterns;
    }

    public static IdleMatcher defaults() {
        return of(String.join(",", DEFAULT_PATTERNS));
    }

    /** Compiles a comma-separated list of regular expressions; replaces the defaults. */
    public static IdleMatcher of(String spec) {
        List<Pattern> compiled = new ArrayList<>();
        for (String p : spec.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                compiled.add(Pattern.compile(t));
            }
        }
        if (compiled.isEmpty()) {
            throw new IllegalArgumentException("idle pattern list is empty");
        }
        return new IdleMatcher(spec, List.copyOf(compiled));
    }

    public boolean isIdle(Stack stack) {
        List<Frame> frames = stack.frames();
        int depth = Math.min(DEPTH, frames.size());
        for (int i = 0; i < depth; i++) {
            if (isIdle(frames.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isIdle(Frame frame) {
        Boolean known = decided.get(frame);
        if (known != null) {
            return known;
        }
        boolean idle = false;
        String name = frame.qualifiedName();
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                idle = true;
                break;
            }
        }
        decided.put(frame, idle);
        return idle;
    }

    @Override
    public String toString() {
        return source;
    }
}
