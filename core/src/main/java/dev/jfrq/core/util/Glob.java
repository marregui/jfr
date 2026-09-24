// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Shell-style glob for thread and lock names: {@code *} matches any run of characters,
 * {@code ?} one character, {@code [abc]} / {@code [a-z]} one character of a set and
 * {@code [!abc]} / {@code [^abc]} one character outside it; a backslash makes the next
 * character literal, and a {@code [} with no closing {@code ]} is literal too. Every
 * character is a character, line breaks included: a thread name is whatever the
 * application set. A comma-separated list matches if any member matches (a comma inside
 * brackets is part of the set). Matching is case-sensitive because thread names are.
 */
public final class Glob implements Predicate<String> {

    private final List<Pattern> patterns;
    private final String source;

    private Glob(final String source, final List<Pattern> patterns) {
        this.source = source;
        this.patterns = patterns;
    }

    /** Compiles {@code "event-loop-*,worker-[0-3]"}. An empty or null spec matches nothing. */
    public static Glob of(final String spec) {
        final List<Pattern> patterns = new ArrayList<>();
        if (spec != null) {
            int from = 0;
            for (int i = 0; i <= spec.length(); i++) {
                if (i == spec.length() || spec.charAt(i) == ',') {
                    final String p = spec.substring(from, i).trim();
                    if (!p.isEmpty()) {
                        patterns.add(Pattern.compile(toRegex(p), Pattern.DOTALL));
                    }
                    from = i + 1;
                } else if (spec.charAt(i) == '\\' && i + 1 < spec.length()) {
                    i++;
                } else if (spec.charAt(i) == '[') {
                    final int close = classEnd(spec, i);
                    if (close > 0) {
                        i = close;
                    }
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
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        i++;
                    }
                    quote(sb, glob.charAt(i));
                }
                case '[' -> {
                    final int close = classEnd(glob, i);
                    if (close < 0) {
                        quote(sb, c);
                    } else {
                        characterClass(sb, glob, i + 1, close);
                        i = close;
                    }
                }
                default -> quote(sb, c);
            }
        }
        return sb.append('$').toString();
    }

    /**
     * The index of the {@code ]} that closes the class opened at {@code open}, or -1 when
     * there is none. A {@code ]} right after the opening (or after its {@code !}/{@code ^})
     * is a member, as in the shell.
     */
    private static int classEnd(final String glob, final int open) {
        int i = open + 1;
        if (i < glob.length() && (glob.charAt(i) == '!' || glob.charAt(i) == '^')) {
            i++;
        }
        if (i < glob.length() && glob.charAt(i) == ']') {
            i++;
        }
        for (; i < glob.length(); i++) {
            final char c = glob.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The members in {@code [lo, hi)} as a regex class. Every member is written as an
     * escape, so no character in a thread name can mean anything to the regex engine; a
     * reversed range ({@code z-a}) has no members, as in the shell.
     */
    private static void characterClass(final StringBuilder sb, final String glob, final int lo, final int hi) {
        int i = lo;
        final boolean negated = i < hi && (glob.charAt(i) == '!' || glob.charAt(i) == '^');
        if (negated) {
            i++;
        }
        final StringBuilder members = new StringBuilder();
        while (i < hi) {
            int c = glob.charAt(i);
            if (c == '\\' && i + 1 < hi) {
                c = glob.charAt(++i);
            }
            i++;
            if (i + 1 < hi && glob.charAt(i) == '-') {
                int end = glob.charAt(i + 1);
                i += 2;
                if (end == '\\' && i < hi) {
                    end = glob.charAt(i++);
                }
                if (c <= end) {
                    escape(members, c).append('-');
                    escape(members, end);
                }
            } else {
                escape(members, c);
            }
        }
        if (members.isEmpty()) {
            // Nothing in the set: it matches no character, and its complement every character.
            sb.append(negated ? "." : "(?!)");
        } else {
            sb.append('[').append(negated ? "^" : "").append(members).append(']');
        }
    }

    private static StringBuilder escape(final StringBuilder sb, final int c) {
        return sb.append("\\x{").append(Integer.toHexString(c)).append('}');
    }

    private static void quote(final StringBuilder sb, final char c) {
        sb.append(Pattern.quote(String.valueOf(c)));
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
