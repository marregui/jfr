// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.model;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;

/**
 * One stack frame: declaring type, method name and line. The frame kind
 * ({@code Interpreted}, {@code JIT compiled}, {@code Inlined}, {@code Native}) is kept
 * because a native top frame is how an idle event loop looks.
 */
public record Frame(String type, String method, int line, String kind) {

    public static Frame of(final RecordedFrame f) {
        final RecordedMethod m = f.getMethod();
        final String type = m != null && m.getType() != null ? m.getType().getName() : "?";
        final String method = m != null ? m.getName() : "?";
        return new Frame(type, method, f.getLineNumber(), f.getType());
    }

    /** {@code java.lang.Thread.sleep}. */
    public String qualifiedName() {
        return type + "." + method;
    }

    public boolean isNative() {
        return "Native".equals(kind);
    }

    /**
     * True for frames in the JDK and in the JVM's own support code, which are rarely what
     * the user wants to see named as the culprit.
     */
    public boolean isJdk() {
        return type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jdk.")
                || type.startsWith("sun.") || type.startsWith("com.sun.");
    }

    /** True for JVM-generated hidden classes such as lambda bodies. */
    public boolean isHidden() {
        return type.contains("$$Lambda") || type.contains("/0x");
    }

    /** {@code java.lang.Thread.sleep(Thread.java:509)} in the style of a stack trace line. */
    public String pretty() {
        if (isHidden()) {
            final int cut = type.indexOf("$$Lambda");
            final String shown = cut > 0 ? type.substring(0, cut + "$$Lambda".length()) : type;
            return shown + "." + method + "(lambda)";
        }
        String file = type.substring(type.lastIndexOf('.') + 1);
        final int inner = file.indexOf('$');
        if (inner > 0) {
            file = file.substring(0, inner);
        }
        final String location = line > 0 ? file + ".java:" + line : isNative() ? "Native Method" : file + ".java";
        return qualifiedName() + "(" + location + ")";
    }

    @Override
    @SuppressWarnings("NullableProblems") // Record.toString() carries an external @NotNull; this never returns null
    public String toString() {
        return pretty();
    }
}
