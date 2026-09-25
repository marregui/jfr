// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;

import dev.jfrq.core.coll.IdentityObjObjHashMap;
import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjHashSet;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.stalls.Stall.Evidence;
import dev.jfrq.core.stalls.Stall.Verdict;
import dev.jfrq.core.stalls.StallReport.Sight;
import dev.jfrq.core.stalls.StallReport.ThreadSummary;
import dev.jfrq.core.stalls.Timeline.Block;
import dev.jfrq.core.stalls.Timeline.BlockKind;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.stalls.Timeline.ThreadTimeline;
import dev.jfrq.core.util.Bytes;
import dev.jfrq.core.util.Durations;
import dev.jfrq.core.util.Sorts;

/**
 * Finds stalls in per-thread timelines. Pure logic over {@link Timeline} values, so it is
 * testable without a recording.
 *
 * <p>A stall is an interval of at least the configured gap during which the thread did
 * not return to its idle point. Three detectors find candidates:
 * <ol>
 *   <li><b>Events.</b> A blocking event (monitor, park, sleep, socket, file) at least the
 *       gap long is a stall on its own, with the event as the explanation.</li>
 *   <li><b>Sample runs.</b> Consecutive non-idle samples, each within a few sampler
 *       periods of the previous one, spanning at least the gap: the thread kept executing
 *       without reaching the selector. The explanation is the dominant application frame
 *       across the run, or "saturated" when there is none. Samples further apart than a
 *       few periods do not chain, because nothing proves the thread was busy in between;
 *       nor do two with a gap's worth of JVM pause or blocking between them.</li>
 *   <li><b>Silence.</b> Consecutive samples further apart than the thread's own sampling
 *       cadence allows: the thread was in a state the sampler cannot see (blocked, in the
 *       VM, at a safepoint). Explained by whichever blocking event or JVM pause covers most
 *       of the silence, otherwise reported as unexplained. When the recording bounds the
 *       thread's life, the stretch before its first sample and after its last are silences
 *       too: a call still in progress when the recording stopped has written no event.</li>
 * </ol>
 *
 * <p>A thread's stalls are disjoint. Where candidates overlap, the stronger evidence claims
 * the time — an event, then a silence a JVM pause explains, then a run of samples, then any
 * other silence — and what is left of a weaker candidate is judged again piece by piece.
 *
 * <p>Sampling cadence is measured, not assumed. The JFR sampler visits at most five
 * threads executing Java and one thread in native code per period, round-robin, so a
 * thread's effective cadence depends on how many threads compete for each slot. Threads
 * sitting in a selector are in native code and share the single native slot with every
 * thread blocked in a socket read, which is why native and Java cadences are tracked
 * separately per thread.
 *
 * <p>One instance analyses one recording at a time: the scratch collections it reuses
 * between candidates are not shared between threads.
 */
public final class StallAnalysis {

    /** A silence must exceed this many cadences to count as evidence. */
    static final int CADENCE_FACTOR = 3;
    /** Non-idle samples chain into a run only when closer than this many sampler periods. */
    static final int RUN_FACTOR = 3;
    /** An explanation must cover this share of a candidate interval. */
    static final double COVER = 0.5;
    /** A single culprit must own this share of a busy run to be named. */
    static final double DOMINANT = 0.5;
    /** A run needs at least this many samples before it can be a stall at all. */
    static final int RUN_MIN_SAMPLES = 2;
    /** A "saturated" verdict (no dominant culprit) needs at least this many samples. */
    static final int SATURATED_MIN_SAMPLES = 5;

    private static final int[] THRESHOLDED_BLOCK_EVENTS = {
            EventKinds.JAVA_MONITOR_ENTER, EventKinds.THREAD_PARK, EventKinds.JAVA_MONITOR_WAIT,
            EventKinds.THREAD_SLEEP, EventKinds.SOCKET_READ, EventKinds.SOCKET_WRITE, EventKinds.FILE_READ,
            EventKinds.FILE_WRITE, EventKinds.FILE_FORCE};
    private static final int[] SAMPLER_EVENTS = {EventKinds.EXECUTION_SAMPLE, EventKinds.NATIVE_METHOD_SAMPLE};

    /** How many threads with nothing to judge them by are named before the rest are counted. */
    private static final int SILENT_THREADS_SHOWN = 5;
    /**
     * A timer loop has to have run out its own timeout at least this often: one wait that did
     * is as likely a caller that gave up on a result as a thread that meant to wait.
     */
    static final int TIMER_MIN_WAITS = 2;
    /** Timer-loop threads named in the warning before the rest are counted. */
    private static final int TIMER_THREADS_SHOWN = 5;

    /** A silence's position against the thread's life, for its label. */
    private static final long MID = 0;
    private static final long FROM_START = 1;
    private static final long TO_END = 2;

    private static final Comparator<Pause> PAUSE_BY_INTERVAL = Comparator.comparing(Pause::interval);
    private static final Comparator<ThreadTimeline> BY_THREAD_NAME = Comparator.comparing(t -> t.thread().name());
    private static final Comparator<Stall> BY_START = Comparator.comparingLong(Stall::start);
    private static final ToLongFunction<Stall> STALL_START = Stall::start;
    private static final ToLongFunction<Stall> STALL_DURATION = Stall::duration;
    private static final ToLongFunction<Block> BLOCK_START = Block::start;
    private static final ToLongFunction<Block> BLOCK_DURATION = Block::duration;
    private static final ToLongFunction<Pause> PAUSE_START = Pause::start;
    private static final ToLongFunction<Pause> PAUSE_DURATION = Pause::duration;
    /**
     * Precedence between judged candidates that overlap, strongest first, after the event
     * stalls, which are exact and claim their time before any of them: a silence a JVM pause
     * explains (exact too, cut to the pauses), a run of samples (as good as the sampling),
     * and last a silence explained by blocks or by nothing, the weakest evidence there is.
     */
    private static final int TIER_PAUSE = 0;
    private static final int TIER_RUN = 1;
    private static final int TIER_SILENCE = 2;
    private static final ToLongFunction<Interval> INTERVAL_END = Interval::end;
    private static final ToLongFunction<Sample> SAMPLE_TIME = Sample::time;
    private static final Comparator<Interval> INTERVAL_BY_START = Comparator.comparingLong(Interval::start);
    private static final long PERCH = 1;
    private static final long NOT_PERCH = 0;
    private static final long TIMER = 1;
    private static final long NOT_TIMER = 0;

    private final long gap;
    /** Which parks are a worker with nothing to do rather than a wait someone is paying for. */
    private final IdleMatcher workWaits;
    /** A culprit's qualified name, built once per distinct frame (G-2.3). */
    private final ObjObjHashMap<Frame, String> culpritNames = new ObjObjHashMap<>(1024);
    /** Scratch for {@link #busy}: culprit counts in first-seen order, a stack per culprit, and their pool (G-3.1). */
    private final ObjList<Culprit> culprits = new ObjList<>();
    private final ObjList<Culprit> culpritPool = new ObjList<>();
    private final ObjObjHashMap<String, Culprit> culpritByName = new ObjObjHashMap<>(64);
    /** Event stalls cut down to the recording's span in the current analysis; reset per {@link #analyse}. */
    private int clippedStalls;
    /** Blocks left out as "waiting for work" in the current analysis, and their total. */
    private int workWaitCount;
    /** The loops {@link Perch} recognised in this recording, as their stacks print. */
    private final ObjHashSet<String> perchRenderings = new ObjHashSet<>(16);
    /** That verdict per distinct stack, so a rendering is built once and not once per block (G-2.2). */
    private final ObjLongHashMap<Stack> perchVerdict = new ObjLongHashMap<>(256);
    private long workWaitNanos;
    /** The loops, as their stacks print, where the thread being analysed waits out its own timeouts. */
    private final ObjHashSet<String> timerLoops = new ObjHashSet<>(4);
    /** That verdict per distinct stack, for the thread being analysed (G-2.2). */
    private final ObjLongHashMap<Stack> timerVerdict = new ObjLongHashMap<>(64);
    /** Scratch for {@link #findTimerLoops}: per loop, the time its timed-out waits took and how many there were. */
    private final ObjLongHashMap<String> timerTotals = new ObjLongHashMap<>(16);
    private final ObjLongHashMap<String> timerCounts = new ObjLongHashMap<>(16);
    /** A stack's loop as it prints, built once per distinct stack in the analysis (G-2.2). */
    private final ObjObjHashMap<Stack, String> loopNames = new ObjObjHashMap<>(256);
    /** Blocks left out as timer loops in the current analysis, their total, and the total per thread. */
    private int timerWaitCount;
    private long timerWaitNanos;
    private final ObjLongHashMap<String> timerWaitByThread = new ObjLongHashMap<>(16);

    /** Whether a block is a worker parked on its own empty queue rather than a wait that costs someone. */
    private boolean isWaitingForWork(final Block b) {
        return isWaitingForWork(verdictOf(b.kind()), b.stack());
    }

    /**
     * Whether a block, or the explanation of a candidate, is the thread at rest: a worker
     * waiting for work, or a timer loop of the thread being analysed waiting for its next
     * deadline. Either way nobody is paying for the wait.
     */
    private boolean isAtRest(final Verdict verdict, final Stack stack) {
        return isWaitingForWork(verdict, stack) || onATimer(verdict, stack);
    }

    private static boolean canRest(final Verdict verdict) {
        return verdict == Verdict.PARKED || verdict == Verdict.OBJECT_WAIT || verdict == Verdict.SLEEP;
    }

    /**
     * Whether this stack is one of the timer loops {@link #findTimerLoops} found on the thread
     * being analysed. Matched on what the frames say, as {@link #onAPerch} is, so that every
     * wait from the loop is at rest, including one something woke before its deadline: a
     * timer thread is woken early whenever an earlier task is scheduled.
     */
    private boolean onATimer(final Verdict verdict, final Stack stack) {
        if (timerLoops.isEmpty() || !canRest(verdict)) {
            return false;
        }
        final int index = timerVerdict.keyIndex(stack);
        if (index < 0) {
            return timerVerdict.valueAtQuick(index) == TIMER;
        }
        final String loop = loopName(stack);
        final boolean timer = loop != null && timerLoops.contains(loop);
        timerVerdict.putAt(index, stack, timer ? TIMER : NOT_TIMER);
        return timer;
    }

    /** {@link Perch#loop}, built once per distinct stack. */
    private String loopName(final Stack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        final int index = loopNames.keyIndex(stack);
        return index < 0 ? loopNames.valueAtQuick(index) : loopNames.putAt(index, stack, Perch.loop(stack));
    }

    /**
     * The loops where this thread waits for a deadline of its own choosing: a park, a wait or a
     * sleep, from one place, that ran out the time the thread gave it at least
     * {@link #TIMER_MIN_WAITS} times and, in those waits alone, for more than half of the
     * thread's life in the window. A timer thread, a cleaner, a periodic poll: the recording
     * says the wait was voluntary ({@code timedOut} on {@code jdk.JavaMonitorWait}, a park's
     * duration against its {@code timeout} or {@code until}, a sleep against its {@code time}),
     * so no list of names is needed, and it is exact. An event loop that sleeps once, or a
     * caller whose {@code get} with a timeout gave up once, is neither repeated nor most of a
     * life, and stays a stall. Off with {@code --idle none}, like {@link Perch}.
     */
    private void findTimerLoops(final ThreadTimeline tl, final Interval span) {
        timerLoops.clear();
        timerVerdict.clear();
        if (workWaits.matchesNothing()) {
            return;
        }
        timerTotals.clear();
        timerCounts.clear();
        final Interval life = tl.isLifeKnown() ? new Interval(tl.lifeStart(), tl.lifeEnd()) : span;
        final List<Block> blocks = tl.blocks();
        for (int i = 0, n = blocks.size(); i < n; i++) {
            final Block b = blocks.get(i);
            if (!b.timedOut() || !canRest(verdictOf(b.kind()))) {
                continue;
            }
            final String loop = loopName(b.stack());
            if (loop != null) {
                timerTotals.increment(loop, b.interval().clampTo(life).duration());
                timerCounts.increment(loop, 1);
            }
        }
        for (int s = 0, n = timerTotals.slots(); s < n; s++) {
            if (timerTotals.hasKeyAtSlot(s)) {
                final String loop = timerTotals.keyAtSlot(s);
                if (timerCounts.get(loop) >= TIMER_MIN_WAITS && timerTotals.valueAtSlot(s) * 2 > life.duration()) {
                    timerLoops.add(loop);
                }
            }
        }
    }

    /**
     * The same question from a candidate's explanation, which carries the representative
     * stack. A park, a wait or a sleep can be a thread with nothing to do; a monitor or an
     * I/O call is always waiting for something someone else has.
     */
    private boolean isWaitingForWork(final Verdict verdict, final Stack stack) {
        return canRest(verdict) && (workWaits.isIdle(stack) || onAPerch(stack));
    }

    /**
     * Whether this stack is one of the loops {@link Perch} recognised. Matched on what the
     * frames say rather than on the stack object: a blocking event and a sample taken in the
     * same park are two stacks with one meaning, and the candidate that explains a run of
     * samples carries the sampler's. The verdict is reached once per distinct stack (G-2.2).
     */
    private boolean onAPerch(final Stack stack) {
        if (perchRenderings.isEmpty()) {
            return false;
        }
        final int index = perchVerdict.keyIndex(stack);
        if (index < 0) {
            return perchVerdict.valueAtQuick(index) == PERCH;
        }
        final String loop = Perch.loop(stack);
        final boolean perch = loop != null && perchRenderings.contains(loop);
        perchVerdict.putAt(index, stack, perch ? PERCH : NOT_PERCH);
        return perch;
    }

    /**
     * The loops {@link Perch} recognised: a worker loop this JVM has and no idle list knows
     * about. Found once for the whole analysis, because the evidence is what a lock did across
     * every thread in the recording, not what one block did.
     */
    private void findPerches(final ParkShapes parks, final Interval span) {
        perchRenderings.clear();
        perchVerdict.clear();
        if (workWaits.matchesNothing()) {
            return;
        }
        final ObjList<Stack> stacks = parks.perchStacks(span);
        for (int i = 0, n = stacks.size(); i < n; i++) {
            final String loop = Perch.loop(stacks.getQuick(i));
            if (loop != null) {
                perchRenderings.add(loop);
            }
        }
    }

    public StallAnalysis(final long gapNanos) {
        this(gapNanos, IdleMatcher.forWorkWaits());
    }

    public StallAnalysis(final long gapNanos, final IdleMatcher workWaits) {
        if (gapNanos <= 0) {
            throw new IllegalArgumentException("gap must be positive");
        }
        this.gap = gapNanos;
        this.workWaits = workWaits;
    }

    public long gap() {
        return gap;
    }

    /**
     * Stalls in {@code timelines}, with {@link Perch} judged on the parks the timelines hold.
     * That is every thread's evidence only when the timelines are every thread; the collector,
     * which sees the whole recording, weighs the parks of the threads it does not watch too.
     */
    public StallReport analyse(final RecordingInfo info, final List<ThreadTimeline> timelines, final List<Pause> pauses) {
        final ParkShapes parks = new ParkShapes();
        for (int t = 0, n = timelines.size(); t < n; t++) {
            final ThreadTimeline tl = timelines.get(t);
            final List<Block> blocks = tl.blocks();
            for (int i = 0, m = blocks.size(); i < m; i++) {
                final Block b = blocks.get(i);
                if (b.kind() == BlockKind.PARK) {
                    parks.add(tl.thread(), b.detail(), b.start(), b.interval().end(), b.stack());
                }
            }
        }
        final SamplerShares shares = new SamplerShares();
        for (int t = 0, n = timelines.size(); t < n; t++) {
            final ThreadTimeline tl = timelines.get(t);
            int inNative = 0;
            for (int i = 0, m = tl.samples().size(); i < m; i++) {
                inNative += tl.samples().get(i).isInNative() ? 1 : 0;
            }
            shares.add(tl.samples().size() - inNative, inNative, life(tl, info.span()));
        }
        return analyse(info, timelines, pauses, parks, List.of(), shares);
    }

    /** The thread's life inside the window, or the window when the recording cannot say. */
    static long life(final ThreadTimeline tl, final Interval span) {
        return tl.isLifeKnown() ? tl.lifeEnd() - tl.lifeStart() : span.duration();
    }

    /**
     * The highest rate at which any thread was sampled in Java and in native code, over every
     * thread in the recording, watched or not. The sampler goes round the threads in each state
     * in turn, so when a slot is contended every thread that sat in that state all along gets
     * the same share, the highest there is; a thread's own rate against it is the share of its
     * life it spent where that slot could see it. Threads with too few samples for a rate are
     * left out: two samples a millisecond apart are not a rate.
     */
    public static final class SamplerShares {
        static final int MIN_SAMPLES = 10;
        private double java;
        private double inNative;

        public void add(final int javaSamples, final int nativeSamples, final long lifeNanos) {
            if (lifeNanos <= 0) {
                return;
            }
            if (javaSamples >= MIN_SAMPLES) {
                java = Math.max(java, (double) javaSamples / lifeNanos);
            }
            if (nativeSamples >= MIN_SAMPLES) {
                inNative = Math.max(inNative, (double) nativeSamples / lifeNanos);
            }
        }

        /**
         * How long the slot takes to come round to a thread that sits in its state all along:
         * the gap between samples of the most-sampled thread of that kind; 0 when unknown.
         */
        long roundTrip(final boolean inNative) {
            final double rate = inNative ? this.inNative : java;
            return rate > 0 ? (long) (1 / rate) : 0;
        }

        /** The estimated share of a life of {@code lifeNanos} the sampler could see the thread in. */
        double observed(final int javaSamples, final int nativeSamples, final long lifeNanos) {
            if (lifeNanos <= 0) {
                return 0;
            }
            final double j = java > 0 ? javaSamples / (java * lifeNanos) : 0;
            final double n = inNative > 0 ? nativeSamples / (inNative * lifeNanos) : 0;
            return j + n;
        }
    }

    /**
     * @param parks  every thread's parks, watched or not, for {@link Perch}
     * @param silent threads the filter matched that have neither a sample nor a blocking event
     */
    StallReport analyse(final RecordingInfo info, final List<ThreadTimeline> timelines, final List<Pause> pauses,
                        final ParkShapes parks, final List<String> silent, final SamplerShares shares) {
        final List<String> warnings = new ArrayList<>();
        warnRecording(info, warnings);
        warnSilent(silent, warnings);
        final long period = samplerPeriod(info);
        clippedStalls = 0;
        workWaitCount = 0;
        workWaitNanos = 0;
        timerWaitCount = 0;
        timerWaitNanos = 0;
        timerWaitByThread.clear();
        loopNames.clear();
        findPerches(parks, info.span());

        final ObjList<Pause> sortedPauses = new ObjList<>(pauses.size());
        for (int i = 0, n = pauses.size(); i < n; i++) {
            sortedPauses.add(pauses.get(i));
        }
        sortedPauses.sort(PAUSE_BY_INTERVAL);
        final ObjList<Pause> longPauses = new ObjList<>();
        for (int i = 0, n = sortedPauses.size(); i < n; i++) {
            final Pause p = sortedPauses.getQuick(i);
            if (p.duration() >= gap) {
                longPauses.add(p);
            }
        }

        final ObjList<ThreadTimeline> ordered = new ObjList<>(timelines.size());
        for (int i = 0, n = timelines.size(); i < n; i++) {
            ordered.add(timelines.get(i));
        }
        ordered.sort(BY_THREAD_NAME);
        final ObjList<Stall> stalls = new ObjList<>();
        final ObjList<ThreadSummary> summaries = new ObjList<>(ordered.size());
        final Windows windows = new Windows(sortedPauses);
        for (int i = 0, n = ordered.size(); i < n; i++) {
            final ThreadTimeline tl = ordered.getQuick(i);
            final Cadence cadence = Cadence.of(tl.samples(), period);
            final int before = stalls.size();
            analyseThread(tl, cadence, windows, stalls, info.span());
            long stalled = 0;
            long worst = 0;
            for (int s = before, m = stalls.size(); s < m; s++) {
                final long duration = stalls.getQuick(s).duration();
                stalled += duration;
                worst = Math.max(worst, duration);
            }
            final long unseenBelow = cadence.routineAbsence() * CADENCE_FACTOR;
            final Sight sight = sight(tl, cadence, unseenBelow, info.span(), shares);
            final long roundTrip = sight == Sight.NATIVE_SAMPLER ? shares.roundTrip(true)
                    : sight == Sight.JAVA_SAMPLER ? shares.roundTrip(false) : 0;
            summaries.add(new ThreadSummary(tl.thread(), tl.samples().size(), cadence.java, cadence.inNative,
                    stalls.size() - before, stalled, worst, unseenBelow, sight, roundTrip));
        }
        if (workWaitCount > 0) {
            warnings.add(workWaitCount + (workWaitCount == 1 ? " wait totalling " : " waits totalling ")
                    + Durations.format(workWaitNanos) + " were workers waiting for their own queue, or at a frame "
                    + "--idle names, and are not stalls; --idle none turns this off");
        }
        warnTimers(warnings);
        if (clippedStalls > 0) {
            warnings.add(clippedStalls == 1
                    ? "1 stall extends beyond the recording's span and is counted only for the part inside it"
                    : clippedStalls + " stalls extend beyond the recording's span and are counted only for the "
                            + "part inside it");
        }
        return new StallReport(info, gap, summaries.toList(), markSimultaneous(stalls.toList()), longPauses.toList(),
                warnings);
    }

    /**
     * What limits what the samples can show of a thread, when anything does: when the shortest
     * silence that counts as evidence is longer than the gap, or the thread was seen fewer than
     * twice. The sampler's slot limits it when the thread was where that slot sees it for at
     * least half its life, by its share of the sampler's attention ({@link SamplerShares}), and
     * its routine absence is mostly the slot's own round trip, at most twice it. Otherwise the
     * absences are the thread's own, parked or blocked where no slot sees it: an idle worker,
     * a timer, an event loop blocked on a lock for stretches; a shorter period would not
     * change those much.
     */
    private Sight sight(final ThreadTimeline tl, final Cadence cadence, final long unseenBelow, final Interval span,
                        final SamplerShares shares) {
        final long life = life(tl, span);
        final List<Sample> samples = tl.samples();
        if (samples.size() < 2) {
            return life >= gap ? Sight.OWN_ABSENCE : Sight.CLEAR;
        }
        if (unseenBelow <= gap) {
            return Sight.CLEAR;
        }
        int inNative = 0;
        for (int i = 0, n = samples.size(); i < n; i++) {
            inNative += samples.get(i).isInNative() ? 1 : 0;
        }
        final boolean inNativeSlot = cadence.nativeP90 >= cadence.javaP90;
        final long roundTrip = shares.roundTrip(inNativeSlot);
        if (shares.observed(samples.size() - inNative, inNative, life) < COVER
                || roundTrip == 0 || cadence.routineAbsence() > 2 * roundTrip) {
            return Sight.OWN_ABSENCE;
        }
        return inNativeSlot ? Sight.NATIVE_SAMPLER : Sight.JAVA_SAMPLER;
    }

    /**
     * An unexplained silence that several watched threads share at the same moment is
     * far more likely to be the sampler not running, or a pause the recording did not
     * capture, than each thread independently blocking; say so in the detail.
     */
    static List<Stall> markSimultaneous(final List<Stall> stalls) {
        final ObjList<Stall> unexplained = new ObjList<>();
        for (int i = 0, n = stalls.size(); i < n; i++) {
            final Stall s = stalls.get(i);
            if (s.verdict() == Verdict.UNEXPLAINED) {
                unexplained.add(s);
            }
        }
        if (unexplained.size() < 2) {
            return stalls;
        }
        unexplained.sort(BY_START);
        final long longest = Sorts.maxDuration(unexplained, STALL_DURATION);
        final IdentityObjObjHashMap<Stall, Stall> marked = new IdentityObjObjHashMap<>(unexplained.size());
        for (int u = 0, n = unexplained.size(); u < n; u++) {
            final Stall s = unexplained.getQuick(u);
            int count = 0;
            final int from = Sorts.lowerBound(unexplained, STALL_START, s.start() - longest);
            for (int i = from; i < n; i++) {
                final Stall o = unexplained.getQuick(i);
                if (o.start() >= s.interval().end()) {
                    break;
                }
                if (o != s && !o.thread().equals(s.thread())
                        && o.interval().overlap(s.interval()) >= COVER * s.duration()) {
                    count++;
                }
            }
            if (count > 0) {
                marked.put(s, new Stall(s.thread(), s.interval(), s.verdict(),
                        s.detail() + "; simultaneous on " + (count + 1)
                                + " watched threads, so more likely the sampler than this thread",
                        s.stack(), s.evidence(), s.samples()));
            }
        }
        if (marked.isEmpty()) {
            return stalls;
        }
        final ObjList<Stall> out = new ObjList<>(stalls.size());
        for (int i = 0, n = stalls.size(); i < n; i++) {
            final Stall s = stalls.get(i);
            final Stall replacement = marked.get(s);
            out.add(replacement == null ? s : replacement);
        }
        return out.toList();
    }

    /**
     * One line for every wait the timer-loop rule left out, naming the threads that waited
     * longest: a thread that was stalled in a way the rule missed must still be findable.
     */
    private void warnTimers(final List<String> warnings) {
        if (timerWaitCount == 0) {
            return;
        }
        final List<String> threads = new ArrayList<>(timerWaitByThread.size());
        for (int s = 0, n = timerWaitByThread.slots(); s < n; s++) {
            if (timerWaitByThread.hasKeyAtSlot(s)) {
                threads.add(timerWaitByThread.keyAtSlot(s));
            }
        }
        threads.sort(Comparator.comparingLong((String t) -> -timerWaitByThread.get(t)).thenComparing(t -> t));
        final StringBuilder names = new StringBuilder();
        final int shown = Math.min(threads.size(), TIMER_THREADS_SHOWN);
        for (int i = 0; i < shown; i++) {
            names.append(i > 0 ? ", " : "").append(threads.get(i));
        }
        if (threads.size() > shown) {
            names.append(" and ").append(threads.size() - shown).append(" more");
        }
        warnings.add(timerWaitCount + (timerWaitCount == 1 ? " wait totalling " : " waits totalling ")
                + Durations.format(timerWaitNanos) + " were timer loops waiting out their own timeout (" + names
                + "): scheduled idle, not stalls; --idle none turns this off");
    }

    /** The finer of the two sampler periods in the recording's settings, or 0 if unknown. */
    static long samplerPeriod(final RecordingInfo info) {
        long period = 0;
        for (final int kind : SAMPLER_EVENTS) {
            final long p = info.periodNanos(EventKinds.nameOf(kind));
            if (p != Nulls.LONG_NULL && p > 0 && (period == 0 || p < period)) {
                period = p;
            }
        }
        return period;
    }

    /**
     * The threads a filter matched that the analysis has nothing on: the JVM's own threads,
     * which the sampler never visits, and any thread blocked through the whole window with no
     * call ending inside it. Named, so that a thread {@code jfrq info} lists is not silently
     * missing from the report.
     */
    private static void warnSilent(final List<String> silent, final List<String> warnings) {
        if (silent.isEmpty()) {
            return;
        }
        final StringBuilder names = new StringBuilder();
        final int shown = Math.min(silent.size(), SILENT_THREADS_SHOWN);
        for (int i = 0; i < shown; i++) {
            names.append(i > 0 ? ", " : "").append(silent.get(i));
        }
        if (silent.size() > shown) {
            names.append(" and ").append(silent.size() - shown).append(" more");
        }
        warnings.add(silent.size() + (silent.size() == 1 ? " matching thread has" : " matching threads have")
                + " no samples and no blocking events, so nothing to judge by (" + names + "): the sampler "
                + "visits only threads running Java or native code, and a thread blocked through the whole "
                + "recording leaves no event");
    }

    private void warnRecording(final RecordingInfo info, final List<String> warnings) {
        if (!info.has(EventKinds.nameOf(EventKinds.EXECUTION_SAMPLE))
                && !info.has(EventKinds.nameOf(EventKinds.NATIVE_METHOD_SAMPLE))) {
            warnings.add("no sampler events in the recording: only event-based stalls can be found");
        }
        final StringBuilder throttled = new StringBuilder();
        for (final int kind : THRESHOLDED_BLOCK_EVENTS) {
            final String type = EventKinds.nameOf(kind);
            if (!info.isEnabled(type)) {
                continue;
            }
            final long threshold = info.thresholdNanos(type);
            if (threshold != Nulls.LONG_NULL && threshold > gap) {
                warnings.add(type + " threshold " + Durations.format(threshold) + " exceeds gap "
                        + Durations.format(gap) + ": shorter blocks of this kind are not in the file");
            }
            final String throttle = info.throttle(type).orElse(null);
            if (throttle != null) {
                throttled.append(throttled.isEmpty() ? "" : ", ").append(type).append(' ').append(throttle);
            }
        }
        if (!throttled.isEmpty()) {
            warnings.add("throttled events (" + throttled + "): not every blocking call is in the file, "
                    + "so a silence made of many short ones may stay unexplained");
        }
    }

    /**
     * Sampling intervals for a thread, split by sample kind. The median says how often
     * the thread is normally seen; the 90th percentile says how long a routine absence
     * can last (the native slot is round-robin, so a thread's native samples come in
     * bursts with long regular gaps between). Silence is judged against the percentile,
     * run chaining against the median.
     *
     * <p>A median is only printed for a kind the thread has consecutive pairs of: a thread
     * seen only in native code has no Java cadence, and the spacing of its native samples
     * standing in for one would feed run chaining a number that is not about running Java.
     * The routine absence still falls back to every consecutive pair, since it is about any
     * observation.
     *
     * @param java       median spacing between consecutive Java samples; 0 without such a pair
     * @param inNative   median spacing between consecutive native samples; 0 without such a pair
     * @param javaP90    90th percentile of the Java spacing, or of all spacings without a Java pair
     * @param nativeP90  90th percentile of the native spacing, or of all spacings without a native pair
     * @param period     the configured sampler period; the Java median, then the median of all
     *                   spacings, if unknown
     */
    record Cadence(long java, long inNative, long javaP90, long nativeP90, long period) {
        static Cadence of(final List<Sample> samples, final long configuredPeriod) {
            final int n = samples.size();
            final LongList javaDiffs = new LongList(n);
            final LongList nativeDiffs = new LongList(n);
            final LongList allDiffs = new LongList(n);
            for (int i = 1; i < n; i++) {
                final Sample a = samples.get(i - 1);
                final Sample b = samples.get(i);
                final long d = b.time() - a.time();
                allDiffs.add(d);
                if (!a.isInNative() && !b.isInNative()) {
                    javaDiffs.add(d);
                } else if (a.isInNative() && b.isInNative()) {
                    nativeDiffs.add(d);
                }
            }
            // Sorting in place is fine: only the order statistics are read from here on.
            javaDiffs.sort();
            nativeDiffs.sort();
            allDiffs.sort();
            final long java = percentile(javaDiffs, 0.5);
            final long inNative = percentile(nativeDiffs, 0.5);
            final long all = percentile(allDiffs, 0.5);
            final long period = configuredPeriod > 0 ? configuredPeriod : java > 0 ? java : all;
            return new Cadence(java, inNative, percentile(javaDiffs.isEmpty() ? allDiffs : javaDiffs, 0.9),
                    percentile(nativeDiffs.isEmpty() ? allDiffs : nativeDiffs, 0.9), period);
        }

        /**
         * The longest routine absence between two observations. Between any two samples the
         * thread may have passed through the other state unseen (a burst of Java work, then
         * back to the selector before the native slot came round), so the worse of the two
         * percentiles applies whatever the neighbouring samples show.
         */
        long routineAbsence() {
            return Math.max(javaP90, nativeP90);
        }

        /** The {@code p}-th order statistic of a <em>sorted</em> list; 0 when empty. */
        static long percentile(final LongList sorted, final double p) {
            if (sorted.isEmpty()) {
                return 0;
            }
            final int index = (int) Math.min(sorted.size() - 1, Math.floor(p * sorted.size()));
            return sorted.getQuick(index);
        }
    }

    private void analyseThread(final ThreadTimeline tl, final Cadence cadence, final Windows windows,
                               final ObjList<Stall> stalls, final Interval span) {
        // 1. Event-based stalls: precise, independent of sampling.
        findTimerLoops(tl, span);
        final List<Block> blocks = tl.blocks();
        final ObjList<Stall> eventStalls = new ObjList<>();
        long claimedTo = Long.MIN_VALUE;
        for (int i = 0, n = blocks.size(); i < n; i++) {
            final Block b = blocks.get(i);
            // A block that began before the recording, or was still running at its end, is in
            // the file whole; only the part inside the span happened in the window the report
            // is about, and the gap applies to that part.
            final Interval inside = b.interval().clampTo(span);
            if (inside.duration() >= gap) {
                // A worker parked on its own empty queue is not stalled, it is unemployed. The
                // block stays in the timeline below, because it is still what explains the
                // silence in the samples; it just does not become a stall of its own.
                if (isWaitingForWork(b)) {
                    workWaitNanos += inside.duration();
                    workWaitCount++;
                    continue;
                }
                // Nor is a timer loop waiting for its next deadline: it is where it means to be.
                if (onATimer(verdictOf(b.kind()), b.stack())) {
                    timerWaitNanos += inside.duration();
                    timerWaitCount++;
                    timerWaitByThread.increment(tl.thread().name(), inside.duration());
                    continue;
                }
                // Blocks come in start order. One that begins inside an earlier event stall is
                // part of that stall's time: nested, it adds nothing; overlapping, only what
                // it adds past the end is its own, and that must still be a gap long.
                final Interval own = inside.start() < claimedTo
                        ? new Interval(Math.min(claimedTo, inside.end()), inside.end()) : inside;
                if (own.duration() < gap) {
                    continue;
                }
                if (inside != b.interval()) {
                    clippedStalls++;
                }
                eventStalls.add(new Stall(tl.thread(), own, verdictOf(b.kind()), describe(b), b.stack(),
                        Evidence.EVENT, 0));
                claimedTo = own.end();
            }
        }
        stalls.addAll(eventStalls);
        windows.of(blocks, eventStalls);

        // 2. Sample-based candidates: the silences between samples and the runs of non-idle ones.
        final ObjList<Candidate> candidates = new ObjList<>();
        edgeSilences(tl, candidates);
        final List<Sample> samples = tl.samples();
        final long runLimit = runLimit(cadence);
        final long unexplainedThreshold = silentThreshold(cadence);
        int i = 0;
        final int n = samples.size();
        while (i < n) {
            final Sample first = samples.get(i);
            if (i > 0) {
                // Every gap of at least the stall length is a candidate; whether an unexplained
                // one is evidence of anything is decided against the cadence below.
                final Sample prev = samples.get(i - 1);
                if (first.time() - prev.time() >= gap) {
                    candidates.add(Candidate.silence(new Interval(prev.time(), first.time()), MID));
                }
            }
            if (first.isIdle()) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n) {
                final Sample cur = samples.get(j);
                final Sample next = samples.get(j + 1);
                if (next.isIdle() || next.time() - cur.time() > runLimit
                        || stops(windows, cur.time(), next.time(), unexplainedThreshold)) {
                    break;
                }
                j++;
            }
            final Sample last = samples.get(j);
            // The run reaches one sampler period past its last sample, or to the next
            // observation, whichever comes first; never past the end of the recording, since
            // that period is an estimate and the file says nothing beyond its span.
            long end = last.time() + cadence.period;
            if (j + 1 < n) {
                end = Math.min(end, samples.get(j + 1).time());
            }
            end = Math.max(Math.min(end, span.end()), last.time());
            final Interval run = new Interval(first.time(), end);
            if (run.duration() >= gap && j + 1 - i >= RUN_MIN_SAMPLES) {
                candidates.add(Candidate.run(run, samples.subList(i, j + 1)));
            }
            // A pair inside the run was chained, so it is not a silence: two samples are either
            // evidence the thread kept running or a candidate absence, never both.
            i = j + 1;
        }

        // 3. Judged, then kept disjoint: what the stronger evidence claims first is not the
        // weaker evidence's to count again.
        final ObjList<Stall> judged = new ObjList<>(candidates.size());
        for (int c = 0, m = candidates.size(); c < m; c++) {
            final Candidate candidate = candidates.getQuick(c);
            // Already in the report when the thread's event stalls cover half of it together.
            judged.add(windows.coveredByEvent(candidate.interval) ? null
                    : judge(tl, cadence, windows, candidate, unexplainedThreshold));
        }
        ObjList<Interval> claimed = new ObjList<>(eventStalls.size());
        for (int e = 0, m = eventStalls.size(); e < m; e++) {
            claimed.add(eventStalls.getQuick(e).interval());
        }
        ObjList<Stall> runs = null;
        for (int tier = TIER_PAUSE; tier <= TIER_SILENCE; tier++) {
            final ObjList<Stall> kept = new ObjList<>();
            for (int c = 0, m = candidates.size(); c < m; c++) {
                final Stall stall = judged.getQuick(c);
                if (stall != null && tierOf(stall) == tier) {
                    keepUnclaimed(tl, cadence, windows, candidates.getQuick(c), stall, claimed,
                            unexplainedThreshold, kept);
                }
            }
            if (tier == TIER_RUN) {
                // A run claims up to its last sample only: the period after it is an estimate,
                // and gives way to a silence that starts there.
                runs = kept;
                claimed = claim(claimed, sampledParts(tl, kept));
            } else {
                if (runs != null) {
                    giveWayTails(runs, kept);
                }
                stalls.addAll(kept);
                claimed = claim(claimed, intervals(kept));
            }
        }
        if (runs != null) {
            stalls.addAll(runs);
        }
    }

    /** Each run up to its last sample, the part the samples are evidence for. */
    private static ObjList<Interval> sampledParts(final ThreadTimeline tl, final ObjList<Stall> runs) {
        final List<Sample> samples = tl.samples();
        final ObjList<Interval> parts = new ObjList<>(runs.size());
        for (int i = 0, n = runs.size(); i < n; i++) {
            final Interval run = runs.getQuick(i).interval();
            final int after = Sorts.lowerBound(samples, SAMPLE_TIME, run.end());
            final long last = after > 0 ? samples.get(after - 1).time() : run.end();
            parts.add(new Interval(run.start(), Math.max(run.start(), Math.min(last, run.end()))));
        }
        return parts;
    }

    /** Cuts each run's estimated tail where a later-tier stall starts inside it, so stalls stay disjoint. */
    private static void giveWayTails(final ObjList<Stall> runs, final ObjList<Stall> kept) {
        for (int i = 0, n = runs.size(); i < n; i++) {
            final Stall run = runs.getQuick(i);
            long end = run.interval().end();
            for (int j = 0, m = kept.size(); j < m; j++) {
                final long start = kept.getQuick(j).start();
                if (start > run.start() && start < end) {
                    end = start;
                }
            }
            if (end != run.interval().end()) {
                runs.setQuick(i, new Stall(run.thread(), new Interval(run.start(), end), run.verdict(), run.detail(),
                        run.stack(), run.evidence(), run.samples()));
            }
        }
    }

    private static ObjList<Interval> intervals(final ObjList<Stall> stalls) {
        final ObjList<Interval> out = new ObjList<>(stalls.size());
        for (int i = 0, n = stalls.size(); i < n; i++) {
            out.add(stalls.getQuick(i).interval());
        }
        return out;
    }

    private static int tierOf(final Stall stall) {
        if (stall.evidence() == Evidence.SAMPLES) {
            return TIER_RUN;
        }
        return stall.verdict().isThreadLocal() ? TIER_SILENCE : TIER_PAUSE;
    }

    /**
     * Keeps a judged candidate if nothing stronger claims any of its time; otherwise what is
     * left of it, piece by piece, judged again as the same kind of candidate. A piece is
     * judged afresh rather than trimmed, because the explanation of the whole may rest on
     * the very events that now stand as stalls of their own: "29 × parked" over a run that
     * holds one long park listed separately counted that park twice.
     */
    private void keepUnclaimed(final ThreadTimeline tl, final Cadence cadence, final Windows windows,
                               final Candidate candidate, final Stall stall, final ObjList<Interval> claimed,
                               final long unexplainedThreshold, final ObjList<Stall> kept) {
        final int free = Sorts.lowerBound(claimed, INTERVAL_END, stall.start() + 1);
        if (free == claimed.size() || claimed.getQuick(free).start() >= stall.interval().end()) {
            kept.add(stall);
            return;
        }
        int k = Sorts.lowerBound(claimed, INTERVAL_END, candidate.interval.start() + 1);
        long from = candidate.interval.start();
        final long to = candidate.interval.end();
        while (from < to) {
            final long pieceEnd = k < claimed.size() ? Math.min(to, claimed.getQuick(k).start()) : to;
            if (pieceEnd - from >= gap) {
                final Stall piece = judge(tl, cadence, windows, candidate.piece(from, pieceEnd), unexplainedThreshold);
                if (piece != null) {
                    kept.add(piece);
                }
            }
            if (k == claimed.size()) {
                break;
            }
            from = Math.max(from, claimed.getQuick(k).end());
            k++;
        }
    }

    /** The claimed intervals with the newly kept ones merged in; both are disjoint, so the result is. */
    private static ObjList<Interval> claim(final ObjList<Interval> claimed, final ObjList<Interval> kept) {
        if (kept.isEmpty()) {
            return claimed;
        }
        kept.sort(INTERVAL_BY_START);
        final ObjList<Interval> merged = new ObjList<>(claimed.size() + kept.size());
        int a = 0;
        int b = 0;
        while (a < claimed.size() || b < kept.size()) {
            if (b == kept.size() || a < claimed.size() && claimed.getQuick(a).start() <= kept.getQuick(b).start()) {
                merged.add(claimed.getQuick(a++));
            } else {
                merged.add(kept.getQuick(b++));
            }
        }
        return merged;
    }

    /**
     * Whether two chained samples have something between them that stops the run: a stretch
     * at least a gap long that the thread's event stalls, a JVM pause or its blocking events
     * explain as a silence would be. The chaining limit can reach past the gap, since it never
     * falls below the sampler's resolution; without this a collection between two busy
     * samples was folded into the run and reported as the thread's own work.
     */
    private boolean stops(final Windows windows, final long from, final long to, final long unexplainedThreshold) {
        if (to - from < gap) {
            return false;
        }
        final Interval between = new Interval(from, to);
        return windows.coveredByEvent(between) || explainSilence(windows, between, unexplainedThreshold) != null;
    }

    private Stall judge(final ThreadTimeline tl, final Cadence cadence, final Windows windows, final Candidate c,
                        final long unexplainedThreshold) {
        return c.samples == null ? judgeSilence(tl, cadence, windows, c.interval, c.edge, unexplainedThreshold)
                : judgeRun(tl, windows, c.interval, c.samples);
    }

    /**
     * A silence explained by the blocks or pauses that cover it, as the coverage rule has it,
     * or unexplained when it is longer than the thread's routine absence; otherwise nothing.
     */
    private Stall judgeSilence(final ThreadTimeline tl, final Cadence cadence, final Windows windows,
                               final Interval silence, final long edge, final long unexplainedThreshold) {
        final Explanation ex = explainSilence(windows, silence, unexplainedThreshold);
        if (ex != null) {
            // The same rule as for events, at the other door: a silence whose explanation is a
            // worker's own empty queue is not a stall either, and must not fall through to
            // UNEXPLAINED, which would be a worse answer than the one just rejected.
            if (isAtRest(ex.verdict, ex.stack)) {
                return null;
            }
            // Pauses explain only the stretch from the first of them to the last: the thread may
            // have been running on either side, and a stall longer than what stopped it would
            // overstate it. When that stretch is shorter than a gap, the pauses still cover the
            // silence by the rule blocks are held to, so the silence is theirs, whole, and the
            // detail says how much of it they stopped.
            final boolean cut = ex.extent != null && ex.extent.duration() >= gap;
            final Interval explained = cut ? ex.extent : silence;
            final String detail = ex.extent == null || cut ? ex.detail
                    : ex.detail + ", " + Durations.format(ex.stopped) + " of a " + Durations.format(silence.duration())
                            + " silence";
            return new Stall(tl.thread(), explained, ex.verdict, detail + edgeNote(tl, explained, edge), ex.stack,
                    Evidence.SILENCE, 0);
        }
        // Longer than the thread's routine absence: the sampler would have seen it otherwise.
        // A thread seen fewer than twice has no routine absence to be longer than, and its
        // silence says nothing unless something explains it. Nor does the stretch after the
        // last sample of a thread that waits for work where the sampler cannot see it: its
        // last park may simply not have ended yet.
        if (cadence.routineAbsence() > 0 && silence.duration() >= unexplainedThreshold
                && ((edge & TO_END) == 0 || !restsUnseen(tl.blocks()))) {
            return new Stall(tl.thread(), silence, Verdict.UNEXPLAINED,
                    "no samples and no blocking event: blocked below the recording's thresholds, "
                            + "or sampled too sparsely" + edgeNote(tl, silence, edge), Stack.EMPTY,
                    Evidence.SILENCE, 0);
        }
        return null;
    }

    /**
     * A silence shorter than the routine absence is not evidence by itself, so what explains
     * it must cover a whole gap on its own; a longer one is, and half is enough.
     */
    private Explanation explainSilence(final Windows windows, final Interval silence, final long unexplainedThreshold) {
        final long minCover = silence.duration() < unexplainedThreshold ? Math.max(gap, cover(silence)) : cover(silence);
        return windows.explain(silence, true, minCover, false);
    }

    /**
     * Blocking events together, whatever they were, covering half the run say the thread was
     * not running, and the verdict is the largest group's; otherwise the samples name what it
     * was busy with.
     */
    private Stall judgeRun(final ThreadTimeline tl, final Windows windows, final Interval run,
                           final List<Sample> samples) {
        if (run.duration() < gap || samples.size() < RUN_MIN_SAMPLES) {
            return null;
        }
        final Explanation ex = windows.explain(run, false, cover(run), true);
        if (ex == null) {
            return busy(tl, run, samples);
        }
        // The third door, and the same rule as the other two: a run of samples whose
        // explanation is the thread's own empty queue is not a stall. The samples are
        // not idle by the sampler's reckoning — a park shows as native code, not as the
        // idle point — so without this a worker's own waiting reappears here after
        // being kept out of the event stalls and the silences.
        return isAtRest(ex.verdict, ex.stack) ? null
                : new Stall(tl.thread(), run, ex.verdict, ex.detail, ex.stack, Evidence.SAMPLES, samples.size());
    }

    /**
     * A silence or a run of samples, before it is judged.
     *
     * @param samples the run's samples in time order; {@code null} for a silence
     * @param edge    for a silence, where it sits against the thread's life ({@link #MID},
     *                {@link #FROM_START}, {@link #TO_END})
     */
    private record Candidate(Interval interval, List<Sample> samples, long edge) {
        static Candidate silence(final Interval interval, final long edge) {
            return new Candidate(interval, null, edge);
        }

        static Candidate run(final Interval interval, final List<Sample> samples) {
            return new Candidate(interval, samples, MID);
        }

        /**
         * The part of this candidate in {@code [from, to)}: a run keeps the samples taken in
         * it, a silence keeps an end of the thread's life only if the piece still reaches it.
         */
        Candidate piece(final long from, final long to) {
            final Interval part = new Interval(from, to);
            if (samples == null) {
                final long kept = (from == interval.start() ? edge & FROM_START : 0)
                        | (to == interval.end() ? edge & TO_END : 0);
                return silence(part, kept);
            }
            int lo = 0;
            while (lo < samples.size() && samples.get(lo).time() < from) {
                lo++;
            }
            int hi = lo;
            while (hi < samples.size() && samples.get(hi).time() < to) {
                hi++;
            }
            return run(part, samples.subList(lo, hi));
        }
    }

    /** Whether the thread was seen, anywhere in the recording, waiting for work or for a deadline of its own. */
    private boolean restsUnseen(final List<Block> blocks) {
        for (int i = 0, n = blocks.size(); i < n; i++) {
            final Block b = blocks.get(i);
            if (isAtRest(verdictOf(b.kind()), b.stack())) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far apart two non-idle samples may be and still chain: three of the thread's Java
     * cadences, or three sampler periods when that is longer. Capped at the gap, because a
     * stretch a whole stall long with nothing seen in it is not evidence of running — but
     * never below three periods, the resolution of the sampler itself: capped there, a gap at
     * or below the period chained nothing at all, and a smaller gap found fewer stalls than a
     * larger one.
     */
    private long runLimit(final Cadence cadence) {
        final long limit = Math.min(Math.max(gap, RUN_FACTOR * cadence.period),
                RUN_FACTOR * Math.max(cadence.period, cadence.java));
        return limit > 0 ? limit : gap;
    }

    /**
     * The stretches of the thread's life before its first sample and after its last, when the
     * recording bounds that life; the whole of it for a thread never sampled. A call still in
     * progress when the recording stopped has written no event, so a thread stuck from the
     * middle of the recording to its end leaves nothing but this.
     */
    private void edgeSilences(final ThreadTimeline tl, final ObjList<Candidate> candidates) {
        if (!tl.isLifeKnown()) {
            return;
        }
        final List<Sample> samples = tl.samples();
        if (samples.isEmpty()) {
            addEdge(tl.lifeStart(), tl.lifeEnd(), FROM_START | TO_END, candidates);
            return;
        }
        addEdge(tl.lifeStart(), samples.getFirst().time(), FROM_START, candidates);
        addEdge(samples.getLast().time(), tl.lifeEnd(), TO_END, candidates);
    }

    private void addEdge(final long from, final long to, final long edge, final ObjList<Candidate> candidates) {
        if (to - from >= gap) {
            candidates.add(Candidate.silence(new Interval(from, to), edge));
        }
    }

    /** What a silence at an end of the thread's life means, appended to its detail. */
    private static String edgeNote(final ThreadTimeline tl, final Interval silence, final long edge) {
        if (edge == MID) {
            return "";
        }
        final StringBuilder note = new StringBuilder("; ");
        if ((edge & FROM_START) != 0 && silence.start() == tl.lifeStart()) {
            note.append("from the thread's first moment in the recording");
            if ((edge & TO_END) != 0 && silence.end() == tl.lifeEnd()) {
                note.append(" to its last, without a single sample");
            }
        } else if ((edge & TO_END) != 0 && silence.end() == tl.lifeEnd()) {
            note.append("runs to the thread's last moment in the recording")
                    .append(tl.samples().isEmpty() ? ", which has no sample of it" : ", after its last sample")
                    .append(": a call still in progress when the recording stopped has written no event");
        } else {
            return "";
        }
        return note.toString();
    }

    /** The coverage an explanation needs for an interval that is evidence in its own right. */
    private static long cover(final Interval interval) {
        return (long) Math.ceil(COVER * interval.duration());
    }

    /** The shortest silence that means anything on its own: above the gap and above the routine absence. */
    private long silentThreshold(final Cadence cadence) {
        return Math.max(gap, CADENCE_FACTOR * cadence.routineAbsence());
    }

    /**
     * @param extent  for an explanation by pauses, the part of the candidate from the first
     *                pause to the last, which is all they account for; {@code null} for blocks
     * @param stopped for an explanation by pauses, how long they stopped the JVM inside the
     *                candidate; 0 for blocks
     */
    private record Explanation(Verdict verdict, String detail, Stack stack, Interval extent, long stopped) {
    }

    /** Coverage of one (kind, detail) group of blocks over a candidate interval; reused across candidates. */
    private static final class Group {
        BlockKind kind;
        String detail;
        long overlap;
        long count;
        long bytes;
        /** The longest block in the group: its stack stands for the group. */
        Block representative;

        Group of(final BlockKind kind, final String detail) {
            this.kind = kind;
            this.detail = detail;
            this.overlap = 0;
            this.count = 0;
            this.bytes = 0;
            this.representative = null;
            return this;
        }
    }

    /**
     * The sorted lists a candidate interval is checked against, with the longest element of
     * each remembered so a lookup scans only the elements that can overlap. The pauses are
     * fixed for the analysis; the blocks and event stalls are re-pointed per thread
     * ({@link #of}), and the grouping scratch is cleared per lookup (G-3.1, G-3.3).
     */
    private static final class Windows {
        private final ObjList<Pause> pauses;
        private final long longestPause;
        private List<Block> blocks = List.of();
        private long longestBlock;
        private ObjList<Stall> eventStalls = new ObjList<>();
        private long longestEventStall;
        /** Groups in first-seen order, so a tie in coverage goes to the earliest group, and their pool. */
        private final ObjList<Group> groups = new ObjList<>();
        private final ObjList<Group> pool = new ObjList<>();
        private final ObjObjHashMap<String, Group>[] groupByDetail;

        @SuppressWarnings({"unchecked", "rawtypes"}) // an array of a generic type has no other spelling
        Windows(final ObjList<Pause> pauses) {
            this.pauses = pauses;
            this.longestPause = Sorts.maxDuration(pauses, PAUSE_DURATION);
            final BlockKind[] kinds = BlockKind.values();
            this.groupByDetail = new ObjObjHashMap[kinds.length];
            for (int i = 0; i < kinds.length; i++) {
                groupByDetail[i] = new ObjObjHashMap<>(16);
            }
        }

        void of(final List<Block> blocks, final ObjList<Stall> eventStalls) {
            this.blocks = blocks;
            this.longestBlock = Sorts.maxDuration(blocks, BLOCK_DURATION);
            this.eventStalls = eventStalls;
            this.longestEventStall = Sorts.maxDuration(eventStalls, STALL_DURATION);
        }

        /**
         * Whether the thread's event stalls, together, already cover half the candidate: its
         * time is then already in the report, however many events it took. Taken one at a
         * time, two one-minute waits inside a silence of two and a half came back as a third
         * stall the length of both.
         */
        boolean coveredByEvent(final Interval candidate) {
            long covered = 0;
            long coveredTo = candidate.start();
            final int from = Sorts.lowerBound(eventStalls, STALL_START, candidate.start() - longestEventStall);
            for (int i = from, n = eventStalls.size(); i < n; i++) {
                final Stall s = eventStalls.getQuick(i);
                if (s.start() >= candidate.end()) {
                    break;
                }
                final long end = Math.min(s.interval().end(), candidate.end());
                final long start = Math.max(s.start(), coveredTo);
                if (end > start) {
                    covered += end - start;
                    coveredTo = end;
                }
            }
            return covered >= COVER * candidate.duration();
        }

        /**
         * Groups blocking events overlapping the interval by kind and detail; the group with
         * the most coverage wins if it covers at least {@code minCover} nanoseconds, or, when
         * {@code together}, if all of them between them do. Failing that, and only when
         * {@code tryPauses}, JVM pauses are tried the same way.
         */
        Explanation explain(final Interval interval, final boolean tryPauses, final long minCover,
                            final boolean together) {
            final Explanation byBlock = explainByBlocks(interval, minCover, together);
            if (byBlock != null || !tryPauses) {
                return byBlock;
            }
            return explainByPauses(interval, minCover);
        }

        /**
         * Blocks are grouped by kind and detail, which for I/O is the peer or the path and
         * not the byte count, so that many short reads from one peer add up to one answer.
         * Coverage together is the union of the blocks, so a block inside another counts once.
         */
        private Explanation explainByBlocks(final Interval interval, final long minCover, final boolean together) {
            clearGroups();
            long union = 0;
            long coveredTo = interval.start();
            int count = 0;
            final int from = Sorts.lowerBound(blocks, BLOCK_START, interval.start() - longestBlock);
            for (int i = from, n = blocks.size(); i < n; i++) {
                final Block b = blocks.get(i);
                if (b.start() >= interval.end()) {
                    break;
                }
                final long overlap = b.interval().overlap(interval);
                if (overlap == 0) {
                    continue;
                }
                final long end = Math.min(b.interval().end(), interval.end());
                final long start = Math.max(b.start(), coveredTo);
                if (end > start) {
                    union += end - start;
                }
                coveredTo = Math.max(coveredTo, end);
                count++;
                final Group g = group(b.kind(), b.detail());
                g.overlap += overlap;
                g.count++;
                g.bytes += b.bytes();
                if (g.representative == null || b.duration() > g.representative.duration()) {
                    g.representative = b;
                }
            }
            Group best = null;
            for (int i = 0, n = groups.size(); i < n; i++) {
                final Group g = groups.getQuick(i);
                if (best == null || g.overlap > best.overlap) {
                    best = g;
                }
            }
            if (best != null && (together ? union : best.overlap) >= minCover) {
                final Block rep = best.representative;
                final String detail = best.count > 1 ? best.count + " × " + describe(rep, best.bytes) : describe(rep);
                // Named only when the largest group needed them: then they are part of the answer.
                final long others = best.overlap < minCover ? count - best.count : 0;
                return new Explanation(verdictOf(rep.kind()), others == 0 ? detail
                        : detail + ", with " + others + (others == 1 ? " other blocking event" : " other blocking events"),
                        rep.stack(), null, 0);
            }
            return null;
        }

        private Group group(final BlockKind kind, final String detail) {
            // A block without a detail groups under the literal "null", as the old string key did.
            final String key = detail == null ? "null" : detail;
            final ObjObjHashMap<String, Group> byDetail = groupByDetail[kind.ordinal()];
            final int index = byDetail.keyIndex(key);
            if (index < 0) {
                return byDetail.valueAtQuick(index);
            }
            final Group g = groups.size() < pool.size() ? pool.getQuick(groups.size()) : allocate();
            groups.add(g.of(kind, key));
            return byDetail.putAt(index, key, g);
        }

        private Group allocate() {
            final Group g = new Group();
            pool.add(g);
            return g;
        }

        private void clearGroups() {
            for (int i = 0, n = groups.size(); i < n; i++) {
                final Group g = groups.getQuick(i);
                groupByDetail[g.kind.ordinal()].remove(g.detail);
                g.representative = null;
            }
            groups.clear();
        }

        /**
         * GC pauses first, then other safepoints, each kind on its own. Several pauses explain
         * a silence together, and the answer says how many and how long they stopped the JVM
         * for: many short collections are one pause's worth of label otherwise, and read as a
         * single multi-second stop that never happened.
         */
        private Explanation explainByPauses(final Interval interval, final long minCover) {
            final Explanation gc = explainByPauses(interval, minCover, PauseKind.GC, Verdict.GC_PAUSE);
            return gc != null ? gc : explainByPauses(interval, minCover, PauseKind.SAFEPOINT, Verdict.SAFEPOINT);
        }

        private Explanation explainByPauses(final Interval interval, final long minCover, final PauseKind kind,
                                            final Verdict verdict) {
            long covered = 0;
            long coveredTo = interval.start();
            long first = Nulls.LONG_NULL;
            int count = 0;
            Pause longest = null;
            final int from = Sorts.lowerBound(pauses, PAUSE_START, interval.start() - longestPause);
            for (int i = from, n = pauses.size(); i < n; i++) {
                final Pause p = pauses.getQuick(i);
                if (p.start() >= interval.end()) {
                    break;
                }
                if (p.kind() != kind || p.interval().overlap(interval) == 0) {
                    continue;
                }
                final long end = Math.min(p.interval().end(), interval.end());
                final long start = Math.max(p.start(), coveredTo);
                if (end > start) {
                    covered += end - start;
                }
                if (first == Nulls.LONG_NULL) {
                    first = Math.max(p.start(), interval.start());
                }
                coveredTo = Math.max(coveredTo, end);
                count++;
                if (longest == null || p.duration() > longest.duration()) {
                    longest = p;
                }
            }
            if (longest == null || covered < minCover) {
                return null;
            }
            final String detail = count == 1 ? kind.label() + ": " + longest.detail()
                    : count + " × " + kind.label() + ", " + Durations.format(covered) + " stopped in total, longest "
                    + Durations.format(longest.duration()) + " (" + longest.detail() + ")";
            return new Explanation(verdict, detail, Stack.EMPTY, new Interval(first, coveredTo), covered);
        }
    }

    /** One culprit of a busy run: how many samples named it, and the first stack that did. */
    private static final class Culprit {
        String name;
        Stack stack;
        int count;

        Culprit of(final String name, final Stack stack) {
            this.name = name;
            this.stack = stack;
            this.count = 0;
            return this;
        }
    }

    /** A culprit from the pool, re-pointed; the pool only grows to the most culprits one run had. */
    private Culprit culprit(final String name, final Stack stack) {
        final Culprit c;
        if (culprits.size() < culpritPool.size()) {
            c = culpritPool.getQuick(culprits.size());
        } else {
            c = new Culprit();
            culpritPool.add(c);
        }
        culprits.add(c);
        return c.of(name, stack);
    }

    /**
     * {@code BUSY} when one culprit owns at least half the run's samples and at least two of
     * them: a name resting on one sample is a guess, however few samples the run has.
     * {@code SATURATED} when none does and there are enough samples to say the thread never
     * yielded; otherwise nothing.
     */
    private Stall busy(final ThreadTimeline tl, final Interval run, final List<Sample> samples) {
        culprits.clear();
        culpritByName.clear();
        int nativeTop = 0;
        final int n = samples.size();
        for (int i = 0; i < n; i++) {
            final Sample s = samples.get(i);
            final String name = culpritName(s.stack());
            final int index = culpritByName.keyIndex(name);
            final Culprit c = index < 0 ? culpritByName.valueAtQuick(index)
                    : culpritByName.putAt(index, name, culprit(name, s.stack()));
            c.count++;
            if (s.isInNative()) {
                nativeTop++;
            }
        }
        Culprit top = null;
        for (int i = 0, m = culprits.size(); i < m; i++) {
            final Culprit c = culprits.getQuick(i);
            if (top == null || c.count > top.count) {
                top = c;
            }
        }
        final double share = top == null ? 0 : (double) top.count / n;
        final boolean dominant = share >= DOMINANT && top.count >= RUN_MIN_SAMPLES;
        if (!dominant && n < SATURATED_MIN_SAMPLES) {
            // Too few samples to claim the thread never yielded; the run is not reported.
            return null;
        }
        final String pct = String.format(Locale.ROOT, "%.0f%%", share * 100);
        final String nativeNote = nativeTop * 2 >= n ? " [mostly in native code]" : "";
        if (dominant) {
            return new Stall(tl.thread(), run, Verdict.BUSY,
                    "busy in " + top.name + " (" + pct + " of " + n + " samples)" + nativeNote,
                    top.stack, Evidence.SAMPLES, n);
        }
        return new Stall(tl.thread(), run, Verdict.SATURATED,
                "no return to idle across " + n + " samples; " + culprits.size() + " distinct culprits, top "
                        + top.name + " " + pct + nativeNote,
                top.stack, Evidence.SAMPLES, n);
    }

    /** The culprit frame's {@code type.method}, or {@code <no stack>}; the name is built once per frame. */
    private String culpritName(final Stack stack) {
        final Frame culprit = stack.culpritOrNull();
        if (culprit == null) {
            return "<no stack>";
        }
        final int index = culpritNames.keyIndex(culprit);
        return index < 0 ? culpritNames.valueAtQuick(index)
                : culpritNames.putAt(index, culprit, culprit.qualifiedName());
    }

    static Verdict verdictOf(final BlockKind kind) {
        return switch (kind) {
            case MONITOR -> Verdict.BLOCKED_MONITOR;
            case PARK -> Verdict.PARKED;
            case OBJECT_WAIT -> Verdict.OBJECT_WAIT;
            case SLEEP -> Verdict.SLEEP;
            case SOCKET_READ, SOCKET_WRITE, FILE_READ, FILE_WRITE, FILE_FORCE -> Verdict.BLOCKING_IO;
        };
    }

    static String describe(final Block b) {
        return describe(b, b.bytes());
    }

    /** {@link #describe(Block)} with the byte count of a whole group of I/O blocks. */
    static String describe(final Block b, final long bytes) {
        final StringBuilder sb = new StringBuilder(b.kind().label());
        if (b.detail() != null && !b.detail().isEmpty()) {
            sb.append(' ').append(b.detail());
        }
        if (b.kind().isIo() && bytes > 0) {
            sb.append(" (").append(Bytes.format(bytes)).append(')');
        }
        if (b.kind() == BlockKind.MONITOR) {
            sb.append(" held by ").append(b.owner() == null ? "unknown" : b.owner().name());
            Holders.appendVia(sb, b.via());
        }
        return sb.toString();
    }
}
