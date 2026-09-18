// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.stalls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Stack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IdleMatcherTest {

    static Stack top(String type, String method) {
        return new Stack(List.of(new Frame(type, method, 0, "Native"),
                new Frame("io.netty.channel.nio.NioEventLoop", "run", 500, "JIT compiled")), false);
    }

    @ParameterizedTest
    @CsvSource({
            "sun.nio.ch.KQueue, poll",
            "sun.nio.ch.EPoll, wait",
            "sun.nio.ch.WEPoll, wait",
            "sun.nio.ch.EPollSelectorImpl, doSelect",
            "sun.nio.ch.SelectorImpl, select",
            "sun.nio.ch.SelectorImpl, lockAndDoSelect",
            "io.netty.channel.epoll.Native, epollWait",
            "io.netty.channel.epoll.Native, epollWait0",
            "io.netty.channel.kqueue.Native, keventWait",
            "jdk.internal.misc.Unsafe, park",
            "java.util.concurrent.locks.LockSupport, parkNanos",
            "java.lang.Object, wait0",
    })
    void defaultsRecogniseSelectorsAndParks(String type, String method) {
        assertTrue(IdleMatcher.defaults().isIdle(top(type, method)), type + "." + method);
    }

    @ParameterizedTest
    @CsvSource({
            "sun.nio.ch.SocketDispatcher, read0",
            "dev.app.CpuWork, burn",
            "java.lang.Thread, sleep",
            "io.netty.channel.nio.NioEventLoop, processSelectedKeys",
    })
    void defaultsRejectWork(String type, String method) {
        assertFalse(IdleMatcher.defaults().isIdle(top(type, method)), type + "." + method);
    }

    @Test
    void onlyTheInnermostFramesCount() {
        Stack deepIdle = new Stack(List.of(
                new Frame("dev.app.Work", "a", 1, "JIT compiled"),
                new Frame("dev.app.Work", "b", 1, "JIT compiled"),
                new Frame("dev.app.Work", "c", 1, "JIT compiled"),
                new Frame("sun.nio.ch.KQueue", "poll", 0, "Native")), false);
        assertFalse(IdleMatcher.defaults().isIdle(deepIdle));
        Stack thirdFrame = new Stack(List.of(
                new Frame("dev.app.Work", "a", 1, "JIT compiled"),
                new Frame("dev.app.Work", "b", 1, "JIT compiled"),
                new Frame("sun.nio.ch.KQueue", "poll", 0, "Native")), false);
        assertTrue(IdleMatcher.defaults().isIdle(thirdFrame));
        assertFalse(IdleMatcher.defaults().isIdle(Stack.EMPTY));
    }

    @Test
    void customPatternsReplaceTheDefaults() {
        IdleMatcher custom = IdleMatcher.of("dev\\.app\\.Loop\\.take, org\\.x\\.Y\\.z");
        assertTrue(custom.isIdle(top("dev.app.Loop", "take")));
        assertTrue(custom.isIdle(top("org.x.Y", "z")));
        assertFalse(custom.isIdle(top("sun.nio.ch.KQueue", "poll")));
        assertEquals("dev\\.app\\.Loop\\.take, org\\.x\\.Y\\.z", custom.toString());
        assertThrows(IllegalArgumentException.class, () -> IdleMatcher.of(" , "));
        assertThrows(IllegalArgumentException.class, () -> IdleMatcher.of("(unclosed"));
    }
}
