// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.cli;

import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.jfrq.core.util.Durations;

/**
 * Command-line arguments: positionals plus {@code --name value} / {@code --name=value}
 * options and {@code --flag} switches. Small enough that a library is not worth the
 * dependency. Unknown options are an error, so typos never pass silently.
 */
final class Args {

    private final List<String> positional = new ArrayList<>();
    private final Map<String, String> options = new LinkedHashMap<>();
    private final Set<String> flags;

    /** Parses {@code argv}; {@code valued} names options that take a value, {@code flags} those that do not. */
    static Args parse(String[] argv, Set<String> valued, Set<String> flags) {
        Args a = new Args(flags);
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (!arg.startsWith("--")) {
                a.positional.add(arg);
                continue;
            }
            String name = arg.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (flags.contains(name)) {
                if (value != null) {
                    throw new UsageException("--" + name + " takes no value");
                }
                a.options.put(name, "true");
            } else if (valued.contains(name)) {
                if (value == null) {
                    if (i + 1 >= argv.length) {
                        throw new UsageException("--" + name + " needs a value");
                    }
                    value = argv[++i];
                }
                a.options.put(name, value);
            } else {
                throw new UsageException("unknown option --" + name);
            }
        }
        return a;
    }

    private Args(Set<String> flags) {
        this.flags = flags;
    }

    List<String> positional() {
        return positional;
    }

    /** The first positional argument, or a usage error naming what is missing. */
    String first(String what) {
        if (positional.isEmpty()) {
            throw new UsageException("missing " + what);
        }
        return positional.getFirst();
    }

    Optional<String> option(String name) {
        return Optional.ofNullable(options.get(name));
    }

    boolean flag(String name) {
        return flags.contains(name) && options.containsKey(name);
    }

    /** {@code --top N}: rows per table, a positive number, 15 by default. */
    int top() {
        String v = options.get("top");
        if (v == null) {
            return 15;
        }
        try {
            int n = Integer.parseInt(v);
            if (n <= 0) {
                throw new UsageException("--top must be positive");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException("--top is not a number: " + v);
        }
    }

    long durationOption(String name, String fallback, boolean zeroAllowed) {
        String v = options.getOrDefault(name, fallback);
        if (!v.isEmpty() && Character.isDigit(v.charAt(v.length() - 1)) && !v.trim().equals("0")) {
            // A bare number would be nanoseconds, which nobody means on a command line.
            throw new UsageException("--" + name + " needs a unit: " + v + "ms, " + v + "s ...");
        }
        try {
            long nanos = Durations.parseNanos(v);
            if (nanos < 0 || (nanos == 0 && !zeroAllowed)) {
                throw new UsageException("--" + name + " must be " + (zeroAllowed ? "zero or more" : "positive"));
            }
            return nanos;
        } catch (IllegalArgumentException e) {
            throw new UsageException("--" + name + ": " + e.getMessage());
        }
    }

    /** A usage error: the message is printed with the command's help and exit code 2. */
    static final class UsageException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
