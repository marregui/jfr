// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.live;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.jfrq.cli.Args;
import dev.jfrq.cli.Main;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.util.Bytes;
import dev.jfrq.core.util.Durations;
import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;

/**
 * {@code jfrq-live}: the loop that works on a running JVM. A live recording cannot be read
 * in place (its last chunk is open; see {@code docs/DESIGN.md} section 6), so each
 * question is asked of a dump: {@code full} for everything the recording holds,
 * {@code delta} for what happened since the previous dump, {@code again} for the previous
 * window once more. A cursor file per JVM remembers where the last dump stopped. Every
 * dump is followed by the span check ({@code jfrq info} on the file, against the window
 * that was asked for) and, after {@code --}, by the {@code jfrq} question to run on it.
 *
 * <pre>
 *   jfrq-live 4242 start --max-age 10m
 *   jfrq-live 4242 full  -- stalls --thread 'event-loop-*'
 *   jfrq-live 4242 delta -- stalls --thread 'event-loop-*'
 *   jfrq-live 4242 delta --out t2.jfr -- alloc --baseline t1.jfr
 * </pre>
 */
public final class Live {

    /** The settings README.md recommends on top of a JDK profile: short waits recorded, I/O unthrottled. */
    static final Map<String, String> RECOMMENDED = Map.ofEntries(
            Map.entry("jdk.JavaMonitorEnter#threshold", "1 ms"),
            Map.entry("jdk.ThreadPark#threshold", "1 ms"),
            Map.entry("jdk.ThreadSleep#threshold", "1 ms"),
            Map.entry("jdk.SocketRead#threshold", "1 ms"),
            Map.entry("jdk.SocketWrite#threshold", "1 ms"),
            Map.entry("jdk.FileRead#threshold", "1 ms"),
            Map.entry("jdk.FileWrite#threshold", "1 ms"),
            Map.entry("jdk.SocketRead#throttle", "off"),
            Map.entry("jdk.SocketWrite#throttle", "off"),
            Map.entry("jdk.FileRead#throttle", "off"),
            Map.entry("jdk.FileWrite#throttle", "off"),
            Map.entry("jdk.ExecutionSample#period", "10 ms"),
            Map.entry("jdk.NativeMethodSample#period", "10 ms"),
            Map.entry("jdk.ObjectAllocationSample#throttle", "1000/s"));

    static final String USAGE = """
            jfrq-live %s - ask a running JVM's flight recording one question at a time

            usage: jfrq-live <pid> <command> [options] [-- <jfrq command> [jfrq options]]

            commands:
              status  the JVM's recordings (state, bounds) and this tool's cursor for it
              start   start a recording in the JVM: the JDK 'profile' settings with the thresholds
                      README.md recommends, or --settings NAME for another JDK profile as recommended,
                      or --settings FILE.jfc taken as it is
              bound   set --max-age / --max-size on the running recording
              full    dump everything the recording holds; the cursor moves to the dump's end
              delta   dump what happened since the cursor; the cursor moves to the dump's end
              again   dump the previous window once more; the cursor stays
              stop    stop and close the recording

            options:
              --recording ID|NAME  which recording, when the JVM runs more than one
              --out FILE           where the dump goes (default <pid>-<command>-<HHmmss>.jfr here)
              --state DIR          where cursors are kept (default ~/.jfrq/live)
              --max-age D          keep this much recent data: 10m, 1h; 0 removes the bound (start, bound)
              --max-size SIZE      keep this much data: 200MB, 1GB; 0 removes the bound (start, bound)
              --settings NAME|FILE JDK profile name (default, profile) or a .jfc file (start)
              --name NAME          the recording's name (start; default jfrq-live)
              --version, --help

            after --: a jfrq command and its options, run on the dump; the file is inserted for you:
              jfrq-live 4242 delta -- stalls --thread 'event-loop-*'
              jfrq-live 4242 delta --out t2.jfr -- alloc --baseline t1.jfr

            every dump prints its actual span next to the window asked for: dumps are whole
            chunks, and a window the JVM has already discarded (max-age, max-size) comes back
            shorter than asked. exit status: 0 ok, 1 the JVM or the dump failed, 2 usage error
            """.formatted(Main.VERSION);

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final String DEFAULT_NAME = "jfrq-live";
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final Set<String> FLAGS = Set.of("help", "version");
    private static final Pattern SIZE = Pattern.compile("\\s*(\\d+)\\s*([kKmMgGtT]?)[bB]?\\s*");
    /** Chunk boundaries are why a file and its window differ by a little; more than this is worth a line. */
    private static final Duration SLACK = Duration.ofSeconds(1);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final PrintStream err;
    private final PrintStream out;

    Live(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    static void main(String[] argv) {
        System.exit(new Live(System.out, System.err).run(argv));
    }

    /** {@code 14:03:07.121 .. 14:05:12.004}, with "start" and "now" for the open ends. */
    static String describe(Window w) {
        return (w.begin() == null ? "the start" : TIME.format(w.begin())) + " .. "
                + (w.end() == null ? "now" : TIME.format(w.end()));
    }

    /** Runs a command and returns the exit status without calling {@link System#exit}. */
    int run(String[] argv) {
        try {
            if (argv.length == 0 || argv[0].equals("--help") || argv[0].equals("-h") || argv[0].equals("help")) {
                out.print(USAGE);
                return 0;
            }
            if (argv[0].equals("--version")) {
                out.println("jfrq-live " + Main.VERSION);
                return 0;
            }
            String pid = argv[0];
            if (!pid.chars().allMatch(Character::isDigit)) {
                throw new Args.UsageException("the first argument is the JVM's pid, not '" + pid + "'");
            }
            if (argv.length == 1) {
                throw new Args.UsageException("missing command after the pid");
            }
            String command = argv[1];
            int separator = Arrays.asList(argv).indexOf("--");
            String[] rest = Arrays.copyOfRange(argv, 2, separator < 0 ? argv.length : separator);
            String[] question = separator < 0 ? new String[0] : Arrays.copyOfRange(argv, separator + 1, argv.length);
            Args args = Args.parse(rest, valued(command), FLAGS);
            if (args.flag("help")) {
                out.print(USAGE);
                return 0;
            }
            if (args.flag("version")) {
                out.println("jfrq-live " + Main.VERSION);
                return 0;
            }
            if (!args.positional().isEmpty()) {
                throw new Args.UsageException("unexpected argument '" + args.positional().getFirst() + "'");
            }
            boolean dumps = command.equals("full") || command.equals("delta") || command.equals("again");
            if (question.length > 0 && !dumps) {
                throw new Args.UsageException("only full, delta and again take a jfrq command after --");
            }
            if (separator >= 0 && question.length == 0) {
                throw new Args.UsageException("nothing after --: name the jfrq command to run on the dump");
            }
            try (Jvm jvm = Jvm.attach(pid)) {
                return switch (command) {
                    case "status" -> status(jvm, args);
                    case "start" -> start(jvm, args);
                    case "bound" -> bound(jvm, args);
                    case "stop" -> stop(jvm, args);
                    default -> dump(jvm, command, args, question);
                };
            }
        } catch (Args.UsageException e) {
            err.println("jfrq-live: " + e.getMessage());
            err.println("Run 'jfrq-live --help' for usage.");
            return 2;
        } catch (IOException e) {
            err.println("jfrq-live: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The recorder's own refusals: a recording in the wrong state, a setting it does not know.
            err.println("jfrq-live: the JVM refused: " + e.getMessage());
            return 1;
        }
    }

    /** The options each command accepts, so an option in the wrong place is a usage error. */
    private static Set<String> valued(String command) {
        Set<String> common = Set.of("recording", "state");
        return switch (command) {
            case "status", "stop" -> common;
            case "bound" -> union(common, "max-age", "max-size");
            case "start" -> union(common, "max-age", "max-size", "settings", "name");
            case "full", "delta", "again" -> union(common, "out");
            default -> throw new Args.UsageException("unknown command '" + command + "'");
        };
    }

    private static Set<String> union(Set<String> base, String... more) {
        Set<String> s = new HashSet<>(base);
        s.addAll(Arrays.asList(more));
        return s;
    }

    private int status(Jvm jvm, Args args) throws IOException {
        out.println(jvmLine(jvm));
        List<RecordingInfo> recordings = jvm.flightRecorder().getRecordings();
        if (recordings.isEmpty()) {
            out.println("Recording  none: start one with 'jfrq-live " + jvm.pid()
                    + " start', or run the JVM with -XX:StartFlightRecording");
        }
        for (RecordingInfo r : recordings) {
            out.println(recordingLine(r));
        }
        out.println(cursorLine(cursor(jvm, args)));
        return 0;
    }

    private int start(Jvm jvm, Args args) throws IOException {
        FlightRecorderMXBean fr = jvm.flightRecorder();
        String settings = args.option("settings").orElse("profile");
        Map<String, String> options = new HashMap<>();
        options.put("name", args.option("name").orElse(DEFAULT_NAME));
        boolean bounded = bounds(args, options);
        long id = fr.newRecording();
        try {
            if (settings.endsWith(".jfc")) {
                fr.setConfiguration(id, Files.readString(Path.of(settings), StandardCharsets.UTF_8));
            } else {
                fr.setPredefinedConfiguration(id, settings);
                // setRecordingSettings replaces the whole map, so the profile is re-read and overlaid.
                Map<String, String> merged = new HashMap<>(fr.getRecordingSettings(id));
                merged.putAll(RECOMMENDED);
                fr.setRecordingSettings(id, merged);
            }
            fr.setRecordingOptions(id, options);
            fr.startRecording(id);
        } catch (IOException | RuntimeException e) {
            fr.closeRecording(id);
            throw e;
        }
        out.println(jvmLine(jvm));
        out.println(recordingLine(recording(fr, id)));
        if (!bounded) {
            out.println(unboundedWarning(jvm));
        }
        return 0;
    }

    private int bound(Jvm jvm, Args args) throws IOException {
        Map<String, String> options = new HashMap<>();
        if (!bounds(args, options)) {
            throw new Args.UsageException("bound needs --max-age and/or --max-size");
        }
        FlightRecorderMXBean fr = jvm.flightRecorder();
        RecordingInfo r = pick(jvm, args);
        fr.setRecordingOptions(r.getId(), options);
        out.println(recordingLine(recording(fr, r.getId())));
        return 0;
    }

    private int stop(Jvm jvm, Args args) throws IOException {
        FlightRecorderMXBean fr = jvm.flightRecorder();
        RecordingInfo r = pick(jvm, args);
        fr.stopRecording(r.getId());
        fr.closeRecording(r.getId());
        out.println(String.format(Locale.ROOT, "Stopped    %d  %s  and closed it; the JVM discards its data", r.getId(),
                r.getName()));
        return 0;
    }

    private int dump(Jvm jvm, String command, Args args, String[] question) throws IOException {
        FlightRecorderMXBean fr = jvm.flightRecorder();
        RecordingInfo r = pick(jvm, args);
        Cursor cursor = cursor(jvm, args);
        Window window = switch (command) {
            case "full" -> Window.EVERYTHING;
            case "delta" -> {
                if (cursor.next() == null) {
                    throw new Args.UsageException("no cursor for JVM " + jvm.pid() + " yet: run 'full' first");
                }
                yield new Window(cursor.next(), null);
            }
            default -> {
                if (cursor.lastWindow() == null) {
                    throw new Args.UsageException("no window to repeat for JVM " + jvm.pid()
                            + ": run 'full' or 'delta' first");
                }
                yield cursor.lastWindow();
            }
        };
        Path file = args.option("out").map(Path::of)
                .orElseGet(() -> Path.of(jvm.pid() + "-" + command + "-" + FILE_STAMP.format(Instant.now()) + ".jfr"));
        Snapshot snapshot = Snapshot.take(fr, r.getId(), window, file);
        boolean again = command.equals("again");
        if (!again) {
            cursor.advance(window, snapshot.stop());
        }
        check(snapshot, window, command, r, jvm);
        out.println(cursorLine(cursor));
        if (question.length == 0) {
            return 0;
        }
        out.println();
        List<String> argv = new ArrayList<>(question.length + 1);
        argv.add(question[0]);
        argv.add(file.toString());
        argv.addAll(Arrays.asList(question).subList(1, question.length));
        return Main.run(argv.toArray(String[]::new), out, err);
    }

    /** The span check: what the file holds against what was asked, and why they differ when they do. */
    private void check(Snapshot snapshot, Window window, String command, RecordingInfo r, Jvm jvm) throws IOException {
        dev.jfrq.core.jfr.RecordingInfo info = JfrReader.read(snapshot.file());
        Instant start = Instant.ofEpochSecond(0, info.startNanos());
        Instant end = Instant.ofEpochSecond(0, info.endNanos());
        out.println(String.format(Locale.ROOT, "Dumped     %s  %s, %d chunk%s, %s .. %s (%s)", snapshot.file(),
                Bytes.format(snapshot.bytes()), info.chunks(), info.chunks() == 1 ? "" : "s", TIME.format(start),
                TIME.format(end), Durations.format(info.duration())));
        for (String w : info.warnings()) {
            out.println("WARNING    " + w);
        }
        String why = switch (command) {
            case "full" -> "everything the recording kept";
            case "delta" -> "since the previous dump";
            default -> "the previous window again";
        };
        out.println(String.format(Locale.ROOT, "Window     %s (%s)", describe(window), why));
        if (window.begin() != null) {
            Duration early = Duration.between(start, window.begin());
            if (early.compareTo(SLACK) > 0) {
                out.println("Note       the file starts " + Durations.format(early)
                        + " before the window: the JVM hands over whole chunks");
            } else if (early.negated().compareTo(SLACK) > 0) {
                out.println("WARNING    the file starts " + Durations.format(early.negated())
                        + " after the window: the JVM had already discarded that data (" + bounds(r) + ")");
            }
        }
        if (window.end() != null) {
            Duration late = Duration.between(window.end(), end);
            if (late.compareTo(SLACK) > 0) {
                out.println("Note       the file ends " + Durations.format(late)
                        + " after the window: the JVM hands over whole chunks");
            }
        }
        if (command.equals("full") && r.getMaxAge() == 0 && r.getMaxSize() == 0) {
            out.println(unboundedWarning(jvm));
        }
    }

    /** {@code --max-age} and {@code --max-size} as recorder options; {@code true} when either was given. */
    private static boolean bounds(Args args, Map<String, String> options) {
        boolean any = false;
        if (args.option("max-age").isPresent()) {
            long nanos = args.durationOption("max-age", "0", true);
            // The recorder takes a timespan with a unit; 0 is its own "no bound".
            options.put("maxAge", nanos / 1_000_000L + " ms");
            any = true;
        }
        if (args.option("max-size").isPresent()) {
            options.put("maxSize", Long.toString(parseSize(args.option("max-size").orElseThrow())));
            any = true;
        }
        return any;
    }

    /** {@code 200MB}, {@code 1g}, {@code 0}: bytes, decimal units as every size jfrq prints. */
    static long parseSize(String text) {
        Matcher m = SIZE.matcher(text);
        if (!m.matches()) {
            throw new Args.UsageException("--max-size: not a size: '" + text + "' (e.g. 200MB, 1GB, 0)");
        }
        long unit = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "" -> 1L;
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "g" -> 1_000_000_000L;
            default -> 1_000_000_000_000L;
        };
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new Args.UsageException("--max-size: too large: " + text);
        }
        if (n > Long.MAX_VALUE / unit) {
            throw new Args.UsageException("--max-size: too large: " + text);
        }
        return n * unit;
    }

    /** The recording to act on: {@code --recording} by id or name, else the JVM's single running one. */
    private static RecordingInfo pick(Jvm jvm, Args args) throws IOException {
        List<RecordingInfo> all = jvm.flightRecorder().getRecordings();
        Optional<String> wanted = args.option("recording");
        if (wanted.isPresent()) {
            String w = wanted.get();
            for (RecordingInfo r : all) {
                if (w.equals(r.getName()) || w.equals(Long.toString(r.getId()))) {
                    return r;
                }
            }
            throw new IOException("JVM " + jvm.pid() + " has no recording '" + w + "'; 'jfrq-live " + jvm.pid()
                    + " status' lists them");
        }
        List<RecordingInfo> running = new ArrayList<>();
        for (RecordingInfo r : all) {
            if ("RUNNING".equals(r.getState())) {
                running.add(r);
            }
        }
        if (running.isEmpty()) {
            throw new IOException("JVM " + jvm.pid() + " has no running recording: start one with 'jfrq-live "
                    + jvm.pid() + " start', or run the JVM with -XX:StartFlightRecording");
        }
        if (running.size() > 1) {
            StringBuilder sb = new StringBuilder("JVM " + jvm.pid() + " has " + running.size()
                    + " running recordings; pick one with --recording ID|NAME:");
            for (RecordingInfo r : running) {
                sb.append(' ').append(r.getId()).append('=').append(r.getName());
            }
            throw new IOException(sb.toString());
        }
        return running.getFirst();
    }

    private static RecordingInfo recording(FlightRecorderMXBean fr, long id) throws IOException {
        for (RecordingInfo r : fr.getRecordings()) {
            if (r.getId() == id) {
                return r;
            }
        }
        throw new IOException("the JVM lost recording " + id);
    }

    private static Cursor cursor(Jvm jvm, Args args) throws IOException {
        Path dir = args.option("state").map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".jfrq", "live"));
        return Cursor.load(dir, jvm.pid(), jvm.startTime());
    }

    private static String jvmLine(Jvm jvm) {
        return String.format(Locale.ROOT, "JVM        %s, started %s, %s", jvm.name(),
                DATE_TIME.format(Instant.ofEpochMilli(jvm.startTime())), jvm.vmVersion());
    }

    private static String recordingLine(RecordingInfo r) {
        String since = r.getStartTime() == 0 ? "not started" : "since " + TIME.format(Instant.ofEpochMilli(r.getStartTime()));
        return String.format(Locale.ROOT, "Recording  %-3d %-16s %-8s %s, %s, %s", r.getId(), r.getName(), r.getState(),
                since, bounds(r), r.isToDisk() ? "to disk" : "in memory");
    }

    private static String bounds(RecordingInfo r) {
        if (r.getMaxAge() == 0 && r.getMaxSize() == 0) {
            return "no bound";
        }
        StringBuilder sb = new StringBuilder();
        if (r.getMaxAge() != 0) {
            sb.append("max-age ").append(Durations.format(Duration.ofSeconds(r.getMaxAge())));
        }
        if (r.getMaxSize() != 0) {
            sb.append(sb.isEmpty() ? "" : ", ").append("max-size ").append(Bytes.format(r.getMaxSize()));
        }
        return sb.toString();
    }

    private static String cursorLine(Cursor c) {
        if (c.next() == null) {
            return "Cursor     none: 'full' sets it (" + c.file() + ")";
        }
        return String.format(Locale.ROOT, "Cursor     next delta from %s; last window %s (%s)", TIME.format(c.next()),
                describe(c.lastWindow()), c.file());
    }

    private static String unboundedWarning(Jvm jvm) {
        return "WARNING    the recording has no bound: the JVM keeps every chunk and each 'full' dump is bigger"
                + " than the last; 'jfrq-live " + jvm.pid() + " bound --max-age 10m' keeps the recent ten minutes";
    }
}
