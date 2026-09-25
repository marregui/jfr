// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.model;

import jdk.jfr.consumer.RecordedFrame;

/**
 * One stack frame: declaring type, method name and line, and whether the method is native.
 *
 * <p>JFR also records how the method was running when the stack was taken
 * ({@code Interpreted}, {@code JIT compiled}, {@code Inlined}, {@code Native}). Only the
 * native bit is kept, because it is how a frame without a line prints
 * ({@code Native Method}). The other three say what the JIT had done with the method at
 * that instant, not where the code is: kept, the same line sampled before and after it was
 * compiled was two frames, so one site was split into several stacks that print
 * identically, and the biggest of them was not the one a reader would pick.
 *
 * @param isNative whether JFR recorded the frame as {@code Native}
 */
public record Frame(String type, String method, int line, boolean isNative) {

    /** The frame type JFR gives a native method. */
    public static final String NATIVE = "Native";

    /** A frame from the type JFR names it with; only {@link #NATIVE} is kept of it. */
    public Frame(final String type, final String method, final int line, final String kind) {
        this(type, method, line, isNativeKind(kind));
    }

    /** Whether a JFR frame type ({@link RecordedFrame#getType()}) is the native one. */
    public static boolean isNativeKind(final String kind) {
        return NATIVE.equals(kind);
    }

    /** {@code java.lang.Thread.sleep}. */
    public String qualifiedName() {
        return type + "." + method;
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

    /**
     * {@link #qualifiedName()} without the per-JVM address of a hidden class:
     * {@code dev.app.Handler$$Lambda.run}, the same in every run of the same code.
     */
    public String stableName() {
        return isHidden() ? stableType() + "." + method : qualifiedName();
    }

    private String stableType() {
        final int cut = type.indexOf("$$Lambda");
        return cut > 0 ? type.substring(0, cut + "$$Lambda".length()) : type;
    }

    /** {@code java.lang.Thread.sleep(Thread.java:509)} in the style of a stack trace line. */
    public String pretty() {
        if (isHidden()) {
            return stableType() + "." + method + "(lambda)";
        }
        String file = type.substring(type.lastIndexOf('.') + 1);
        final int inner = file.indexOf('$');
        if (inner > 0) {
            file = file.substring(0, inner);
        }
        final String location = line > 0 ? file + ".java:" + line : isNative ? "Native Method" : file + ".java";
        return qualifiedName() + "(" + location + ")";
    }

    @Override
    @SuppressWarnings("NullableProblems") // Record.toString() carries an external @NotNull; this never returns null
    public String toString() {
        return pretty();
    }
}
