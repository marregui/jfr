package dev.jfrq.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Set;

import dev.jfrq.core.alloc.AllocationCollector;
import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionCollector;
import dev.jfrq.core.report.Html;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.stalls.StallCollector;
import dev.jfrq.core.util.Glob;

/**
 * {@code jfrq}: ask a JFR recording one question and get the answer.
 *
 * <pre>
 *   jfrq info   recording.jfr
 *   jfrq alloc  recording.jfr [--baseline before.jfr] [--top N] [--sites] [--html out.html]
 *   jfrq locks  recording.jfr [--min 10ms] [--thread GLOB] [--top N] [--html out.html]
 *   jfrq stalls recording.jfr --thread GLOB [--gap 50ms] [--idle REGEX,...] [--top N] [--html out.html]
 * </pre>
 */
public final class Main {

    static final String VERSION = "0.1.0";

    static final String USAGE = """
            jfrq %s - ask a JFR recording one question

            usage: jfrq <command> <recording.jfr> [options]

            commands:
              info    what is in the recording: span, threads, event counts, active thresholds
              alloc   allocation pressure by thread, class and site; --baseline diffs two recordings
              locks   lock contention: which locks, who waited, who held them, convoys
              stalls  when a thread did not return to its idle point, and why

            common options:
              --top N        rows per table (default 15; not for info)
              --html FILE    also write a self-contained HTML report
              --timing       print how long reading and analysing took, to stderr
              --version, --help

            alloc:
              --baseline F   compare against recording F (rates, so lengths may differ)
              --sites        list allocation sites with stacks

            locks:
              --min D        ignore waits shorter than D (default 0; e.g. 10ms)
              --thread GLOB  only waits by threads matching GLOB (e.g. 'event-loop-*,worker-?')

            stalls:
              --thread GLOB  threads to watch (required; e.g. 'event-loop-*')
              --gap D        a stall is at least D without returning to idle (default 50ms)
              --idle REGEX   comma-separated regexes that mean "idle", each matching a whole
                             'pkg.Class.method' (so no commas inside one); replaces the defaults
                             (JDK selectors, Netty transports, park, Object.wait)

            durations take a unit: 50ms, 1.5s, 2m. Options belong to their command; a stalls
            option on locks is an error, so a typo never passes silently.
            exit status: 0 ok, 1 could not read the recording, 2 usage error
            """.formatted(VERSION);

    private static final Set<String> COMMON_VALUED = Set.of("top", "html");
    private static final Set<String> COMMON_FLAGS = Set.of("help", "version", "timing");

    /** The options each command accepts, so an option in the wrong place is a usage error. */
    private static Args parse(String command, String[] rest) {
        Set<String> valued = new java.util.HashSet<>(COMMON_VALUED);
        Set<String> flags = new java.util.HashSet<>(COMMON_FLAGS);
        switch (command) {
            case "info" -> valued.remove("top");
            case "alloc" -> {
                valued.add("baseline");
                flags.add("sites");
            }
            case "locks" -> valued.addAll(Set.of("min", "thread"));
            case "stalls" -> valued.addAll(Set.of("thread", "gap", "idle"));
            default -> throw new Args.UsageException("unknown command '" + command + "'");
        }
        return Args.parse(rest, valued, flags);
    }

    private final PrintStream out;
    private final PrintStream err;
    private boolean timing;
    private long phaseStart;

    Main(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    private void phase(String name) {
        long now = System.nanoTime();
        if (timing && name != null && phaseStart != 0) {
            err.printf("timing: %-10s %s%n", name, dev.jfrq.core.util.Durations.format(now - phaseStart));
        }
        phaseStart = now;
    }

    static void main(String[] argv) {
        System.exit(new Main(System.out, System.err).run(argv));
    }

    /** Runs a command and returns the exit status without calling {@link System#exit}. */
    int run(String[] argv) {
        try {
            if (argv.length == 0 || argv[0].equals("--help") || argv[0].equals("-h") || argv[0].equals("help")) {
                out.print(USAGE);
                return 0;
            }
            if (argv[0].equals("--version")) {
                out.println("jfrq " + VERSION);
                return 0;
            }
            String command = argv[0];
            String[] rest = java.util.Arrays.copyOfRange(argv, 1, argv.length);
            Args args = parse(command, rest);
            if (args.flag("help")) {
                out.print(USAGE);
                return 0;
            }
            if (args.flag("version")) {
                out.println("jfrq " + VERSION);
                return 0;
            }
            timing = args.flag("timing");
            phase(null);
            return switch (command) {
                case "info" -> info(args);
                case "alloc" -> alloc(args);
                case "locks" -> locks(args);
                case "stalls" -> stalls(args);
                default -> throw new IllegalStateException(command);
            };
        } catch (Args.UsageException e) {
            err.println("jfrq: " + e.getMessage());
            err.println("Run 'jfrq --help' for usage.");
            return 2;
        } catch (NoSuchFileException e) {
            err.println("jfrq: no such file: " + e.getFile());
            return 1;
        } catch (HtmlWriteException e) {
            err.println("jfrq: cannot write HTML report " + e.target + ": " + e.getCause().getMessage());
            return 1;
        } catch (IOException e) {
            err.println("jfrq: cannot read recording: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            // The JDK parser signals a damaged file with unchecked exceptions; say so instead of a bare trace.
            err.println("jfrq: failed while reading the recording (damaged file?): " + e);
            e.printStackTrace(err);
            return 1;
        }
    }

    private int info(Args args) throws IOException {
        Path file = recording(args);
        RecordingInfo info = JfrReader.read(file);
        phase("read");
        out.print(Text.info(info));
        html(args, () -> Html.info(info));
        phase("render");
        return 0;
    }

    private int alloc(Args args) throws IOException {
        Path file = recording(args);
        int top = args.top();
        boolean sites = args.flag("sites");
        AllocationCollector current = new AllocationCollector();

        if (args.option("baseline").isPresent()) {
            Path baselineFile = existing(Path.of(args.option("baseline").orElseThrow()));
            AllocationCollector baseline = new AllocationCollector();
            readBoth(file, current, baselineFile, baseline);
            phase("read");
            AllocationDiff diff = new AllocationDiff(baseline.report(), current.report());
            out.print(Text.allocDiff(diff, top, sites));
            html(args, () -> Html.allocDiff(diff, top));
        } else {
            JfrReader.read(file, current);
            phase("read");
            AllocationReport report = current.report();
            out.print(Text.alloc(report, top, sites));
            html(args, () -> Html.alloc(report, top));
        }
        phase("render");
        return 0;
    }

    private int locks(Args args) throws IOException {
        Path file = recording(args);
        int top = args.top();
        long min = args.durationOption("min", "0", true);
        Glob threads = args.option("thread").map(Glob::of).orElse(Glob.any());
        if (threads.isEmpty()) {
            throw new Args.UsageException("--thread must name at least one pattern");
        }
        ContentionCollector collector = new ContentionCollector(min, threads);
        JfrReader.read(file, collector);
        phase("read");
        out.print(Text.locks(collector.report(), top));
        html(args, () -> Html.locks(collector.report(), top));
        phase("render");
        return 0;
    }

    private int stalls(Args args) throws IOException {
        Path file = recording(args);
        int top = args.top();
        Glob threads = Glob.of(args.option("thread")
                .orElseThrow(() -> new Args.UsageException("stalls needs --thread GLOB (see 'jfrq info' for names)")));
        if (threads.isEmpty()) {
            throw new Args.UsageException("--thread must name at least one pattern");
        }
        long gap = args.durationOption("gap", "50ms", false);
        IdleMatcher idle;
        try {
            idle = args.option("idle").map(IdleMatcher::of).orElse(IdleMatcher.defaults());
        } catch (IllegalArgumentException e) {
            throw new Args.UsageException("--idle: " + e.getMessage());
        }
        StallCollector collector = new StallCollector(threads, idle, gap);
        JfrReader.read(file, collector);
        phase("read");
        out.print(Text.stalls(collector.report(), top));
        html(args, () -> Html.stalls(collector.report(), top));
        phase("render");
        return 0;
    }

    /**
     * Reads two recordings concurrently, one per virtual thread; the parser is single-threaded
     * per file, so a diff otherwise costs two sequential passes.
     */
    private static void readBoth(Path a, JfrReader.Sink sinkA, Path b, JfrReader.Sink sinkB) throws IOException {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> JfrReader.read(a, sinkA));
            var second = executor.submit(() -> JfrReader.read(b, sinkB));
            first.get();
            second.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading", e);
        }
    }

    private static Path recording(Args args) throws IOException {
        if (args.positional().size() > 1) {
            throw new Args.UsageException("unexpected argument '" + args.positional().get(1) + "'");
        }
        return existing(Path.of(args.first("recording file")));
    }

    private static Path existing(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            throw new Args.UsageException(p + " is a directory, not a recording");
        }
        if (!Files.isRegularFile(p)) {
            throw new NoSuchFileException(p.toString());
        }
        return p;
    }

    /** Writes the HTML report if {@code --html} was given; the page is only built then. */
    private void html(Args args, java.util.function.Supplier<String> html) throws HtmlWriteException {
        if (args.option("html").isEmpty()) {
            return;
        }
        Path target = Path.of(args.option("html").orElseThrow());
        try {
            Files.writeString(target, html.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HtmlWriteException(target, e);
        }
        err.println("HTML report written to " + target);
    }

    /** Distinguishes a failed report write from a failed recording read: both are I/O. */
    private static final class HtmlWriteException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final transient Path target;

        HtmlWriteException(Path target, IOException cause) {
            super(cause);
            this.target = target;
        }
    }
}
