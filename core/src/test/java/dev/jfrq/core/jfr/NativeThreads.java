// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * A thread the JVM did not make, calling into Java: native code creates it, and its first
 * call through an upcall stub attaches it to the JVM, as JNI's {@code AttachCurrentThread}
 * does for a library's callback thread. POSIX only ({@code pthread_create}); the tests that
 * use it skip on Windows.
 */
public final class NativeThreads {

    private static volatile String attachedName;
    private static volatile long burnMillis;

    private NativeThreads() {
    }

    /** Whether this platform has {@code pthread_create} to make the thread with. */
    public static boolean available() {
        return !System.getProperty("os.name", "").startsWith("Windows");
    }

    /**
     * Starts one native thread that calls into Java once and ends, waits for it, and returns
     * the name the JVM gave it when it attached.
     */
    public static String attachOnce() throws Exception {
        return attachOnce(0);
    }

    /** {@link #attachOnce()}, with the attached thread burning a core for {@code millis} before it returns. */
    @SuppressWarnings("restricted") // a native thread is the point; the test JVM grants native access
    public static String attachOnce(final long millis) throws Exception {
        burnMillis = millis;
        final Linker linker = Linker.nativeLinker();
        final SymbolLookup libc = linker.defaultLookup();
        final MethodHandle body = MethodHandles.lookup().findStatic(NativeThreads.class, "body",
                MethodType.methodType(MemorySegment.class, MemorySegment.class));
        final MethodHandle create = linker.downcallHandle(libc.find("pthread_create").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        final MethodHandle join = linker.downcallHandle(libc.find("pthread_join").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment stub = linker.upcallStub(body,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS), arena);
            // pthread_t is an integer on Linux and a pointer on macOS: eight bytes on both.
            final MemorySegment id = arena.allocate(ValueLayout.JAVA_LONG);
            final int created;
            final int joined;
            try {
                created = (int) create.invokeExact(id, MemorySegment.NULL, stub, MemorySegment.NULL);
                joined = created != 0 ? 0 : (int) join.invokeExact(id.get(ValueLayout.JAVA_LONG, 0), MemorySegment.NULL);
            } catch (final Exception | Error e) {
                throw e;
            } catch (final Throwable t) {
                // invokeExact declares Throwable; nothing else can come out of a downcall.
                throw new IllegalStateException(t);
            }
            if (created != 0 || joined != 0) {
                throw new IllegalStateException("pthread_create returned " + created + ", pthread_join " + joined);
            }
        }
        return attachedName;
    }

    /** What the native thread runs, attached. */
    private static MemorySegment body(final MemorySegment ignored) {
        attachedName = Thread.currentThread().getName();
        JfrFixtures.burn(burnMillis);
        return MemorySegment.NULL;
    }
}
