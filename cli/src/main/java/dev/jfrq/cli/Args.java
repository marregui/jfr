package dev.jfrq.cli;

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

    String positional(int index, String what) {
        if (index >= positional.size()) {
            throw new UsageException("missing " + what);
        }
        return positional.get(index);
    }

    Optional<String> option(String name) {
        return Optional.ofNullable(options.get(name));
    }

    String option(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    boolean flag(String name) {
        return flags.contains(name) && options.containsKey(name);
    }

    int intOption(String name, int fallback) {
        String v = options.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(v);
            if (n <= 0) {
                throw new UsageException("--" + name + " must be positive");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " is not a number: " + v);
        }
    }

    long durationOption(String name, String fallback) {
        String v = options.getOrDefault(name, fallback);
        try {
            long nanos = Durations.parseNanos(v);
            if (nanos <= 0) {
                throw new UsageException("--" + name + " must be positive");
            }
            return nanos;
        } catch (IllegalArgumentException e) {
            throw new UsageException("--" + name + ": " + e.getMessage());
        }
    }

    /** A usage error: the message is printed with the command's help and exit code 2. */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
