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

    static Args parse(String... argv) {
        return Args.parse(argv, VALUED, FLAGS);
    }

    @Test
    void positionalsOptionsAndFlags() {
        Args a = parse("rec.jfr", "--top", "5", "--gap=20ms", "--sites", "second");
        assertEquals(List.of("rec.jfr", "second"), a.positional());
        assertEquals("rec.jfr", a.positional(0, "file"));
        assertEquals(5, a.intOption("top", 15));
        assertEquals(20_000_000L, a.durationOption("gap", "50ms"));
        assertTrue(a.flag("sites"));
        assertFalse(a.flag("nope"));
        assertEquals("x", a.option("thread", "x"));
        assertTrue(a.option("thread").isEmpty());
    }

    @Test
    void defaultsApplyWhenAbsent() {
        Args a = parse();
        assertEquals(15, a.intOption("top", 15));
        assertEquals(50_000_000L, a.durationOption("gap", "50ms"));
        assertThrows(Args.UsageException.class, () -> a.positional(0, "recording file"));
    }

    @Test
    void usageErrors() {
        assertThrows(Args.UsageException.class, () -> parse("--bogus"));
        assertThrows(Args.UsageException.class, () -> parse("--top"));
        assertThrows(Args.UsageException.class, () -> parse("--sites=yes"));
        assertThrows(Args.UsageException.class, () -> parse("--top", "abc").intOption("top", 1));
        assertThrows(Args.UsageException.class, () -> parse("--top", "0").intOption("top", 1));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "soon").durationOption("gap", "1s"));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "0ms").durationOption("gap", "1s"));
        String message = assertThrows(Args.UsageException.class, () -> parse("--bogus")).getMessage();
        assertEquals("unknown option --bogus", message);
    }
}
