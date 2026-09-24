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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Paced closed-loop load: each connection sends a request, waits for the reply, then
 * waits out the rest of its interval before the next. The pacing keeps the event loops
 * mostly idle, as a real service's are, so the injected stalls stand out instead of
 * drowning in saturation. Latency is measured per request so the summary can show what
 * each pathology costs the clients.
 */
final class LoadClient {

    private final List<Thread> threads = new ArrayList<>();
    private final List<long[]> latencies = new ArrayList<>();
    /**
     * Samples taken per connection. Only the connection's own thread writes it, after the
     * sample it counts, with release semantics; a reader that acquires it may copy that many
     * samples even while the thread is still running (it outlived {@link #stop()}'s join).
     */
    private final List<AtomicInteger> counts = new ArrayList<>();
    private final long intervalNanos;
    private volatile boolean running = true;

    LoadClient(final int port, final int connections, final int requestsPerSecondPerConnection, final int maxSamplesPerConnection) {
        this.intervalNanos = requestsPerSecondPerConnection <= 0 ? 0
                : 1_000_000_000L / requestsPerSecondPerConnection;
        for (int c = 1; c <= connections; c++) {
            final long[] samples = new long[maxSamplesPerConnection];
            final AtomicInteger count = new AtomicInteger();
            latencies.add(samples);
            counts.add(count);
            final Thread t = new Thread(() -> drive(port, samples, count), "load-client-" + c);
            t.setDaemon(true);
            threads.add(t);
        }
    }

    void start() {
        threads.forEach(Thread::start);
    }

    private void drive(final int port, final long[] samples, final AtomicInteger count) {
        try (final Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setTcpNoDelay(true);
            final OutputStream out = s.getOutputStream();
            final BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            long n = 0;
            long next = System.nanoTime();
            while (running) {
                if (intervalNanos > 0) {
                    final long wait = next - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    } else if (wait < -intervalNanos) {
                        // Behind by more than one interval (a stall, or this process was starved):
                        // drop the backlog rather than burst it, so the offered rate stays the rate.
                        next = System.nanoTime();
                    }
                    next += intervalNanos;
                }
                final long t0 = System.nanoTime();
                out.write(("REQ " + n + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                final String reply = in.readLine();
                final long latency = System.nanoTime() - t0;
                if (reply == null) {
                    break;
                }
                final int taken = count.getPlain();
                if (taken < samples.length) {
                    samples[taken] = latency;
                    count.setRelease(taken + 1);
                }
                n++;
            }
        } catch (final IOException e) {
            if (running) {
                System.err.println(Thread.currentThread().getName() + " stopped: " + e);
            }
        }
    }

    /**
     * Percentiles over every connection's samples. Each count is read once, so a connection
     * that is still running adds samples past the snapshot, never inside the copy.
     */
    String summary() {
        final int[] taken = new int[counts.size()];
        int total = 0;
        for (int k = 0; k < taken.length; k++) {
            taken[k] = counts.get(k).getAcquire();
            total += taken[k];
        }
        if (total == 0) {
            return "no completed requests";
        }
        final long[] all = new long[total];
        int i = 0;
        for (int k = 0; k < taken.length; k++) {
            System.arraycopy(latencies.get(k), 0, all, i, taken[k]);
            i += taken[k];
        }
        Arrays.sort(all);
        return String.format(Locale.ROOT, "%d requests; latency p50 %s  p90 %s  p99 %s  max %s",
                total, ms(all[(int) (total * 0.50)]), ms(all[(int) (total * 0.90)]),
                ms(all[Math.min(total - 1, (int) (total * 0.99))]), ms(all[total - 1]));
    }

    int completed() {
        int total = 0;
        for (final AtomicInteger c : counts) {
            total += c.getAcquire();
        }
        return total;
    }

    private static String ms(final long nanos) {
        return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
    }

    /** Stops sending; waits briefly for in-flight requests so the summary is complete. */
    void stop() {
        running = false;
        for (final Thread t : threads) {
            try {
                t.join(2_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
