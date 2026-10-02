// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.jfrq.core.report.JsonParser;

/**
 * The installed tools, run the way a user runs them: the {@code bin/} launchers of
 * {@code jfrq} and {@code jfrq-live}, on recordings of {@code ci/Workload.java}. The unit
 * tests call {@code Main} in-process; this is what they cannot see: the start scripts, the
 * JVM options they pass, attaching to another JVM, and files written and read back.
 *
 * <pre>
 *   java -cp core/build/libs/core-VERSION-test-fixtures.jar ci/Smoke.java live
 *   java -cp ... ci/Smoke.java offline recording.jfr
 * </pre>
 *
 * {@code live} starts the workload under this JVM's {@code java} with NMT on, starts a
 * recording in it with {@code jfrq-live}, dumps it twice with a question each time, and asks
 * every command of the two dumps. {@code offline} asks every command of a recording made
 * elsewhere, such as by an older JDK. Exits 1 with every failed check listed.
 */
public final class Smoke {

    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path JFRQ = launcher("cli", "jfrq");
    private static final Path LIVE = launcher("live", "jfrq-live");
    private static final Path OUT = ROOT.resolve("build").resolve("smoke");
    private static final List<String> FAILED = new ArrayList<>();

    public static void main(final String[] args) throws Exception {
        Files.createDirectories(OUT);
        switch (args.length > 0 ? args[0] : "") {
            case "live" -> live();
            case "offline" -> offline(Path.of(args[1]).toAbsolutePath(), args.length > 2 && args[2].equals("--jdk21"));
            default -> throw new IllegalArgumentException("usage: Smoke live | offline FILE [--jdk21]");
        }
        if (!FAILED.isEmpty()) {
            System.out.println("\n" + FAILED.size() + " check(s) failed:");
            for (final String f : FAILED) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("\nall checks passed");
    }

    private static void live() throws Exception {
        final Run version = run(JFRQ, "--version");
        expect(version, 0);
        check(version.out().startsWith("jfrq "), "jfrq --version prints its name: " + version.out());

        final Path java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
        final Process workload = new ProcessBuilder(java.toString(), "-XX:NativeMemoryTracking=summary",
                ROOT.resolve("ci").resolve("Workload.java").toString(), "120")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            awaitReady(workload);
            final String pid = Long.toString(workload.pid());
            final Path state = Files.createDirectories(OUT.resolve("state"));
            final Path a = OUT.resolve("a.jfr");
            final Path b = OUT.resolve("b.jfr");

            final Run start = run(LIVE, pid, "start", "--state", state.toString());
            expect(start, 0);
            check(start.out().contains("RUNNING"), "jfrq-live start shows the recording running");
            // Two ThreadCPULoad periods (10 s in the profile settings) and a dozen bursts.
            Thread.sleep(25_000);

            final Run full = run(LIVE, pid, "full", "--state", state.toString(), "--out", a.toString(), "--",
                    "info", "--thread", "worker-*");
            expect(full, 0);
            check(full.out().contains("STARTS OF worker-*"), "full -- info --thread lists the starts");
            check(full.out().contains("CREATED BY"), "full -- info --thread lists the creators");
            check(Files.size(a) > 0, "the full dump is written");
            Thread.sleep(12_000);

            final Run delta = run(LIVE, pid, "delta", "--state", state.toString(), "--out", b.toString(), "--",
                    "locks");
            expect(delta, 0);
            check(delta.out().contains("LOCKS BY TOTAL WAIT"), "delta -- locks prints the lock table");
            check(delta.out().contains("java.lang.Object@"), "the housekeeper's lock is in the delta");

            final Run compared = run(JFRQ, "health", a.toString(), b.toString(), "--json");
            expect(compared, 0);
            final List<Object> reports = list(json(compared, "health a b --json"), "reports");
            check(reports.size() == 2, "health of two recordings has two reports: " + reports.size());
            if (!reports.isEmpty()) {
                final Map<String, Object> first = map(reports.getFirst());
                check(number(map(first.get("threadCpu")), "readings") > 0, "the full dump has thread CPU readings");
                check(!list(first, "nativeMemory").isEmpty(), "NMT was on: native memory is reported");
            }
            // UTF-8 on every OS: the arrow of a range survives the launcher, also on Windows.
            final Run text = run(JFRQ, "health", a.toString(), b.toString());
            expect(text, 0);
            check(text.out().contains(" \u2192 "), "health of two recordings prints its ranges with an arrow, in UTF-8");
            final Path html = OUT.resolve("health.html");
            expect(run(JFRQ, "health", a.toString(), b.toString(), "--html", html.toString()), 0);
            check(Files.exists(html) && Files.readString(html).contains("</html>"), "the HTML comparison is written");

            final Run stop = run(LIVE, pid, "stop", "--state", state.toString());
            expect(stop, 0);

            commands(a, "a.jfr");
            // Mistakes a user makes: the exit status says which kind.
            expect(run(JFRQ, "info", OUT.resolve("missing.jfr").toString()), 1);
            expect(run(JFRQ, "health", a.toString(), a.toString()), 2);
            expect(run(JFRQ, "info", "--top", "3", a.toString()), 2);
        } finally {
            workload.destroy();
            if (!workload.waitFor(20, TimeUnit.SECONDS)) {
                workload.destroyForcibly();
            }
        }
    }

    private static void offline(final Path recording, final boolean jdk21) throws Exception {
        commands(recording, recording.getFileName().toString());
        final Run health = run(JFRQ, "health", recording.toString());
        expect(health, 0);
        if (jdk21) {
            // JDK 21's profile settings leave jdk.JavaExceptionThrow off; health names the setting.
            check(health.out().contains("jdk.JavaExceptionThrow#enabled=true"), "health names the setting that "
                    + "records throwables, which JDK 21's profile leaves off");
        }
        final Map<String, Object> info = json(run(JFRQ, "info", recording.toString(), "--json"), "info --json");
        check(number(map(info.get("threadCpu")), "readings") > 0, "the recording has thread CPU readings");
    }

    /** Every command on one recording, text and JSON, each answering with exit status 0. */
    private static void commands(final Path recording, final String name) throws Exception {
        final String file = recording.toString();
        for (final String[] question : new String[][] {
                {"info", file}, {"info", file, "--thread", "event-loop-*"}, {"health", file}, {"locks", file},
                {"locks", file, "--by-site"}, {"alloc", file}, {"alloc", file, "--sites"},
                {"stalls", file, "--thread", "event-loop-*"}}) {
            final Run text = run(JFRQ, question);
            expect(text, 0);
            final String[] withJson = new String[question.length + 1];
            System.arraycopy(question, 0, withJson, 0, question.length);
            withJson[question.length] = "--json";
            final Map<String, Object> doc = json(run(JFRQ, withJson), String.join(" ", question) + " --json");
            check(question[0].equals(doc.get("command")), name + ": " + question[0] + " --json names its command");
        }
        final Run stalls = run(JFRQ, "stalls", file, "--thread", "event-loop-*");
        check(stalls.out().contains("Threads    2 matched"), name + ": stalls finds both event loops");
        final Run locks = run(JFRQ, "locks", file);
        check(locks.out().contains("housekeeper"), name + ": locks names the housekeeper as the holder");
    }

    // ---------------------------------------------------------------------------- plumbing

    private record Run(String command, int status, String out, String err) {
    }

    private static Run run(final Path tool, final String... args) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        command.add(tool.toString());
        command.addAll(List.of(args));
        final Path out = Files.createTempFile(OUT, "out", ".txt");
        final Path err = Files.createTempFile(OUT, "err", ".txt");
        final Process p = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            FAILED.add(String.join(" ", command) + ": no answer in 180 s");
            p.destroyForcibly().waitFor();
        }
        final Run r = new Run(String.join(" ", command), p.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
        System.out.println("$ " + r.command() + "  -> " + r.status());
        return r;
    }

    private static void expect(final Run r, final int status) {
        if (r.status() != status) {
            FAILED.add(r.command() + ": exit " + r.status() + ", expected " + status + "\n" + tail(r.out()) + tail(r.err()));
        }
    }

    private static void check(final boolean ok, final String what) {
        if (!ok) {
            FAILED.add(what);
        }
    }

    private static Map<String, Object> json(final Run r, final String what) {
        expect(r, 0);
        try {
            return JsonParser.object(r.out());
        } catch (final RuntimeException e) {
            FAILED.add(what + ": not one JSON document: " + e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Map<String, Object> doc, final String key) {
        return doc.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static long number(final Map<String, Object> doc, final String key) {
        return doc.get(key) instanceof Number n ? n.longValue() : -1;
    }

    private static void awaitReady(final Process workload) throws IOException {
        final BufferedReader lines = new BufferedReader(new InputStreamReader(workload.getInputStream(),
                StandardCharsets.UTF_8));
        final String first = lines.readLine();
        if (!"ready".equals(first)) {
            throw new IllegalStateException("the workload did not start: " + first);
        }
    }

    private static String tail(final String s) {
        return s.isEmpty() ? "" : "    " + s.substring(Math.max(0, s.length() - 2_000)).replace("\n", "\n    ") + "\n";
    }

    private static Path launcher(final String module, final String name) {
        return ROOT.resolve(module).resolve("build").resolve("install").resolve(name).resolve("bin")
                .resolve(WINDOWS ? name + ".bat" : name);
    }

    private Smoke() {
    }
}
