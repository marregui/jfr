package dev.jfrq.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import org.junit.jupiter.api.Test;

class HtmlTest {

    static final long MS = 1_000_000L;
    static final ThreadRef LOOP = new ThreadRef(1, "event-loop-1");
    static final ThreadRef HOLDER = new ThreadRef(2, "house<keeper>");
    static final Stack STACK = new Stack(List.of(new Frame("dev.app.A", "b", 1, "JIT compiled")), false);

    static RecordingInfo info() {
        return new RecordingInfo(Path.of("dir", "rec.jfr"), new Interval(0, 2_000 * MS), 1, Map.of(),
                Map.of("jdk.JavaMonitorEnter", Map.of("threshold", "10 ms")), Set.of(), List.of());
    }

    @Test
    void stallsPageHasEverySectionAndEscapesText() {
        Stall s = new Stall(LOOP, new Interval(100 * MS, 400 * MS), Stall.Verdict.BLOCKED_MONITOR,
                "blocked on monitor X held by house<keeper>", STACK, Stall.Evidence.EVENT, 0);
        Pause gc = new Pause(new Interval(900 * MS, 1000 * MS), PauseKind.GC, "G1 Young");
        StallReport r = new StallReport(info(), 50 * MS,
                List.of(new StallReport.ThreadSummary(LOOP, 10, 10 * MS, 20 * MS, 1, 300 * MS, 300 * MS)),
                List.of(s), List.of(gc), List.of("a & warning"));
        String html = Html.stalls(r, 10);

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("<title>jfrq stalls · rec.jfr</title>"));
        assertTrue(html.contains("house&lt;keeper&gt;"));
        assertFalse(html.contains("house<keeper>"));
        assertTrue(html.contains("a &amp; warning"));
        for (String section : List.of("Threads", "By verdict", "Timeline", "Stalls, longest first", "JVM-wide pauses")) {
            assertTrue(html.contains("<h2>" + section), section);
        }
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("BLOCKED_MONITOR"));
        assertTrue(html.contains("G1 Young"));
        assertTrue(html.contains("dev.app.A.b(A.java:1)"));
        assertTrue(html.contains("class=\"legend\""));
        assertTrue(html.endsWith("</html>\n"));
    }

    @Test
    void stallsTableIsCappedAndTheTimelineKeepsTheLongestBoxes() {
        List<Stall> many = new java.util.ArrayList<>();
        for (int i = 0; i < Html.MIN_LISTED + 1; i++) {
            many.add(new Stall(LOOP, new Interval(i * 10 * MS, i * 10 * MS + 5 * MS), Stall.Verdict.SLEEP, "s" + i,
                    Stack.EMPTY, Stall.Evidence.EVENT, 0));
        }
        StallReport r = new StallReport(info(), MS,
                List.of(new StallReport.ThreadSummary(LOOP, 0, 0, 0, many.size(), 0, 0)), many, List.of(), List.of());
        String html = Html.stalls(r, 1);
        assertTrue(html.contains("Stalls, longest first (" + Html.MIN_LISTED + " of " + (Html.MIN_LISTED + 1) + ")"));
        // One box per stall in the SVG while under the cap.
        assertEquals(Html.MIN_LISTED + 1, html.split("<title>SLEEP").length - 1);

        // Past the cap, the longest boxes are drawn and the file stays bounded.
        List<Stall> flood = new java.util.ArrayList<>();
        for (int i = 0; i < Html.MAX_BOXES_PER_ROW + 500; i++) {
            long length = i < 500 ? 9 * MS : MS;
            flood.add(new Stall(LOOP, new Interval(i * 10 * MS, i * 10 * MS + length), Stall.Verdict.SLEEP, "f" + i,
                    Stack.EMPTY, Stall.Evidence.EVENT, 0));
        }
        StallReport big = new StallReport(info(), MS,
                List.of(new StallReport.ThreadSummary(LOOP, 0, 0, 0, flood.size(), 0, 0)), flood, List.of(), List.of());
        String capped = Html.stalls(big, 1);
        assertEquals(Html.MAX_BOXES_PER_ROW, capped.split("<title>SLEEP").length - 1);
        for (int i = 0; i < 500; i++) {
            assertTrue(capped.contains("<title>SLEEP 9.00 ms: f" + i + "</title>"), "long box " + i + " dropped");
        }
    }

    @Test
    void infoPageListsEveryEventType() {
        RecordingInfo info = new RecordingInfo(Path.of("rec.jfr"), new Interval(0, MS), 2,
                Map.of("jdk.ThreadSleep", 3L, "jdk.SocketRead", 7L),
                Map.of("jdk.SocketRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "300/s")),
                Set.of(LOOP), List.of());
        String html = Html.info(info);
        assertTrue(html.contains("<title>jfrq info"));
        assertTrue(html.contains("<dt>Chunks</dt><dd>2</dd>"));
        assertTrue(html.contains("jdk.ThreadSleep"));
        assertTrue(html.contains("300/s"), html);
        assertTrue(html.indexOf("jdk.SocketRead") < html.indexOf("jdk.ThreadSleep"), "sorted by count");
    }

    @Test
    void fileWarningsAppearOnEveryPage() {
        RecordingInfo damaged = new RecordingInfo(Path.of("cut.jfr"), new Interval(0, MS), 1, Map.of(), Map.of(), Set.of(),
                List.of("the file is truncated: <cut>"));
        AllocationReport a = new AllocationReport(damaged, "jdk.ObjectAllocationSample", 0, 0, 0, Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        assertTrue(Html.alloc(a, 5).contains("<li>the file is truncated: &lt;cut&gt;</li>"));
        assertTrue(Html.locks(new ContentionReport(damaged, List.of()), 5).contains("class=\"warn\""));
        StallReport r = new StallReport(damaged, MS, List.of(), List.of(), List.of(), List.of());
        assertTrue(Html.stalls(r, 5).contains("the file is truncated"));
    }

    @Test
    void stallsPageWithoutPausesOrWarnings() {
        StallReport r = new StallReport(info(), 50 * MS, List.of(), List.of(), List.of(), List.of());
        String html = Html.stalls(r, 10);
        assertFalse(html.contains("JVM-wide pauses"));
        assertFalse(html.contains("class=\"warn\""));
    }

    @Test
    void locksPage() {
        Wait.LockKey key = new Wait.LockKey("dev.app.Registry", 0xabc, Wait.Kind.MONITOR_ENTER);
        Wait.LockKey store = new Wait.LockKey("dev.app.Store", 0xdef, Wait.Kind.MONITOR_ENTER);
        ThreadRef flusher = new ThreadRef(3, "flusher");
        ContentionReport r = new ContentionReport(info(), List.of(
                new Wait(new Interval(100 * MS, 300 * MS), LOOP, key, HOLDER, STACK),
                new Wait(new Interval(150 * MS, 200 * MS), HOLDER, store, flusher, Stack.EMPTY)));
        String html = Html.locks(r, 10);
        assertTrue(html.contains("Locks by total wait"));
        assertTrue(html.contains("Threads by time blocked"));
        assertTrue(html.contains("Convoys"));
        assertTrue(html.contains("Longest waits"));
        assertTrue(html.contains("dev.app.Registry@abc"));
        assertTrue(html.contains("held by house&lt;keeper&gt;"));
        assertTrue(html.contains("jdk.JavaMonitorEnter 10.0 ms"));
        assertTrue(html.contains("class=\"legend\""));
    }

    @Test
    void allocAndDiffPages() {
        AllocationReport a = new AllocationReport(info(), "jdk.ObjectAllocationSample", 1000, 10, 10, Map.of("worker", 950L),
                Map.of("worker", 1000L), Map.of("[B", 1000L), Map.of(STACK, 1000L),
                Map.of("worker", Map.of("[B", 1000L)), Map.of("worker", Map.of(STACK, 1000L)));
        String html = Html.alloc(a, 10);
        assertTrue(html.contains("By thread"));
        assertTrue(html.contains("<dt>JVM counters</dt><dd>950 B on 1 threads seen at both ends of the file; the estimate for those is 1.00 KB (+5%)</dd>"), html);
        assertTrue(html.contains("<td class=\"n\">950 B</td>"), html);
        assertTrue(html.contains("By class"));
        assertTrue(html.contains("By site"));
        assertTrue(html.contains("byte[]"));
        assertTrue(html.contains("worker"));

        AllocationReport b = new AllocationReport(info(), "jdk.ObjectAllocationSample", 500, 5, 5, Map.of(),
                Map.of("worker", 500L), Map.of("[B", 500L), Map.of(STACK, 500L),
                Map.of(), Map.of());
        String diff = Html.allocDiff(new AllocationDiff(a, b), 10);
        assertTrue(diff.contains("<title>jfrq alloc diff"));
        assertTrue(diff.contains("Baseline"));
        assertTrue(diff.contains("-50%"));
        assertTrue(diff.contains("By site"));
    }

    @Test
    void helpers() {
        assertEquals("new", Html.ratio(Double.POSITIVE_INFINITY));
        assertEquals("+25%", Html.ratio(0.25));
        assertEquals("12.5%", Html.pct(0.125));
        assertEquals("50%", Html.pct(1, 2));
        assertEquals("0%", Html.pct(1, 0));
        assertEquals("a&lt;b&gt;&amp;&quot;c", Html.escape("a<b>&\"c"));
        for (Stall.Verdict v : Stall.Verdict.values()) {
            assertTrue(Html.colour(v).startsWith("#"));
        }
    }
}
