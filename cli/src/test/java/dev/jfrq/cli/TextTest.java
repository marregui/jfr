// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.report.Html;
import dev.jfrq.core.report.ThreadCensus;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import org.junit.jupiter.api.Test;

/** The renderers on hand-built reports, where a recording cannot produce the case on demand. */
class TextTest {

    static final long MS = 1_000_000L;
    static final ThreadRef LOGBACK = new ThreadRef(1, "logback-4");
    static final ThreadRef HOLDER = new ThreadRef(2, "housekeeper");
    static final Wait.LockKey REGISTRY = new Wait.LockKey("dev.app.Registry", 0xabc, Wait.Kind.MONITOR_ENTER);

    /** A window of one second, as a {@code jfrq-live delta} dump gives it. */
    static RecordingInfo window() {
        return new RecordingInfo(Path.of("delta.jfr"), new Interval(1_000 * MS, 2_000 * MS), 1, Map.of(), Map.of(),
                Set.of(), List.of());
    }

    static Wait blocked(final long fromMs, final long toMs) {
        return new Wait(new Interval(fromMs * MS, toMs * MS), LOGBACK, REGISTRY, HOLDER, Stack.EMPTY);
    }

    @Test
    void locksSaysWhenAWaitWasCountedOnlyForThePartInsideTheWindow() {
        final String text = Text.locks(new ContentionReport(window(), List.of(blocked(400, 1_900))), 15, false);
        assertTrue(text.contains("Note       1 wait began before the recording or outlived it; "
                + "only the part inside it is counted"), text);
        // The whole wait is 1.5 s; 900 ms of it is inside the window, and that is what is reported.
        assertTrue(text.contains("Blocked    900 ms across 1 wait\n"), text);
        assertTrue(text.contains("90.0%"), text);
        assertFalse(text.contains("1.50 s"), text);
    }

    @Test
    void allocSaysHowMuchOfTheEstimateTheJvmCountersCover() {
        // 1 070 of 1 570 estimated bytes are on the one thread with a counter: the +7 % is about 68 % of the report.
        final AllocationReport r = new AllocationReport(new RecordingInfo(Path.of("a.jfr"),
                new Interval(0, 1_000 * MS), 1, Map.of(), Map.of(), Set.of(), List.of()),
                "jdk.ObjectAllocationSample", 1570, 100, 100, Map.of("worker", 1000L),
                Map.of("worker", 1070L, "short-lived", 500L), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        final String text = Text.alloc(r, 15, false, SiteKey.culpritMethod());
        assertTrue(text.contains("the estimate for those is 1.07 KB (+7%), 68.2% of the estimate above"), text);
    }

    @Test
    void waiterListsAreCappedAndCounted() {
        final List<Wait> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new Wait(new Interval((1_000 + i) * MS, (1_010 + i) * MS), new ThreadRef(i, "worker-" + i),
                    REGISTRY, HOLDER, Stack.EMPTY));
        }
        final String text = Text.locks(new ContentionReport(window(), many), 15, false);
        // The lock's Waiters cell names four and counts the rest; the threads still have
        // their own rows in THREADS BY TIME BLOCKED, which --top governs.
        assertTrue(text.contains("worker-0, worker-1, worker-2, worker-3 (+16 more)"), text);
    }

    @Test
    void infoListsThreadFamiliesAndDerivesTheThresholdLine() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1_000 * MS), 1,
                Map.of("jdk.SocketWrite", 3L),
                Map.of("jdk.SocketWrite", Map.of("enabled", "true", "threshold", "1 ms"),
                        "jdk.ObjectAllocationSample", Map.of("enabled", "true", "throttle", "1000/s")),
                Set.of(new ThreadRef(1, "milo-shared-thread-pool-17"), new ThreadRef(2, "milo-shared-thread-pool-3"),
                        new ThreadRef(3, "main"), new ThreadRef(4, "event-loop-1")),
                List.of());
        final String text = Text.info(info, ThreadCensus.Result.UNKNOWN);

        // SocketWrite is outside the old fixed whitelist, and its 1 ms threshold was in force.
        assertTrue(text.contains("Thresholds SocketWrite 1.00 ms"), text);
        assertTrue(text.contains("Throttled  ObjectAllocationSample 1000/s"), text);
        assertTrue(text.contains("Threads    4 seen in events"), text);
        assertTrue(text.contains("milo-shared-thread-pool-N*"), text);
        assertTrue(text.contains("main"), text);
        // A family of one is named by its thread: "event-loop-N" is not a name --thread can match.
        assertTrue(text.contains("  event-loop-1 "), text);
        assertFalse(text.contains("event-loop-N"), text);
    }

    @Test
    void locksSaysNothingWhenEveryWaitIsInsideTheWindow() {
        final String text = Text.locks(new ContentionReport(window(), List.of(blocked(1_100, 1_400))), 15, false);
        assertFalse(text.contains("Note"), text);
        assertTrue(text.contains("Blocked    300 ms across 1 wait\n"), text);
    }

    /** The part of a report under one heading, up to the blank line that ends it. */
    static String section(final String text, final String heading) {
        final int from = text.indexOf(heading);
        assertTrue(from >= 0, "no section " + heading + " in\n" + text);
        final int to = text.indexOf("\n\n", from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    static int occurrences(final String text, final String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void thresholdLineLeavesOutTheZeroesAndKeepsAStableOrder() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1_000 * MS), 1,
                Map.of("jdk.RetransformClasses", 2L),
                Map.of("jdk.SocketWrite", Map.of("enabled", "true", "threshold", "1 ms"),
                        "jdk.JavaMonitorEnter", Map.of("enabled", "true", "threshold", "10 ms"),
                        "jdk.RetransformClasses", Map.of("enabled", "true", "threshold", "0 ns"),
                        "jdk.GCPhasePause", Map.of("enabled", "true", "threshold", "0 ns")),
                Set.of(), List.of());
        final String text = Text.info(info, ThreadCensus.Result.UNKNOWN);
        // A threshold of zero suppresses nothing, so it is not a threshold a reader chose.
        assertTrue(text.contains("Thresholds JavaMonitorEnter 10.0 ms, SocketWrite 1.00 ms\n"), text);
        // It is still a fact about the file, and the per-type table below is where it belongs.
        // The per-type table keeps the "jdk." prefix; the settings lines above strip it.
        final String table = section(text, "Event type");
        assertTrue(table.contains("jdk.RetransformClasses") && table.contains("0 ns"), table);
    }

    @Test
    void locksPrintsOneStackForTheLocksThatShareIt() {
        // One mailbox per worker is one lock per worker and a single stack between them: printed
        // once per lock it was 66 lines of a 177-line report.
        final Stack mailbox = new Stack(List.of(new Frame("dev.app.DefaultMailbox", "awaitNextMessage", 92, "JIT compiled"),
                new Frame("dev.app.Dispatcher", "run", 31, "JIT compiled")), false);
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waits.add(new Wait(new Interval(1_100 * MS, (1_200 + i * 10) * MS), new ThreadRef(i, "dispatcher-" + i),
                    new Wait.LockKey("dev.app.DefaultMailbox", 0x10 + i, Wait.Kind.PARK), HOLDER, mailbox));
        }
        final String where = section(Text.locks(new ContentionReport(window(), waits), 15, false), "WHERE THEY WAITED");
        assertEquals(1, occurrences(where, "DefaultMailbox.awaitNextMessage"), where);
        assertTrue(where.contains("3 locks with this stack, longest 120 ms"), where);
        // The locks it stands for are named, one per line because a name is a class and an
        // address, so a --lock glob can still be aimed at one of them.
        assertTrue(where.contains("\n    dev.app.DefaultMailbox@10\n"), where);
        assertTrue(where.contains("\n    dev.app.DefaultMailbox@12\n"), where);
    }

    @Test
    void anIdleProcessIsToldItIsIdle() {
        // A node doing nothing: every park is a worker on its own queue. The report has no
        // contention in it, and the old answer blamed --thread and --min, which nothing had
        // set, and then dropped the one section that held the whole window's content.
        final Stack noWork = new Stack(List.of(
                new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
                new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
                new Frame("java.util.concurrent.LinkedBlockingQueue", "take", 435, "JIT compiled"),
                new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1070, "JIT compiled"),
                new Frame("java.util.concurrent.ThreadPoolExecutor", "runWorker", 1130, "JIT compiled")), false);
        final List<Wait> parks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parks.add(new Wait(new Interval(1_000 * MS, 2_000 * MS), new ThreadRef(i, "worker-" + i),
                    new Wait.LockKey("java.util.concurrent.LinkedBlockingQueue", 0x20 + i, Wait.Kind.PARK),
                    null, noWork));
        }
        final String text = Text.locks(new ContentionReport(window(), parks), 15, false);
        assertTrue(text.contains("No contention: every wait was a worker waiting for work"), text);
        assertFalse(text.contains("--thread"), text);
        assertTrue(text.contains("WAITING FOR WORK (not contention: 3 threads parked on an empty queue"), text);
        assertTrue(text.contains("LinkedBlockingQueue"), text);
    }

    /** Three pool workers idle on their own queues for the whole window. */
    static List<Wait> idleWorkers() {
        final Stack noWork = new Stack(List.of(
                new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
                new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
                new Frame("java.util.concurrent.LinkedBlockingQueue", "take", 435, "JIT compiled"),
                new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1070, "JIT compiled")), false);
        final List<Wait> parks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parks.add(new Wait(new Interval(1_000 * MS, 2_000 * MS), new ThreadRef(i, "worker-" + i),
                    new Wait.LockKey("java.util.concurrent.LinkedBlockingQueue", 0x20 + i, Wait.Kind.PARK),
                    null, noWork));
        }
        return parks;
    }

    @Test
    void anIdleAnswerUnderFiltersSaysItIsAboutWhatMatched() {
        // logback-4 waited 300 ms for the registry; --thread 'worker-*' left it out. What is
        // left is only idle workers, and "every wait was a worker waiting for work" would be
        // a claim about the recording that the recording contradicts.
        final List<Wait> waits = new ArrayList<>(idleWorkers());
        waits.add(blocked(1_100, 1_400));
        final String text = Text.locks(new ContentionReport(window(), waits, 0, name -> name.startsWith("worker-")),
                15, false);
        assertTrue(text.contains("No contention: every wait that matches the filters (--thread, --min, --lock) was "
                + "a worker waiting for work, not a thread held up by another; 4 in the recording.\n"), text);
        assertFalse(text.contains("every wait was a worker"), text);
        assertTrue(text.contains("WAITING FOR WORK"), text);
    }

    @Test
    void textAndHtmlGiveTheSameAnswerWhenThereIsNoContention() {
        final List<ContentionReport> reports = List.of(
                new ContentionReport(window(), List.of()),
                new ContentionReport(window(), idleWorkers()),
                new ContentionReport(window(), List.of(blocked(1_100, 1_400)), 0, _ -> false),
                new ContentionReport(window(), List.of(blocked(1_100, 1_400)), 500 * MS, _ -> true));
        for (final ContentionReport r : reports) {
            final String answer = r.noContention();
            final String text = Text.locks(r, 15, false);
            final String html = Html.locks(r, 15, false);
            assertTrue(text.contains("\n" + answer + "\n"), text);
            assertTrue(html.contains("<p>" + answer + "</p>"), html);
            // An empty page is not a report of nothing: no zero totals, no empty tables.
            assertFalse(html.contains("Total blocked time"), html);
            assertFalse(html.contains("Locks by total wait"), html);
            assertEquals(r.workWaits().isEmpty(), !html.contains("Waiting for work"), html);
            assertEquals(r.workWaits().isEmpty(), !text.contains("WAITING FOR WORK"), text);
        }
        assertTrue(reports.get(0).noContention().startsWith("No contended monitor enters or parks in the recording"));
        assertTrue(reports.get(1).noContention().startsWith("No contention: every wait was a worker"));
        assertTrue(reports.get(2).noContention().contains("match the filters"));
        assertTrue(reports.get(3).noContention().contains("match the filters"));
    }

    @Test
    void textAndHtmlAddUpTheSameLockSites() {
        // Two queues whose stacks differ only below the frames a site is compared on: one site
        // with two instances in both reports, whatever depth each prints its other stacks at.
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final List<Frame> frames = new ArrayList<>();
            for (int f = 0; f < 10; f++) {
                frames.add(new Frame("dev.app.Layer" + f, "call", f == 8 ? 100 + i : 1, "JIT compiled"));
            }
            waits.add(new Wait(new Interval(1_100 * MS, 1_200 * MS), new ThreadRef(10 + i, "browse-" + i),
                    new Wait.LockKey("dev.app.Queue", 0x100 + i, Wait.Kind.PARK), null, new Stack(frames, false)));
        }
        final ContentionReport r = new ContentionReport(window(), waits);
        final String text = section(Text.locks(r, 15, true), "LOCK SITES BY TOTAL WAIT");
        final String page = Html.locks(r, 15, true);
        final int from = page.indexOf("<h2>Lock sites by total wait");
        final String html = page.substring(from, page.indexOf("<h2>", from + 1));
        assertTrue(text.contains("2 lock instances"), text);
        assertEquals(1, occurrences(text, "Layer0.call"), text);
        assertTrue(html.contains("2 lock instances"), html);
        assertEquals(1, occurrences(html, "Layer0.call"), html);
        // Neither prints the frame the grouping did not look at.
        assertFalse(text.contains("Layer8"), text);
        assertFalse(html.contains("Layer8"), html);
    }

    @Test
    void locksBySiteRankOneRowPerStackWithItsInstanceCount() {
        final Stack mailbox = new Stack(List.of(new Frame("dev.app.DefaultMailbox", "awaitNextMessage", 92, "JIT compiled"),
                new Frame("dev.app.Dispatcher", "run", 31, "JIT compiled")), false);
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waits.add(new Wait(new Interval(1_100 * MS, (1_200 + i * 10) * MS), new ThreadRef(i, "dispatcher-" + i),
                    new Wait.LockKey("dev.app.DefaultMailbox", 0x10 + i, Wait.Kind.PARK), HOLDER, mailbox));
        }
        final String text = Text.locks(new ContentionReport(window(), waits), 15, true);
        final String sites = section(text, "LOCK SITES BY TOTAL WAIT");
        assertTrue(sites.contains("330 ms across 3 waits, 3 lock instances, longest 120 ms"), sites);
        assertEquals(1, occurrences(sites, "DefaultMailbox.awaitNextMessage"), sites);
        // The per-instance table is what --by-site replaces, so it is not printed as well.
        assertFalse(text.contains("LOCKS BY TOTAL WAIT\n"), text);
        assertFalse(text.contains("WHERE THEY WAITED"), text);
        // Everything that does not group by stack stays where it was.
        assertTrue(text.contains("THREADS BY TIME BLOCKED"), text);
        assertTrue(text.contains("LONGEST WAITS"), text);
    }

    @Test
    void allocWarnsThatVirtualThreadsAreUnderCounted() {
        final AllocationReport r = new AllocationReport(window(), "jdk.ObjectAllocationSample", 1000, 1, 2,
                Map.of(), Map.of("vt-1", 1000L), Map.of(), Map.of(), Map.of(), Map.of(), AllocationReport.Support.NONE,
                new AllocationReport.Dropped(1, 2048));
        final String text = Text.alloc(r, 15, false, SiteKey.culpritMethod());
        assertTrue(text.contains("\nWARNING    allocation on virtual threads is under-counted, and can still be "
                + "over-counted: 1 first sample of virtual threads, 2.05 KB, not counted"), text);
        // A diff says it once, with both recordings' counts, and names a side that had none.
        final AllocationReport none = new AllocationReport(window(), "jdk.ObjectAllocationSample", 1000, 1, 2,
                Map.of(), Map.of("vt-1", 1000L), Map.of(), Map.of(), Map.of(), Map.of(), AllocationReport.Support.NONE,
                AllocationReport.Dropped.NONE);
        final String diff = Text.allocDiff(new AllocationDiff(none, r), 15, false, SiteKey.culpritMethod());
        assertEquals(1, occurrences(diff, "allocation on virtual threads"), diff);
        assertTrue(diff.contains("\nWARNING    allocation on virtual threads is under-counted, and can still be "
                + "over-counted: baseline none; current 1 first sample of virtual threads, 2.05 KB, not counted"), diff);
        assertTrue(Text.allocDiff(new AllocationDiff(none, none), 15, false, SiteKey.culpritMethod())
                .lines().noneMatch(l -> l.startsWith("WARNING")));
    }

    @Test
    void allocSitesNameTheMethodAndCountEverySampleBehindTheRow() {
        // The same method reached two ways: one row, and its support is both stacks' samples.
        final Frame culprit = new Frame("dev.app.NodeId", "parse", 453, "JIT compiled");
        final Stack viaSubstring = new Stack(List.of(new Frame("java.lang.String", "substring", 2904, "JIT compiled"),
                culprit), false);
        final Stack viaCopy = new Stack(List.of(new Frame("java.util.Arrays", "copyOfRange", 3849, "JIT compiled"),
                new Frame("dev.app.NodeId", "parse", 454, "JIT compiled")), false);
        final AllocationReport r = new AllocationReport(window(), "jdk.ObjectAllocationSample", 1000, 1200, 1200,
                Map.of(), Map.of("worker", 1000L), Map.of(), Map.of(viaSubstring, 700L, viaCopy, 300L),
                Map.of(), Map.of(), new AllocationReport.Support(Map.of(), Map.of(),
                Map.of(viaSubstring, 900L, viaCopy, 300L)));

        final String sites = section(Text.alloc(r, 15, true, SiteKey.culpritMethod()), "BY SITE");
        assertTrue(sites.contains("1200 samples  dev.app.NodeId.parse  (2 stacks, the biggest below)"), sites);
        assertEquals(1, occurrences(sites, "dev.app.NodeId.parse "), sites);
        // The line that tells a reader what --app could be pointed at.
        assertTrue(sites.contains("Packages dev.app 100.0%"), sites);
    }

    @Test
    void stallsPrintARepeatedStackOnceAndPointAtIt() {
        // One lock convoying two event loops is several stalls and one stack: the demo printed
        // the same six frames five times down the list.
        final ThreadRef loop1 = new ThreadRef(1, "event-loop-1");
        final ThreadRef loop2 = new ThreadRef(2, "event-loop-2");
        final Stack touch = new Stack(List.of(new Frame("dev.app.Registry", "touch", 29, "JIT compiled")), false);
        final Stack flush = new Stack(List.of(new Frame("dev.app.Persistence", "flush", 15, "JIT compiled")), false);
        final Stall a = new Stall(loop1, new Interval(1_000 * MS, 1_176 * MS), Stall.Verdict.BLOCKED_MONITOR,
                "blocked on dev.app.Registry", touch, Stall.Evidence.EVENT, 2);
        final Stall b = new Stall(loop2, new Interval(1_000 * MS, 1_175 * MS), Stall.Verdict.BLOCKED_MONITOR,
                "blocked on dev.app.Registry", touch, Stall.Evidence.EVENT, 2);
        final Stall c = new Stall(loop1, new Interval(1_300 * MS, 1_400 * MS), Stall.Verdict.BLOCKED_MONITOR,
                "blocked on dev.app.Store", flush, Stall.Evidence.EVENT, 1);
        final StallReport r = new StallReport(window(), 50 * MS,
                List.of(new StallReport.ThreadSummary(loop1, 10, MS, MS, 2, 276 * MS, 176 * MS)),
                List.of(a, b, c), List.of(), List.of());
        final String listed = section(Text.stalls(r, 15), "STALLS >=");

        // Every stall keeps its own row: they are separate occurrences, not one aggregate.
        assertEquals(3, occurrences(listed, "BLOCKED_MONITOR"), listed);
        assertEquals(1, occurrences(listed, "Registry.touch"), listed);
        assertTrue(listed.contains("same stack as #1"), listed);
        assertEquals(1, occurrences(listed, "Persistence.flush"), listed);
    }

    @Test
    void stallsLeadsWithWhatTheRecordingCannotShowAndSaysSoWhenItFoundNothing() {
        // The field case: event loops, 0 stalls, and a recording blind to anything under 750 ms.
        final ThreadRef loop = new ThreadRef(1, "event-loop-1");
        final StallReport r = new StallReport(window(), 50 * MS,
                List.of(new StallReport.ThreadSummary(loop, 460, 0, 250 * MS, 0, 0, 0, 750 * MS,
                        StallReport.Sight.NATIVE_SAMPLER, 250 * MS)),
                List.of(), List.of(), List.of("a warning"));
        final String text = Text.stalls(r, 15);
        // Before the warnings, and before the empty list it qualifies.
        assertTrue(text.contains("Unseen     on 1 of 1 threads, a stall no event explains is seen only from 750 ms"), text);
        assertTrue(text.indexOf("Unseen") < text.indexOf("WARNING    a warning"), text);
        assertTrue(text.contains("  none this recording can show: see Unseen above"), text);
        // A thread with no stall is counted, not listed: the Unseen line speaks for it.
        assertTrue(section(text, "PER THREAD").contains("1 thread with no stall"), text);
    }

    @Test
    void stallsRanksUnexplainedGapsApartFromTheStallsItCanExplain() {
        final ThreadRef worker = new ThreadRef(1, "browse-1");
        final Stall gap = new Stall(worker, new Interval(1_000 * MS, 1_474 * MS), Stall.Verdict.UNEXPLAINED,
                "no samples and no blocking event", Stack.EMPTY, Stall.Evidence.SILENCE, 0);
        final Stall parked = new Stall(worker, new Interval(1_500 * MS, 1_671 * MS), Stall.Verdict.PARKED,
                "parked at ChannelBrowseSink.take", Stack.EMPTY, Stall.Evidence.EVENT, 3);
        final StallReport r = new StallReport(window(), 100 * MS,
                List.of(new StallReport.ThreadSummary(worker, 10, MS, MS, 2, 645 * MS, 474 * MS)),
                List.of(gap, parked), List.of(), List.of());
        final String text = Text.stalls(r, 15);

        // The 474 ms nobody can act on no longer outranks the 171 ms with an explanation:
        // they are different kinds of claim, so they are different lists.
        assertTrue(text.contains("STALLS >= 100 ms: 1 found"), text);
        assertTrue(text.contains("UNEXPLAINED GAPS >= 100 ms: 1 found"), text);
        assertTrue(text.indexOf("ChannelBrowseSink") < text.indexOf("UNEXPLAINED GAPS"), text);
        // The lead that turns a gap into a next step.
        assertTrue(text.contains("jdk.SocketWrite"), text);
        // Both kinds still count in the totals.
        assertTrue(section(text, "BY VERDICT").contains("UNEXPLAINED"), text);
    }

    @Test
    void stallsWithNoThreadNameWhatMatchedAndStillListThePauses() {
        // `jfrq info` lists the VM Thread; stalls has nothing to judge it by. Saying "use jfrq
        // info" back sent the reader round in a circle, and the pauses that stopped every
        // thread were dropped with the rest.
        final Pause gc = new Pause(new Interval(1_100 * MS, 1_400 * MS), PauseKind.GC, "Full (gcId 9)");
        final StallReport r = new StallReport(window(), 50 * MS, List.of(), List.of(), List.of(gc),
                List.of("1 matching thread has no samples and no blocking events, so nothing to judge by (VM Thread)"));
        final String text = Text.stalls(r, 15);
        assertTrue(text.contains("WARNING    1 matching thread has no samples"), text);
        assertTrue(text.contains(StallReport.NO_THREAD + "\n"), text);
        assertTrue(text.contains("JVM-WIDE PAUSES >= gap"), text);
        assertTrue(text.contains("Full (gcId 9)"), text);
        assertFalse(text.contains("PER THREAD"), text);
        assertTrue(text.endsWith("\n"), text);
    }
}
