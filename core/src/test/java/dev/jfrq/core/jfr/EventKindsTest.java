// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class EventKindsTest {

    @Test
    void tagsAreDenseAndRoundTripThroughNames() {
        Set<String> names = new HashSet<>();
        for (int kind = 0; kind < EventKinds.COUNT; kind++) {
            String name = EventKinds.nameOf(kind);
            assertNotNull(name, "kind " + kind);
            assertTrue(name.startsWith("jdk."), name);
            assertTrue(names.add(name), "duplicate " + name);
            assertEquals(kind, EventKinds.kindOf(name));
        }
        assertEquals(EventKinds.UNKNOWN, EventKinds.kindOf("jdk.GCPhaseParallel"));
        assertEquals(EventKinds.ACTIVE_SETTING, EventKinds.kindOf("jdk.ActiveSetting"));
        assertEquals(Set.of("jdk.SocketRead", "jdk.FileRead"),
                EventKinds.names(EventKinds.SOCKET_READ, EventKinds.FILE_READ));
    }
}
