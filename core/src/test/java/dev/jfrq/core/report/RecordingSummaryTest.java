// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
import org.junit.jupiter.api.Test;

class RecordingSummaryTest {

    @Test
    void threadFamiliesFoldEveryNumberAPoolVaries() {
        assertEquals("pool-N-thread-N", RecordingSummary.family("pool-36-thread-2"));
        assertEquals("milo-shared-thread-pool-N", RecordingSummary.family("milo-shared-thread-pool-17"));
        assertEquals("main", RecordingSummary.family("main"));
        assertEquals("RMI TCP Connection(N)-N.N.N.N", RecordingSummary.family("RMI TCP Connection(1)-192.168.1.120"));
    }

    @Test
    void familiesCountEveryThreadAndNameTheFirstByName() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(), Map.of(),
                Set.of(new ThreadRef(1, "worker-9"), new ThreadRef(2, "worker-10"), new ThreadRef(3, "worker-9"),
                        new ThreadRef(4, "main")), List.of());
        // Two threads that share a name are two threads; the example does not depend on hash order.
        final int unknown = Nulls.INT_NULL;
        assertEquals(List.of(new RecordingSummary.Family("main", 1, 1, 0, unknown, unknown, unknown, unknown, "main"),
                new RecordingSummary.Family("worker-N", 3, 3, 0, unknown, unknown, unknown, unknown, "worker-10")),
                RecordingSummary.threadFamilies(info, ThreadCensus.Result.UNKNOWN));
        assertEquals(List.of("Family", "Threads", "Seen", "Example"),
                RecordingSummary.familyHeaders(ThreadCensus.Result.UNKNOWN, List.of()));
    }

    @Test
    void aFamilyOfVirtualThreadsHasNoLifeCountsRatherThanZero() {
        final ThreadRef pooled = new ThreadRef(1, "pool-1-thread-1");
        final ThreadRef churn = new ThreadRef(2, "churn-0", true);
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(), Map.of(),
                Set.of(pooled, churn), List.of());
        final ThreadCensus.Result census = new ThreadCensus.Result(Set.of(), Map.of(pooled, 1L), Map.of(),
                Set.of(pooled), null);
        final List<RecordingSummary.Family> families = RecordingSummary.threadFamilies(info, census);
        assertEquals(List.of("churn-N", "pool-N-thread-N"), families.stream().map(RecordingSummary.Family::name).toList());
        // Neither the census nor ThreadStart/ThreadEnd covers a virtual thread; the table says
        // which dashes are virtual threads, so they do not read like a VM thread's.
        assertEquals(List.of("Family", "Threads", "Seen", "Virtual", "At start", "Started", "Ended", "At end", "Example"),
                RecordingSummary.familyHeaders(census, families));
        assertArrayEquals(new Object[] {"churn-0", 1, 1, 1, "—", "—", "—", "—", ""},
                RecordingSummary.familyCells(families.getFirst(), census, true));
        assertArrayEquals(new Object[] {"pool-1-thread-1", 1, 1, "", 0, 1, 0, 1, ""},
                RecordingSummary.familyCells(families.get(1), census, true));
        // Without a virtual thread there is no such column.
        assertEquals(List.of("Family", "Threads", "Seen", "At start", "Started", "Ended", "At end", "Example"),
                RecordingSummary.familyHeaders(census, families.subList(1, 2)));
    }

    @Test
    void aFamilyTheCensusNeverNamedHasNoLifeCounts() {
        // A GC worker is seen in events but has no Java identity: in no census, never started or ended.
        final ThreadRef gc = new ThreadRef(9001, "GC Thread#0");
        final ThreadRef pooled = new ThreadRef(1, "pool-1-thread-1");
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(), Map.of(),
                Set.of(gc, pooled), List.of());
        final ThreadCensus.Result census = new ThreadCensus.Result(Set.of(pooled), Map.of(), Map.of(),
                Set.of(pooled), Set.of(pooled));
        final List<RecordingSummary.Family> families = RecordingSummary.threadFamilies(info, census);
        assertArrayEquals(new Object[] {"GC Thread#0", 1, 1, "—", "—", "—", "—", ""},
                RecordingSummary.familyCells(families.getFirst(), census, false));
        assertArrayEquals(new Object[] {"pool-1-thread-1", 1, 1, 1, 0, 0, 1, ""},
                RecordingSummary.familyCells(families.get(1), census, false));
    }

    @Test
    void threadNamesAreFoldedByPoolOnlyWhenTheyDoNotFit() {
        final List<ThreadRef> two = List.of(new ThreadRef(1, "event-loop-3-1"), new ThreadRef(2, "event-loop-3-2"));
        assertEquals("event-loop-3-1, event-loop-3-2", RecordingSummary.threadNames(two, 4));
        final List<ThreadRef> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(new ThreadRef(i, "ForkJoinPool.commonPool-worker-" + i));
        }
        many.add(new ThreadRef(20, "main"));
        many.add(new ThreadRef(21, "main"));
        assertEquals("ForkJoinPool.commonPool-worker-N* (11 threads), main (2 threads)",
                RecordingSummary.threadNames(many, 4));
        final List<ThreadRef> distinct = new ArrayList<>();
        for (final String n : List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta")) {
            distinct.add(new ThreadRef(distinct.size(), n));
        }
        assertEquals("alpha, beta, gamma, delta (+2 more)", RecordingSummary.threadNames(distinct, 4));
    }

    @Test
    void settingsNameTheThresholdThenThePeriodThenTheThrottle() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(),
                Map.of("jdk.ThreadPark", Map.of("enabled", "true", "threshold", "10 ms", "period", "1 s"),
                        "jdk.ExecutionSample", Map.of("enabled", "true", "period", "20 ms"),
                        "jdk.ObjectAllocationSample", Map.of("enabled", "true", "throttle", "150/s")),
                Set.of(), List.of());
        assertEquals("ThreadPark 10.0 ms, ExecutionSample 20.0 ms, ObjectAllocationSample 150/s",
                RecordingSummary.settings(info, "jdk.ThreadPark", "jdk.ExecutionSample", "jdk.SocketRead",
                        "jdk.ObjectAllocationSample"));
        assertEquals("", RecordingSummary.settings(info, "jdk.SocketRead"));
    }

    @Test
    void theThrottledLineNamesTheThrottleEvenWhereAThresholdIsSetToo() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(),
                Map.of("jdk.FileRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "300/s"),
                        "jdk.ThreadPark", Map.of("enabled", "true", "threshold", "10 ms"),
                        "jdk.SocketRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "off")),
                Set.of(), List.of());
        assertEquals("FileRead 300/s", RecordingSummary.throttles(info, "jdk.FileRead", "jdk.ThreadPark",
                "jdk.SocketRead"));
    }
}
