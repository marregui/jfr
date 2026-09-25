// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import dev.jfrq.core.alloc.AllocationCollector;
import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionCollector;
import dev.jfrq.core.report.Html;
import dev.jfrq.core.report.Json;
import dev.jfrq.core.report.ThreadCensus;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.stalls.StallCollector;
import dev.jfrq.core.util.Durations;
import dev.jfrq.core.util.Glob;

/**
 * {@code jfrq}: ask a JFR recording one question and get the answer.
 *
 * <pre>
 *   jfrq info   recording.jfr [--json]
 *   jfrq alloc  recording.jfr [--baseline before.jfr] [--top N] [--sites] [--app PREFIX] [--html out.html] [--json]
 *   jfrq locks  recording.jfr [--min 10ms] [--thread GLOB] [--lock GLOB] [--idle REGEX,...] [--by-site] [--top N] [--html out.html] [--json]
 *   jfrq stalls recording.jfr --thread GLOB [--gap 50ms] [--idle REGEX,...] [--top N] [--html out.html] [--json]
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
              --html FILE    also write a self-contained HTML report (never over a recording read)
              --json         print the answer as one JSON document instead of text: stable
                             field names, units in the names, UTC instants (docs/JSON.md)
              --timing       print how long reading and analysing took, to stderr
              --version, --help

            alloc:
              --baseline F   compare against recording F (rates, so lengths may differ)
              --sites        list allocation sites with stacks, one row per allocating method
                             (every path through it summed, so one site is one row)
              --app PREFIX   rank those sites by the innermost frame under one of these
                             comma-separated class or package prefixes instead, e.g.
                             'com.example,org.example'; a prefix ends at a '.' or '$', so
                             'io.nett' is not 'io.netty' and 'com.x.Handler' covers
                             'com.x.Handler$Inner'; the report names the packages it saw

            locks:
              --min D        ignore waits shorter than D (default 0; e.g. 10ms); a wait that
                             crosses an end of the recording counts only the part inside it
              --thread GLOB  only waits by threads matching GLOB (e.g. 'event-loop-*,worker-?')
              --idle REGEX   comma-separated regexes naming the frame of a pool waiting for work;
                             those parks are reported apart from contention, as is any lock one
                             thread sits on for most of the recording with nobody holding it.
                             'none' reports every park as contention (default: the JDK pools,
                             Netty, logback)
              --lock GLOB    only these locks, by class or by 'class@address'
                             (e.g. 'java.lang.Object@714697020', '*Registry')
              --by-site      rank one row per stack rather than per lock instance: fifteen
                             queues of the same kind are one site with fifteen instances

            stalls:
              --thread GLOB  threads to watch (required; e.g. 'event-loop-*')
              --gap D        a stall is at least D without returning to idle (default 50ms);
                             a block that crosses an end of the recording counts only the
                             part inside it
              --idle REGEX   comma-separated regexes that mean "idle", each matching a whole
                             'pkg.Class.method' (so no commas inside one); replaces the defaults
                             (JDK selectors, Netty transports, park, Object.wait). A sleep, wait
                             or park under a frame you name is idle too. 'none' also turns off
                             the split of workers waiting for their own queue and of timer
                             loops waiting out their own timeout

            durations take a unit: 50ms, 1.5s, 2m. Options belong to their command; a stalls
            option on locks is an error, as is an option given twice, so a typo never passes
            silently.
            exit status: 0 ok, 1 could not read the recording, write the HTML report or write
            to standard output, 2 usage error (a directory for the recording, an --html target
            that cannot be written or is a recording being read)
            """.formatted(VERSION);

    private static final Set<String> COMMON_VALUED = Set.of("top", "html");
    private static final Set<String> COMMON_FLAGS = Set.of("help", "version", "timing", "json");

    /** The options each command accepts, so an option in the wrong place is a usage error. */
    private static Args parse(final String command, final String[] rest) {
        final Set<String> valued = new HashSet<>(COMMON_VALUED);
        final Set<String> flags = new HashSet<>(COMMON_FLAGS);
        switch (command) {
            case "info" -> valued.remove("top");
            case "alloc" -> {
                valued.addAll(Set.of("baseline", "app"));
                flags.add("sites");
            }
            case "locks" -> {
                valued.addAll(Set.of("min", "thread", "idle", "lock"));
                flags.add("by-site");
            }
            case "stalls" -> valued.addAll(Set.of("thread", "gap", "idle"));
            default -> throw new Args.UsageException("unknown command '" + command + "'");
        }
        return Args.parse(rest, valued, flags);
    }

    private final PrintStream out;
    private final PrintStream err;
    private boolean timing;
    /** {@code --json}: the answer as one JSON document on standard output instead of text. */
    private boolean json;
    private long phaseStart;

    Main(final PrintStream out, final PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Writes one line ending in {@code '\n'}, never the platform separator: a report is text
     * that gets diffed, pasted and compared across machines, so it reads the same on all of them.
     */
    private static void line(final PrintStream stream, final String text) {
        stream.print(text + '\n');
    }

    private void phase(final String name) {
        final long now = System.nanoTime();
        if (timing && name != null && phaseStart != 0) {
            if (name.equals("read")) {
                // The read includes the analysis, which runs in the sinks' finish(); report both.
                final long analyse = JfrReader.analyseNanos();
                err.printf("timing: %-10s %s\n", "parse", Durations.format(now - phaseStart - analyse));
                err.printf("timing: %-10s %s\n", "analyse", Durations.format(analyse));
            } else {
                err.printf("timing: %-10s %s\n", name, Durations.format(now - phaseStart));
            }
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

    /**
     * Checks a question before there is a recording to ask it of: the command, every option
     * and the files they name, exactly as {@link #run(String[], PrintStream, PrintStream)}
     * will, with {@code recording} standing in as the file. {@code jfrq-live} calls this
     * before it attaches, so a typo after {@code --} costs nothing: no dump, no cursor moved.
     * One check is added, since {@code recording} is a file about to be written: a
     * {@code --baseline} naming it is a usage error.
     *
     * @param question the command and its options, without the recording
     * @throws Args.UsageException what {@code run} would report with exit status 2, or a
     *                             baseline that is {@code recording}
     * @throws IOException         a file an option names cannot be used ({@code run} exits with 1)
     */
    public static void check(final String[] question, final Path recording) throws IOException {
        final String[] argv = new String[question.length + 1];
        argv[0] = question[0];
        argv[1] = recording.toString();
        System.arraycopy(question, 1, argv, 2, question.length - 1);
        final Args args = parse(argv[0], Arrays.copyOfRange(argv, 1, argv.length));
        resolve(argv[0], args, recordingPath(args));
        // Unlike a recording given to run, this one is about to be written: over a baseline it
        // would replace the file it is compared with before either is read.
        final Optional<String> baseline = args.option("baseline");
        if (baseline.isPresent() && sameFile(Path.of(baseline.orElseThrow()), recording)) {
            throw new Args.UsageException("--baseline " + baseline.orElseThrow()
                    + " is the file the dump is written to; the dump would replace it");
        }
    }

    /**
     * Whether a question {@link #check} accepted answers in JSON, by the same parser that will
     * run it: a caller that prints lines of its own around the answer moves them out of the way.
     */
    public static boolean answersInJson(final String[] question) {
        return parse(question[0], Arrays.copyOfRange(question, 1, question.length)).flag("json");
    }

    /**
     * The message for an I/O failure that names what went wrong. The JDK's file-system
     * exceptions carry only the path when the OS gave no reason, which reads as nothing at all.
     */
    public static String describe(final IOException e) {
        return switch (e) {
            case final NoSuchFileException n -> "no such file or directory: " + n.getFile();
            case final AccessDeniedException a -> "permission denied: " + a.getFile();
            case final FileAlreadyExistsException f -> "already exists: " + f.getFile();
            case final NotDirectoryException d -> "not a directory: " + d.getFile();
            case final FileSystemException f when f.getReason() == null -> "cannot use " + f.getFile();
            default -> e.getMessage();
        };
    }

    /** Runs a command and returns the exit status without calling {@link System#exit}. */
    int run(final String[] argv) {
        final int status = answer(argv);
        if (out.checkError()) {
            // A closed or full standard output loses the report without a word; exit 0 would
            // tell a script that it has one.
            line(err, "jfrq: cannot write the report to standard output");
            return status == 0 ? 1 : status;
        }
        return status;
    }

    private int answer(final String[] argv) {
        try {
            if (argv.length == 0 || argv[0].equals("--help") || argv[0].equals("-h") || argv[0].equals("help")) {
                out.print(USAGE);
                return 0;
            }
            if (argv[0].equals("--version")) {
                line(out, "jfrq " + VERSION);
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
                line(out, "jfrq " + VERSION);
                return 0;
            }
            timing = args.flag("timing");
            json = args.flag("json");
            // Every option is resolved and checked before the recording is opened (G-9.4): a bad
            // --html target is found in a millisecond, not after the whole analysis.
            final Question question = resolve(command, args, recordingPath(args));
            existing(question.recording());
            phase(null);
            return switch (question) {
                case final Info q -> info(q);
                case final Alloc q -> alloc(q);
                case final Locks q -> locks(q);
                case final Stalls q -> stalls(q);
            };
        } catch (final Args.UsageException e) {
            line(err, "jfrq: " + e.getMessage());
            line(err, "Run 'jfrq --help' for usage.");
            return 2;
        } catch (final HtmlWriteException e) {
            line(err, "jfrq: cannot write HTML report " + e.target + ": " + describe(e.getCause()));
            return 1;
        } catch (final IOException e) {
            line(err, "jfrq: cannot read recording: " + describe(e));
            return 1;
        } catch (final RuntimeException e) {
            // The JDK parser signals a damaged file with unchecked exceptions; say so instead of a bare trace.
            line(err, "jfrq: failed while reading the recording (damaged file?): " + e);
            e.printStackTrace(err);
            return 1;
        }
    }

    /** A question with every option resolved into a final value, before any file is read. */
    private sealed interface Question permits Info, Alloc, Locks, Stalls {
        Path recording();
    }

    private record Info(Path recording, Path html) implements Question {
    }

    private record Alloc(Path recording, Path baseline, int top, boolean sites, SiteKey key, Path html)
            implements Question {
    }

    private record Locks(Path recording, int top, long min, Glob threads, Glob locks, IdleMatcher workWaits,
            boolean bySite, Path html) implements Question {
    }

    private record Stalls(Path recording, int top, Glob threads, long gap, IdleMatcher idle,
            IdleMatcher workWaits, Path html) implements Question {
    }

    private static Question resolve(final String command, final Args args, final Path recording) throws IOException {
        return switch (command) {
            case "info" -> new Info(recording, htmlTarget(args, recording, null));
            case "alloc" -> alloc(args, recording);
            case "locks" -> locks(args, recording);
            case "stalls" -> stalls(args, recording);
            default -> throw new IllegalStateException(command);
        };
    }

    private static Alloc alloc(final Args args, final Path recording) throws IOException {
        final int top = args.top();
        final Optional<String> baselineOption = args.option("baseline");
        final Path baseline = baselineOption.isPresent() ? existing(Path.of(baselineOption.orElseThrow())) : null;
        final SiteKey key = siteKey(args);
        // --app says how to group the sites, so asking for it is asking for them.
        final boolean sites = args.flag("sites") || args.option("app").isPresent();
        return new Alloc(recording, baseline, top, sites, key, htmlTarget(args, recording, baseline));
    }

    private int info(final Info q) throws IOException {
        final ThreadCensus census = new ThreadCensus();
        final RecordingInfo info = JfrReader.read(q.recording(), census);
        phase("read");
        out.print(json ? Json.info(info, census.result(), VERSION) : Text.info(info, census.result()));
        html(q.html(), () -> Html.info(info, census.result()));
        phase("render");
        return 0;
    }

    private int alloc(final Alloc q) throws IOException {
        final AllocationCollector current = new AllocationCollector();
        if (q.baseline() != null) {
            final AllocationCollector baseline = new AllocationCollector();
            readBoth(q.recording(), current, q.baseline(), baseline);
            phase("read");
            final AllocationDiff diff = new AllocationDiff(baseline.report(), current.report());
            out.print(json ? Json.allocDiff(diff, q.top(), q.sites(), q.key(), VERSION)
                    : Text.allocDiff(diff, q.top(), q.sites(), q.key()));
            html(q.html(), () -> Html.allocDiff(diff, q.top(), q.sites(), q.key()));
        } else {
            JfrReader.read(q.recording(), current);
            phase("read");
            final AllocationReport report = current.report();
            out.print(json ? Json.alloc(report, q.top(), q.sites(), q.key(), VERSION)
                    : Text.alloc(report, q.top(), q.sites(), q.key()));
            html(q.html(), () -> Html.alloc(report, q.top(), q.sites(), q.key()));
        }
        phase("render");
        return 0;
    }

    private static Locks locks(final Args args, final Path recording) {
        final int top = args.top();
        final long min = args.durationOption("min", "0", true);
        final Glob threads = args.option("thread").map(Glob::of).orElse(Glob.any());
        if (threads.isEmpty()) {
            throw new Args.UsageException("--thread must name at least one pattern");
        }
        final Glob locks = args.option("lock").map(Glob::of).orElse(Glob.any());
        if (locks.isEmpty()) {
            throw new Args.UsageException("--lock must name at least one pattern");
        }
        return new Locks(recording, top, min, threads, locks, workWaits(args), args.flag("by-site"),
                htmlTarget(args, recording, null));
    }

    private int locks(final Locks q) throws IOException {
        final Glob locks = q.locks();
        final ContentionCollector collector = new ContentionCollector(q.min(), q.threads(), q.workWaits(),
                lock -> locks.test(lock.pretty()) || locks.test(lock.prettyClass()));
        JfrReader.read(q.recording(), collector);
        phase("read");
        out.print(json ? Json.locks(collector.report(), q.top(), q.bySite(), VERSION)
                : Text.locks(collector.report(), q.top(), q.bySite()));
        html(q.html(), () -> Html.locks(collector.report(), q.top(), q.bySite()));
        phase("render");
        return 0;
    }

    /**
     * What {@code alloc --sites} ranks by: the innermost non-JDK frame, or, when
     * {@code --app} names package prefixes, the innermost frame in one of them.
     */
    private static SiteKey siteKey(final Args args) {
        final Optional<String> app = args.option("app");
        if (app.isEmpty()) {
            return SiteKey.culpritMethod();
        }
        final List<String> prefixes = new ArrayList<>();
        for (final String prefix : app.orElseThrow().split(",", -1)) {
            if (!prefix.isBlank()) {
                prefixes.add(prefix.trim());
            }
        }
        if (prefixes.isEmpty()) {
            throw new Args.UsageException("--app must name at least one package prefix");
        }
        return SiteKey.inPackages(List.copyOf(prefixes));
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

    private static Stalls stalls(final Args args, final Path recording) {
        final int top = args.top();
        final Glob threads = Glob.of(args.option("thread")
                .orElseThrow(() -> new Args.UsageException("stalls needs --thread GLOB (see 'jfrq info' for names)")));
        if (threads.isEmpty()) {
            throw new Args.UsageException("--thread must name at least one pattern");
        }
        final long gap = args.durationOption("gap", "50ms", false);
        final Optional<String> idleOption = args.option("idle");
        // Here --idle replaces the patterns that say a *sample* is at the thread's idle point,
        // which is a different question from which parks are a worker with nothing to do. The
        // one answer that has to mean the same in both commands is "none": it is the escape
        // hatch, and an escape hatch that leaves a rule running is not one.
        final boolean none = idleOption.filter("none"::equals).isPresent();
        final IdleMatcher idle;
        try {
            idle = none ? IdleMatcher.none() : idleOption.map(IdleMatcher::of).orElse(IdleMatcher.defaults());
        } catch (final IllegalArgumentException e) {
            throw new Args.UsageException("--idle: " + e.getMessage());
        }
        return new Stalls(recording, top, threads, gap, idle, none ? IdleMatcher.none() : IdleMatcher.forWorkWaits(idle),
                htmlTarget(args, recording, null));
    }

    private int stalls(final Stalls q) throws IOException {
        final StallCollector collector = new StallCollector(q.threads(), q.idle(), q.workWaits(), q.gap());
        JfrReader.read(q.recording(), collector);
        phase("read");
        out.print(json ? Json.stalls(collector.report(), q.top(), VERSION) : Text.stalls(collector.report(), q.top()));
        html(q.html(), () -> Html.stalls(collector.report(), q.top()));
        phase("render");
        return 0;
    }

    /**
     * Reads two recordings concurrently, one per virtual thread; the parser is single-threaded
     * per file, so a diff otherwise costs two sequential passes. A failure names its file; when
     * both fail the second is suppressed into the first, and an {@link Error} stays an error.
     */
    static void readBoth(final Path a, final JfrReader.Sink sinkA, final Path b, final JfrReader.Sink sinkB) throws IOException {
        try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<RecordingInfo> first = executor.submit(() -> JfrReader.read(a, sinkA));
            final Future<RecordingInfo> second = executor.submit(() -> JfrReader.read(b, sinkB));
            Throwable failure = outcome(first, a, null);
            failure = outcome(second, b, failure);
            if (failure instanceof final IOException e) {
                throw e;
            }
            if (failure instanceof final RuntimeException e) {
                throw e;
            }
            if (failure instanceof final Error e) {
                throw e;
            }
        }
    }

    /** Waits for one read; its failure becomes {@code primary}, or is added to it as suppressed (G-4.3). */
    private static Throwable outcome(final Future<RecordingInfo> read, final Path file, final Throwable primary) {
        final Throwable failure;
        try {
            read.get();
            return primary;
        } catch (final ExecutionException e) {
            failure = switch (e.getCause()) {
                case final Error error -> error;
                case final IOException io -> new IOException(file + ": " + describe(io), io);
                default -> new IllegalStateException(file + ": " + e.getCause(), e.getCause());
            };
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = new IOException("interrupted while reading " + file, e);
        }
        if (primary == null) {
            return failure;
        }
        primary.addSuppressed(failure);
        return primary;
    }

    /** The recording argument, without looking at the file: {@link #check} has none yet. */
    private static Path recordingPath(final Args args) {
        if (args.positional().size() > 1) {
            throw new Args.UsageException("unexpected argument '" + args.positional().get(1) + "'");
        }
        return Path.of(args.first("recording file"));
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

    /**
     * The {@code --html} target, or {@code null} without one; checked here, before the analysis,
     * so a report that cannot land costs nothing. It may not be one of the recordings being
     * read: the report would replace the file it describes.
     */
    private static Path htmlTarget(final Args args, final Path recording, final Path baseline) {
        final Optional<String> option = args.option("html");
        if (option.isEmpty()) {
            return null;
        }
        final Path target = Path.of(option.orElseThrow());
        if (Files.isDirectory(target)) {
            throw new Args.UsageException("--html " + target + " is a directory");
        }
        final Path parent = target.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new Args.UsageException("--html " + target + ": no such directory " + parent);
        }
        if (!Files.isWritable(parent)) {
            throw new Args.UsageException("--html " + target + ": cannot write in " + parent);
        }
        if (sameFile(target, recording) || (baseline != null && sameFile(target, baseline))) {
            throw new Args.UsageException("--html " + target + " is a recording being read; the report would replace it");
        }
        return target;
    }

    private static boolean sameFile(final Path a, final Path b) {
        if (Files.exists(a) && Files.exists(b)) {
            try {
                return Files.isSameFile(a, b);
            } catch (final IOException e) {
                // Unreadable metadata: fall back to comparing the names.
            }
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /** Writes the HTML report to {@code target} unless it is {@code null}; the page is only built then. */
    private void html(final Path target, final Supplier<String> html) throws HtmlWriteException {
        if (target == null) {
            return;
        }
        try {
            Files.writeString(target, html.get(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new HtmlWriteException(target, e);
        }
        line(err, "HTML report written to " + target);
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

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
