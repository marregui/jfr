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

    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong LOOKUPS = new AtomicLong();

    private final Scenario scenario;
    private final SessionRegistry registry;
    private final int backendPort;

    private Socket backend;
    private BufferedReader backendIn;
    private OutputStream backendOut;

    RequestHandler(Scenario scenario, SessionRegistry registry, int backendPort) {
        this.scenario = scenario;
        this.registry = registry;
        this.backendPort = backendPort;
    }

    /** Synchronous backend lookups made on event loop threads. */
    static long lookups() {
        return LOOKUPS.get();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String line) throws Exception {
        long n = REQUESTS.incrementAndGet();
        String session = "session-" + ctx.channel().id().asShortText();

        if (scenario.lock()) {
            // Bug: a hot-path touch on a lock that housekeeping holds for hundreds of ms.
            registry.touch(session);
        }
        if (scenario.blockingIo() && n % 400 == 0) {
            // Bug: a synchronous round-trip on the event loop thread.
            if (lookup(line) != null) {
                LOOKUPS.incrementAndGet();
            }
        }
        if (scenario.cpu() && n % 1000 == 0) {
            // Bug: a long computation on the event loop thread.
            CpuWork.burn(120);
        }
        if (scenario.alloc()) {
            // Ordinary per-request garbage; the bulk allocators dwarf it, which is the point.
            String padded = (line + " ").repeat(16);
            if (padded.isEmpty()) {
                throw new IllegalStateException();
            }
        }
        ctx.writeAndFlush("OK " + line.substring(line.indexOf(' ') + 1) + "\n");
    }

    private String lookup(String key) throws IOException {
        if (backend == null) {
            backend = new Socket(InetAddress.getLoopbackAddress(), backendPort);
            backend.setTcpNoDelay(true);
            backendIn = new BufferedReader(new InputStreamReader(backend.getInputStream(), StandardCharsets.UTF_8));
            backendOut = backend.getOutputStream();
        }
        backendOut.write((key + "\n").getBytes(StandardCharsets.UTF_8));
        backendOut.flush();
        return backendIn.readLine();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (backend != null) {
            backend.close();
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
