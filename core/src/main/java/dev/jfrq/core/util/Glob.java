// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Shell-style glob for thread names: {@code *} matches any run of characters, {@code ?} one
 * character. A comma-separated list matches if any member matches. Matching is
 * case-sensitive because thread names are.
 */
public final class Glob implements Predicate<String> {

    private final List<Pattern> patterns;
    private final String source;

    private Glob(final String source, final List<Pattern> patterns) {
        this.source = source;
        this.patterns = patterns;
    }

    /** Compiles {@code "event-loop-*,worker-?"}. An empty or null spec matches nothing. */
    public static Glob of(final String spec) {
        final List<Pattern> patterns = new ArrayList<>();
        if (spec != null) {
            for (final String part : spec.split(",")) {
                final String p = part.trim();
                if (!p.isEmpty()) {
                    patterns.add(Pattern.compile(toRegex(p)));
                }
            }
        }
        return new Glob(spec == null ? "" : spec, List.copyOf(patterns));
    }

    /** A glob that matches every name. */
    public static Glob any() {
        return of("*");
    }

    static String toRegex(final String glob) {
        final StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            final char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.append('$').toString();
    }

    @Override
    public boolean test(final String name) {
        if (name == null) {
            return false;
        }
        for (final Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    @Override
    public String toString() {
        return source;
    }
}
