// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.util.Bytes;
import dev.jfrq.core.util.ClassNames;
import dev.jfrq.core.util.Durations;
import dev.jfrq.core.util.TextTable;

/** Terminal rendering of every report. Plain text, paste-able into a ticket. */
final class Text {

    private static final int STACK_FRAMES = 6;

    private Text() {
    }

    static String header(RecordingInfo info) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "Recording  %s  %s  starting %s%n",
                info.file().getFileName(), Durations.format(info.duration()), info.start()));
        for (String w : info.warnings()) {
            sb.append("WARNING    ").append(w).append('\n');
        }
        return sb.toString();
    }

    static String settingsLine(RecordingInfo info, String label, String... types) {
        if (!info.hasSettings()) {
            return String.format(Locale.ROOT, "%-10s unknown: the recording has no jdk.ActiveSetting events%n", label);
        }
        StringBuilder sb = new StringBuilder();
        for (String t : types) {
            String value = info.threshold(t).map(Durations::format)
                    .or(() -> info.period(t).map(Durations::format))
                    .or(() -> info.setting(t, "throttle"))
                    .orElse(null);
            if (value == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(t.substring("jdk.".length())).append(' ').append(value);
        }
        return sb.isEmpty() ? "" : String.format(Locale.ROOT, "%-10s %s%n", label, sb);
    }

    static String info(RecordingInfo info) {
        StringBuilder sb = new StringBuilder(header(info));
        sb.append(String.format(Locale.ROOT, "%-10s %d%n", "Threads", info.threads().size()));
        sb.append(String.format(Locale.ROOT, "%-10s %d%n", "Chunks", info.chunks()));
        sb.append(settingsLine(info, "Sampling", "jdk.ExecutionSample", "jdk.NativeMethodSample"));
        sb.append(settingsLine(info, "Thresholds", "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ThreadSleep",
                "jdk.SocketRead", "jdk.FileRead"));
        sb.append(settingsLine(info, "Allocation", "jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB"));
        sb.append('\n');
        TextTable t = new TextTable("Event type", "Count", "Enabled", "Threshold", "Period").numeric(1);
        List<Map.Entry<String, Long>> byCount = new ArrayList<>(info.eventCounts().entrySet());
        byCount.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<String, Long> e : byCount) {
            String type = e.getKey();
            t.row(type, e.getValue(), info.settings().containsKey(type) ? (info.enabled(type) ? "yes" : "no") : "",
                    info.threshold(type).map(Durations::format).orElse(""),
                    info.period(type).map(Durations::format).or(() -> info.setting(type, "period")).orElse(""));
        }
        sb.append(t.render());
        return sb.toString();
    }

    static String alloc(AllocationReport r, int top, boolean sites) {
        StringBuilder sb = new StringBuilder(header(r.info()));
        sb.append(String.format(Locale.ROOT, "%-10s %s (%d samples)%n", "Source", r.source(), r.samples()));
        sb.append(String.format(Locale.ROOT, "%-10s %s over %s = %s%n", "Estimate", Bytes.format(r.totalBytes()),
                Durations.format(r.info().duration()), Bytes.rate(r.rate())));
        if (r.hasCounters()) {
            sb.append(String.format(Locale.ROOT, "%-10s %s by the JVM's own counters on the %d threads seen at both ends "
                    + "of the file; the estimate for those is %s%s%n", "Counted", Bytes.format(r.countedBytes()),
                    r.countedByThread().size(), Bytes.format(r.estimatedOnCountedThreads()), errorNote(r)));
        }
        if (r.samples() == 0) {
            if (r.events() == 0) {
                sb.append("\nNo allocation events. Record with the 'profile' settings, or enable jdk.ObjectAllocationSample.\n");
            } else {
                sb.append("""

                        Every thread was sampled once, and a thread's first sample carries its history from before \
                        the recording, so none is in the estimate (docs/DESIGN.md, section 2). Record for longer, \
                        or read the JVM's counters below.
                        """);
            }
            if (r.hasCounters()) {
                sb.append("\nBY THREAD (JVM counters)\n");
                TextTable counted = new TextTable("Thread", "Counted").numeric(1);
                List<Map.Entry<String, Long>> rows = new ArrayList<>(r.countedByThread().entrySet());
                rows.sort(Map.Entry.<String, Long>comparingByValue().reversed());
                for (Map.Entry<String, Long> e : rows.subList(0, Math.min(top, rows.size()))) {
                    counted.row(e.getKey(), Bytes.format(e.getValue()));
                }
                sb.append(counted.render("  "));
            }
            return sb.toString();
        }

        sb.append("\nBY THREAD\n");
        TextTable threads = new TextTable("Thread", "Bytes", "Counted", "Rate", "Share", "Top classes").numeric(1, 2, 3, 4);
        for (AllocationReport.Row<String> row : r.threads(top)) {
            StringBuilder classes = new StringBuilder();
            for (AllocationReport.Row<String> c : r.classesOf(row.key(), 3)) {
                if (!classes.isEmpty()) {
                    classes.append(", ");
                }
                classes.append(ClassNames.simple(c.key())).append(' ')
                        .append(String.format(Locale.ROOT, "%.0f%%", 100.0 * c.bytes() / Math.max(1, row.bytes())));
            }
            threads.row(row.key(), Bytes.format(row.bytes()), r.counted(row.key()).map(Bytes::format).orElse(""),
                    Bytes.rate(r.rate(row.bytes())), pct(row.share()), classes);
        }
        sb.append(threads.render("  "));

        sb.append("\nBY CLASS\n");
        TextTable classes = new TextTable("Class", "Bytes", "Rate", "Share").numeric(1, 2, 3);
        for (AllocationReport.Row<String> row : r.classes(top)) {
            classes.row(ClassNames.pretty(row.key()), Bytes.format(row.bytes()), Bytes.rate(r.rate(row.bytes())),
                    pct(row.share()));
        }
        sb.append(classes.render("  "));

        if (sites) {
            sb.append("\nBY SITE\n");
            int n = 1;
            for (AllocationReport.Row<Stack> row : r.sites(top)) {
                sb.append(String.format(Locale.ROOT, "  %2d  %10s  %10s  %6s%n", n++, Bytes.format(row.bytes()),
                        Bytes.rate(r.rate(row.bytes())), pct(row.share())));
                sb.append(row.key().pretty("        ", STACK_FRAMES));
            }
        }
        return sb.toString();
    }

    static String allocDiff(AllocationDiff d, int top, boolean sites) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-10s %s  %s  %s%n", "Baseline", d.baseline().info().file().getFileName(),
                Durations.format(d.baseline().info().duration()), Bytes.rate(d.baseline().rate())));
        for (String w : d.baseline().info().warnings()) {
            sb.append("WARNING    baseline: ").append(w).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s  %s  %s%n", "Current", d.current().info().file().getFileName(),
                Durations.format(d.current().info().duration()), Bytes.rate(d.current().rate())));
        for (String w : d.current().info().warnings()) {
            sb.append("WARNING    current: ").append(w).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s (%s)%n", "Change", Bytes.signedRate(d.total().delta()),
                ratio(d.total().ratio())));
        sb.append("Rates are bytes/second so recordings of different length compare.\n");

        sb.append("\nBY THREAD\n");
        TextTable threads = new TextTable("Thread", "Before", "After", "Change", "").numeric(1, 2, 3);
        for (AllocationDiff.Delta<String> x : d.threads(top)) {
            threads.row(x.key(), Bytes.rate(x.beforeRate()), Bytes.rate(x.afterRate()), Bytes.signedRate(x.delta()),
                    ratio(x.ratio()));
        }
        sb.append(threads.render("  "));

        sb.append("\nBY CLASS\n");
        TextTable classes = new TextTable("Class", "Before", "After", "Change", "").numeric(1, 2, 3);
        for (AllocationDiff.Delta<String> x : d.classes(top)) {
            classes.row(ClassNames.pretty(x.key()), Bytes.rate(x.beforeRate()), Bytes.rate(x.afterRate()),
                    Bytes.signedRate(x.delta()), ratio(x.ratio()));
        }
        sb.append(classes.render("  "));

        if (sites) {
            sb.append("\nBY SITE\n");
            int n = 1;
            for (AllocationDiff.Delta<Stack> x : d.sites(top)) {
                sb.append(String.format(Locale.ROOT, "  %2d  %10s -> %-10s %10s (%s)%n", n++, Bytes.rate(x.beforeRate()),
                        Bytes.rate(x.afterRate()), Bytes.signedRate(x.delta()), ratio(x.ratio())));
                sb.append(x.key().pretty("        ", STACK_FRAMES));
            }
        }
        return sb.toString();
    }

    static String locks(ContentionReport r, int top) {
        StringBuilder sb = new StringBuilder(header(r.info()));
        sb.append(settingsLine(r.info(), "Thresholds", "jdk.JavaMonitorEnter", "jdk.ThreadPark"));
        if (r.isEmpty()) {
            sb.append(r.filtered()
                    ? "\nNo contended monitor enters or parks match the filters (--thread, --min); "
                            + r.unfilteredCount() + " in the recording.\n"
                    : "\nNo contended monitor enters or parks in the recording (at or above the thresholds above).\n");
            return sb.toString();
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s across %d waits%n", "Blocked", Durations.format(r.totalNanos()),
                r.waits().size()));

        sb.append("\nLOCKS BY TOTAL WAIT\n");
        TextTable locks = new TextTable("Lock", "Kind", "Total", "Waits", "Max", "Waiters", "Held by").numeric(2, 3, 4);
        for (ContentionReport.LockStats l : r.locks(top)) {
            locks.row(l.lock().pretty(), l.lock().kind().label(), Durations.format(l.totalNanos()), l.count(),
                    Durations.format(l.maxNanos()), names(l.waiters()), names(l.owners()));
        }
        sb.append(locks.render("  "));

        sb.append("\nTHREADS BY TIME BLOCKED\n");
        TextTable threads = new TextTable("Thread", "Total", "Waits", "Max", "Share").numeric(1, 2, 3, 4);
        double span = Math.max(1, r.info().span().length());
        for (ContentionReport.ThreadStats t : r.waiters(top)) {
            threads.row(t.thread().name(), Durations.format(t.totalNanos()), t.count(), Durations.format(t.maxNanos()),
                    pct(t.totalNanos() / span));
        }
        sb.append(threads.render("  "));

        List<ContentionReport.Convoy> convoys = r.convoys(5, top);
        if (!convoys.isEmpty()) {
            sb.append("\nCONVOYS (the holder was itself blocked)\n");
            for (ContentionReport.Convoy c : convoys) {
                sb.append("  ").append(Durations.offset(c.head().start() - r.info().startNanos())).append("  ");
                boolean first = true;
                for (Wait w : c.links()) {
                    if (!first) {
                        sb.append("\n              -> ");
                    }
                    first = false;
                    sb.append(w.waiter().name()).append(" waited ").append(Durations.format(w.duration()))
                            .append(" for ").append(w.lock().pretty());
                    if (w.owner() != null) {
                        sb.append(' ').append(w.heldBy());
                    }
                }
                sb.append('\n');
            }
        }

        sb.append("\nLONGEST WAITS\n");
        int n = 1;
        for (Wait w : r.longest(top)) {
            sb.append(String.format(Locale.ROOT, "  %2d  %s  %8s  %s waited for %s%s%n", n++,
                    Durations.offset(w.start() - r.info().startNanos()), Durations.format(w.duration()),
                    w.waiter().name(), w.lock().pretty(), w.owner() == null ? "" : " " + w.heldBy()));
            sb.append(w.stack().pretty("        ", STACK_FRAMES));
        }
        return sb.toString();
    }

    static String stalls(StallReport r, int top) {
        StringBuilder sb = new StringBuilder(header(r.info()));
        sb.append(settingsLine(r.info(), "Sampling", "jdk.ExecutionSample", "jdk.NativeMethodSample"));
        sb.append(settingsLine(r.info(), "Thresholds", "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ThreadSleep",
                "jdk.SocketRead", "jdk.FileRead"));
        sb.append(String.format(Locale.ROOT, "%-10s %s%n", "Gap", Durations.format(r.gapNanos())));
        if (r.threads().isEmpty()) {
            sb.append("\nNo thread matched. Use `jfrq info` to list the threads in the recording.\n");
            return sb.toString();
        }
        StringBuilder threads = new StringBuilder();
        for (StallReport.ThreadSummary t : r.threads()) {
            if (!threads.isEmpty()) {
                threads.append(", ");
            }
            threads.append(t.thread().name()).append(" (").append(t.samples()).append(" samples, cadence ")
                    .append(Durations.format(t.javaCadenceNanos())).append(" java / ")
                    .append(Durations.format(t.nativeCadenceNanos())).append(" native)");
        }
        sb.append(String.format(Locale.ROOT, "%-10s %d matched: %s%n", "Threads", r.threads().size(), threads));
        for (String w : r.warnings()) {
            sb.append("WARNING    ").append(w).append('\n');
        }

        List<Stall> shown = r.top(top);
        sb.append(String.format(Locale.ROOT, "%nSTALLS >= %s: %d found%s, longest first%n",
                Durations.format(r.gapNanos()), r.stalls().size(),
                shown.size() < r.stalls().size() ? ", showing " + shown.size() : ""));
        if (r.stalls().isEmpty()) {
            sb.append("  none\n");
        }
        int n = 1;
        for (Stall s : shown) {
            sb.append(String.format(Locale.ROOT, "  %2d  %-22s %s  %8s  %-15s %s%s%n", n++, s.thread().name(),
                    Durations.offset(s.start() - r.info().startNanos()), Durations.format(s.duration()),
                    s.verdict(), s.detail(), evidence(s)));
            sb.append(s.stack().pretty("        ", STACK_FRAMES));
        }

        if (!r.stalls().isEmpty()) {
            sb.append("\nBY VERDICT\n");
            TextTable verdicts = new TextTable("Verdict", "Stalls", "Stalled", "Worst").numeric(1, 2, 3);
            for (StallReport.VerdictSummary v : r.byVerdict()) {
                verdicts.row(v.verdict(), v.count(), Durations.format(v.totalNanos()), Durations.format(v.worstNanos()));
            }
            sb.append(verdicts.render("  "));
        }

        sb.append("\nPER THREAD\n");
        TextTable summary = new TextTable("Thread", "Stalls", "Stalled", "Share", "Worst").numeric(1, 2, 3, 4);
        double span = Math.max(1, r.info().span().length());
        for (StallReport.ThreadSummary t : r.threads()) {
            summary.row(t.thread().name(), t.stalls(), Durations.format(t.stalledNanos()), pct(t.stalledNanos() / span),
                    Durations.format(t.worstNanos()));
        }
        sb.append(summary.render("  "));

        if (!r.pauses().isEmpty()) {
            sb.append("\nJVM-WIDE PAUSES >= gap (stop every thread)\n");
            List<Pause> pauses = r.pauses();
            for (int i = 0; i < Math.min(top, pauses.size()); i++) {
                Pause p = pauses.get(i);
                sb.append(String.format(Locale.ROOT, "  %s  %8s  %s: %s%n", Durations.offset(p.interval().start()
                        - r.info().startNanos()), Durations.format(p.length()), p.kind().label(), p.detail()));
            }
            if (pauses.size() > top) {
                sb.append("  ... ").append(pauses.size() - top).append(" more\n");
            }
        }
        return sb.toString();
    }

    /** {@code " (+7%)"} when the counted threads carry enough of the estimate for the comparison to mean something. */
    static String errorNote(AllocationReport r) {
        return r.estimateErrorMaterial() ? String.format(Locale.ROOT, " (%+.0f%%)", r.estimateError() * 100) : "";
    }

    /** How a stall was found, for anything less exact than an event: {@code  [samples]}, {@code  [silence]}. */
    static String evidence(Stall s) {
        return s.evidence() == Stall.Evidence.EVENT ? "" : " [" + s.evidence().name().toLowerCase(Locale.ROOT) + "]";
    }

    static String pct(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    /** {@code +25%}, {@code -96%}, {@code ×2349} beyond tenfold, {@code new} from nothing. */
    static String ratio(double r) {
        if (Double.isInfinite(r)) {
            return "new";
        }
        if (r >= 10) {
            return String.format(Locale.ROOT, "×%.0f", r + 1);
        }
        return String.format(Locale.ROOT, "%+.0f%%", r * 100);
    }

    private static String names(Set<ThreadRef> threads) {
        List<String> names = new ArrayList<>();
        for (ThreadRef t : threads) {
            names.add(t.name());
        }
        return String.join(", ", names);
    }
}
