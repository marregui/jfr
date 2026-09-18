package dev.jfrq.demo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * A small Netty service with deliberately injected event-loop pathologies, recorded with
 * JFR so that {@code jfrq} has something to find.
 *
 * <pre>
 *   netty-demo [--scenario all|blocking-io|lock|cpu|alloc|clean] [--duration 20s]
 *              [--out demo-&lt;scenario&gt;.jfr] [--connections 8] [--rate 200] [--loops 2] [--no-jfr]
 * </pre>
 *
 * With {@code --no-jfr} the app does not record itself, for running under
 * {@code -XX:StartFlightRecording} or a profiler.
 */
public final class DemoApp {

    private DemoApp() {
    }

    public static void main(String[] argv) throws Exception {
        Scenario scenario = Scenario.ALL;
        Duration duration = Duration.ofSeconds(20);
        Path out = null;
        int connections = 8;
        int rate = 200;
        int loops = 2;
        boolean jfr = true;

        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--scenario" -> scenario = Scenario.parse(need(argv, ++i, "--scenario"));
                case "--duration" -> duration = parseDuration(need(argv, ++i, "--duration"));
                case "--out" -> out = Path.of(need(argv, ++i, "--out"));
                case "--connections" -> connections = Integer.parseInt(need(argv, ++i, "--connections"));
                case "--rate" -> rate = Integer.parseInt(need(argv, ++i, "--rate"));
                case "--loops" -> loops = Integer.parseInt(need(argv, ++i, "--loops"));
                case "--no-jfr" -> jfr = false;
                case "--help", "-h" -> {
                    System.out.println(usage());
                    return;
                }
                default -> {
                    System.err.println("unknown argument: " + argv[i]);
                    System.err.println(usage());
                    System.exit(2);
                }
            }
        }
        if (out == null) {
            out = Path.of("demo-" + scenario.flag() + ".jfr");
        }
        Result r = run(scenario, duration, jfr ? out : null, connections, rate, loops);
        System.out.println(r.describe());
        if (jfr) {
            System.out.println();
            System.out.println("Recording written to " + out.toAbsolutePath());
            System.out.println("Try:");
            System.out.println("  jfrq stalls " + out + " --thread 'event-loop-*' --gap 50ms");
            System.out.println("  jfrq locks  " + out);
            System.out.println("  jfrq alloc  " + out);
        }
    }

    /** What a run produced; used by the tests and printed by {@link #main}. */
    public record Result(Scenario scenario, int requests, String latency, Path recording, long lookups) {
        String describe() {
            String s = String.format(Locale.ROOT, "scenario %s: %s", scenario.flag(), latency);
            return lookups == 0 ? s : s + "; " + lookups + " synchronous backend lookups on the event loops";
        }
    }

    /**
     * Runs one scenario to completion.
     *
     * @param scenario    what to inject
     * @param duration    how long the load runs
     * @param recording   where to write the JFR file, or {@code null} to not record
     * @param connections client connections
     * @param rate        requests per second per connection; 0 for unpaced
     * @param loops       Netty event loops
     */
    public static Result run(Scenario scenario, Duration duration, Path recording, int connections, int rate,
                             int loops) throws Exception {
        Persistence persistence = new Persistence();
        SessionRegistry registry = new SessionRegistry(persistence);
        Background background = new Background();
        try (SlowBackend backend = scenario.blockingIo() ? new SlowBackend(120, 220) : null;
             Recorder recorder = recording == null ? null : new Recorder(recording);
             Server server = new Server(scenario, registry, backend == null ? -1 : backend.port(), loops)) {
            LoadClient load = new LoadClient(server.port(), connections, rate, 2_000_000);
            if (scenario.lock()) {
                background.flusher(persistence);
                background.housekeeper(registry);
            }
            if (scenario.alloc()) {
                background.allocators();
            }
            if (recorder != null) {
                recorder.start();
            }
            load.start();
            Thread.sleep(duration.toMillis());
            load.stop();
            if (recorder != null) {
                recorder.stop();
            }
            return new Result(scenario, load.completed(), load.summary(), recording, RequestHandler.lookups());
        } finally {
            background.stop();
        }
    }

    private static String need(String[] argv, int i, String flag) {
        if (i >= argv.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return argv[i];
    }

    private static Duration parseDuration(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith("ms")) {
            return Duration.ofMillis(number(t, 2));
        }
        if (t.endsWith("s")) {
            return Duration.ofSeconds(number(t, 1));
        }
        if (t.endsWith("m")) {
            return Duration.ofMinutes(number(t, 1));
        }
        return Duration.ofSeconds(number(t, 0));
    }

    private static long number(String text, int unitLength) {
        return Long.parseLong(text.substring(0, text.length() - unitLength).trim());
    }

    static String usage() {
        return """
                netty-demo: a Netty service with injected event-loop pathologies, recorded with JFR

                  --scenario S     one of %s (default all)
                  --duration D     how long to run, e.g. 20s, 2m (default 20s)
                  --out FILE       where to write the recording (default demo-<scenario>.jfr)
                  --connections N  closed-loop client connections (default 8)
                  --rate N         requests per second per connection, 0 = as fast as possible (default 200)
                  --loops N        Netty event loops (default 2)
                  --no-jfr         do not record; use with -XX:StartFlightRecording
                """.formatted(Scenario.flags());
    }
}
