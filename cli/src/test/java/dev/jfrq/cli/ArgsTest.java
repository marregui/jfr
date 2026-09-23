// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ArgsTest {

    static final Set<String> VALUED = Set.of("top", "gap", "thread");
    static final Set<String> FLAGS = Set.of("sites");

    static Args parse(final String... argv) {
        return Args.parse(argv, VALUED, FLAGS);
    }

    @Test
    void positionalsOptionsAndFlags() {
        final Args a = parse("rec.jfr", "--top", "5", "--gap=20ms", "--sites", "second");
        assertEquals(List.of("rec.jfr", "second"), a.positional());
        assertEquals("rec.jfr", a.first("file"));
        assertEquals(5, a.top());
        assertEquals(20_000_000L, a.durationOption("gap", "50ms", false));
        assertTrue(a.flag("sites"));
        assertFalse(a.flag("nope"));
        assertTrue(a.option("thread").isEmpty());
    }

    @Test
    void defaultsApplyWhenAbsent() {
        final Args a = parse();
        assertEquals(15, a.top());
        assertEquals(50_000_000L, a.durationOption("gap", "50ms", false));
        assertThrows(Args.UsageException.class, () -> a.first("recording file"));
    }

    @Test
    void usageErrors() {
        assertThrows(Args.UsageException.class, () -> parse("--bogus"));
        assertThrows(Args.UsageException.class, () -> parse("--top"));
        assertThrows(Args.UsageException.class, () -> parse("--sites=yes"));
        assertThrows(Args.UsageException.class, () -> parse("--top", "abc").top());
        assertThrows(Args.UsageException.class, () -> parse("--top", "0").top());
        assertThrows(Args.UsageException.class, () -> parse("--gap", "soon").durationOption("gap", "1s", false));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "0ms").durationOption("gap", "1s", false));
        assertEquals(0, parse("--gap", "0ms").durationOption("gap", "1s", true));
        assertEquals(0, parse("--gap", "0").durationOption("gap", "1s", true));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "50").durationOption("gap", "1s", false));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "-1ms").durationOption("gap", "1s", true));
        final String message = assertThrows(Args.UsageException.class, () -> parse("--bogus")).getMessage();
        assertEquals("unknown option --bogus", message);
    }
}
