// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        assertEquals(List.of(new RecordingSummary.Family("main", 1, "main"),
                new RecordingSummary.Family("worker-N", 3, "worker-10")), RecordingSummary.threadFamilies(info));
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
}
