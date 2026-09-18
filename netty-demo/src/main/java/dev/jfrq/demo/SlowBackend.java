package dev.jfrq.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A line-oriented TCP service that answers every request after a deliberate delay. It
 * stands in for the "quick lookup" that a handler calls synchronously: a cache, a
 * config service, a database, anything with a socket behind it.
 */
final class SlowBackend implements AutoCloseable {

    private final ServerSocket server;
    private final long minDelayMillis;
    private final long maxDelayMillis;
    private volatile boolean running = true;

    SlowBackend(long minDelayMillis, long maxDelayMillis) throws IOException {
        this.minDelayMillis = minDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.server = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "slow-backend-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        int n = 0;
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "slow-backend-" + (++n));
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("slow-backend accept failed: " + e);
                }
            }
        }
    }

    private void serve(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            String line;
            while ((line = in.readLine()) != null) {
                long delay = ThreadLocalRandom.current().nextLong(minDelayMillis, maxDelayMillis + 1);
                // The delay is the service; this is a slow backend, not a busy-wait.
                //noinspection BusyWait
                Thread.sleep(delay);
                out.write(("LOOKUP " + line + " after " + delay + "ms\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // The client went away or the demo is shutting down; either way this connection is done.
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
    }
}
