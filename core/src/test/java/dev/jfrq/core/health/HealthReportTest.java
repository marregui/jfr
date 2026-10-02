// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.health;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.health.HealthReport.Series;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
import org.junit.jupiter.api.Test;

/** What {@link HealthReport} says about itself, on hand-built reports with exact values. */
class HealthReportTest {

    static final long S = 1_000_000_000L;
    static final long MB = 1_000_000L;
    static final String MAC = "uname: Darwin 27.0.0 Darwin Kernel Version 27.0.0 arm64";

    static RecordingInfo info(final Map<String, Map<String, String>> settings) {
        return new RecordingInfo(Path.of("a.jfr"), new Interval(10 * S, 110 * S), 1, Map.of(), settings, Set.of(),
                List.of());
    }

    static HealthReport report(final RecordingInfo info, final List<Series> trends, final long throwSamples,
                               final ThreadCpu.Result cpu) {
        final HealthReport.Gc gc = new HealthReport.Gc(Map.of("G1New", 4L), Map.of("G1 Evacuation Pause", 4L), 0,
                2 * S / 100, 30 * MB, 12, Nulls.LONG_NULL, Nulls.LONG_NULL);
        final HealthReport.Throwables t = new HealthReport.Throwables(500, 50 * S, throwSamples, null, List.of(),
                List.of(), Map.of());
        return new HealthReport(info, List.of(), gc, trends, new HealthReport.Threads(7, 30), t, cpu, List.of(),
                List.of());
    }

    static Series bytes(final String name, final double start, final double end, final double floorFirst,
                        final double floorLast) {
        return new Series(name, Series.Unit.BYTES, 9, start, end, Math.min(start, end), Math.max(start, end),
                (start + end) / 2, floorFirst, floorLast);
    }

    static Series fraction(final String name, final double mean) {
        return new Series(name, Series.Unit.FRACTION, 9, mean, mean, mean, mean, mean, mean, mean);
    }

    @Test
    void anEventTheSettingsTurnedOffIsSaidWithTheSettingThatTurnsItOn() {
        final HealthReport off = report(info(Map.of("jdk.JavaExceptionThrow", Map.of("enabled", "false"))), List.of(),
                0, ThreadCpu.Result.UNKNOWN);
        assertTrue(off.isThrowEventOff());
        assertTrue(off.noThrows().contains(HealthReport.ENABLE_THROWS), off.noThrows());
        assertTrue(off.noThrows().contains("jcmd <pid> JFR.start"), off.noThrows());
        // Without settings, or with the event on, an empty file says nothing about why.
        final HealthReport unknown = report(info(Map.of()), List.of(), 0, ThreadCpu.Result.UNKNOWN);
        assertFalse(unknown.isThrowEventOff());
        assertEquals(HealthReport.NO_THROWS, unknown.noThrows());
        final HealthReport on = report(info(Map.of("jdk.JavaExceptionThrow", Map.of("enabled", "true"))), List.of(),
                0, ThreadCpu.Result.UNKNOWN);
        assertFalse(on.isThrowEventOff());
        // A setting map for the type without "enabled" in it is not a setting that says off.
        assertFalse(report(info(Map.of("jdk.JavaExceptionThrow", Map.of("throttle", "100/s"))), List.of(), 0,
                ThreadCpu.Result.UNKNOWN).isThrowEventOff());
    }

    @Test
    void aRisingHeapFloorSaysWhereTheAnswerIsAndAFlatOneSaysNothing() {
        final HealthReport rising = report(info(Map.of()), List.of(bytes(HealthReport.HEAP_AFTER_GC, 20 * MB,
                400 * MB, 18 * MB, 301 * MB)), 0, ThreadCpu.Result.UNKNOWN);
        assertTrue(rising.heapNote().startsWith("The floor of heap after GC rose by 283 MB."), rising.heapNote());
        assertTrue(rising.heapNote().contains("GC.class_histogram"), rising.heapNote());
        assertEquals("", report(info(Map.of()), List.of(bytes(HealthReport.HEAP_AFTER_GC, 20 * MB, 20 * MB, 18 * MB,
                18 * MB)), 0, ThreadCpu.Result.UNKNOWN).heapNote());
        // A rise the trends round away is not one: 748 MB to 748 MB on a flat heap, 204 KB apart.
        assertEquals("", report(info(Map.of()), List.of(bytes(HealthReport.HEAP_AFTER_GC, 754 * MB, 754 * MB,
                748 * MB, 748 * MB + 204_000)), 0, ThreadCpu.Result.UNKNOWN).heapNote());
        // Under three points there are no floors, and nothing to say.
        assertEquals("", report(info(Map.of()), List.of(bytes(HealthReport.HEAP_AFTER_GC, 20 * MB, 30 * MB,
                Double.NaN, Double.NaN)), 0, ThreadCpu.Result.UNKNOWN).heapNote());
        assertEquals("", report(info(Map.of()), List.of(), 0, ThreadCpu.Result.UNKNOWN).heapNote());
    }

    @Test
    void aJvmTotalBelowItsOwnThreadsIsWarnedAboutAndAConsistentOneIsNot() {
        final ThreadCpu.Result threads = new ThreadCpu.Result(Map.of(new ThreadRef(1, "t"), 0.086), 0.086, 100, 10);
        final String warning = HealthCollector.jvmCpuWarning(List.of(fraction(HealthReport.JVM_CPU, 0.002)), threads,
                MAC);
        assertTrue(warning.startsWith("JVM CPU (jdk.CPULoad) averages 0.2% of the JVM's CPUs, below the 8.6% its own "
                + "Java threads used"), warning);
        // A JDK 25 node: threads a little under the JVM, which also runs the collector's threads.
        final ThreadCpu.Result consistent = new ThreadCpu.Result(Map.of(), 0.042, 100, 10);
        assertNull(HealthCollector.jvmCpuWarning(List.of(fraction(HealthReport.JVM_CPU, 0.046)), consistent,
                MAC));
        // Within half again plus a point of the machine, two figures read over different stretches agree.
        assertNull(HealthCollector.jvmCpuWarning(List.of(fraction(HealthReport.JVM_CPU, 0.01)),
                new ThreadCpu.Result(Map.of(), 0.0249, 1, 1), MAC));
        assertNull(HealthCollector.jvmCpuWarning(List.of(), threads, MAC));
        assertNull(HealthCollector.jvmCpuWarning(List.of(fraction(HealthReport.JVM_CPU, 0.002)),
                ThreadCpu.Result.UNKNOWN, MAC));
        // On Linux the JVM's figure divides by the host's CPUs, the threads' by the JVM's own count:
        // a JVM held to 2 of 12 CPUs reads a sixth of its threads with nothing wrong. No warning
        // there, nor where the system is unknown.
        assertNull(HealthCollector.jvmCpuWarning(List.of(fraction(HealthReport.JVM_CPU, 0.002)), threads,
                "uname: Linux 6.8.0-45-generic #45-Ubuntu SMP x86_64"));
        assertNull(HealthCollector.jvmCpuWarning(List.of(fraction(HealthReport.JVM_CPU, 0.002)), threads, null));
    }

    @Test
    void theThreadCpuLineSaysWhatWasLeftOut() {
        final HealthReport r = report(info(Map.of()), List.of(), 0, new ThreadCpu.Result(Map.of(), 0.0857, 7000, 1064));
        assertEquals("Java threads used 8.6% of the JVM's CPUs (jdk.ThreadCPULoad, 7000 readings; 1064 readings "
                + "left out: of threads whose start is not in the file, read before any evaluation the file "
                + "shows (alive before the recording) or in a recording without jdk.ThreadStart, which cover a "
                + "stretch not in the file; and of threads native code attached, up to the first below one core, which "
                + "hold CPU the native thread used before the attach)", r.threadCpuLine());
        assertEquals("", HealthReport.leftOut(0));
        assertEquals("", report(info(Map.of()), List.of(), 0, ThreadCpu.Result.UNKNOWN).threadCpuLine());
    }

    @Test
    void aComparedRowHasEveryColumnAndADashForWhatTheRecordingLacks() {
        final HealthReport full = report(info(Map.of()), List.of(bytes(HealthReport.HEAP_AFTER_GC, 20 * MB, 400 * MB,
                        18 * MB, 301 * MB), bytes(HealthReport.RESIDENT_SET, 155 * MB, 2230 * MB, 0, 0),
                new Series(HealthReport.LIVE_THREADS, Series.Unit.COUNT, 9, 9, 169, 9, 174, 161, 9, 164),
                fraction(HealthReport.JVM_CPU, 0.002)), 3, new ThreadCpu.Result(Map.of(), 0.086, 1, 1));
        final Object[] cells = full.comparedCells("n1.jfr");
        assertEquals(HealthReport.COMPARED.size(), cells.length);
        assertArrayEquals(new Object[] {"n1.jfr", Instant.ofEpochSecond(10), "1m40s", 0, "0.02%", "30.0 ms",
                "18.0 MB → 301 MB", "155 MB → 2.23 GB", "9 → 169", 7L, "0.2%", "8.6%", "10.0"}, cells);
        final HealthReport bare = new HealthReport(info(Map.of()), List.of(), new HealthReport.Gc(Map.of(), Map.of(),
                0, 0, 0, Nulls.INT_NULL, Nulls.LONG_NULL, Nulls.LONG_NULL), List.of(), new HealthReport.Threads(
                Nulls.LONG_NULL, Nulls.LONG_NULL), new HealthReport.Throwables(Nulls.LONG_NULL, 0, 0, null, List.of(),
                List.of(), Map.of()), ThreadCpu.Result.UNKNOWN, List.of(), List.of());
        assertArrayEquals(new Object[] {"x", Instant.ofEpochSecond(10), "1m40s", 0, "—", "—", "—", "—", "—", "—", "—",
                "—", "—"}, bare.comparedCells("x"));
        assertNull(bare.series(HealthReport.HEAP_AFTER_GC));
    }
}
