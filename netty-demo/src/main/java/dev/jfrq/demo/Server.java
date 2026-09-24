// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.demo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * The Netty server: one acceptor, {@code loops} event loops named {@code event-loop-*},
 * and a line protocol in front of {@link RequestHandler}.
 */
final class Server implements AutoCloseable {

    private final RequestHandler.Counters counters = new RequestHandler.Counters();
    private MultiThreadIoEventLoopGroup acceptor;
    private MultiThreadIoEventLoopGroup loops;
    private Channel channel;

    /** Binds {@code port} on the loopback address, 0 for any free one; shuts the event loops down again if the bind fails. */
    Server(final Scenario scenario, final SessionRegistry registry, final int backendPort, final int loopCount, final int port)
            throws InterruptedException {
        try {
            acceptor = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("acceptor"), NioIoHandler.newFactory());
            loops = new MultiThreadIoEventLoopGroup(loopCount, new DefaultThreadFactory("event-loop"), NioIoHandler.newFactory());
            final ServerBootstrap b = new ServerBootstrap()
                    .group(acceptor, loops)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new LineBasedFrameDecoder(1024),
                                    new StringDecoder(StandardCharsets.UTF_8),
                                    new StringEncoder(StandardCharsets.UTF_8),
                                    new RequestHandler(scenario, registry, backendPort, counters));
                        }
                    });
            channel = b.bind(InetAddress.getLoopbackAddress(), port).sync().channel();
        } catch (final Throwable e) {
            close();
            throw e;
        }
    }

    int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** Synchronous backend lookups this server's handlers made on its event loops. */
    long lookups() {
        return counters.lookups();
    }

    /** Idempotent, and safe on a server whose constructor failed half-way (G-4.1). */
    @Override
    public void close() {
        try {
            channel = close(channel);
        } finally {
            try {
                loops = shutdown(loops);
            } finally {
                acceptor = shutdown(acceptor);
            }
        }
    }

    private static Channel close(final Channel channel) {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        return null;
    }

    private static MultiThreadIoEventLoopGroup shutdown(final MultiThreadIoEventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
        return null;
    }
}
