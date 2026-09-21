// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.util;

/**
 * Turns JVM descriptor-style class names as recorded by JFR ({@code [B},
 * {@code [Ljava.lang.String;}) into the source form people read ({@code byte[]},
 * {@code java.lang.String[]}).
 */
public final class ClassNames {

    private ClassNames() {
    }

    public static String pretty(final String jvmName) {
        if (jvmName == null || jvmName.isEmpty()) {
            return "?";
        }
        int dims = 0;
        while (dims < jvmName.length() && jvmName.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0) {
            return jvmName;
        }
        final String base = jvmName.substring(dims);
        final String element = switch (base) {
            case "B" -> "byte";
            case "C" -> "char";
            case "D" -> "double";
            case "F" -> "float";
            case "I" -> "int";
            case "J" -> "long";
            case "S" -> "short";
            case "Z" -> "boolean";
            default -> {
                if (base.startsWith("L") && base.endsWith(";")) {
                    yield base.substring(1, base.length() - 1);
                }
                yield base;
            }
        };
        return element + "[]".repeat(dims);
    }

    /** {@code java.util.HashMap$Node} to {@code HashMap$Node}; arrays keep their brackets. */
    public static String simple(final String name) {
        final String pretty = pretty(name);
        final int dot = pretty.lastIndexOf('.');
        return dot < 0 ? pretty : pretty.substring(dot + 1);
    }
}
