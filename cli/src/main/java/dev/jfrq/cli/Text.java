// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.health.HealthReport;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.report.RecordingSummary;
import dev.jfrq.core.report.ThreadCensus;
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
    /** Names listed before the rest become a count. */
    private static final int NAMES_SHOWN = 4;
    /** Package roots named on the line that explains {@code --app}. */
    private static final int PACKAGES_SHOWN = 4;

    private Text() {
    }

    static String header(final RecordingInfo info) {
        final StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "Recording  %s  %s  starting %s\n",
                info.file().getFileName(), Durations.format(info.duration()), info.start()));
        for (final String w : info.warnings()) {
            sb.append("WARNING    ").append(w).append('\n');
        }
        return sb.toString();
    }

    static String settingsLine(final RecordingInfo info, final String label, final String... types) {
        if (!info.hasSettings()) {
            return String.format(Locale.ROOT, "%-10s unknown: the recording has no jdk.ActiveSetting events\n", label);
        }
        return line(label, RecordingSummary.settings(info, types));
    }

    private static String line(final String label, final String settings) {
        return settings.isEmpty() ? "" : String.format(Locale.ROOT, "%-10s %s\n", label, settings);
    }

    static String info(final RecordingInfo info, final ThreadCensus.Result census) {
        final StringBuilder sb = new StringBuilder(header(info));
        final String lives = RecordingSummary.lives(census);
        sb.append(String.format(Locale.ROOT, "%-10s %d seen in events%s\n", "Threads", info.threads().size(),
                lives.isEmpty() ? "" : "; " + lives));
        sb.append(String.format(Locale.ROOT, "%-10s %d\n", "Chunks", info.chunks()));
        sb.append(settingsLine(info, "Sampling", "jdk.ExecutionSample", "jdk.NativeMethodSample"));
        // Derived, not a whitelist: this line exists to answer "did the settings I asked for
        // take effect", and a fixed list answers it for the events someone thought of in 2026.
        sb.append(settingsLine(info, "Thresholds", RecordingSummary.thresholded(info)));
        sb.append(info.hasSettings() ? line("Throttled", RecordingSummary.throttles(info, RecordingSummary.throttled(info)))
                : settingsLine(info, "Throttled"));
        sb.append(settingsLine(info, "Allocation", "jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB"));
        sb.append('\n');
        final TextTable t = new TextTable("Event type", "Count", "Enabled", "Threshold", "Period").numeric(1);
        final List<Map.Entry<String, Long>> byCount = new ArrayList<>(info.eventCounts().entrySet());
        byCount.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (final Map.Entry<String, Long> e : byCount) {
            final String type = e.getKey();
            t.row(type, e.getValue(), info.settings().containsKey(type) ? (info.isEnabled(type) ? "yes" : "no") : "",
                    info.threshold(type).map(Durations::format).orElse(""),
                    info.period(type).map(Durations::format).or(() -> info.setting(type, "period")).orElse(""));
        }
        sb.append(t.render());
        sb.append(threadFamilies(info, census));
        return sb.toString();
    }

    /** The threads in the file by family ({@link RecordingSummary#threadFamilies}): what {@code --thread} matches. */
    static String threadFamilies(final RecordingInfo info, final ThreadCensus.Result census) {
        final List<RecordingSummary.Family> families = RecordingSummary.threadFamilies(info, census);
        if (families.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder("\nTHREADS (the names --thread matches)\n");
        final List<String> headers = RecordingSummary.familyHeaders(census);
        final int[] numeric = new int[headers.size() - 2];
        for (int i = 0; i < numeric.length; i++) {
            numeric[i] = i + 1;
        }
        final TextTable table = new TextTable(headers.toArray(new String[0])).numeric(numeric);
        for (final RecordingSummary.Family f : families) {
            table.row(RecordingSummary.familyCells(f, census));
        }
        sb.append(table.render("  "));
        return sb.toString();
    }

    static String health(final HealthReport r, final int top) {
        final StringBuilder sb = new StringBuilder(header(r.info()));
        sb.append("\nFINDINGS (from the JVM's own events, the most serious first)\n");
        if (r.findings().isEmpty()) {
            sb.append("  none: no OutOfMemoryError Java code created, no failed evacuation or full collection, GC time "
                    + "and pauses within the JVM's goals, and no collection forced by a humongous allocation, metaspace "
                    + "or System.gc()\n");
        }
        int n = 1;
        for (final HealthReport.Finding f : r.findings()) {
            sb.append(String.format(Locale.ROOT, "  %2d  %s\n", n++, f.text()));
        }

        final HealthReport.Gc gc = r.gc();
        sb.append("\nGC\n");
        if (gc.count() == 0) {
            sb.append("  no jdk.GarbageCollection events in the recording\n");
        } else {
            sb.append(String.format(Locale.ROOT, "  %-12s %d (%s); %d old-generation cycle%s\n", "Collections",
                    gc.count(), counts(gc.collections()), gc.oldCycles(), gc.oldCycles() == 1 ? "" : "s"));
            sb.append(String.format(Locale.ROOT, "  %-12s %s in %s, %.2f%% of the time%s\n", "Paused",
                    Durations.format(gc.pauseNanos()), Durations.format(r.info().span().duration()),
                    100.0 * gc.pauseNanos() / Math.max(1, r.info().span().duration()),
                    gc.gcTimeRatio() == Nulls.INT_NULL ? "" : String.format(Locale.ROOT,
                            " (the JVM's goal: at most %.1f%%, GCTimeRatio %d)", 100.0 / (1 + gc.gcTimeRatio()),
                            gc.gcTimeRatio())));
            sb.append(String.format(Locale.ROOT, "  %-12s %s%s\n", "Longest", Durations.format(gc.longestPauseNanos()),
                    gc.pauseTargetNanos() == Nulls.LONG_NULL ? " (no pause target set)"
                            : " (target " + Durations.format(gc.pauseTargetNanos()) + ")"));
            if (gc.maxHeapBytes() != Nulls.LONG_NULL) {
                sb.append(String.format(Locale.ROOT, "  %-12s %s\n", "Heap max", Bytes.format(gc.maxHeapBytes())));
            }
            sb.append(String.format(Locale.ROOT, "  %-12s %s\n", "Causes", counts(gc.causes()) + gc.causesNote()));
        }

        sb.append("\nTRENDS (floor: the lowest value in the first and in the last third of the window; a floor that "
                + "rises is growth that did not come back down)\n");
        if (r.trends().isEmpty()) {
            sb.append("  ").append(HealthReport.NO_TRENDS).append('\n');
        } else {
            final TextTable trends = new TextTable("Series", "Start", "End", "Min", "Max", "Mean", "Floor, first third",
                    "Floor, last third").numeric(1, 2, 3, 4, 5, 6, 7);
            for (final HealthReport.Series s : r.trends()) {
                trends.row(s.name(), s.format(s.start()), s.format(s.end()), s.format(s.min()), s.format(s.max()),
                        s.format(s.mean()), s.format(s.floorFirst()), s.format(s.floorLast()));
            }
            sb.append(trends.render("  "));
        }
        final HealthReport.Threads threads = r.threads();
        if (threads.started() != Nulls.LONG_NULL) {
            sb.append(String.format(Locale.ROOT, "  %d thread%s started in the window%s\n", threads.started(),
                    threads.started() == 1 ? "" : "s", threads.peak() == Nulls.LONG_NULL ? ""
                            : "; at most " + threads.peak() + " alive at once since the JVM started"));
        }
        sb.append(throwables(r, top));
        return sb.toString();
    }

    private static String throwables(final HealthReport r, final int top) {
        final HealthReport.Throwables t = r.throwables();
        final StringBuilder sb = new StringBuilder("\nTHROWABLES CREATED (jdk.JavaExceptionThrow fires in the constructor: "
                + "an object made only for its stack trace counts, a rethrow does not)\n");
        final double rate = t.rate();
        sb.append(String.format(Locale.ROOT, "  %-8s %s\n", "Created", Double.isNaN(rate)
                ? "unknown: jdk.ExceptionStatistics was not recorded twice"
                : String.format(Locale.ROOT, "%d in %s = %.1f/s, exactly (jdk.ExceptionStatistics, its first reading to its last)", t.created(),
                        Durations.format(t.createdNanos()), rate)));
        if (t.samples() == 0) {
            sb.append("  ").append(HealthReport.NO_THROWS).append('\n');
        } else {
            sb.append(String.format(Locale.ROOT, "  %-8s %d jdk.JavaExceptionThrow%s\n", "Events", t.samples(),
                    t.throttle() == null ? ", every one" : " (throttled at " + t.throttle()
                            + ": every one below that rate, a sample above it; the shares below are of the events)"));
        }
        if (!t.errors().isEmpty()) {
            sb.append(String.format(Locale.ROOT, "  %-8s %s (jdk.JavaErrorThrow)\n", "Errors", counts(t.errors())));
        }
        if (t.samples() == 0) {
            return sb.toString();
        }
        sb.append("\n  BY CLASS\n");
        final TextTable classes = new TextTable("Class", "Events", "Share", "Per second", "Example message")
                .numeric(1, 2, 3);
        for (final HealthReport.ClassRow c : t.byClass().subList(0, Math.min(top, t.byClass().size()))) {
            classes.row(ClassNames.pretty(c.className()), c.samples(), pct(c.share()),
                    Double.isNaN(rate) ? "" : String.format(Locale.ROOT, "~%.1f", c.share() * rate),
                    c.message() == null ? "" : c.message().replace('\n', ' '));
        }
        sb.append(classes.render("    "));
        sb.append("\n  BY SITE (the innermost frame outside the JDK)\n");
        int n = 1;
        for (final HealthReport.SiteRow s : t.bySite().subList(0, Math.min(top, t.bySite().size()))) {
            sb.append(String.format(Locale.ROOT, "  %2d  %6d  %6s  %s  %s\n", n++, s.samples(), pct(s.share()), s.site(),
                    ClassNames.pretty(s.className())));
            sb.append(s.stack().pretty("        ", STACK_FRAMES));
        }
        return sb.toString();
    }

    /** {@code G1New 37, G1Old 17}, in the order the map has them. */
    private static String counts(final Map<String, Long> counts) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, Long> e : counts.entrySet()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
        }
        return sb.toString();
    }

    static String alloc(final AllocationReport r, final int top, final boolean sites, final SiteKey key) {
        final StringBuilder sb = new StringBuilder(header(r.info()));
        for (final String w : r.warnings()) {
            sb.append("WARNING    ").append(w).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s (%d samples)\n", "Source", r.source(), r.samples()));
        sb.append(String.format(Locale.ROOT, "%-10s %s over %s = %s\n", "Estimate", Bytes.format(r.totalBytes()),
                Durations.format(r.info().duration()), Bytes.rate(r.rate())));
        if (r.hasCounters()) {
            sb.append(String.format(Locale.ROOT, "%-10s %s by the JVM's own counters on the %d %s seen at both ends "
                    + "of the file; the estimate for those is %s%s%s\n", "Counted", Bytes.format(r.countedBytes()),
                    r.countedByThread().size(), r.countedByThread().size() == 1 ? "thread" : "threads",
                    Bytes.format(r.estimatedOnCountedThreads()), errorNote(r), coverageNote(r)));
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
        final TextTable threads = new TextTable("Thread", "Bytes", "Counted", "Rate", "Share", "Samples", "Top classes")
                .numeric(1, 2, 3, 4, 5);
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
                    Bytes.rate(r.rate(row.bytes())), pct(row.share()), r.support().thread(row.key()), classes);
        }
        sb.append(threads.render("  "));

        sb.append("\nBY CLASS\n");
        final TextTable classes = new TextTable("Class", "Bytes", "Rate", "Share", "Samples").numeric(1, 2, 3, 4);
        for (final AllocationReport.Row<String> row : r.classes(top)) {
            classes.row(ClassNames.pretty(row.key()), Bytes.format(row.bytes()), Bytes.rate(r.rate(row.bytes())),
                    pct(row.share()), r.support().className(row.key()));
        }
        sb.append(classes.render("  "));

        if (sites) {
            sb.append("\nBY SITE (").append(key.description()).append("; every path through it is one row)\n");
            sb.append(packages(r));
            int n = 1;
            for (final AllocationReport.SiteRow row : r.sites(key, top)) {
                sb.append(String.format(Locale.ROOT, "  %2d  %10s  %10s  %6s  %d sample%s  %s%s\n", n++,
                        Bytes.format(row.bytes()), Bytes.rate(r.rate(row.bytes())), pct(row.share()),
                        row.samples(), row.samples() == 1 ? "" : "s", row.label(),
                        row.stacks() > 1 ? "  (" + row.stacks() + " stacks, the biggest below)" : ""));
                sb.append(row.stack().pretty("        ", STACK_FRAMES));
            }
        }
        return sb.toString();
    }

    /**
     * The line that makes {@code --app} usable by a reader who has never seen the application:
     * its own report names the packages it could be pointed at.
     */
    private static String packages(final AllocationReport r) {
        final List<AllocationReport.Row<String>> roots = r.packageRoots(PACKAGES_SHOWN);
        if (roots.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final AllocationReport.Row<String> root : roots) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(root.key()).append(' ').append(pct(root.share()));
        }
        return "  Packages " + sb + "  (--app PREFIX ranks by the innermost frame in one of them instead)\n";
    }

    static String allocDiff(final AllocationDiff d, final int top, final boolean sites, final SiteKey key) {
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-10s %s  %s  %s\n", "Baseline", d.baseline().info().file().getFileName(),
                Durations.format(d.baseline().info().duration()), Bytes.rate(d.baseline().rate())));
        for (final String w : d.baseline().info().warnings()) {
            sb.append("WARNING    baseline: ").append(w).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s  %s  %s\n", "Current", d.current().info().file().getFileName(),
                Durations.format(d.current().info().duration()), Bytes.rate(d.current().rate())));
        for (final String w : d.current().info().warnings()) {
            sb.append("WARNING    current: ").append(w).append('\n');
        }
        for (final String w : d.warnings()) {
            sb.append("WARNING    ").append(w).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s (%s)\n", "Change", Bytes.signedRate(d.total().delta()),
                ratio(d.total().ratio())));
        sb.append("Rates are bytes/second so recordings of different length compare. The sample counts are the "
                + "evidence behind each\nchange: a few hundred percent on a handful of samples is noise, not a "
                + "finding.\n");

        sb.append("\nBY THREAD\n");
        final TextTable threads = new TextTable("Thread", "Before", "After", "Change", "", "Samples").numeric(1, 2, 3);
        for (final AllocationDiff.Delta<String> x : d.threads(top)) {
            threads.row(x.key(), Bytes.rate(x.beforeRate()), Bytes.rate(x.afterRate()), Bytes.signedRate(x.delta()),
                    ratio(x.ratio()), d.baseline().support().thread(x.key()) + " -> "
                            + d.current().support().thread(x.key()));
        }
        sb.append(threads.render("  "));

        sb.append("\nBY CLASS\n");
        final TextTable classes = new TextTable("Class", "Before", "After", "Change", "", "Samples").numeric(1, 2, 3);
        for (final AllocationDiff.Delta<String> x : d.classes(top)) {
            classes.row(ClassNames.pretty(x.key()), Bytes.rate(x.beforeRate()), Bytes.rate(x.afterRate()),
                    Bytes.signedRate(x.delta()), ratio(x.ratio()), d.baseline().support().className(x.key()) + " -> "
                            + d.current().support().className(x.key()));
        }
        sb.append(classes.render("  "));

        if (sites) {
            sb.append("\nBY SITE (").append(key.description()).append("; every path through it is one row)\n");
            int n = 1;
            for (final AllocationDiff.Delta<AllocationDiff.Site> x : d.sites(key, top)) {
                sb.append(String.format(Locale.ROOT, "  %2d  %10s -> %-10s %10s (%s)  %d -> %d samples  %s\n", n++,
                        Bytes.rate(x.beforeRate()), Bytes.rate(x.afterRate()), Bytes.signedRate(x.delta()),
                        ratio(x.ratio()), x.key().beforeSamples(), x.key().afterSamples(), x.key().label()));
                sb.append(x.key().stack().pretty("        ", STACK_FRAMES));
            }
        }
        return sb.toString();
    }

    static String locks(final ContentionReport r, final int top, final boolean bySite) {
        final StringBuilder sb = new StringBuilder(header(r.info()));
        sb.append(settingsLine(r.info(), "Thresholds", "jdk.JavaMonitorEnter", "jdk.ThreadPark"));
        final String none = r.noContention();
        if (none != null) {
            // When every wait that got this far was a worker waiting for its own queue, that is
            // the answer rather than an empty report: an idle node has no contention, and the
            // section below is all there is to say about it, so it is what gets said.
            sb.append('\n').append(none).append('\n');
            sb.append(waitingForWork(r, top));
            return sb.toString();
        }
        sb.append(String.format(Locale.ROOT, "%-10s %s across %d wait%s\n", "Blocked", Durations.format(r.totalNanos()),
                r.waits().size(), r.waits().size() == 1 ? "" : "s"));
        if (r.clippedCount() > 0) {
            sb.append(String.format(Locale.ROOT, "%-10s %d wait%s began before the recording or outlived it; "
                            + "only the part inside it is counted\n", "Note", r.clippedCount(),
                    r.clippedCount() == 1 ? "" : "s"));
        }

        if (bySite) {
            sb.append(lockSites(r, top));
        } else {
            sb.append("\nLOCKS BY TOTAL WAIT\n");
            final List<ContentionReport.LockStats> ranked = r.locks(top);
            final TextTable locks = new TextTable("Lock", "Kind", "Total", "Waits", "Max", "Waiters", "Held by")
                    .numeric(2, 3, 4);
            for (final ContentionReport.LockStats l : ranked) {
                locks.row(l.lock().pretty(), l.lock().kind().label(), Durations.format(l.totalNanos()), l.count(),
                        Durations.format(l.maxNanos()), names(l.waiters()), names(l.owners()));
            }
            sb.append(locks.render("  "));

            // Without this a row above is a name nobody can act on: a hot lock of many short waits
            // never reaches LONGEST WAITS, which is where the only other stack is.
            if (!ranked.isEmpty()) {
                sb.append("\nWHERE THEY WAITED (the longest wait for each lock above)\n");
                for (final ContentionReport.StackGroup g : r.lockStacks(top, STACK_FRAMES)) {
                    // Lock names are long (a fully qualified class and an address), so several of
                    // them go one per line under a count rather than end to end across the page.
                    if (g.locks().size() == 1) {
                        sb.append(String.format(Locale.ROOT, "  %s  %s\n", g.locks().getFirst().pretty(),
                                Durations.format(g.longest().duration())));
                    } else {
                        sb.append(String.format(Locale.ROOT, "  %d locks with this stack, longest %s\n",
                                g.locks().size(), Durations.format(g.longest().duration())));
                        for (final Wait.LockKey lock : shown(g.locks())) {
                            sb.append("    ").append(lock.pretty()).append('\n');
                        }
                        if (g.locks().size() > NAMES_SHOWN) {
                            sb.append("    (+").append(g.locks().size() - NAMES_SHOWN).append(" more)\n");
                        }
                    }
                    sb.append(g.longest().stack().pretty("        ", STACK_FRAMES));
                }
            }
        }

        sb.append("\nTHREADS BY TIME BLOCKED\n");
        final TextTable threads = new TextTable("Thread", "Total", "Waits", "Max", "Share").numeric(1, 2, 3, 4);
        final double span = Math.max(1, r.info().span().duration());
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

        sb.append(waitingForWork(r, top));

        sb.append("\nLONGEST WAITS\n");
        int n = 1;
        for (final Wait w : r.longest(top)) {
            sb.append(String.format(Locale.ROOT, "  %2d  %s  %8s  %s waited for %s%s\n", n++,
                    Durations.offset(w.start() - r.info().startNanos()), Durations.format(w.duration()),
                    w.waiter().name(), w.lock().pretty(), w.owner() == null ? "" : " " + w.heldBy()));
            sb.append(w.stack().pretty("        ", STACK_FRAMES));
        }
        return sb.toString();
    }

    /**
     * The parks that are a worker waiting for its own queue: not contention, and on an idle
     * process the only thing the recording has to say. Empty when nothing was classified that
     * way, so both the ordinary report and the no-contention one can simply append it.
     */
    private static String waitingForWork(final ContentionReport r, final int top) {
        if (r.workWaits().isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "\nWAITING FOR WORK (not contention: %d thread%s parked on an empty "
                        + "queue, %s across %d park%s)\n", r.workWaitThreads(), r.workWaitThreads() == 1 ? "" : "s",
                Durations.format(r.workWaitNanos()), r.workWaits().size(),
                r.workWaits().size() == 1 ? "" : "s"));
        if (r.perchCount() > 0) {
            sb.append(String.format(Locale.ROOT, "  %d of these lock%s recognised by shape rather than by name: "
                            + "one thread, no holder,\n  most of the recording parked there — or the same stack "
                            + "as a lock like that. Pass --idle none to see them all.\n",
                    r.perchCount(), r.perchCount() == 1 ? " was" : "s were"));
        }
        final TextTable idle = new TextTable("Queue", "Total", "Parks", "Max", "Threads").numeric(1, 2, 3);
        for (final ContentionReport.LockStats l : r.workWaitLocks(top)) {
            idle.row(l.lock().pretty(), Durations.format(l.totalNanos()), l.count(),
                    Durations.format(l.maxNanos()), names(l.waiters()));
        }
        return sb.append(idle.render("  ")).toString();
    }

    /**
     * {@code --by-site}: one row per stack instead of one per lock instance. Fifteen queues
     * of the same kind are one site with fifteen instances, not fifteen rows a reader has to
     * recognise as one and add up.
     */
    private static String lockSites(final ContentionReport r, final int top) {
        final StringBuilder sb = new StringBuilder(
                "\nLOCK SITES BY TOTAL WAIT (one row per stack; without --by-site each instance has its own row)\n");
        int n = 1;
        for (final ContentionReport.SiteStats s : r.lockSites(top)) {
            sb.append(String.format(Locale.ROOT, "  %2d  %-8s %10s across %d wait%s, %d lock instance%s, longest %s\n",
                    n++, s.kind().label(), Durations.format(s.totalNanos()), s.count(), s.count() == 1 ? "" : "s",
                    s.locks().size(), s.locks().size() == 1 ? "" : "s", Durations.format(s.maxNanos())));
            sb.append(String.format(Locale.ROOT, "      waited by %s%s\n", names(s.waiters()),
                    s.owners().isEmpty() ? "" : ", held by " + names(s.owners())));
            if (s.locks().size() == 1) {
                sb.append("      ").append(s.locks().getFirst().pretty()).append('\n');
            }
            // The depth the rows were grouped at, so what one row stands for is what it prints.
            sb.append(s.longest().stack().pretty("        ", ContentionReport.SITE_FRAMES));
        }
        return sb.toString();
    }

    static String stalls(final StallReport r, final int top) {
        final StringBuilder sb = new StringBuilder(header(r.info()));
        sb.append(settingsLine(r.info(), "Sampling", "jdk.ExecutionSample", "jdk.NativeMethodSample"));
        sb.append(settingsLine(r.info(), "Thresholds", "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ThreadSleep",
                "jdk.SocketRead", "jdk.FileRead"));
        sb.append(String.format(Locale.ROOT, "%-10s %s\n", "Gap", Durations.format(r.gapNanos())));
        if (r.isNoThreadMatched()) {
            // Warnings still matter here (they name the matching threads there was nothing on),
            // and so do the JVM-wide pauses, which stop every thread whichever were asked about.
            for (final String w : r.warnings()) {
                sb.append("WARNING    ").append(w).append('\n');
            }
            sb.append('\n').append(StallReport.NO_THREAD).append('\n');
            sb.append(pauses(r, top));
            return sb.toString();
        }
        // A count, not the roll call: thirteen dispatchers made this one line 1200 characters wide,
        // and every name on it is in the PER THREAD table below, with the same samples and cadence.
        sb.append(String.format(Locale.ROOT, "%-10s %d matched\n", "Threads", r.threads().size()));
        // The verdict on the question comes before the stalls, and before the warnings: "0 found"
        // on threads the recording cannot see into is not an answer.
        final List<String> unseen = r.unseen();
        for (final String u : unseen) {
            sb.append(String.format(Locale.ROOT, "%-10s %s\n", "Unseen", u));
        }
        for (final String w : r.warnings()) {
            sb.append("WARNING    ").append(w).append('\n');
        }

        // Summary first, as the HTML page is: the totals and who stalled, then the evidence.
        if (!r.stalls().isEmpty()) {
            sb.append("\nBY VERDICT\n");
            final TextTable verdicts = new TextTable("Verdict", "Stalls", "Stalled", "Worst").numeric(1, 2, 3);
            for (final StallReport.VerdictSummary v : r.byVerdict()) {
                verdicts.row(v.verdict(), v.count(), Durations.format(v.totalNanos()), Durations.format(v.worstNanos()));
            }
            sb.append(verdicts.render("  "));
        }
        sb.append(perThread(r, top));

        final List<Stall> explained = r.explained();
        final List<Stall> shown = StallReport.top(explained, top);
        sb.append(String.format(Locale.ROOT, "\nSTALLS >= %s: %d found%s, longest first\n",
                Durations.format(r.gapNanos()), explained.size(),
                shown.size() < explained.size() ? ", showing " + shown.size() : ""));
        if (explained.isEmpty()) {
            sb.append(unseen.isEmpty() ? "  none\n" : "  none this recording can show: see Unseen above\n");
        }
        sb.append(rows(r, shown));

        // Ranked apart, not hidden: an unexplained gap is a long number with nothing under it,
        // and next to an explained stall it wins every comparison it should lose.
        final List<Stall> gaps = r.unexplained();
        if (!gaps.isEmpty()) {
            final List<Stall> shownGaps = StallReport.top(gaps, top);
            sb.append(String.format(Locale.ROOT, "\nUNEXPLAINED GAPS >= %s: %d found%s, longest first\n",
                    Durations.format(r.gapNanos()), gaps.size(),
                    shownGaps.size() < gaps.size() ? ", showing " + shownGaps.size() : ""));
            sb.append(blindSpotNote(r.info()));
            sb.append(rows(r, shownGaps));
        }
        sb.append(pauses(r, top));
        return sb.toString();
    }

    /**
     * The threads that stalled, most stalled first, at most {@code top}; the rest, and the
     * threads with no stall, counted on one line rather than listed.
     */
    private static String perThread(final StallReport r, final int top) {
        final List<StallReport.ThreadSummary> stalled = r.stalledThreads();
        final List<StallReport.ThreadSummary> shown = stalled.size() > top ? stalled.subList(0, top) : stalled;
        final StringBuilder sb = new StringBuilder("\nPER THREAD (most stalled first; cadence: median interval between "
                + "samples, which bounds what can be seen)\n");
        if (!shown.isEmpty()) {
            final TextTable summary = new TextTable("Thread", "Samples", "Java cadence", "Native cadence",
                    "Unseen below", "Stalls", "Stalled", "Share", "Worst").numeric(1, 2, 3, 4, 5, 6, 7, 8);
            final double span = Math.max(1, r.info().span().duration());
            for (final StallReport.ThreadSummary t : shown) {
                summary.row(t.thread().name(), t.samples(), Durations.formatOrDash(t.javaCadenceNanos()),
                        Durations.formatOrDash(t.nativeCadenceNanos()), t.unseenBelow(), t.stalls(),
                        Durations.format(t.stalledNanos()), pct(t.stalledNanos() / span),
                        Durations.format(t.worstNanos()));
            }
            sb.append(summary.render("  "));
        }
        final String rest = rest(stalled.size() - shown.size(), r.threads().size() - stalled.size());
        if (!rest.isEmpty()) {
            sb.append("  ").append(rest).append('\n');
        }
        return sb.toString();
    }

    /** {@code 12 more that stalled, 110 with no stall}, leaving out a zero. */
    static String rest(final int moreStalled, final int clean) {
        final StringBuilder sb = new StringBuilder();
        if (moreStalled > 0) {
            sb.append(moreStalled).append(" more that stalled");
        }
        if (clean > 0) {
            sb.append(sb.isEmpty() ? "" : ", ").append(clean).append(clean == 1 ? " thread" : " threads")
                    .append(" with no stall");
        }
        return sb.toString();
    }


    private static String pauses(final StallReport r, final int top) {
        if (r.pauses().isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder("\nJVM-WIDE PAUSES >= gap (stop every thread)\n");
        final List<Pause> pauses = r.pauses();
        for (int i = 0; i < Math.min(top, pauses.size()); i++) {
            final Pause p = pauses.get(i);
            sb.append(String.format(Locale.ROOT, "  %s  %8s  %s: %s\n", Durations.offset(p.interval().start()
                    - r.info().startNanos()), Durations.format(p.duration()), p.kind().label(), p.detail()));
        }
        if (pauses.size() > top) {
            sb.append("  ... ").append(pauses.size() - top).append(" more\n");
        }
        return sb.toString();
    }

    /**
     * One numbered line per stall. Each keeps its own row — they are separate occurrences, not
     * one aggregate — but a stack is printed once: one lock convoying two event loops filled
     * five consecutive rows with the same six frames.
     */
    private static String rows(final StallReport r, final List<Stall> stalls) {
        final StringBuilder sb = new StringBuilder();
        final Map<String, Integer> firstPrinted = new java.util.HashMap<>();
        int n = 1;
        for (final Stall s : stalls) {
            final int row = n++;
            sb.append(String.format(Locale.ROOT, "  %2d  %-22s %s  %8s  %-15s %s%s\n", row, s.thread().name(),
                    Durations.offset(s.start() - r.info().startNanos()), Durations.format(s.duration()),
                    s.verdict(), s.detail(), evidence(s)));
            final String stack = s.stack().pretty("        ", STACK_FRAMES);
            if (stack.isEmpty()) {
                continue;
            }
            final Integer seen = firstPrinted.putIfAbsent(stack, row);
            if (seen == null) {
                sb.append(stack);
            } else {
                sb.append(String.format(Locale.ROOT, "        same stack as #%d\n", seen));
            }
        }
        return sb.toString();
    }

    /**
     * What a gap with no evidence most often is. The socket count is a fact about the file and
     * it can be near zero on a server that streamed gigabytes: an HTTP stack that writes a
     * response through its own buffering produces no {@code jdk.SocketWrite} at all, so time
     * spent in one lands here with nothing under it.
     */
    static String blindSpotNote(final RecordingInfo info) {
        final long writes = info.eventCounts().getOrDefault("jdk.SocketWrite", 0L);
        return String.format(Locale.ROOT, "  No blocking event and too few samples to say what the thread was doing.\n"
                        + "  This recording holds %d jdk.SocketWrite event%s in %s: some HTTP stacks produce none, so a\n"
                        + "  response being written is invisible here.\n", writes, writes == 1 ? "" : "s",
                Durations.format(info.duration()));
    }

    /** {@code " (+7%)"} when the counted threads carry enough of the estimate for the comparison to mean something. */
    static String errorNote(final AllocationReport r) {
        return r.isEstimateErrorMaterial() ? String.format(Locale.ROOT, " (%+.0f%%)", r.estimateError() * 100) : "";
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

    /**
     * Thread names, capped: a lock on a hundred-thread server listed every distinct waiter
     * on one line, 3,630 characters of it, and the count is the information a reader wants.
     */
    static String names(final Set<ThreadRef> threads) {
        final List<String> names = new ArrayList<>(threads.size());
        for (final ThreadRef t : threads) {
            names.add(t.name());
        }
        return capped(names);
    }

    /** The head of a list under the same cap every other name list uses. */
    static <T> List<T> shown(final List<T> all) {
        return all.size() > NAMES_SHOWN ? all.subList(0, NAMES_SHOWN) : all;
    }

    private static String capped(final List<String> names) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0, n = names.size(); i < n; i++) {
            if (i == NAMES_SHOWN) {
                sb.append(" (+").append(n - i).append(" more)");
                break;
            }
            sb.append(i > 0 ? ", " : "").append(names.get(i));
        }
        return sb.toString();
    }
}
