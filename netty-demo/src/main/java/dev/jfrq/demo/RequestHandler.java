// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * The server's request handler. Runs on the event loop thread, as Netty handlers do,
 * which is exactly why each injected pathology hurts every other connection on the same
 * loop.
 *
 * <p>Requests are lines {@code REQ <n>}; replies are {@code OK <n>}.
 */
final class RequestHandler extends SimpleChannelInboundHandler<String> {

    private final Scenario scenario;
    private final SessionRegistry registry;
    private final int backendPort;
    private final Counters counters;

    /** This connection's own link to the backend: opened by the first lookup, closed with the channel. */
    private Backend backend;

    RequestHandler(final Scenario scenario, final SessionRegistry registry, final int backendPort, final Counters counters) {
        this.scenario = scenario;
        this.registry = registry;
        this.backendPort = backendPort;
        this.counters = counters;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final String line) throws Exception {
        final long n = counters.requests.incrementAndGet();
        final String session = "session-" + ctx.channel().id().asShortText();

        if (scenario.lock()) {
            // Bug: a hot-path touch on a lock that housekeeping holds for hundreds of ms.
            registry.touch(session);
        }
        if (scenario.blockingIo() && n % 400 == 0) {
            // Bug: a synchronous round-trip on the event loop thread.
            if (lookup(line) != null) {
                counters.lookups.incrementAndGet();
            }
        }
        if (scenario.cpu() && n % 1000 == 0) {
            // Bug: a long computation on the event loop thread.
            CpuWork.burn(120);
        }
        if (scenario.alloc()) {
            // Ordinary per-request garbage; the bulk allocators dwarf it, which is the point.
            final String padded = (line + " ").repeat(16);
            if (padded.isEmpty()) {
                throw new IllegalStateException();
            }
        }
        ctx.writeAndFlush("OK " + line.substring(line.indexOf(' ') + 1) + "\n");
    }

    private String lookup(final String key) throws IOException {
        if (backend == null) {
            backend = new Backend(backendPort);
        }
        backend.out.write((key + "\n").getBytes(StandardCharsets.UTF_8));
        backend.out.flush();
        return backend.in.readLine();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        try {
            backend = Backend.free(backend);
        } finally {
            super.channelInactive(ctx);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        ctx.close();
    }

    /**
     * What one server run counts across all its connections. One instance per {@link Server},
     * so a second run in the same JVM starts from zero.
     */
    static final class Counters {
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong lookups = new AtomicLong();

        /** Synchronous backend lookups made on event loop threads. */
        long lookups() {
            return lookups.get();
        }
    }

    /** A blocking line-protocol connection to {@link SlowBackend}; the socket owns both streams. */
    private static final class Backend implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final OutputStream out;

        Backend(final int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            try {
                socket.setTcpNoDelay(true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = socket.getOutputStream();
            } catch (final Throwable e) {
                try {
                    close();
                } catch (final IOException c) {
                    e.addSuppressed(c);
                }
                throw e;
            }
        }

        /** Closes {@code b} if there is one; returns {@code null} for the caller to store (G-4.3). */
        static Backend free(final Backend b) throws IOException {
            if (b != null) {
                b.close();
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
