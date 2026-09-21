// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoAppTest {

    @TempDir
    Path dir;

    @Test
    void cleanScenarioServesRequestsWithoutRecording() throws Exception {
        final DemoApp.Result r = DemoApp.run(Scenario.CLEAN, Duration.ofMillis(1500), null, 4, 100, 1);
        assertTrue(r.requests() > 50, r.latency());
        assertTrue(r.latency().contains("p99"));
        assertNull(r.recording());
        assertTrue(r.describe().startsWith("scenario clean:"));
    }

    @Test
    void allScenarioWritesARecording() throws Exception {
        final Path out = dir.resolve("all.jfr");
        final DemoApp.Result r = DemoApp.run(Scenario.ALL, Duration.ofMillis(2500), out, 4, 100, 2);
        assertTrue(r.requests() > 0, r.latency());
        assertTrue(Files.size(out) > 10_000, "recording is too small");
        assertEquals(out, r.recording());
    }

    @Test
    void scenarioParsing() {
        assertEquals(Scenario.BLOCKING_IO, Scenario.parse("blocking-io"));
        assertEquals(Scenario.LOCK, Scenario.parse("LOCK"));
        assertEquals(Scenario.ALL, Scenario.parse("all"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("nope"));
        assertTrue(Scenario.flags().contains("blocking-io"));
        assertTrue(Scenario.ALL.blockingIo() && Scenario.ALL.lock() && Scenario.ALL.cpu() && Scenario.ALL.alloc());
        assertTrue(Scenario.CPU.cpu() && !Scenario.CPU.lock());
        assertTrue(DemoApp.usage().contains("--scenario"));
    }

    @Test
    void cpuWorkRespectsItsBudget() {
        final long t0 = System.nanoTime();
        CpuWork.burn(50);
        final long elapsed = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsed >= 50 && elapsed < 1000, "took " + elapsed + " ms");
    }
}
