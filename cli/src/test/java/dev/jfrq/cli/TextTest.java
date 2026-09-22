// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
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
        final String text = Text.locks(new ContentionReport(window(), List.of(blocked(400, 1_900))), 15);
        assertTrue(text.contains("Note       1 wait began before the recording or outlived it; "
                + "only the part inside it is counted"), text);
        // The whole wait is 1.5 s; 900 ms of it is inside the window, and that is what is reported.
        assertTrue(text.contains("Blocked    900 ms across 1 waits"), text);
        assertTrue(text.contains("90.0%"), text);
        assertFalse(text.contains("1.50 s"), text);
    }

    @Test
    void allocSaysHowMuchOfTheEstimateTheJvmCountersCover() {
        // 1 070 of 1 570 estimated bytes are on the one thread with a counter: the +7 % is about 68 % of the report.
        final AllocationReport r = new AllocationReport(new RecordingInfo(Path.of("a.jfr"),
                new Interval(0, 1_000 * MS), 1, Map.of(), Map.of(), Set.of(), List.of()),
                "jdk.ObjectAllocationSample", 1570, 100, 100, Map.of("worker", 1000L),
                Map.of("worker", 1070L, "short-lived", 500L), Map.of(), Map.of(), Map.of(), Map.of());
        final String text = Text.alloc(r, 15, false);
        assertTrue(text.contains("the estimate for those is 1.07 KB (+7%), 68.2% of the estimate above"), text);
    }

    @Test
    void waiterListsAreCappedAndCounted() {
        final List<Wait> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new Wait(new Interval((1_000 + i) * MS, (1_010 + i) * MS), new ThreadRef(i, "worker-" + i),
                    REGISTRY, HOLDER, Stack.EMPTY));
        }
        final String text = Text.locks(new ContentionReport(window(), many), 15);
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
                        new ThreadRef(3, "main")),
                List.of());
        final String text = Text.info(info);

        // SocketWrite is outside the old fixed whitelist, and its 1 ms threshold was in force.
        assertTrue(text.contains("Thresholds SocketWrite 1.00 ms"), text);
        assertTrue(text.contains("Throttled  ObjectAllocationSample 1000/s"), text);
        assertTrue(text.contains("Threads    3 seen in events"), text);
        assertTrue(text.contains("milo-shared-thread-pool-N*"), text);
        assertTrue(text.contains("main"), text);
    }

    @Test
    void threadFamiliesFoldEveryNumberAPoolVaries() {
        assertEquals("pool-N-thread-N", Text.family("pool-36-thread-2"));
        assertEquals("milo-shared-thread-pool-N", Text.family("milo-shared-thread-pool-17"));
        assertEquals("main", Text.family("main"));
        assertEquals("RMI TCP Connection(N)-N.N.N.N", Text.family("RMI TCP Connection(1)-192.168.1.120"));
    }

    @Test
    void locksSaysNothingWhenEveryWaitIsInsideTheWindow() {
        final String text = Text.locks(new ContentionReport(window(), List.of(blocked(1_100, 1_400))), 15);
        assertFalse(text.contains("Note"), text);
        assertTrue(text.contains("Blocked    300 ms across 1 waits"), text);
    }
}
