package dev.jfrq.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ModelTest {

    static Frame jdk(String type, String method, int line) {
        return new Frame(type, method, line, "JIT compiled");
    }

    static Frame app(String method, int line) {
        return new Frame("dev.app.Handler", method, line, "Interpreted");
    }

    @Nested
    class IntervalTest {
        @Test
        void overlapAndContainment() {
            Interval a = new Interval(10, 20);
            Interval b = new Interval(15, 30);
            assertEquals(5, a.overlap(b));
            assertEquals(5, b.overlap(a));
            assertTrue(a.overlaps(b));
            assertEquals(0, a.overlap(new Interval(20, 25)));
            assertFalse(a.overlaps(new Interval(20, 25)));
            assertTrue(a.contains(10));
            assertFalse(a.contains(20));
            assertEquals(new Interval(10, 30), a.union(b));
            assertEquals(10, a.length());
            assertEquals(new Interval(5, 12), Interval.ofLength(5, 7));
        }

        @Test
        void ordersByStartThenEnd() {
            assertTrue(new Interval(1, 5).compareTo(new Interval(2, 3)) < 0);
            assertTrue(new Interval(1, 5).compareTo(new Interval(1, 6)) < 0);
            assertEquals(0, new Interval(1, 5).compareTo(new Interval(1, 5)));
        }

        @Test
        void rejectsNegativeLength() {
            assertThrows(IllegalArgumentException.class, () -> new Interval(5, 4));
        }
    }

    @Nested
    class FrameTest {
        @Test
        void prettyLooksLikeAStackTraceLine() {
            assertEquals("java.lang.Thread.sleep(Thread.java:509)", jdk("java.lang.Thread", "sleep", 509).pretty());
            assertEquals("sun.nio.ch.KQueue.poll(Native Method)",
                    new Frame("sun.nio.ch.KQueue", "poll", 0, "Native").pretty());
            assertEquals("java.util.HashMap$Node.hash(HashMap.java)", jdk("java.util.HashMap$Node", "hash", 0).pretty());
            assertEquals("dev.app.Handler.read(Handler.java:12)", app("read", 12).toString());
        }

        @Test
        void hiddenLambdaClassesAreShortened() {
            Frame f = new Frame("dev.app.Background$$Lambda.0x00003800010a1e10", "run", 0, "JIT compiled");
            assertTrue(f.isHidden());
            assertEquals("dev.app.Background$$Lambda.run(lambda)", f.pretty());
            Frame g = new Frame("dev.app.Hidden/0x1234", "run", 0, "JIT compiled");
            assertEquals("dev.app.Hidden/0x1234.run(lambda)", g.pretty());
            assertFalse(app("x", 1).isHidden());
        }

        @Test
        void jdkDetection() {
            assertTrue(jdk("java.lang.Thread", "run", 1).isJdk());
            assertTrue(jdk("jdk.internal.misc.Unsafe", "park", 1).isJdk());
            assertTrue(jdk("sun.nio.ch.KQueue", "poll", 1).isJdk());
            assertTrue(jdk("javax.net.ssl.SSLSocket", "read", 1).isJdk());
            assertTrue(jdk("com.sun.crypto.provider.X", "y", 1).isJdk());
            assertFalse(app("x", 1).isJdk());
            assertFalse(jdk("io.netty.channel.NioEventLoop", "run", 1).isJdk());
        }
    }

    @Nested
    class StackTest {
        final Stack stack = new Stack(List.of(
                new Frame("sun.nio.ch.SocketDispatcher", "read0", 0, "Native"),
                jdk("java.io.BufferedReader", "readLine", 333),
                app("lookup", 80),
                app("channelRead0", 55),
                new Frame("io.netty.channel.NioEventLoop", "run", 500, "JIT compiled")), false);

        @Test
        void culpritIsTheInnermostNonJdkFrame() {
            assertEquals(app("lookup", 80), stack.culprit().orElseThrow());
            assertEquals("sun.nio.ch.SocketDispatcher.read0", stack.top().orElseThrow().qualifiedName());
            Stack allJdk = new Stack(List.of(jdk("java.lang.Thread", "sleep", 1)), false);
            assertEquals(jdk("java.lang.Thread", "sleep", 1), allJdk.culprit().orElseThrow());
            assertTrue(Stack.EMPTY.culprit().isEmpty());
            assertTrue(Stack.EMPTY.top().isEmpty());
            assertTrue(Stack.EMPTY.isEmpty());
        }

        @Test
        void headTruncates() {
            assertEquals(2, stack.head(2).frames().size());
            assertTrue(stack.head(2).truncated());
            assertEquals(stack, stack.head(10));
        }

        @Test
        void prettyShowsTheCulpritEvenWhenItLiesBeyondTheLimit() {
            String out = stack.pretty("  ", 1);
            assertEquals("""
                      at sun.nio.ch.SocketDispatcher.read0(Native Method)
                      ... 1 more
                      at dev.app.Handler.lookup(Handler.java:80)
                      ... 2 more
                    """, out);
        }

        @Test
        void prettyWithoutElisionWhenEverythingFits() {
            String out = stack.pretty("", 10);
            assertEquals(5, out.lines().count());
            assertFalse(out.contains("more"));
            Stack truncated = new Stack(stack.frames(), true);
            assertTrue(truncated.pretty("", 10).endsWith("... 0 more\n"));
        }

        @Test
        void prettyWhenCulpritIsAlreadyVisible() {
            String out = stack.pretty("", 3);
            assertTrue(out.contains("Handler.lookup"));
            assertTrue(out.endsWith("... 2 more\n"));
            assertEquals(4, out.lines().count());
        }
    }

    @Test
    void threadRefUsesNameForDisplay() {
        ThreadRef t = new ThreadRef(7, "event-loop-1");
        assertEquals("event-loop-1", t.toString());
        assertEquals(t, new ThreadRef(7, "event-loop-1"));
        assertEquals(null, ThreadRef.of(null));
    }
}
