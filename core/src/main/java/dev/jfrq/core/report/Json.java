// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.health.HealthReport;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.stalls.Timeline.Pause;

/**
 * The reports as JSON, for a program rather than a person: {@code --json}. Built from the same
 * report objects as the text and the HTML, and bounded by the same {@code --top}, so the three
 * cannot disagree; every list that was cut says how many it had.
 *
 * <p>The field names are the contract, described in {@code docs/JSON.md} and versioned by
 * {@link #SCHEMA}: a field is added without a version change, and renamed, removed or given
 * another meaning only with one. Units are in the names ({@code durationNanos}, {@code bytes},
 * {@code bytesPerSecond}); instants are ISO-8601 in UTC, with {@code offsetNanos} from the
 * recording's start beside them; a value the recording cannot give is {@code null}, never 0.
 */
public final class Json {

    /** The schema version: bumped when a field is renamed, removed or changes meaning. */
    public static final int SCHEMA = 1;
    /** Frames per stack, as the HTML report prints them; the culprit is named apart when deeper. */
    static final int STACK_FRAMES = 12;

    private Json() {
    }

    // --------------------------------------------------------------------------- health

    public static String health(final HealthReport r, final int top, final String version) {
        final Writer w = envelope("health", version, r.info());
        w.name("findings").array();
        for (final HealthReport.Finding f : r.findings()) {
            w.object();
            w.name("kind").value(f.kind().name());
            w.name("count").value(f.count());
            w.name("first").value(f.firstNanos() == Nulls.LONG_NULL ? null : iso(f.firstNanos()));
            w.name("firstOffsetNanos").value(f.firstNanos() == Nulls.LONG_NULL ? Nulls.LONG_NULL
                    : f.firstNanos() - r.info().startNanos());
            w.name("last").value(f.lastNanos() == Nulls.LONG_NULL ? null : iso(f.lastNanos()));
            w.name("lastOffsetNanos").value(f.lastNanos() == Nulls.LONG_NULL ? Nulls.LONG_NULL
                    : f.lastNanos() - r.info().startNanos());
            w.name("text").value(f.text());
            w.end();
        }
        w.end();
        final HealthReport.Gc gc = r.gc();
        w.name("gc").object();
        w.name("collections").value(gc.count());
        w.name("byCollector").object();
        for (final Map.Entry<String, Long> e : gc.collections().entrySet()) {
            w.name(e.getKey()).value(e.getValue());
        }
        w.end();
        w.name("byCause").object();
        for (final Map.Entry<String, Long> e : gc.causes().entrySet()) {
            w.name(e.getKey()).value(e.getValue());
        }
        w.end();
        w.name("oldCycles").value(gc.oldCycles());
        w.name("pauseNanos").value(gc.pauseNanos());
        w.name("pauseShare").value(r.info().span().duration() > 0
                ? (double) gc.pauseNanos() / r.info().span().duration() : Double.NaN);
        w.name("longestPauseNanos").value(gc.longestPauseNanos());
        w.name("gcTimeRatio").value(intOrNull(gc.gcTimeRatio()));
        w.name("pauseTargetNanos").value(gc.pauseTargetNanos());
        w.name("maxHeapBytes").value(gc.maxHeapBytes());
        w.end();
        w.name("trends").array();
        for (final HealthReport.Series s : r.trends()) {
            w.object();
            w.name("series").value(s.name());
            w.name("unit").value(s.unit().name());
            w.name("points").value(s.points());
            w.name("start").value(s.start());
            w.name("end").value(s.end());
            w.name("min").value(s.min());
            w.name("max").value(s.max());
            w.name("mean").value(s.mean());
            w.name("floorFirstThird").value(s.floorFirst());
            w.name("floorLastThird").value(s.floorLast());
            w.end();
        }
        w.end();
        w.name("threadsStarted").value(r.threads().started());
        w.name("threadsPeak").value(r.threads().peak());
        final HealthReport.Throwables t = r.throwables();
        w.name("throwables").object();
        w.name("created").value(t.created());
        w.name("createdNanos").value(t.created() == Nulls.LONG_NULL ? Nulls.LONG_NULL : t.createdNanos());
        w.name("perSecond").value(t.rate());
        w.name("events").value(t.samples());
        w.name("throttle").value(t.throttle());
        w.name("errors").object();
        for (final Map.Entry<String, Long> e : t.errors().entrySet()) {
            w.name(e.getKey()).value(e.getValue());
        }
        w.end();
        w.name("classesFound").value(t.byClass().size());
        w.name("byClass").array();
        for (final HealthReport.ClassRow c : top(t.byClass(), top)) {
            w.object();
            w.name("class").value(c.className());
            w.name("events").value(c.samples());
            w.name("share").value(c.share());
            w.name("perSecond").value(c.share() * t.rate());
            w.name("message").value(c.message());
            w.end();
        }
        w.end();
        w.name("sitesFound").value(t.bySite().size());
        w.name("bySite").array();
        for (final HealthReport.SiteRow s : top(t.bySite(), top)) {
            w.object();
            w.name("site").value(s.site());
            w.name("class").value(s.className());
            w.name("events").value(s.samples());
            w.name("share").value(s.share());
            stack(w, s.stack());
            w.end();
        }
        w.end();
        w.end();
        return w.finish();
    }

    // ----------------------------------------------------------------------------- info

    public static String info(final RecordingInfo info, final ThreadCensus.Result census, final String version) {
        final Writer w = envelope("info", version, info);
        w.name("threadsSeen").value(info.threads().size());
        w.name("threadsAlive").object();
        w.name("atStart").value(size(census.aliveAtStart()));
        w.name("started").value(census.starts());
        w.name("ended").value(census.ends());
        w.name("atEnd").value(size(census.aliveAtEnd()));
        w.end();
        w.name("settingsKnown").value(info.hasSettings());
        w.name("thresholded").strings(List.of(RecordingSummary.thresholded(info)));
        w.name("throttled").strings(List.of(RecordingSummary.throttled(info)));
        w.name("eventTypes").array();
        final List<Map.Entry<String, Long>> byCount = new ArrayList<>(info.eventCounts().entrySet());
        byCount.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (final Map.Entry<String, Long> e : byCount) {
            final String type = e.getKey();
            w.object();
            w.name("type").value(type);
            w.name("count").value(e.getValue());
            w.name("enabled").value(info.settings().containsKey(type) ? (Boolean) info.isEnabled(type) : null);
            w.name("thresholdNanos").value(info.thresholdNanos(type));
            w.name("period").value(info.setting(type, "period").orElse(null));
            w.name("periodNanos").value(info.periodNanos(type));
            w.name("throttle").value(info.throttle(type).orElse(null));
            w.end();
        }
        w.end();
        w.name("threadFamilies").array();
        for (final RecordingSummary.Family f : RecordingSummary.threadFamilies(info, census)) {
            w.object();
            w.name("family").value(f.name());
            w.name("glob").value(f.count() > 1 ? RecordingSummary.glob(f.example()) : RecordingSummary.literal(f.example()));
            w.name("threads").value(f.count());
            w.name("seen").value(f.seen());
            w.name("aliveAtStart").value(intOrNull(f.aliveAtStart()));
            w.name("started").value(intOrNull(f.started()));
            w.name("ended").value(intOrNull(f.ended()));
            w.name("aliveAtEnd").value(intOrNull(f.aliveAtEnd()));
            w.name("example").value(f.example());
            w.end();
        }
        w.end();
        return w.finish();
    }

    // --------------------------------------------------------------------------- stalls

    public static String stalls(final StallReport r, final int top, final String version) {
        final Writer w = envelope("stalls", version, r.info());
        final RecordingInfo info = r.info();
        w.name("gapNanos").value(r.gapNanos());
        w.name("samplingPeriodNanos").object();
        w.name("java").value(info.periodNanos("jdk.ExecutionSample"));
        w.name("native").value(info.periodNanos("jdk.NativeMethodSample"));
        w.end();
        w.name("thresholdNanos").object();
        for (final String type : new String[] {"jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.JavaMonitorWait",
                "jdk.ThreadSleep", "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite"}) {
            w.name(type).value(info.thresholdNanos(type));
        }
        w.end();
        w.name("threadsMatched").value(r.threads().size());
        w.name("unseen").strings(r.unseen());
        w.name("warnings").strings(r.warnings());
        w.name("byVerdict").array();
        for (final StallReport.VerdictSummary v : r.byVerdict()) {
            w.object();
            w.name("verdict").value(v.verdict().name());
            w.name("stalls").value(v.count());
            w.name("stalledNanos").value(v.totalNanos());
            w.name("worstNanos").value(v.worstNanos());
            w.end();
        }
        w.end();
        final List<StallReport.ThreadSummary> stalled = r.stalledThreads();
        final List<StallReport.ThreadSummary> shown = top(stalled, top);
        w.name("threadsWithStalls").value(stalled.size());
        w.name("threadsWithoutStalls").value(r.threads().size() - stalled.size());
        w.name("threads").array();
        for (final StallReport.ThreadSummary t : shown) {
            w.object();
            thread(w, t.thread());
            w.name("samples").value(t.samples());
            w.name("javaCadenceNanos").value(positiveOrNull(t.javaCadenceNanos()));
            w.name("nativeCadenceNanos").value(positiveOrNull(t.nativeCadenceNanos()));
            w.name("sight").value(t.sight().name());
            w.name("unseenBelowNanos").value(t.sight() == StallReport.Sight.CLEAR ? null
                    : positiveOrNull(t.unseenBelowNanos()));
            w.name("stalls").value(t.stalls());
            w.name("stalledNanos").value(t.stalledNanos());
            w.name("worstNanos").value(t.worstNanos());
            w.end();
        }
        w.end();
        final List<Stall> explained = r.explained();
        w.name("stallsFound").value(explained.size());
        w.name("stalls").array();
        for (final Stall s : top(explained, top)) {
            stall(w, s, info);
        }
        w.end();
        final List<Stall> gaps = r.unexplained();
        w.name("unexplainedFound").value(gaps.size());
        w.name("unexplained").array();
        for (final Stall s : top(gaps, top)) {
            stall(w, s, info);
        }
        w.end();
        w.name("pausesFound").value(r.pauses().size());
        w.name("pauses").array();
        for (final Pause p : top(r.pauses(), top)) {
            w.object();
            instant(w, p.interval().start(), info);
            w.name("durationNanos").value(p.duration());
            w.name("kind").value(p.kind().name());
            w.name("detail").value(p.detail());
            w.end();
        }
        w.end();
        return w.finish();
    }

    private static void stall(final Writer w, final Stall s, final RecordingInfo info) {
        w.object();
        w.name("thread").value(s.thread().name());
        instant(w, s.start(), info);
        w.name("durationNanos").value(s.duration());
        w.name("verdict").value(s.verdict().name());
        w.name("evidence").value(s.evidence().name());
        w.name("detail").value(s.detail());
        w.name("samples").value(s.samples());
        stack(w, s.stack());
        w.end();
    }

    // ---------------------------------------------------------------------------- locks

    public static String locks(final ContentionReport r, final int top, final boolean bySite, final String version) {
        final Writer w = envelope("locks", version, r.info());
        final RecordingInfo info = r.info();
        w.name("thresholdNanos").object();
        w.name("jdk.JavaMonitorEnter").value(info.thresholdNanos("jdk.JavaMonitorEnter"));
        w.name("jdk.ThreadPark").value(info.thresholdNanos("jdk.ThreadPark"));
        w.end();
        w.name("noContention").value(r.noContention());
        w.name("blockedNanos").value(r.totalNanos());
        w.name("waits").value(r.waits().size());
        w.name("clippedWaits").value(r.clippedCount());
        if (bySite) {
            w.name("sites").array();
            for (final ContentionReport.SiteStats s : r.lockSites(top)) {
                w.object();
                w.name("kind").value(s.kind().name());
                w.name("totalNanos").value(s.totalNanos());
                w.name("waits").value(s.count());
                w.name("maxNanos").value(s.maxNanos());
                w.name("locks").strings(s.locks().stream().map(Wait.LockKey::pretty).toList());
                w.name("waiters").strings(names(s.waiters()));
                w.name("heldBy").strings(names(s.owners()));
                w.name("stack");
                stackValue(w, s.longest().stack(), ContentionReport.SITE_FRAMES);
                w.end();
            }
            w.end();
        } else {
            w.name("locks").array();
            for (final ContentionReport.LockStats l : r.locks(top)) {
                lockStats(w, l);
            }
            w.end();
        }
        w.name("threads").array();
        final double span = Math.max(1, info.span().duration());
        for (final ContentionReport.ThreadStats t : r.waiters(top)) {
            w.object();
            thread(w, t.thread());
            w.name("totalNanos").value(t.totalNanos());
            w.name("waits").value(t.count());
            w.name("maxNanos").value(t.maxNanos());
            w.name("share").value(t.totalNanos() / span);
            w.end();
        }
        w.end();
        w.name("convoys").array();
        for (final ContentionReport.Convoy c : r.convoys(5, top)) {
            w.array();
            for (final Wait link : c.links()) {
                wait(w, link, info, false);
            }
            w.end();
        }
        w.end();
        w.name("waitingForWork").object();
        w.name("threads").value(r.workWaitThreads());
        w.name("parks").value(r.workWaits().size());
        w.name("totalNanos").value(r.workWaitNanos());
        w.name("byShape").value(r.perchCount());
        w.name("queues").array();
        for (final ContentionReport.LockStats l : r.workWaitLocks(top)) {
            lockStats(w, l);
        }
        w.end();
        w.end();
        w.name("longest").array();
        for (final Wait wait : r.longest(top)) {
            wait(w, wait, info, true);
        }
        w.end();
        return w.finish();
    }

    private static void lockStats(final Writer w, final ContentionReport.LockStats l) {
        w.object();
        w.name("lock").value(l.lock().pretty());
        w.name("class").value(l.lock().className());
        w.name("kind").value(l.lock().kind().name());
        w.name("totalNanos").value(l.totalNanos());
        w.name("waits").value(l.count());
        w.name("maxNanos").value(l.maxNanos());
        w.name("waiters").strings(names(l.waiters()));
        w.name("heldBy").strings(names(l.owners()));
        stack(w, l.longest().stack());
        w.end();
    }

    private static void wait(final Writer w, final Wait wait, final RecordingInfo info, final boolean withStack) {
        w.object();
        w.name("waiter").value(wait.waiter().name());
        instant(w, wait.start(), info);
        w.name("durationNanos").value(wait.duration());
        w.name("lock").value(wait.lock().pretty());
        w.name("heldBy").value(wait.owner() == null ? null : wait.owner().name());
        w.name("handedOnThrough").strings(wait.via().stream().map(ThreadRef::name).toList());
        if (withStack) {
            stack(w, wait.stack());
        }
        w.end();
    }

    // ---------------------------------------------------------------------------- alloc

    public static String alloc(final AllocationReport r, final int top, final boolean sites, final SiteKey key,
                               final String version) {
        final Writer w = envelope("alloc", version, r.info());
        estimate(w, r);
        w.name("threads").array();
        for (final AllocationReport.Row<String> row : r.threads(top)) {
            w.object();
            w.name("thread").value(row.key());
            w.name("bytes").value(row.bytes());
            w.name("countedBytes").value(r.counted(row.key()).orElse(null));
            w.name("bytesPerSecond").value(Math.round(r.rate(row.bytes())));
            w.name("share").value(row.share());
            w.name("samples").value(r.support().thread(row.key()));
            w.name("topClasses").array();
            for (final AllocationReport.Row<String> c : r.classesOf(row.key(), 3)) {
                w.object();
                w.name("class").value(c.key());
                w.name("bytes").value(c.bytes());
                w.end();
            }
            w.end();
            w.end();
        }
        w.end();
        w.name("classes").array();
        for (final AllocationReport.Row<String> row : r.classes(top)) {
            w.object();
            w.name("class").value(row.key());
            w.name("bytes").value(row.bytes());
            w.name("bytesPerSecond").value(Math.round(r.rate(row.bytes())));
            w.name("share").value(row.share());
            w.name("samples").value(r.support().className(row.key()));
            w.end();
        }
        w.end();
        if (sites) {
            w.name("siteKey").value(key.description());
            w.name("packages").array();
            for (final AllocationReport.Row<String> root : r.packageRoots(top)) {
                w.object();
                w.name("package").value(root.key());
                w.name("share").value(root.share());
                w.end();
            }
            w.end();
            w.name("sites").array();
            for (final AllocationReport.SiteRow row : r.sites(key, top)) {
                w.object();
                w.name("site").value(row.label());
                w.name("bytes").value(row.bytes());
                w.name("bytesPerSecond").value(Math.round(r.rate(row.bytes())));
                w.name("share").value(row.share());
                w.name("samples").value(row.samples());
                w.name("stacks").value(row.stacks());
                stack(w, row.stack());
                w.end();
            }
            w.end();
        }
        return w.finish();
    }

    private static void estimate(final Writer w, final AllocationReport r) {
        w.name("warnings").strings(r.warnings());
        w.name("source").value(r.source());
        w.name("samples").value(r.samples());
        w.name("events").value(r.events());
        w.name("estimatedBytes").value(r.totalBytes());
        w.name("bytesPerSecond").value(Math.round(r.rate()));
        w.name("counted").value(r.hasCounters());
        if (r.hasCounters()) {
            w.name("countedBytes").value(r.countedBytes());
            w.name("countedThreads").value(r.countedByThread().size());
            w.name("estimatedOnCountedThreads").value(r.estimatedOnCountedThreads());
            w.name("estimateError").value(r.estimateError());
        }
    }

    public static String allocDiff(final AllocationDiff d, final int top, final boolean sites, final SiteKey key,
                                   final String version) {
        final Writer w = envelope("alloc", version, d.current().info());
        w.name("baseline").object();
        w.name("recording").object();
        recordingFields(w, d.baseline().info());
        w.end();
        estimate(w, d.baseline());
        w.end();
        w.name("current").object();
        estimate(w, d.current());
        w.end();
        w.name("change").object();
        delta(w, d.total());
        w.end();
        w.name("threads").array();
        for (final AllocationDiff.Delta<String> x : d.threads(top)) {
            w.object();
            w.name("thread").value(x.key());
            delta(w, x);
            w.name("samplesBefore").value(d.baseline().support().thread(x.key()));
            w.name("samplesAfter").value(d.current().support().thread(x.key()));
            w.end();
        }
        w.end();
        w.name("classes").array();
        for (final AllocationDiff.Delta<String> x : d.classes(top)) {
            w.object();
            w.name("class").value(x.key());
            delta(w, x);
            w.name("samplesBefore").value(d.baseline().support().className(x.key()));
            w.name("samplesAfter").value(d.current().support().className(x.key()));
            w.end();
        }
        w.end();
        if (sites) {
            w.name("siteKey").value(key.description());
            w.name("sites").array();
            for (final AllocationDiff.Delta<AllocationDiff.Site> x : d.sites(key, top)) {
                w.object();
                w.name("site").value(x.key().label());
                delta(w, x);
                w.name("samplesBefore").value(x.key().beforeSamples());
                w.name("samplesAfter").value(x.key().afterSamples());
                stack(w, x.key().stack());
                w.end();
            }
            w.end();
        }
        return w.finish();
    }

    /** Rates before and after, their difference, and the relative change; {@code null} where nothing was before. */
    private static void delta(final Writer w, final AllocationDiff.Delta<?> x) {
        w.name("bytesPerSecondBefore").value(Math.round(x.beforeRate()));
        w.name("bytesPerSecondAfter").value(Math.round(x.afterRate()));
        w.name("bytesPerSecondChange").value(Math.round(x.delta()));
        w.name("ratio").value(x.ratio());
    }

    // --------------------------------------------------------------------------- shared

    /** The fields every document starts with: what produced it, and the recording it is about. */
    private static Writer envelope(final String command, final String version, final RecordingInfo info) {
        final Writer w = new Writer();
        w.object();
        w.name("tool").value("jfrq");
        w.name("version").value(version);
        w.name("schema").value(SCHEMA);
        w.name("command").value(command);
        w.name("recording").object();
        recordingFields(w, info);
        w.end();
        return w;
    }

    private static void recordingFields(final Writer w, final RecordingInfo info) {
        w.name("file").value(info.file().getFileName().toString());
        w.name("start").value(iso(info.startNanos()));
        w.name("end").value(iso(info.endNanos()));
        w.name("durationNanos").value(info.span().duration());
        w.name("chunks").value(info.chunks());
        w.name("warnings").strings(info.warnings());
    }

    private static void thread(final Writer w, final ThreadRef t) {
        w.name("thread").value(t.name());
        w.name("threadId").value(t.id());
        w.name("virtual").value(t.isVirtual());
    }

    private static void instant(final Writer w, final long nanos, final RecordingInfo info) {
        w.name("start").value(iso(nanos));
        w.name("offsetNanos").value(nanos - info.startNanos());
    }

    private static void stack(final Writer w, final Stack stack) {
        w.name("stack");
        stackValue(w, stack, STACK_FRAMES);
    }

    /**
     * {@code {"frames": [...], "culprit": ..., "truncated": ...}}: the innermost frames as a
     * stack trace prints them, the innermost frame outside the JDK named apart because it may
     * be deeper than the frames kept, and whether frames were left out; {@code null} for none.
     */
    private static void stackValue(final Writer w, final Stack stack, final int frames) {
        if (stack.isEmpty()) {
            w.nullValue();
            return;
        }
        final int shown = Math.min(frames, stack.depth());
        w.object();
        w.name("frames").array();
        for (int i = 0; i < shown; i++) {
            w.value(stack.frameQuick(i).pretty());
        }
        w.end();
        final Frame culprit = stack.culpritOrNull();
        w.name("culprit").value(culprit == null ? null : culprit.stableName());
        w.name("truncated").value(shown < stack.depth() || stack.isTruncated());
        w.end();
    }

    static String iso(final long epochNanos) {
        return Instant.ofEpochSecond(0, epochNanos).toString();
    }

    private static List<String> names(final Collection<ThreadRef> threads) {
        return threads.stream().map(ThreadRef::name).sorted().toList();
    }

    private static <T> List<T> top(final List<T> list, final int n) {
        return list.size() > n ? list.subList(0, n) : list;
    }

    private static Integer size(final Collection<?> c) {
        return c == null ? null : c.size();
    }

    private static Integer intOrNull(final int v) {
        return v == Nulls.INT_NULL ? null : v;
    }

    private static Long positiveOrNull(final long v) {
        return v > 0 ? v : null;
    }

    /**
     * A streaming writer that places the commas: {@link #object()} and {@link #array()} open a
     * container, {@link #end()} closes the innermost, {@link #name} precedes a member's value.
     * Numbers that JSON cannot carry (NaN, infinities) and absent values are {@code null};
     * a {@code long} of {@link Nulls#LONG_NULL} is absent too.
     */
    static final class Writer {
        private final StringBuilder sb = new StringBuilder(4096);
        /** One entry per open container: whether it has a member yet, and its opening bracket. */
        private final List<Boolean> started = new ArrayList<>();
        private final StringBuilder openers = new StringBuilder();
        private boolean afterName;

        Writer object() {
            open('{');
            return this;
        }

        Writer array() {
            open('[');
            return this;
        }

        private void open(final char c) {
            separate();
            sb.append(c);
            started.add(false);
            openers.append(c);
        }

        Writer end() {
            final int last = started.size() - 1;
            started.remove(last);
            sb.append(openers.charAt(last) == '{' ? '}' : ']');
            openers.setLength(last);
            return this;
        }

        Writer name(final String name) {
            separate();
            string(name);
            sb.append(':');
            afterName = true;
            return this;
        }

        private void separate() {
            if (afterName) {
                afterName = false;
                return;
            }
            final int last = started.size() - 1;
            if (last >= 0) {
                if (started.get(last)) {
                    sb.append(',');
                }
                started.set(last, true);
            }
        }

        void value(final String v) {
            separate();
            if (v == null) {
                sb.append("null");
            } else {
                string(v);
            }
        }

        void value(final long v) {
            separate();
            sb.append(v == Nulls.LONG_NULL ? "null" : Long.toString(v));
        }

        void value(final Long v) {
            if (v == null) {
                nullValue();
            } else {
                value(v.longValue());
            }
        }

        void value(final Integer v) {
            if (v == null) {
                nullValue();
            } else {
                value(v.longValue());
            }
        }

        void value(final double v) {
            separate();
            sb.append(Double.isFinite(v) ? Double.toString(v) : "null");
        }

        void value(final boolean v) {
            separate();
            sb.append(v);
        }

        void value(final Boolean v) {
            if (v == null) {
                nullValue();
            } else {
                value(v.booleanValue());
            }
        }

        void nullValue() {
            separate();
            sb.append("null");
        }

        void strings(final List<String> values) {
            array();
            for (final String v : values) {
                value(v);
            }
            end();
        }

        private void string(final String s) {
            sb.append('"');
            for (int i = 0, n = s.length(); i < n; i++) {
                final char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }

        String finish() {
            end();
            if (!started.isEmpty()) {
                throw new IllegalStateException(started.size() + " containers left open");
            }
            return sb.append('\n').toString();
        }
    }
}
