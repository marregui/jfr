// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.demo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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

    private final MultiThreadIoEventLoopGroup acceptor;
    private final MultiThreadIoEventLoopGroup loops;
    private final Channel channel;

    Server(final Scenario scenario, final SessionRegistry registry, final int backendPort, final int loopCount) throws InterruptedException {
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
                                new RequestHandler(scenario, registry, backendPort));
                    }
                });
        channel = b.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
    }

    int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        loops.shutdownGracefully(0, 500, java.util.concurrent.TimeUnit.MILLISECONDS).syncUninterruptibly();
        acceptor.shutdownGracefully(0, 500, java.util.concurrent.TimeUnit.MILLISECONDS).syncUninterruptibly();
    }
}
