// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UtilTest {

    @Nested
    class GlobTest {
        @Test
        void starAndQuestionMark() {
            final Glob g = Glob.of("event-loop-*,worker-?");
            assertTrue(g.test("event-loop-2-1"));
            assertTrue(g.test("event-loop-"));
            assertTrue(g.test("worker-7"));
            assertFalse(g.test("worker-77"));
            assertFalse(g.test("main"));
            assertFalse(g.test(null));
            assertEquals("event-loop-*,worker-?", g.toString());
        }

        @Test
        void literalCharactersAreQuoted() {
            assertTrue(Glob.of("pool.1[a]").test("pool.1[a]"));
            assertFalse(Glob.of("pool.1[a]").test("poolX1a"));
        }

        @Test
        void emptySpecMatchesNothingAndAnyMatchesEverything() {
            assertTrue(Glob.of("").isEmpty());
            assertTrue(Glob.of(null).isEmpty());
            assertTrue(Glob.of(" , ").isEmpty());
            assertFalse(Glob.of("").test("x"));
            assertTrue(Glob.any().test("anything at all"));
        }
    }

    @Nested
    class ClassNamesTest {
        @Test
        void prettyPrintsArraysAndPrimitives() {
            assertEquals("byte[]", ClassNames.pretty("[B"));
            assertEquals("int[][]", ClassNames.pretty("[[I"));
            assertEquals("java.lang.String[]", ClassNames.pretty("[Ljava.lang.String;"));
            assertEquals("boolean[]", ClassNames.pretty("[Z"));
            assertEquals("char[]", ClassNames.pretty("[C"));
            assertEquals("double[]", ClassNames.pretty("[D"));
            assertEquals("float[]", ClassNames.pretty("[F"));
            assertEquals("long[]", ClassNames.pretty("[J"));
            assertEquals("short[]", ClassNames.pretty("[S"));
            assertEquals("java.util.HashMap$Node", ClassNames.pretty("java.util.HashMap$Node"));
            assertEquals("?", ClassNames.pretty(null));
            assertEquals("?", ClassNames.pretty(""));
            assertEquals("Q[]", ClassNames.pretty("[Q"));
        }

        @Test
        void simpleDropsThePackage() {
            assertEquals("HashMap$Node", ClassNames.simple("java.util.HashMap$Node"));
            assertEquals("String[]", ClassNames.simple("[Ljava.lang.String;"));
            assertEquals("byte[]", ClassNames.simple("[B"));
        }
    }

    @Nested
    class BytesTest {
        @Test
        void formatsWithDecimalUnits() {
            assertEquals("0 B", Bytes.format(0));
            assertEquals("999 B", Bytes.format(999));
            assertEquals("1.00 KB", Bytes.format(1000));
            assertEquals("12.3 MB", Bytes.format(12_300_000));
            assertEquals("282 GB", Bytes.format(282_000_000_000L));
            assertEquals("1.50 TB", Bytes.format(1_500_000_000_000L));
            assertEquals("-850 B", Bytes.format(-850));
            // Long.MIN_VALUE has no positive counterpart; it must not recurse forever.
            assertEquals("-9223372 TB", Bytes.format(Long.MIN_VALUE));
            assertEquals("-106751d23h", Durations.format(Long.MIN_VALUE));
        }

        @Test
        void signedAndRates() {
            assertEquals("+1.00 KB", Bytes.signed(1000));
            assertEquals("-1.00 KB", Bytes.signed(-1000));
            assertEquals("0 B", Bytes.signed(0));
            assertEquals("45.1 MB/s", Bytes.rate(45_100_000.0));
            assertEquals("+45.1 MB/s", Bytes.signedRate(45_100_000.0));
            assertEquals("-45.1 MB/s", Bytes.signedRate(-45_100_000.0));
            assertEquals("0 B/s", Bytes.signedRate(0));
        }
    }

    @Nested
    class TextTableTest {
        @Test
        void alignsColumnsAndRightAlignsNumerics() {
            final String out = new TextTable("Name", "Count", "Note").numeric(1)
                    .row("alpha", 1, "x")
                    .row("b", 12345, "")
                    .render("> ");
            assertEquals("""
                    > Name   Count  Note
                    > alpha      1  x
                    > b      12345  \n""", out);
        }

        @Test
        void rejectsWrongArity() {
            final TextTable t = new TextTable("a", "b");
            assertThrows(IllegalArgumentException.class, () -> t.row("only one"));
            assertEquals(0, t.size());
            t.row(null, 2);
            assertEquals(1, t.size());
            assertTrue(t.render().startsWith("a  b\n"));
        }
    }
}
