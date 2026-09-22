// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
    void locksSaysNothingWhenEveryWaitIsInsideTheWindow() {
        final String text = Text.locks(new ContentionReport(window(), List.of(blocked(1_100, 1_400))), 15);
        assertFalse(text.contains("Note"), text);
        assertTrue(text.contains("Blocked    300 ms across 1 waits"), text);
    }
}
