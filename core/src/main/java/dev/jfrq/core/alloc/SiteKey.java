// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.alloc;

import java.util.List;

import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Stack;

/**
 * What an allocation stack is called when sites are ranked, and therefore which stacks are
 * one row.
 *
 * <p>A single logical allocation reaches the sampler down many different paths: the same
 * method allocating on two of its lines, the same line reached through a different depth of
 * library frames, a string built by {@code substring} here and by {@code copyOfRange} there.
 * Ranked one path per row, a site that costs a fifth of the heap appears as a dozen rows of
 * two percent each and reads as noise. The key decides what is summed.
 *
 * <p>{@link #culpritMethod()} is the default: the innermost frame that is not JDK code,
 * without its line number. It needs nothing from the caller and merges the paths above the
 * method that did the allocating.
 *
 * <p>{@link #inPackages(List)} is what {@code --app} builds: the innermost frame in one of
 * the named packages, so the allocation is attributed to the code the reader owns rather
 * than to the library it called. That one cannot be the default, because nothing in a
 * recording says which packages those are — a JVM started from a jar records
 * {@code sun.java.command = app.jar} and no package name anywhere.
 */
@FunctionalInterface
public interface SiteKey {

    /** What a stack with no frames at all is called. */
    String NO_STACK = "(no stack)";

    /** The default key, held once: a strategy object belongs to the run, not to the call site. */
    SiteKey CULPRIT_METHOD = stack -> {
        final Frame culprit = stack.culpritOrNull();
        return culprit == null ? NO_STACK : method(culprit);
    };

    /** The name this stack is ranked under; stacks with the same name are one row. */
    String of(Stack stack);

    /** What the rows are, in the words the report prints above them. */
    default String description() {
        return "the innermost frame outside the JDK";
    }

    /** The innermost non-JDK frame, without its line: {@code com.example.Parser.parse}. */
    static SiteKey culpritMethod() {
        return CULPRIT_METHOD;
    }

    /**
     * The innermost frame whose class starts with one of {@code prefixes}, falling back to
     * {@link #culpritMethod()} for a stack that never enters them.
     */
    static SiteKey inPackages(final List<String> prefixes) {
        return new SiteKey() {

            @Override
            public String of(final Stack stack) {
                for (int i = 0, n = stack.depth(); i < n; i++) {
                    final Frame frame = stack.frameQuick(i);
                    for (final String prefix : prefixes) {
                        if (frame.type().startsWith(prefix)) {
                            return method(frame);
                        }
                    }
                }
                return CULPRIT_METHOD.of(stack);
            }

            @Override
            public String description() {
                return "the innermost frame in " + String.join(" or ", prefixes)
                        + ", or outside the JDK where there is none";
            }
        };
    }

    private static String method(final Frame frame) {
        return frame.type() + "." + frame.method();
    }
}
