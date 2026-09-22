// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import dev.jfrq.core.alloc.AllocationCollector;
import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionCollector;
import dev.jfrq.core.report.Html;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.stalls.StallCollector;
import dev.jfrq.core.util.Durations;
import dev.jfrq.core.util.Glob;

/**
 * {@code jfrq}: ask a JFR recording one question and get the answer.
 *
 * <pre>
 *   jfrq info   recording.jfr
 *   jfrq alloc  recording.jfr [--baseline before.jfr] [--top N] [--sites] [--html out.html]
 *   jfrq locks  recording.jfr [--min 10ms] [--thread GLOB] [--idle REGEX,...] [--top N] [--html out.html]
 *   jfrq stalls recording.jfr --thread GLOB [--gap 50ms] [--idle REGEX,...] [--top N] [--html out.html]
 * </pre>
 */
public final class Main {

    public static final String VERSION = "0.1.0";

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
              --idle REGEX   comma-separated regexes naming the frame of a pool waiting for work;
                             those parks are reported apart from contention. 'none' reports every
                             park as contention (default: the JDK pools, Netty, logback)

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
    private static Args parse(final String command, final String[] rest) {
        final Set<String> valued = new HashSet<>(COMMON_VALUED);
        final Set<String> flags = new HashSet<>(COMMON_FLAGS);
        switch (command) {
            case "info" -> valued.remove("top");
            case "alloc" -> {
                valued.add("baseline");
                flags.add("sites");
            }
            case "locks" -> valued.addAll(Set.of("min", "thread", "idle"));
            case "stalls" -> valued.addAll(Set.of("thread", "gap", "idle"));
            default -> throw new Args.UsageException("unknown command '" + command + "'");
        }
        return Args.parse(rest, valued, flags);
    }

    private final PrintStream out;
    private final PrintStream err;
    private boolean timing;
    private long phaseStart;

    Main(final PrintStream out, final PrintStream err) {
        this.out = out;
        this.err = err;
    }

    private void phase(final String name) {
        final long now = System.nanoTime();
        if (timing && name != null && phaseStart != 0) {
            err.printf("timing: %-10s %s%n", name, Durations.format(now - phaseStart));
        }
        phaseStart = now;
    }

    static void main(final String[] argv) {
        System.exit(new Main(System.out, System.err).run(argv));
    }

    /**
     * Runs a command from another program ({@code jfrq-live} runs one on each dump it
     * takes) and returns the exit status without calling {@link System#exit}.
     */
    public static int run(final String[] argv, final PrintStream out, final PrintStream err) {
        return new Main(out, err).run(argv);
    }

    /** Runs a command and returns the exit status without calling {@link System#exit}. */
    int run(final String[] argv) {
        try {
            if (argv.length == 0 || argv[0].equals("--help") || argv[0].equals("-h") || argv[0].equals("help")) {
                out.print(USAGE);
                return 0;
            }
            if (argv[0].equals("--version")) {
                out.println("jfrq " + VERSION);
                return 0;
            }
            final String command = argv[0];
            final String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
            final Args args = parse(command, rest);
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
        } catch (final Args.UsageException e) {
            err.println("jfrq: " + e.getMessage());
            err.println("Run 'jfrq --help' for usage.");
            return 2;
        } catch (final NoSuchFileException e) {
            err.println("jfrq: no such file: " + e.getFile());
            return 1;
        } catch (final HtmlWriteException e) {
            err.println("jfrq: cannot write HTML report " + e.target + ": " + e.getCause().getMessage());
            return 1;
        } catch (final IOException e) {
            err.println("jfrq: cannot read recording: " + e.getMessage());
            return 1;
        } catch (final RuntimeException e) {
            // The JDK parser signals a damaged file with unchecked exceptions; say so instead of a bare trace.
            err.println("jfrq: failed while reading the recording (damaged file?): " + e);
            e.printStackTrace(err);
            return 1;
        }
    }

    private int info(final Args args) throws IOException {
        final Path file = recording(args);
        final RecordingInfo info = JfrReader.read(file);
        phase("read");
        out.print(Text.info(info));
        html(args, () -> Html.info(info));
        phase("render");
        return 0;
    }

    private int alloc(final Args args) throws IOException {
        final Path file = recording(args);
        final int top = args.top();
        final boolean sites = args.flag("sites");
        final AllocationCollector current = new AllocationCollector();

        if (args.option("baseline").isPresent()) {
            final Path baselineFile = existing(Path.of(args.option("baseline").orElseThrow()));
            final AllocationCollector baseline = new AllocationCollector();
            readBoth(file, current, baselineFile, baseline);
            phase("read");
            final AllocationDiff diff = new AllocationDiff(baseline.report(), current.report());
            out.print(Text.allocDiff(diff, top, sites));
            html(args, () -> Html.allocDiff(diff, top));
        } else {
            JfrReader.read(file, current);
            phase("read");
            final AllocationReport report = current.report();
            out.print(Text.alloc(report, top, sites));
            html(args, () -> Html.alloc(report, top));
        }
        phase("render");
        return 0;
    }

    private int locks(final Args args) throws IOException {
        final Path file = recording(args);
        final int top = args.top();
        final long min = args.durationOption("min", "0", true);
        final Glob threads = args.option("thread").map(Glob::of).orElse(Glob.any());
        if (threads.isEmpty()) {
            throw new Args.UsageException("--thread must name at least one pattern");
        }
        final ContentionCollector collector = new ContentionCollector(min, threads, workWaits(args));
        JfrReader.read(file, collector);
        phase("read");
        out.print(Text.locks(collector.report(), top));
        html(args, () -> Html.locks(collector.report(), top));
        phase("render");
        return 0;
    }

    /**
     * The patterns that decide which parks are a worker waiting for its own queue:
     * {@code --idle none} turns the split off and reports every park as contention, as
     * before this option existed.
     */
    private static IdleMatcher workWaits(final Args args) {
        final String spec = args.option("idle").orElse(null);
        if (spec == null) {
            return IdleMatcher.forWorkWaits();
        }
        if (spec.equals("none")) {
            return IdleMatcher.none();
        }
        try {
            return IdleMatcher.workWaits(spec);
        } catch (final IllegalArgumentException e) {
            throw new Args.UsageException("--idle: " + e.getMessage());
        }
    }

    private int stalls(final Args args) throws IOException {
        final Path file = recording(args);
        final int top = args.top();
        final Glob threads = Glob.of(args.option("thread")
                .orElseThrow(() -> new Args.UsageException("stalls needs --thread GLOB (see 'jfrq info' for names)")));
        if (threads.isEmpty()) {
            throw new Args.UsageException("--thread must name at least one pattern");
        }
        final long gap = args.durationOption("gap", "50ms", false);
        IdleMatcher idle;
        try {
            idle = args.option("idle").map(IdleMatcher::of).orElse(IdleMatcher.defaults());
        } catch (final IllegalArgumentException e) {
            throw new Args.UsageException("--idle: " + e.getMessage());
        }
        final StallCollector collector = new StallCollector(threads, idle, gap);
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
    private static void readBoth(final Path a, final JfrReader.Sink sinkA, final Path b, final JfrReader.Sink sinkB) throws IOException {
        try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<RecordingInfo> first = executor.submit(() -> JfrReader.read(a, sinkA));
            final Future<RecordingInfo> second = executor.submit(() -> JfrReader.read(b, sinkB));
            first.get();
            second.get();
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading", e);
        }
    }

    private static Path recording(final Args args) throws IOException {
        if (args.positional().size() > 1) {
            throw new Args.UsageException("unexpected argument '" + args.positional().get(1) + "'");
        }
        return existing(Path.of(args.first("recording file")));
    }

    private static Path existing(final Path p) throws IOException {
        if (Files.isDirectory(p)) {
            throw new Args.UsageException(p + " is a directory, not a recording");
        }
        if (!Files.isRegularFile(p)) {
            throw new NoSuchFileException(p.toString());
        }
        return p;
    }

    /** Writes the HTML report if {@code --html} was given; the page is only built then. */
    private void html(final Args args, final Supplier<String> html) throws HtmlWriteException {
        if (args.option("html").isEmpty()) {
            return;
        }
        final Path target = Path.of(args.option("html").orElseThrow());
        try {
            Files.writeString(target, html.get(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new HtmlWriteException(target, e);
        }
        err.println("HTML report written to " + target);
    }

    /** Distinguishes a failed report write from a failed recording read: both are I/O. */
    private static final class HtmlWriteException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
        private final transient Path target;

        HtmlWriteException(final Path target, final IOException cause) {
            super(cause);
            this.target = target;
        }
    }
}
