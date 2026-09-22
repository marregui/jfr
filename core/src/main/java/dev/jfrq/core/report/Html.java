// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.util.Bytes;
import dev.jfrq.core.util.ClassNames;
import dev.jfrq.core.util.Durations;

/**
 * Self-contained HTML reports: one file, no scripts, no external resources, so they can
 * be attached to a ticket and opened anywhere. Timelines are inline SVG.
 */
public final class Html {

    private static final int TIMELINE_WIDTH = 1000;
    private static final int ROW_HEIGHT = 22;
    private static final int LABEL_WIDTH = 220;
    /**
     * Boxes drawn per timeline row. A recording with a 1 ms threshold can hold hundreds of
     * thousands of waits; the longest ones are the ones a reader can see anyway, and the
     * file stays a few hundred kilobytes instead of hundreds of megabytes.
     */
    static final int MAX_BOXES_PER_ROW = 2_000;

    private Html() {
    }

    /** Rows listed in the stalls table; the timeline always shows every stall. */
    static final int MIN_LISTED = 100;

    /** Frames a stack row shows, and therefore the depth allocation sites are folded at. */
    static final int STACK_FRAMES = 12;

    /** Names listed in a cell before the rest become a count. */
    private static final int NAMES_SHOWN = 4;

    public static String stalls(final StallReport report, final int top) {
        final Page p = new Page("jfrq stalls", report.info());
        p.kv("Gap", Durations.format(report.gapNanos()));
        p.warnings(report.warnings());
        p.kv("Evidence", "event: exact to the event's timestamps; samples: as good as the sampling density; "
                + "silence: an absence of samples explained by what covered it");

        p.h2("Threads");
        p.tableStart("Thread", "Samples", "Java cadence", "Native cadence", "Stalls", "Stalled", "Worst");
        for (final StallReport.ThreadSummary t : report.threads()) {
            p.row(t.thread().name(), t.samples(), Durations.format(t.javaCadenceNanos()),
                    Durations.format(t.nativeCadenceNanos()), t.stalls(), Durations.format(t.stalledNanos()),
                    Durations.format(t.worstNanos()));
        }
        p.tableEnd();

        p.h2("By verdict");
        p.tableStart("Verdict", "Stalls", "Stalled", "Worst");
        for (final StallReport.VerdictSummary v : report.byVerdict()) {
            p.row(v.verdict(), v.count(), Durations.format(v.totalNanos()), Durations.format(v.worstNanos()));
        }
        p.tableEnd();

        p.h2("Timeline");
        p.raw(stallTimeline(report));

        final List<Stall> listed = report.top(Math.max(top, MIN_LISTED));
        p.h2(listed.size() < report.stalls().size()
                ? "Stalls, longest first (" + listed.size() + " of " + report.stalls().size() + ")"
                : "Stalls, longest first");
        p.tableStart("#", "Thread", "At", "Duration", "Verdict", "Detail", "Evidence");
        int n = 1;
        for (final Stall s : listed) {
            p.row(n++, s.thread().name(), Durations.offset(s.start() - report.info().startNanos()),
                    Durations.format(s.duration()), s.verdict(), s.detail(), s.evidence().name().toLowerCase(Locale.ROOT));
            if (!s.stack().isEmpty()) {
                p.stackRow(7, s.stack());
            }
        }
        p.tableEnd();

        if (!report.pauses().isEmpty()) {
            p.h2("JVM-wide pauses ≥ gap");
            p.tableStart("At", "Duration", "Kind", "Detail");
            for (final Pause pause : report.pauses()) {
                p.row(Durations.offset(pause.interval().start() - report.info().startNanos()),
                        Durations.format(pause.length()), pause.kind().label(), pause.detail());
            }
            p.tableEnd();
        }
        return p.finish();
    }

    private static String stallTimeline(final StallReport report) {
        final Interval span = report.info().span();
        final List<String> labels = new ArrayList<>();
        final List<List<Box>> rows = new ArrayList<>();
        for (final StallReport.ThreadSummary t : report.threads()) {
            labels.add(t.thread().name());
            final List<Box> boxes = new ArrayList<>();
            for (final Stall s : report.stallsOf(t.thread())) {
                boxes.add(new Box(s.interval(), colour(s.verdict()),
                        s.verdict() + " " + Durations.format(s.duration()) + ": " + s.detail()));
            }
            rows.add(longest(boxes));
        }
        if (!report.pauses().isEmpty()) {
            labels.add("JVM pauses");
            final List<Box> boxes = new ArrayList<>();
            for (final Pause pause : report.pauses()) {
                boxes.add(new Box(pause.interval(), "#9e9e9e",
                        pause.kind().label() + " " + Durations.format(pause.length()) + ": " + pause.detail()));
            }
            rows.add(longest(boxes));
        }
        final StringBuilder legend = new StringBuilder("<p class=\"legend\">");
        for (final Stall.Verdict v : Stall.Verdict.values()) {
            legend.append("<span style=\"background:").append(colour(v)).append("\"></span>").append(v.name()).append(' ');
        }
        legend.append("</p>");
        return timeline(span, labels, rows) + legend;
    }

    public static String locks(final ContentionReport report, final int top) {
        final Page p = new Page("jfrq locks", report.info());
        p.kv("Total blocked time", Durations.format(report.totalNanos()) + " across " + report.waits().size()
                + " waits");
        if (report.clippedCount() > 0) {
            p.kv("Clipped to the window", report.clippedCount() + (report.clippedCount() == 1 ? " wait" : " waits")
                    + " began before the recording or outlived it, and count only for the part inside it");
        }
        thresholds(p, report.info());

        p.h2("Locks by total wait");
        p.tableStart("Lock", "Kind", "Total", "Waits", "Max", "Waiters", "Held by");
        for (final ContentionReport.LockStats l : report.locks(top)) {
            p.row(l.lock().pretty(), l.lock().kind().label(), Durations.format(l.totalNanos()), l.count(),
                    Durations.format(l.maxNanos()), names(l.waiters()), names(l.owners()));
            if (l.longest() != null && !l.longest().stack().isEmpty()) {
                p.stackRow(7, l.longest().stack());
            }
        }
        p.tableEnd();

        if (!report.workWaits().isEmpty()) {
            p.h2("Waiting for work: " + report.workWaitThreads() + " threads parked on their own queue");
            p.kv("Not contention", Durations.format(report.workWaitNanos()) + " across " + report.workWaits().size()
                    + " parks, kept out of the totals above");
            p.tableStart("Queue", "Total", "Parks", "Max", "Threads");
            for (final ContentionReport.LockStats l : report.workWaitLocks(top)) {
                p.row(l.lock().pretty(), Durations.format(l.totalNanos()), l.count(), Durations.format(l.maxNanos()),
                        names(l.waiters()));
            }
            p.tableEnd();
        }

        p.h2("Threads by time blocked");
        p.tableStart("Thread", "Total", "Waits", "Max");
        for (final ContentionReport.ThreadStats t : report.waiters(top)) {
            p.row(t.thread().name(), Durations.format(t.totalNanos()), t.count(), Durations.format(t.maxNanos()));
        }
        p.tableEnd();

        p.h2("Timeline");
        p.raw(lockTimeline(report, top));

        final List<ContentionReport.Convoy> convoys = report.convoys(5, top);
        if (!convoys.isEmpty()) {
            p.h2("Convoys: the holder was itself blocked");
            p.tableStart("At", "Chain");
            for (final ContentionReport.Convoy c : convoys) {
                final StringBuilder chain = new StringBuilder();
                for (final Wait w : c.links()) {
                    if (!chain.isEmpty()) {
                        chain.append(" → ");
                    }
                    chain.append(w.waiter().name()).append(" waited ").append(Durations.format(w.duration()))
                            .append(" for ").append(w.lock().pretty());
                    if (w.owner() != null) {
                        chain.append(' ').append(w.heldBy());
                    }
                }
                p.row(Durations.offset(c.head().start() - report.info().startNanos()), chain.toString());
            }
            p.tableEnd();
        }

        p.h2("Longest waits");
        p.tableStart("At", "Duration", "Waiter", "Lock", "Held by");
        for (final Wait w : report.longest(top)) {
            p.row(Durations.offset(w.start() - report.info().startNanos()), Durations.format(w.duration()),
                    w.waiter().name(), w.lock().pretty(), w.heldBy());
            if (!w.stack().isEmpty()) {
                p.stackRow(5, w.stack());
            }
        }
        p.tableEnd();
        return p.finish();
    }

    private static String lockTimeline(final ContentionReport report, final int top) {
        final Interval span = report.info().span();
        final List<String> labels = new ArrayList<>();
        final List<List<Box>> rows = new ArrayList<>();
        final Map<String, String> threadColours = new LinkedHashMap<>();
        for (final ContentionReport.LockStats l : report.locks(top)) {
            labels.add(l.lock().pretty());
            final List<Box> boxes = new ArrayList<>();
            for (final Wait w : report.waits()) {
                if (!w.lock().equals(l.lock())) {
                    continue;
                }
                final String colour = threadColours.computeIfAbsent(w.waiter().name(),
                        _ -> PALETTE[threadColours.size() % PALETTE.length]);
                boxes.add(new Box(w.interval(), colour, w.waiter().name() + " waited "
                        + Durations.format(w.duration()) + (w.owner() == null ? "" : ", " + w.heldBy())));
            }
            rows.add(longest(boxes));
        }
        final StringBuilder legend = new StringBuilder("<p class=\"legend\">");
        threadColours.forEach((name, colour) -> legend.append("<span style=\"background:").append(colour)
                .append("\"></span>").append(escape(name)).append(' '));
        legend.append("</p>");
        return timeline(span, labels, rows) + legend;
    }

    public static String alloc(final AllocationReport report, final int top) {
        final Page p = new Page("jfrq alloc", report.info());
        p.kv("Source", report.source());
        p.kv("Estimated allocation", Bytes.format(report.totalBytes()) + " over "
                + Durations.format(report.info().duration()) + " = " + Bytes.rate(report.rate())
                + " from " + report.samples() + " samples");
        if (report.hasCounters()) {
            p.kv("JVM counters", Bytes.format(report.countedBytes()) + " on " + report.countedByThread().size()
                    + " threads seen at both ends of the file; the estimate for those is "
                    + Bytes.format(report.estimatedOnCountedThreads()) + (report.estimateErrorMaterial()
                    ? String.format(Locale.ROOT, " (%+.0f%%)", report.estimateError() * 100) : "")
                    + (report.totalBytes() > 0
                    ? String.format(Locale.ROOT, ", %.1f%% of the estimate above", report.countedCoverage() * 100)
                    : ""));
        }

        p.h2("By thread");
        p.tableStart("Thread", "Bytes", "Counted", "Rate", "Share", "Top classes");
        for (final AllocationReport.Row<String> r : report.threads(top)) {
            final StringBuilder classes = new StringBuilder();
            for (final AllocationReport.Row<String> c : report.classesOf(r.key(), 3)) {
                if (!classes.isEmpty()) {
                    classes.append(", ");
                }
                classes.append(ClassNames.simple(c.key())).append(' ').append(pct(c.bytes(), r.bytes()));
            }
            p.row(r.key(), Bytes.format(r.bytes()), report.counted(r.key()).map(Bytes::format).orElse(""),
                    Bytes.rate(report.rate(r.bytes())), pct(r.share()), classes);
        }
        p.tableEnd();

        p.h2("By class");
        p.tableStart("Class", "Bytes", "Rate", "Share");
        for (final AllocationReport.Row<String> r : report.classes(top)) {
            p.row(ClassNames.pretty(r.key()), Bytes.format(r.bytes()), Bytes.rate(report.rate(r.bytes())), pct(r.share()));
        }
        p.tableEnd();

        p.h2("By site");
        p.tableStart("Site", "Bytes", "Rate", "Share");
        final Map<Stack, Integer> variants = new LinkedHashMap<>();
        for (final AllocationReport.Row<Stack> r : report.foldedSites(top, STACK_FRAMES, variants)) {
            final int distinct = variants.getOrDefault(r.key(), 1);
            p.row(r.key().top().map(Frame::pretty).orElse("<no stack>")
                            + (distinct > 1 ? " (" + distinct + " stacks that differ only in elided frames)" : ""),
                    Bytes.format(r.bytes()), Bytes.rate(report.rate(r.bytes())), pct(r.share()));
            p.stackRow(4, r.key());
        }
        p.tableEnd();
        return p.finish();
    }

    public static String info(final RecordingInfo info) {
        final Page p = new Page("jfrq info", info);
        p.kv("Threads", Integer.toString(info.threads().size()));
        p.kv("Chunks", Integer.toString(info.chunks()));
        if (!info.hasSettings()) {
            p.kv("Settings", "unknown: the recording has no jdk.ActiveSetting events");
        }
        p.h2("Event types");
        p.tableStart("Event type", "Count", "Enabled", "Threshold", "Period", "Throttle");
        final List<Map.Entry<String, Long>> byCount = new ArrayList<>(info.eventCounts().entrySet());
        byCount.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (final Map.Entry<String, Long> e : byCount) {
            final String type = e.getKey();
            p.row(type, e.getValue(), info.settings().containsKey(type) ? (info.enabled(type) ? "yes" : "no") : "",
                    info.threshold(type).map(Durations::format).orElse(""),
                    info.period(type).map(Durations::format).or(() -> info.setting(type, "period")).orElse(""),
                    info.throttle(type).orElse(""));
        }
        p.tableEnd();
        return p.finish();
    }

    public static String allocDiff(final AllocationDiff diff, final int top) {
        final Page p = new Page("jfrq alloc diff", diff.current().info());
        p.kv("Baseline", diff.baseline().info().file().toString() + " (" + Bytes.rate(diff.baseline().rate()) + ")");
        final List<String> baselineWarnings = new ArrayList<>();
        for (final String w : diff.baseline().info().warnings()) {
            baselineWarnings.add("baseline: " + w);
        }
        p.warnings(baselineWarnings);
        p.kv("Current", diff.current().info().file().toString() + " (" + Bytes.rate(diff.current().rate()) + ")");
        p.kv("Change", Bytes.signedRate(diff.total().delta()) + " (" + ratio(diff.total().ratio()) + ")");

        p.h2("By thread");
        deltaTable(p, diff.threads(top), k -> k);
        p.h2("By class");
        deltaTable(p, diff.classes(top), ClassNames::pretty);
        p.h2("By site");
        p.tableStart("Site", "Before", "After", "Change");
        for (final AllocationDiff.Delta<Stack> d : diff.sites(top)) {
            p.row(d.key().top().map(Frame::pretty).orElse("<no stack>"), Bytes.rate(d.beforeRate()),
                    Bytes.rate(d.afterRate()), Bytes.signedRate(d.delta()) + " (" + ratio(d.ratio()) + ")");
            p.stackRow(4, d.key());
        }
        p.tableEnd();
        return p.finish();
    }

    private static <K> void deltaTable(final Page p, final List<AllocationDiff.Delta<K>> deltas,
                                       final Function<K, String> name) {
        p.tableStart("Key", "Before", "After", "Change");
        for (final AllocationDiff.Delta<K> d : deltas) {
            p.row(name.apply(d.key()), Bytes.rate(d.beforeRate()), Bytes.rate(d.afterRate()),
                    Bytes.signedRate(d.delta()) + " (" + ratio(d.ratio()) + ")");
        }
        p.tableEnd();
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

    static String pct(final double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    static String pct(final long part, final long whole) {
        return whole == 0 ? "0%" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / whole);
    }

    private static void thresholds(final Page p, final RecordingInfo info) {
        final StringBuilder sb = new StringBuilder();
        for (final String t : List.of("jdk.JavaMonitorEnter", "jdk.ThreadPark")) {
            info.threshold(t).ifPresent(d -> sb.append(t).append(' ').append(Durations.format(d)).append("; "));
        }
        if (!sb.isEmpty()) {
            p.kv("Thresholds", sb.toString());
        }
    }

    /** Thread names, capped the same way as the text report: the count carries the information. */
    private static String names(final Set<ThreadRef> threads) {
        final StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (final ThreadRef t : threads) {
            if (shown == NAMES_SHOWN) {
                sb.append(" (+").append(threads.size() - shown).append(" more)");
                break;
            }
            sb.append(shown++ > 0 ? ", " : "").append(t.name());
        }
        return sb.toString();
    }

    /** The {@link #MAX_BOXES_PER_ROW} longest boxes of a row, back in time order. */
    private static List<Box> longest(final List<Box> boxes) {
        if (boxes.size() <= MAX_BOXES_PER_ROW) {
            return boxes;
        }
        final List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingLong((Box b) -> b.interval().length()).reversed());
        final List<Box> kept = new ArrayList<>(sorted.subList(0, MAX_BOXES_PER_ROW));
        kept.sort(Comparator.comparing(Box::interval));
        return kept;
    }

    private static final String[] PALETTE = {
            "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2", "#17becf"};

    static String colour(final Stall.Verdict v) {
        return switch (v) {
            case GC_PAUSE, SAFEPOINT -> "#9e9e9e";
            case BLOCKED_MONITOR -> "#d62728";
            case PARKED, OBJECT_WAIT -> "#e377c2";
            case SLEEP -> "#9467bd";
            case BLOCKING_IO -> "#ff7f0e";
            case BUSY -> "#1f77b4";
            case SATURATED -> "#17becf";
            case UNEXPLAINED -> "#bcbd22";
        };
    }

    private record Box(Interval interval, String colour, String title) {
    }

    private static String timeline(final Interval span, final List<String> labels, final List<List<Box>> rows) {
        final double scale = span.length() <= 0 ? 0 : (double) TIMELINE_WIDTH / span.length();
        final int height = rows.size() * ROW_HEIGHT + 20;
        final StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"timeline\" viewBox=\"0 0 ").append(LABEL_WIDTH + TIMELINE_WIDTH + 10).append(' ')
                .append(height).append("\" xmlns=\"http://www.w3.org/2000/svg\">\n");
        for (int r = 0; r < rows.size(); r++) {
            final int y = r * ROW_HEIGHT;
            svg.append("<text x=\"0\" y=\"").append(y + 15).append("\" class=\"lbl\">").append(escape(trunc(labels.get(r))))
                    .append("</text>\n");
            svg.append("<rect x=\"").append(LABEL_WIDTH).append("\" y=\"").append(y + 3).append("\" width=\"")
                    .append(TIMELINE_WIDTH).append("\" height=\"").append(ROW_HEIGHT - 6).append("\" class=\"track\"/>\n");
            for (final Box b : rows.get(r)) {
                final double x = LABEL_WIDTH + (b.interval.start() - span.start()) * scale;
                final double w = Math.max(1.5, b.interval.length() * scale);
                svg.append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(y + 3).append("\" width=\"").append(fmt(w))
                        .append("\" height=\"").append(ROW_HEIGHT - 6).append("\" fill=\"").append(b.colour)
                        .append("\"><title>").append(escape(b.title)).append("</title></rect>\n");
            }
        }
        final int axisY = rows.size() * ROW_HEIGHT + 12;
        for (int t = 0; t <= 10; t++) {
            final double x = LABEL_WIDTH + t * (TIMELINE_WIDTH / 10.0);
            final long nanos = (long) (span.length() * (t / 10.0));
            svg.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(axisY).append("\" class=\"axis\">")
                    .append(Durations.offset(nanos)).append("</text>\n");
        }
        svg.append("</svg>\n");
        return svg.toString();
    }

    private static String trunc(final String s) {
        return s.length() > 34 ? s.substring(0, 31) + "…" : s;
    }

    private static String fmt(final double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    static String escape(final String s) {
        int first = -1;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '<' || c == '>' || c == '&' || c == '"') {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return s;
        }
        final StringBuilder sb = new StringBuilder(s.length() + 16);
        for (final char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Accumulates a page. */
    private static final class Page {
        private final StringBuilder sb = new StringBuilder();

        Page(final String title, final RecordingInfo info) {
            sb.append("<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>").append(escape(title))
                    .append(" · ").append(escape(info.file().getFileName().toString())).append("</title>\n<style>\n")
                    .append(CSS).append("</style></head><body>\n<h1>").append(escape(title)).append("</h1>\n<dl>");
            kv("Recording", info.file().toString());
            kv("Span", Durations.format(info.duration()) + " from " + info.start());
            warnings(info.warnings());
        }

        void kv(final String k, final String v) {
            sb.append("<dt>").append(escape(k)).append("</dt><dd>").append(escape(v)).append("</dd>\n");
        }

        void warnings(final List<String> warnings) {
            if (warnings.isEmpty()) {
                return;
            }
            sb.append("</dl><ul class=\"warn\">");
            for (final String w : warnings) {
                sb.append("<li>").append(escape(w)).append("</li>");
            }
            sb.append("</ul><dl>");
        }

        void h2(final String text) {
            sb.append("</dl>\n<h2>").append(escape(text)).append("</h2>\n<dl>");
        }

        void raw(final String html) {
            sb.append("</dl>\n").append(html).append("<dl>");
        }

        void tableStart(final String... headers) {
            sb.append("</dl>\n<table><thead><tr>");
            for (final String h : headers) {
                sb.append("<th>").append(escape(h)).append("</th>");
            }
            sb.append("</tr></thead><tbody>\n");
        }

        private static final Pattern NUMERIC =
                Pattern.compile("^[+\\-]?[0-9.]+( ?[a-zA-Zµ%/]+)?$");

        void row(final Object... cells) {
            sb.append("<tr>");
            for (final Object c : cells) {
                final String text = c == null ? "" : String.valueOf(c);
                final boolean numeric = !text.isEmpty() && (Character.isDigit(text.charAt(0)) || text.charAt(0) == '+'
                        || text.charAt(0) == '-') && NUMERIC.matcher(text).matches();
                sb.append(numeric ? "<td class=\"n\">" : "<td>").append(escape(text)).append("</td>");
            }
            sb.append("</tr>\n");
        }

        void stackRow(final int colspan, final Stack stack) {
            sb.append("<tr class=\"stack\"><td colspan=\"").append(colspan).append("\"><pre>")
                    .append(escape(stack.pretty("", STACK_FRAMES))).append("</pre></td></tr>\n");
        }

        void tableEnd() {
            sb.append("</tbody></table>\n<dl>");
        }

        String finish() {
            sb.append("</dl>\n<p class=\"foot\">Generated by jfrq.</p></body></html>\n");
            return sb.toString();
        }
    }

    private static final String CSS = """
            :root {
              color-scheme: light dark;
              --bg: #ffffff; --fg: #222222; --muted: #555555; --faint: #999999;
              --rule: #dddddd; --cell: #eeeeee; --head: #f5f5f5; --track: #f0f0f0;
              --warn-bg: #fff7e0; --warn-edge: #e6a700;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #121417; --fg: #e6e6e6; --muted: #b0b0b0; --faint: #808080;
                --rule: #333a42; --cell: #262b31; --head: #1c2025; --track: #1e2329;
                --warn-bg: #2e2513; --warn-edge: #b98900;
              }
            }
            body { font: 14px/1.4 -apple-system, Segoe UI, Helvetica, Arial, sans-serif; margin: 2em;
                   color: var(--fg); background: var(--bg); }
            h1 { font-size: 1.5em; } h2 { font-size: 1.15em; margin-top: 1.6em; border-bottom: 1px solid var(--rule); }
            dl { display: grid; grid-template-columns: max-content auto; gap: 0.2em 1em; margin: 0.6em 0; }
            dl:empty { display: none; }
            dt { font-weight: 600; } dd { margin: 0; }
            table { border-collapse: collapse; width: 100%; margin: 0.5em 0; }
            th, td { text-align: left; padding: 0.3em 0.6em; border-bottom: 1px solid var(--cell); vertical-align: top; }
            th { background: var(--head); } td.n { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
            tr.stack td { padding: 0 0.6em 0.6em 2em; border-bottom: 1px solid var(--cell); }
            pre { margin: 0; font: 12px/1.35 ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--muted); }
            ul.warn { background: var(--warn-bg); border-left: 4px solid var(--warn-edge); padding: 0.6em 1em 0.6em 2em; }
            svg.timeline { width: 100%; height: auto; font: 11px sans-serif; }
            svg .track { fill: var(--track); } svg .lbl { fill: var(--fg); } svg .axis { fill: var(--faint); text-anchor: middle; }
            p.legend span { display: inline-block; width: 12px; height: 12px; margin: 0 4px 0 10px; vertical-align: middle; }
            p.foot { color: var(--faint); margin-top: 3em; }
            """;
}
