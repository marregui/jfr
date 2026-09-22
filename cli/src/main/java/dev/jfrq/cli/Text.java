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

    static String header(final RecordingInfo info) {
        final StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "Recording  %s  %s  starting %s%n",
                info.file().getFileName(), Durations.format(info.duration()), info.start()));
        for (final String w : info.warnings()) {
            sb.append("WARNING    ").append(w).append('\n');
        }
        return sb.toString();
    }

    static String settingsLine(final RecordingInfo info, final String label, final String... types) {
        if (!info.hasSettings()) {
            return String.format(Locale.ROOT, "%-10s unknown: the recording has no jdk.ActiveSetting events%n", label);
        }
        final StringBuilder sb = new StringBuilder();
        for (final String t : types) {
            final String value = info.threshold(t).map(Durations::format)
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

    static String info(final RecordingInfo info) {
        final StringBuilder sb = new StringBuilder(header(info));
        sb.append(String.format(Locale.ROOT, "%-10s %d%n", "Threads", info.threads().size()));
        sb.append(String.format(Locale.ROOT, "%-10s %d%n", "Chunks", info.chunks()));
        sb.append(settingsLine(info, "Sampling", "jdk.ExecutionSample", "jdk.NativeMethodSample"));
        sb.append(settingsLine(info, "Thresholds", "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ThreadSleep",
                "jdk.SocketRead", "jdk.FileRead"));
        sb.append(settingsLine(info, "Allocation", "jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB"));
        sb.append('\n');
        final TextTable t = new TextTable("Event type", "Count", "Enabled", "Threshold", "Period").numeric(1);
        final List<Map.Entry<String, Long>> byCount = new ArrayList<>(info.eventCounts().entrySet());
        byCount.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (final Map.Entry<String, Long> e : byCount) {
            final String type = e.getKey();
            t.row(type, e.getValue(), info.settings().containsKey(type) ? (info.enabled(type) ? "yes" : "no") : "",
                    info.threshold(type).map(Durations::format).orElse(""),
                    info.period(type).map(Durations::format).or(() -> info.setting(type, "period")).orElse(""));
        }
        sb.append(t.render());
        return sb.toString();
    }

    static String alloc(final AllocationReport r, final int top, final boolean sites) {
        final StringBuilder sb = new StringBuilder(header(r.info()));
        sb.append(String.format(Locale.ROOT, "%-10s %s (%d samples)%n", "Source", r.source(), r.samples()));
        sb.append(String.format(Locale.ROOT, "%-10s %s over %s = %s%n", "Estimate", Bytes.format(r.totalBytes()),
                Durations.format(r.info().duration()), Bytes.rate(r.rate())));
        if (r.hasCounters()) {
            sb.append(String.format(Locale.ROOT, "%-10s %s by the JVM's own counters on the %d threads seen at both ends "
                    + "of the file; the estimate for those is %s%s%s%n", "Counted", Bytes.format(r.countedBytes()),
                    r.countedByThread().size(), Bytes.format(r.estimatedOnCountedThreads()), errorNote(r),
                    coverageNote(r)));
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
                final TextTable counted = new TextTable("Thread", "Counted").numeric(1);
                final List<Map.Entry<String, Long>> rows = new ArrayList<>(r.countedByThread().entrySet());
                rows.sort(Map.Entry.<String, Long>comparingByValue().reversed());
                for (final Map.Entry<String, Long> e : rows.subList(0, Math.min(top, rows.size()))) {
                    counted.row(e.getKey(), Bytes.format(e.getValue()));
                }
                sb.append(counted.render("  "));
            }
            return sb.toString();
        }

        sb.append("\nBY THREAD\n");
        final TextTable threads = new TextTable("Thread", "Bytes", "Counted", "Rate", "Share", "Top classes").numeric(1, 2, 3, 4);
        for (final AllocationReport.Row<String> row : r.threads(top)) {
            final StringBuilder classes = new StringBuilder();
            for (final AllocationReport.Row<String> c : r.classesOf(row.key(), 3)) {
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
        final TextTable classes = new TextTable("Class", "Bytes", "Rate", "Share").numeric(1, 2, 3);
        for (final AllocationReport.Row<String> row : r.classes(top)) {
            classes.row(ClassNames.pretty(row.key()), Bytes.format(row.bytes()), Bytes.rate(r.rate(row.bytes())),
                    pct(row.share()));
        }
        sb.append(classes.render("  "));

        if (sites) {
            sb.append("\nBY SITE\n");
            int n = 1;
            for (final AllocationReport.Row<Stack> row : r.sites(top)) {
                sb.append(String.format(Locale.ROOT, "  %2d  %10s  %10s  %6s%n", n++, Bytes.format(row.bytes()),
                        Bytes.rate(r.rate(row.bytes())), pct(row.share())));
                sb.append(row.key().pretty("        ", STACK_FRAMES));
            }
        }
        return sb.toString();
    }

    static String allocDiff(final AllocationDiff d, final int top, final boolean sites) {
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-10s %s  %s  %s%n", "Baseline", d.baseline().info().file().getFileName(),
                Durations.format(d.baseline().info().duration()), Bytes.rate(d.baseline().rate())));
        for (final String w : d.baseline().info().warnings()) {
            sb.append("WARNING    baseline: ").append(w).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s  %s  %s%n", "Current", d.current().info().file().getFileName(),
                Durations.format(d.current().info().duration()), Bytes.rate(d.current().rate())));
        for (final String w : d.current().info().warnings()) {
            sb.append("WARNING    current: ").append(w).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s (%s)%n", "Change", Bytes.signedRate(d.total().delta()),
                ratio(d.total().ratio())));
        sb.append("Rates are bytes/second so recordings of different length compare.\n");

        sb.append("\nBY THREAD\n");
        final TextTable threads = new TextTable("Thread", "Before", "After", "Change", "").numeric(1, 2, 3);
        for (final AllocationDiff.Delta<String> x : d.threads(top)) {
            threads.row(x.key(), Bytes.rate(x.beforeRate()), Bytes.rate(x.afterRate()), Bytes.signedRate(x.delta()),
                    ratio(x.ratio()));
        }
        sb.append(threads.render("  "));

        sb.append("\nBY CLASS\n");
        final TextTable classes = new TextTable("Class", "Before", "After", "Change", "").numeric(1, 2, 3);
        for (final AllocationDiff.Delta<String> x : d.classes(top)) {
            classes.row(ClassNames.pretty(x.key()), Bytes.rate(x.beforeRate()), Bytes.rate(x.afterRate()),
                    Bytes.signedRate(x.delta()), ratio(x.ratio()));
        }
        sb.append(classes.render("  "));

        if (sites) {
            sb.append("\nBY SITE\n");
            int n = 1;
            for (final AllocationDiff.Delta<Stack> x : d.sites(top)) {
                sb.append(String.format(Locale.ROOT, "  %2d  %10s -> %-10s %10s (%s)%n", n++, Bytes.rate(x.beforeRate()),
                        Bytes.rate(x.afterRate()), Bytes.signedRate(x.delta()), ratio(x.ratio())));
                sb.append(x.key().pretty("        ", STACK_FRAMES));
            }
        }
        return sb.toString();
    }

    static String locks(final ContentionReport r, final int top) {
        final StringBuilder sb = new StringBuilder(header(r.info()));
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
        if (r.clippedCount() > 0) {
            sb.append(String.format(Locale.ROOT, "%-10s %d wait%s began before the recording or outlived it; "
                            + "only the part inside it is counted%n", "Note", r.clippedCount(),
                    r.clippedCount() == 1 ? "" : "s"));
        }

        sb.append("\nLOCKS BY TOTAL WAIT\n");
        final TextTable locks = new TextTable("Lock", "Kind", "Total", "Waits", "Max", "Waiters", "Held by").numeric(2, 3, 4);
        for (final ContentionReport.LockStats l : r.locks(top)) {
            locks.row(l.lock().pretty(), l.lock().kind().label(), Durations.format(l.totalNanos()), l.count(),
                    Durations.format(l.maxNanos()), names(l.waiters()), names(l.owners()));
        }
        sb.append(locks.render("  "));

        sb.append("\nTHREADS BY TIME BLOCKED\n");
        final TextTable threads = new TextTable("Thread", "Total", "Waits", "Max", "Share").numeric(1, 2, 3, 4);
        final double span = Math.max(1, r.info().span().length());
        for (final ContentionReport.ThreadStats t : r.waiters(top)) {
            threads.row(t.thread().name(), Durations.format(t.totalNanos()), t.count(), Durations.format(t.maxNanos()),
                    pct(t.totalNanos() / span));
        }
        sb.append(threads.render("  "));

        final List<ContentionReport.Convoy> convoys = r.convoys(5, top);
        if (!convoys.isEmpty()) {
            sb.append("\nCONVOYS (the holder was itself blocked)\n");
            for (final ContentionReport.Convoy c : convoys) {
                sb.append("  ").append(Durations.offset(c.head().start() - r.info().startNanos())).append("  ");
                boolean first = true;
                for (final Wait w : c.links()) {
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

        if (!r.workWaits().isEmpty()) {
            sb.append(String.format(Locale.ROOT, "%nWAITING FOR WORK (not contention: %d thread%s parked on their own "
                            + "queue, %s across %d parks)%n", r.workWaitThreads(), r.workWaitThreads() == 1 ? "" : "s",
                    Durations.format(r.workWaitNanos()), r.workWaits().size()));
            final TextTable idle = new TextTable("Queue", "Total", "Parks", "Max", "Threads").numeric(1, 2, 3);
            for (final ContentionReport.LockStats l : r.workWaitLocks(top)) {
                idle.row(l.lock().pretty(), Durations.format(l.totalNanos()), l.count(),
                        Durations.format(l.maxNanos()), names(l.waiters()));
            }
            sb.append(idle.render("  "));
        }

        sb.append("\nLONGEST WAITS\n");
        int n = 1;
        for (final Wait w : r.longest(top)) {
            sb.append(String.format(Locale.ROOT, "  %2d  %s  %8s  %s waited for %s%s%n", n++,
                    Durations.offset(w.start() - r.info().startNanos()), Durations.format(w.duration()),
                    w.waiter().name(), w.lock().pretty(), w.owner() == null ? "" : " " + w.heldBy()));
            sb.append(w.stack().pretty("        ", STACK_FRAMES));
        }
        return sb.toString();
    }

    static String stalls(final StallReport r, final int top) {
        final StringBuilder sb = new StringBuilder(header(r.info()));
        sb.append(settingsLine(r.info(), "Sampling", "jdk.ExecutionSample", "jdk.NativeMethodSample"));
        sb.append(settingsLine(r.info(), "Thresholds", "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ThreadSleep",
                "jdk.SocketRead", "jdk.FileRead"));
        sb.append(String.format(Locale.ROOT, "%-10s %s%n", "Gap", Durations.format(r.gapNanos())));
        if (r.threads().isEmpty()) {
            sb.append("\nNo thread matched. Use `jfrq info` to list the threads in the recording.\n");
            return sb.toString();
        }
        final StringBuilder threads = new StringBuilder();
        for (final StallReport.ThreadSummary t : r.threads()) {
            if (!threads.isEmpty()) {
                threads.append(", ");
            }
            threads.append(t.thread().name()).append(" (").append(t.samples()).append(" samples, cadence ")
                    .append(Durations.format(t.javaCadenceNanos())).append(" java / ")
                    .append(Durations.format(t.nativeCadenceNanos())).append(" native)");
        }
        sb.append(String.format(Locale.ROOT, "%-10s %d matched: %s%n", "Threads", r.threads().size(), threads));
        for (final String w : r.warnings()) {
            sb.append("WARNING    ").append(w).append('\n');
        }

        final List<Stall> shown = r.top(top);
        sb.append(String.format(Locale.ROOT, "%nSTALLS >= %s: %d found%s, longest first%n",
                Durations.format(r.gapNanos()), r.stalls().size(),
                shown.size() < r.stalls().size() ? ", showing " + shown.size() : ""));
        if (r.stalls().isEmpty()) {
            sb.append("  none\n");
        }
        int n = 1;
        for (final Stall s : shown) {
            sb.append(String.format(Locale.ROOT, "  %2d  %-22s %s  %8s  %-15s %s%s%n", n++, s.thread().name(),
                    Durations.offset(s.start() - r.info().startNanos()), Durations.format(s.duration()),
                    s.verdict(), s.detail(), evidence(s)));
            sb.append(s.stack().pretty("        ", STACK_FRAMES));
        }

        if (!r.stalls().isEmpty()) {
            sb.append("\nBY VERDICT\n");
            final TextTable verdicts = new TextTable("Verdict", "Stalls", "Stalled", "Worst").numeric(1, 2, 3);
            for (final StallReport.VerdictSummary v : r.byVerdict()) {
                verdicts.row(v.verdict(), v.count(), Durations.format(v.totalNanos()), Durations.format(v.worstNanos()));
            }
            sb.append(verdicts.render("  "));
        }

        sb.append("\nPER THREAD\n");
        final TextTable summary = new TextTable("Thread", "Stalls", "Stalled", "Share", "Worst").numeric(1, 2, 3, 4);
        final double span = Math.max(1, r.info().span().length());
        for (final StallReport.ThreadSummary t : r.threads()) {
            summary.row(t.thread().name(), t.stalls(), Durations.format(t.stalledNanos()), pct(t.stalledNanos() / span),
                    Durations.format(t.worstNanos()));
        }
        sb.append(summary.render("  "));

        if (!r.pauses().isEmpty()) {
            sb.append("\nJVM-WIDE PAUSES >= gap (stop every thread)\n");
            final List<Pause> pauses = r.pauses();
            for (int i = 0; i < Math.min(top, pauses.size()); i++) {
                final Pause p = pauses.get(i);
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
    static String errorNote(final AllocationReport r) {
        return r.estimateErrorMaterial() ? String.format(Locale.ROOT, " (%+.0f%%)", r.estimateError() * 100) : "";
    }

    /** How much of the estimate the counters cover, so the error above is read against the right total. */
    static String coverageNote(final AllocationReport r) {
        return r.totalBytes() > 0 ? ", " + pct(r.countedCoverage()) + " of the estimate above" : "";
    }

    /** How a stall was found, for anything less exact than an event: {@code  [samples]}, {@code  [silence]}. */
    static String evidence(final Stall s) {
        return s.evidence() == Stall.Evidence.EVENT ? "" : " [" + s.evidence().name().toLowerCase(Locale.ROOT) + "]";
    }

    static String pct(final double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    /** {@code +25%}, {@code -96%}, {@code ×2349} beyond tenfold, {@code new} from nothing. */
    static String ratio(final double r) {
        if (Double.isInfinite(r)) {
            return "new";
        }
        if (r >= 10) {
            return String.format(Locale.ROOT, "×%.0f", r + 1);
        }
        return String.format(Locale.ROOT, "%+.0f%%", r * 100);
    }

    private static String names(final Set<ThreadRef> threads) {
        final List<String> names = new ArrayList<>();
        for (final ThreadRef t : threads) {
            names.add(t.name());
        }
        return String.join(", ", names);
    }
}
