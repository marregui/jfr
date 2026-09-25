// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.health.HealthReport.ClassRow;
import dev.jfrq.core.health.HealthReport.Finding;
import dev.jfrq.core.health.HealthReport.Series;
import dev.jfrq.core.health.HealthReport.SiteRow;
import dev.jfrq.core.jfr.JfrFixtures;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.report.Html;
import dev.jfrq.core.report.Json;
import dev.jfrq.core.report.JsonParser;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HealthCollector} on one real recording of a JVM in trouble: two {@code System.gc()}
 * calls, an {@code OutOfMemoryError} constructed the way the JDK's direct-memory code does,
 * fifty {@code Error}s, and a few hundred exceptions made at one site through a factory and
 * a base class of their own. The counts the test controls are exact; what the JVM decides
 * (how many young collections, the CPU) is only checked for shape.
 */
class HealthCollectorTest {

    static final int THROWN = 400;
    static final int ERRORS = 50;
    static final String OOM_MESSAGE = "Cannot reserve 1048576 bytes of direct buffer memory (a test)";
    static Path file;

    @TempDir
    static Path dir;

    static HealthReport report;

    /** An application's own base exception: its constructor is part of every subclass's construction. */
    static class BaseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BaseException(final String message) {
            super(message);
        }
    }

    static final class LeafException extends BaseException {
        private static final long serialVersionUID = 1L;

        private LeafException(final String message) {
            super(message);
        }

        static LeafException of(final int i) {
            return new LeafException("request " + i + " refused");
        }
    }

    /** The site every {@link LeafException} is made at. */
    static int makeTrouble() {
        int made = 0;
        for (int i = 0; i < THROWN; i++) {
            try {
                throw LeafException.of(i);
            } catch (final LeafException e) {
                made++;
            }
        }
        return made;
    }

    @BeforeAll
    static void record() throws Exception {
        file = JfrFixtures.record(dir, "health", HealthCollectorTest::settings, () -> {
            // The throwable count is the difference of two statistics readings: one before the body.
            JfrFixtures.sleep(150);
            assertEquals(THROWN, makeTrouble());
            for (int i = 0; i < ERRORS; i++) {
                assertNotNull(new AssertionError("error " + i));
            }
            assertNotNull(new OutOfMemoryError(OOM_MESSAGE));
            System.gc();
            JfrFixtures.sleep(150);
            System.gc();
            JfrFixtures.sleep(150);
        });
        final HealthCollector health = new HealthCollector();
        JfrReader.read(file, health);
        report = health.report();
    }

    static void settings(final Recording r) {
        for (final String type : List.of("jdk.GarbageCollection", "jdk.OldGarbageCollection", "jdk.GCHeapSummary",
                "jdk.JavaErrorThrow", "jdk.EvacuationFailed")) {
            r.enable(type);
        }
        for (final String type : List.of("jdk.GCConfiguration", "jdk.GCHeapConfiguration", "jdk.CPULoad",
                "jdk.JavaThreadStatistics", "jdk.ResidentSetSize", "jdk.ExceptionStatistics")) {
            r.enable(type).withPeriod(Duration.ofMillis(50));
        }
        r.enable("jdk.JavaExceptionThrow").withStackTrace().with("throttle", "off");
    }

    @Test
    void theFindingsAreTheOnesTheJvmReportedInTheirRankOrder() {
        final List<Finding.Kind> kinds = new ArrayList<>();
        for (final Finding f : report.findings()) {
            kinds.add(f.kind());
        }
        // Whatever else this JVM did (a humongous allocation of its own), these are there, in this order.
        final List<Finding.Kind> expected = List.of(Finding.Kind.OUT_OF_MEMORY, Finding.Kind.FULL_GC,
                Finding.Kind.SYSTEM_GC);
        assertEquals(expected, kinds.stream().filter(expected::contains).toList(), kinds.toString());
        for (int i = 1; i < kinds.size(); i++) {
            assertTrue(kinds.get(i - 1).compareTo(kinds.get(i)) < 0, kinds.toString());
        }
        assertEquals(1, finding(Finding.Kind.OUT_OF_MEMORY).count());
        assertEquals(2, finding(Finding.Kind.SYSTEM_GC).count());
        assertTrue(finding(Finding.Kind.FULL_GC).count() >= 2);
    }

    @Test
    void aFindingSaysWhenItHappenedWithinTheRecording() {
        final RecordingInfo info = report.info();
        final Finding gc = finding(Finding.Kind.SYSTEM_GC);
        assertTrue(info.startNanos() <= gc.firstNanos() && gc.firstNanos() < gc.lastNanos()
                && gc.lastNanos() <= info.span().end(), gc.toString());
        assertTrue(gc.text().startsWith("2 collections caused by System.gc(), from +"), gc.text());
        final Finding oom = finding(Finding.Kind.OUT_OF_MEMORY);
        assertEquals(oom.firstNanos(), oom.lastNanos());
        assertTrue(oom.text().startsWith("1 OutOfMemoryError created, at +"), oom.text());
        assertTrue(oom.text().contains("\"" + OOM_MESSAGE + "\""), oom.text());
    }

    @Test
    void theGcAccountAddsUpAndKeepsItsOrder() {
        final HealthReport.Gc gc = report.gc();
        assertEquals(2L, gc.causes().get("System.gc()"));
        long sum = 0;
        long previous = Long.MAX_VALUE;
        for (final long n : gc.collections().values()) {
            assertTrue(n <= previous, gc.collections().toString());
            previous = n;
            sum += n;
        }
        assertEquals(sum, gc.count());
        final long concurrent = gc.collections().getOrDefault(HealthReport.CONCURRENT_CYCLE, 0L);
        assertEquals(gc.count() - concurrent, gc.causes().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(concurrent == 0, gc.causesNote().isEmpty());
        assertTrue(gc.pauseNanos() > 0 && gc.longestPauseNanos() > 0 && gc.longestPauseNanos() <= gc.pauseNanos());
        assertTrue(gc.gcTimeRatio() > 0, "GCConfiguration read");
        assertTrue(gc.maxHeapBytes() > 0, "GCHeapConfiguration read");
    }

    @Test
    void everyTrendTheSettingsAskedForIsThere() {
        final List<String> names = report.trends().stream().map(Series::name).toList();
        // In this order; the CPU load was missing from one run of a recording this short, under a
        // full parallel build, so it is not required.
        final List<String> all = List.of("Heap after GC", "Resident set", "Live threads", "JVM CPU", "Machine CPU");
        assertEquals(all.stream().filter(names::contains).toList(), names);
        assertTrue(names.containsAll(all.subList(0, 3)), names.toString());
        for (final Series s : report.trends()) {
            assertTrue(s.points() >= 1, s.toString());
            assertTrue(s.min() <= s.mean() && s.mean() <= s.max(), s.toString());
            assertTrue(s.min() <= s.start() && s.start() <= s.max() && s.min() <= s.end() && s.end() <= s.max());
            if (s.points() >= 3) {
                assertTrue(s.floorFirst() >= s.min() && s.floorLast() >= s.min(), s.toString());
            } else {
                assertTrue(Double.isNaN(s.floorFirst()) && Double.isNaN(s.floorLast()), s.toString());
            }
        }
        assertTrue(report.threads().peak() >= 1);
        assertTrue(report.threads().started() >= 0);
    }

    @Test
    void everyThrowableMadeIsCountedAndTheSiteIsTheCodeThatMadeIt() {
        final HealthReport.Throwables t = report.throwables();
        assertNull(t.throttle(), "throttle off");
        // Created counts between the first and last statistics reading, which bracket the body.
        assertTrue(t.created() >= THROWN + 2, String.valueOf(t.created()));
        assertTrue(t.rate() > 0);
        final String leaf = LeafException.class.getName();
        final ClassRow row = t.byClass().stream().filter(c -> c.className().equals(leaf)).findFirst().orElseThrow();
        assertEquals(THROWN, row.samples());
        // One of them, in file order, which is not time order.
        assertTrue(row.message().matches("request \\d+ refused"), row.message());
        assertEquals((double) THROWN / t.samples(), row.share(), 1e-9);
        final SiteRow site = t.bySite().stream().filter(s -> s.className().equals(leaf)).findFirst().orElseThrow();
        // Past Throwable.<init>, BaseException.<init>, LeafException.<init> and LeafException.of.
        assertEquals(HealthCollectorTest.class.getName() + ".makeTrouble", site.site());
        assertEquals(THROWN, site.samples());
        assertEquals("makeTrouble", site.stack().frames().getFirst().method());
        // An Error is emitted twice, from Throwable's constructor and from Error's: counted once.
        assertEquals(ERRORS, t.byClass().stream().filter(c -> c.className().equals("java.lang.AssertionError"))
                .findFirst().orElseThrow().samples());
        assertEquals(ERRORS, t.errors().get("java.lang.AssertionError"));
        // Error's constructor skips an OutOfMemoryError; Throwable's does not.
        assertFalse(t.errors().containsKey("java.lang.OutOfMemoryError"));
        assertEquals(1, t.byClass().stream().filter(c -> c.className().equals("java.lang.OutOfMemoryError"))
                .findFirst().orElseThrow().samples());
    }

    /**
     * The JDK's running total counts an Error twice too; corrected, it is exactly the number
     * of throwables whose event falls between the first and last reading.
     */
    @Test
    void theCountCreatedIsExactOnceErrorsAreCountedOnce() throws Exception {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        final List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        for (final RecordedEvent e : events) {
            if (e.getEventType().getName().equals("jdk.ExceptionStatistics")) {
                final long t = e.getStartTime().getEpochSecond() * 1_000_000_000L + e.getStartTime().getNano();
                first = Math.min(first, t);
                last = Math.max(last, t);
            }
        }
        long inside = 0;
        for (final RecordedEvent e : events) {
            final long t = e.getStartTime().getEpochSecond() * 1_000_000_000L + e.getStartTime().getNano();
            if (e.getEventType().getName().equals("jdk.JavaExceptionThrow") && t > first && t <= last
                    && !e.getStackTrace().getFrames().getFirst().getMethod().getType().getName().equals("java.lang.Error")) {
                inside++;
            }
        }
        assertTrue(inside >= THROWN + ERRORS + 1, String.valueOf(inside));
        assertEquals(inside, report.throwables().created());
    }

    /**
     * A JVM whose live set fills its heap, in a process of its own: G1 fails to evacuate,
     * then collects the whole heap. The collections that failed are counted once each,
     * however many events one of them reported, and ranked above the full collections.
     */
    @Test
    void aHeapWithNoRoomLeftIsAFailedEvacuationBeforeAFullCollection() throws Exception {
        final Path file = dir.resolve("squeeze.jfr");
        final Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
                "-Xms48m", "-Xmx48m", "-XX:+UseG1GC", "-XX:StartFlightRecording=filename=" + file + ",settings=default",
                "-Xlog:disable", "-Xlog:all=error:stderr", "-cp", System.getProperty("java.class.path"),
                Squeeze.class.getName(), "1500").redirectErrorStream(true).redirectOutput(dir.resolve("squeeze.log").toFile())
                .start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the squeezed JVM finished");
        assertEquals(0, p.exitValue(), () -> read(dir.resolve("squeeze.log")));

        final HealthCollector health = new HealthCollector();
        JfrReader.read(file, health);
        final HealthReport squeezed = health.report();
        final Finding failed = squeezed.findings().stream().filter(f -> f.kind() == Finding.Kind.EVACUATION_FAILED)
                .findFirst().orElseThrow(() -> new AssertionError(squeezed.findings().toString()));
        final Set<Long> gcIds = new HashSet<>();
        for (final RecordedEvent e : RecordingFile.readAllEvents(file)) {
            if (e.getEventType().getName().equals("jdk.EvacuationFailed")) {
                gcIds.add(e.getLong("gcId"));
            }
        }
        assertEquals(gcIds.size(), failed.count());
        assertTrue(failed.text().contains(" failed to evacuate, from +"), failed.text());
        assertEquals(Finding.Kind.EVACUATION_FAILED, squeezed.findings().getFirst().kind());
        assertTrue(squeezed.findings().stream().anyMatch(f -> f.kind() == Finding.Kind.FULL_GC));
        // G1's concurrent cycles carry the cause of the pause that started them and are not counted twice.
        final HealthReport.Gc gc = squeezed.gc();
        assertTrue(gc.collections().getOrDefault(HealthReport.CONCURRENT_CYCLE, 0L) > 0, gc.collections().toString());
        assertEquals(gc.count() - gc.collections().get(HealthReport.CONCURRENT_CYCLE),
                gc.causes().values().stream().mapToLong(Long::longValue).sum());
    }

    /** Keeps about 40 MB alive in a 48 MB heap and churns it, for as many milliseconds as it is told. */
    static final class Squeeze {
        public static void main(final String[] args) {
            final long end = System.nanoTime() + Long.parseLong(args[0]) * 1_000_000L;
            final List<byte[]> live = new ArrayList<>();
            final Random random = new Random(1);
            while (System.nanoTime() < end) {
                final byte[] b = new byte[1024 + random.nextInt(4096)];
                if (live.size() < 13_000) {
                    live.add(b);
                } else {
                    live.set(random.nextInt(live.size()), b);
                }
            }
        }
    }

    static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            return e.toString();
        }
    }

    @Test
    void theJsonDocumentCarriesTheReportInUnitsAProgramCanUse() {
        final Map<String, Object> doc = JsonParser.object(Json.health(report, 1, "test"));
        assertEquals("health", doc.get("command"));
        final List<Object> findings = list(doc, "findings");
        assertEquals(report.findings().size(), findings.size());
        for (int i = 0; i < findings.size(); i++) {
            final Map<String, Object> f = object(findings.get(i));
            final Finding expected = report.findings().get(i);
            assertEquals(expected.kind().name(), f.get("kind"));
            assertEquals(expected.count(), f.get("count"));
            assertEquals(expected.firstNanos() - report.info().startNanos(), f.get("firstOffsetNanos"));
            assertEquals(expected.lastNanos() - report.info().startNanos(), f.get("lastOffsetNanos"));
            assertTrue(((String) f.get("first")).endsWith("Z"), f.toString());
        }
        final Map<String, Object> gc = map(doc, "gc");
        assertEquals(report.gc().count(), gc.get("collections"));
        assertEquals(2L, map(gc, "byCause").get("System.gc()"));
        assertEquals(List.copyOf(report.gc().collections().keySet()), List.copyOf(map(gc, "byCollector").keySet()));
        assertEquals(report.trends().size(), list(doc, "trends").size());
        final Map<String, Object> t = map(doc, "throwables");
        assertEquals(report.throwables().created(), t.get("created"));
        assertEquals((long) report.throwables().byClass().size(), t.get("classesFound"));
        assertEquals(1, list(t, "byClass").size(), "--top 1");
        assertEquals(1, list(t, "bySite").size(), "--top 1");
        assertEquals((long) ERRORS, map(t, "errors").get("java.lang.AssertionError"));
        assertTrue(Html.health(report, 3).contains("caused by System.gc()"));
    }

    /** A recording without one of the events health reads: every number is unknown, not zero. */
    @Test
    void aRecordingWithNothingToReadSaysSoRatherThanZero() throws Exception {
        final Path file = JfrFixtures.record(dir, "empty", r -> {
        }, () -> JfrFixtures.sleep(20));
        final HealthCollector health = new HealthCollector();
        JfrReader.read(file, health);
        final HealthReport empty = health.report();
        assertTrue(empty.findings().isEmpty());
        assertTrue(empty.trends().isEmpty());
        assertEquals("", empty.gc().causesNote());
        final Map<String, Object> doc = JsonParser.object(Json.health(empty, 5, "test"));
        final Map<String, Object> gc = map(doc, "gc");
        assertEquals(0L, gc.get("collections"));
        assertNull(gc.get("gcTimeRatio"));
        assertNull(gc.get("maxHeapBytes"));
        assertNull(doc.get("threadsStarted"));
        final Map<String, Object> t = map(doc, "throwables");
        assertNull(t.get("created"));
        assertNull(t.get("createdNanos"));
        assertNull(t.get("perSecond"));
        assertEquals(0L, t.get("events"));
        final String html = Html.health(empty, 5);
        assertTrue(html.contains(HealthReport.NO_TRENDS), html);
        assertTrue(html.contains("jdk.ExceptionStatistics was not recorded twice"), html);
    }

    @SuppressWarnings("unchecked") // the test knows the document's shape
    static Map<String, Object> object(final Object o) {
        return (Map<String, Object>) o;
    }

    static Map<String, Object> map(final Map<String, Object> doc, final String name) {
        return object(doc.get(name));
    }

    @SuppressWarnings("unchecked") // the test knows the document's shape
    static List<Object> list(final Map<String, Object> doc, final String name) {
        return (List<Object>) doc.get(name);
    }

    @Test
    void aReportIsOnlyThereAfterARecordingWasRead() {
        assertThrows(IllegalStateException.class, () -> new HealthCollector().report());
    }

    @Test
    void aSeriesFormatsInItsOwnUnitAndADashForNothing() {
        final Series bytes = new Series("b", Series.Unit.BYTES, 1, 0, 0, 0, 0, 0, Double.NaN, Double.NaN);
        assertEquals("—", bytes.format(Double.NaN));
        assertEquals("2.00 KB", bytes.format(2000));
        assertEquals("12", new Series("c", Series.Unit.COUNT, 1, 0, 0, 0, 0, 0, 0, 0).format(12));
        assertEquals("12.5%", new Series("f", Series.Unit.FRACTION, 1, 0, 0, 0, 0, 0, 0, 0).format(0.125));
        final HealthReport.Throwables none = new HealthReport.Throwables(Nulls.LONG_NULL, 0, 0, null, List.of(),
                List.of(), Map.of());
        assertTrue(Double.isNaN(none.rate()));
        assertFalse(none.errors().containsKey("x"));
    }

    static Finding finding(final Finding.Kind kind) {
        return report.findings().stream().filter(f -> f.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError(kind + " not in " + report.findings()));
    }
}
