# jfrq backlog from the Houston/Edge 2 round, 2026-09-22

Source: `HOUSTON-EDGE2-JFRQ-SEP-22.md` §4, written after using `jfrq` / `jfrq-live` against a
21-hour-old Edge 2 node under load. The T-numbers are that document's; keep them, they are the
only link back to the evidence.

Every "where" below was verified against the source in this repository at `1433cff`. Where a
cause is inferred rather than read, it says so.

| # | Item | Area | Why it ranks there |
|---|---|---|---|
| **P1** | | | wrong numbers, or the tool failing to find what it was pointed at |
| T3 | waits are not clipped to the window | `locks` | prints `112.5%`; inflates the headline total |
| T8 | a pool thread waiting for work is reported as a `PARKED` stall | `stalls` | false positive on the most common shape in a server |
| T1 | `locks` has no idle classification | `locks` | 12 idle parks outrank the one real lock; the tool did not find E1 |
| T2 | no way to get the stack of a named lock | `locks` | with T1, the reason E1 was found outside the tool |
| T7a | the calibration line does not say how much it validates | `alloc` | a ±2 % that covers 29 % of the estimate reads as ±2 % of all of it |
| **P2** | | | orientation and honesty of the output |
| T6 | `info` lists no thread names, and `--thread` is mandatory | `info` | the tool cannot tell you what to pass it; README claims it does |
| T5 | `BY SITE` prints rows indistinguishable in what it shows | `alloc` | the reader has to guess three rows are one site and add them up |
| T4 | `Waiters` / `Held by` print every distinct name | `locks` | 3 630 characters on one line; 66 KB of output mostly thread names |
| T9 | `Thresholds` under-reports what is in force | `info`, `stalls`, `locks` | the line exists to answer "did my settings take effect" |
| T10 | `jfrq-live start` does not echo what it configured | `live` | the answer is cheapest at the moment the operator is at the keyboard |
| T7b | rate diffs carry no support signal | `alloc` | `+469 %` on 143 samples reads like a finding |
| **P3** | | | small |
| T11a | `--timing` has no analyse number | CLI | read includes `finish()`, where the analysis runs |
| T11b | `--html` has no dark-mode block | HTML | otherwise self-contained and correct |

---

## P1

### T3 — clip every wait to the recording window

**Symptom.** `THREADS BY TIME BLOCKED` gave `logback-4  3m00s  1  3m00s  112.5%` in a 2m39s
window; the `Blocked 319m15s across 61064 waits` headline is inflated by the same amount.

**Where.** Nothing clips: `ContentionCollector.accept` (`ContentionCollector.java:87`) stores
`Events.interval(e)` as it comes; `ContentionReport.totalNanos` (`:200-206`), `locks` (`:209-228`)
and `waiters` (`:231-243`) sum `Wait.duration()` whole; `Text.locks` divides by
`info.span().length()` (`Text.java:224-228`). Verified. That the overflow comes from waits that
began before the first chunk is inferred — it is the only way a single wait can exceed the span,
and JFR writes a blocking event when the wait *ends*.

**Fix.** Clip each wait's interval to `info.span()` before it enters any total (report side, so
the collector stays allocation-free), and keep the unclipped duration for `LONGEST WAITS`, which
is about the wait, not about the window. State the choice in `docs/DESIGN.md` §3.

**Why it matters more for `live`.** Every `delta` window slices mid-wait by construction.

**Test.** `ContentionReportTest`: a wait straddling each boundary, share ≤ 100 %, total equal to
the clipped sum.

### T8 — a park that is "waiting for work" is not a stall

**Symptom.** `pool-36-thread-1`, a fixed pool with nothing to do, parked in
`ThreadPoolExecutor` → `ConditionObject.await`, reported as a `1m10s PARKED` stall.

**Where.** `StallAnalysis.analyseThread` (`StallAnalysis.java:325-333`) turns *every* block at
least the gap long into a stall; the idle matcher is consulted only for samples
(`StallCollector.java:163`). Verified.

**Fix.** Classify block events too — but not with `IdleMatcher.DEFAULT_PATTERNS`, which match
`Unsafe.park` / `LockSupport.park` in the innermost three frames (`IdleMatcher.java:36-38`,
`:72-80`) and would swallow every park, including the `CompletableFuture.timedGet` park that is
finding E5. It needs the *waiting-for-work* set below, matched deeper than three frames.

**Shared with T1 — build the classifier once.** The standard JDK shapes:
`ThreadPoolExecutor.getTask`, `SynchronousQueue.poll|take`, `LinkedBlockingQueue.take`,
`ArrayBlockingQueue.take`, `ForkJoinPool.awaitWork`,
`ScheduledThreadPoolExecutor$DelayedWorkQueue.take`. Same list, two callers: block events here,
park waits in T1.

**Decision to take before coding.** Whether a classified idle park disappears from `stalls` or
moves to its own section. The precedent in this tool is to show and label, not to hide
(the cadence warning, the `[samples]` / `[silence]` evidence tags) — recommend a labelled
section, not a silent drop.

**Test.** `StallAnalysisTest` (hand-built timeline, no recording needed): a park block with a
pool stack is not a stall; a park block with an application stack still is.

### T1 — `locks`: split contended from waiting for work

**Symptom.** On a 2m39s window of a real node: `Blocked 319m15s across 61064 waits`; 12 of 12
rows in `LOCKS BY TOTAL WAIT` and 12 of 12 in `LONGEST WAITS` are idle parks. The one actionable
lock — the COW monitor, 33.8 s / 1 028 waits — ranks below all of them and never reaches a
section that prints a stack. It surfaced only because `CONVOYS` does not rank by duration.

**Where.** `ContentionCollector` already drops parks with no blocker object
(`ContentionCollector.java:94-96`) and `docs/DESIGN.md` §3 says a `ConditionObject` blocker "is
usually a worker waiting for work … the class is printed so a reader can tell". That is the whole
mechanism today: the reader tells. `--min` cannot help (the noise is the *longest* waits);
`--thread` requires already knowing the answer.

**Fix.** Classify each `Wait` with the T8 classifier against its own stack (`Wait` already carries
it), and report `CONTENDED` and `WAITING FOR WORK` as separate sections with their own totals and
their own `Blocked` headline. Default on; `--idle REGEX,...` to replace the set and something like
`--idle none` to get today's behaviour back.

**Test.** `ContentionReportTest`: a pool park and a monitor wait in one report land in different
sections and different totals.

### T2 — one representative stack per lock, and a `--lock` filter

**Symptom.** `LOCKS BY TOTAL WAIT` names `java.lang.Object@714697020` with its totals and nothing
else. No section prints a stack for it, and duration ranking guarantees none will. The escape was
to leave the tool: `jfr print --events jdk.JavaMonitorEnter --stack-depth 22 … | awk`.

**Where.** `ContentionReport.LockStats` (`ContentionReport.java:33-35`) has no stack field, though
every `Wait` carries one; `Text.locks` prints stacks only under `LONGEST WAITS`
(`Text.java:252-259`).

**Fix.** Both halves, they are independent and both cheap:
- add the longest wait of each lock to `LockStats` and print it under the row (the pattern
  `StallAnalysis.Group.representative` already uses);
- `--lock <address|class glob>` on `locks`, applied like `--thread`: after holder resolution, to
  what is listed, never to what is walked (`docs/DESIGN.md` §3 states that rule).

**Test.** `ContentionReportTest` for the representative; `MainTest` for the option and for
`--lock` with no match being a clean empty report, not an error.

### T7a — say what fraction the calibration covers

**Symptom.** The `Counted` line reads as "the estimate is within 2 %". It validates the threads
seen at both ends of the file — 29 % of the estimate in that run. The transient pool threads that
did two-thirds of the allocating are, by construction, not seen at both ends.

**Where.** `Text.alloc` (`Text.java:93-95`) prints the counted bytes, the thread count and the
error; `AllocationReport.estimatedOnCountedThreads()` (`AllocationReport.java:77-83`) and
`totalBytes` are both to hand, so the covered fraction is a division, no new collection.

**Fix.** Add it to the line: `… the estimate for those is 14.0 GB (-2%), which is 29% of the
estimate above`. Sentence in `docs/DESIGN.md` §2 and in §9's known limits.

**Test.** `AllocationTest` on a hand-built report; `MainTest` for the rendered line.

---

## P2

### T6 — `info` should list the threads, and the README should stop claiming it does

Two items, one of them a one-line fix.

**T6a (doc, now).** `README.md:103` says "`jfrq info` lists the names in a file". It does not:
`Text.info` prints `Threads 118` and no name anywhere (`Text.java:67`). Either fix the line or
land T6b; do not leave both.

**T6b (feature).** A `THREADS` section grouped into families — `milo-shared-thread-pool-N ×33`,
`pool-36-thread-N ×2` — is the fastest orientation the tool can give, and `stalls --thread` is
mandatory, so it is the first thing a user needs. `RecordingInfo.threads()` already holds every
thread seen (`RecordingInfo.java:42`, filled in `JfrReader.Pass.accept:222-228`); the grouping is
a name-suffix fold at the render layer.

**Also.** `Threads N` counts threads *seen in events*, which is why it moved 118 / 149 / 235 with
window activity. Label it (`Threads 118 seen in events`) rather than explain it in a doc.

### T5 — fold allocation sites that are identical in what is printed

**Symptom.** `BY SITE` ranks 1, 2 and 3 (51.1 %, 10.9 %, 5.3 %) showed the same six frames and the
same `… 12 more`. They are one site to a reader; their sum, 67.3 %, was the actual headline.

**Where.** `bySite` keys on the full stack (`AllocationCollector.java:203`); `Text.alloc` prints
`STACK_FRAMES = 6` plus the culprit line (`Text.java:145-153`, `Stack.pretty:140-158`).

**Fix.** Fold in `AllocationReport.sites(top)` on `Stack.head(n)` for the same `n` the renderer
shows, summing bytes and noting `(N stacks)`; `Stack.head` (`Stack.java:128-133`) exists. Keep the
raw map — `AllocationDiff` matches sites by full stack and must keep doing so. Alternative, if
folding is judged to hide something: print the first frame at which the rows diverge. Folding is
the better default; a reader who wants the variants can raise the frame count.

**Test.** `AllocationTest`: three stacks sharing a six-frame prefix become one row with the summed
share.

### T4 — cap the name lists

**Symptom.** 3 630 characters on one line; 66 KB of `locks` output for a 100-thread server, mostly
thread-name lists. The count is the information; the names are not.

**Where.** `Text.names` (`Text.java:360-366`) and `Html.names`
(`core/.../report/Html.java:354-363`) both join every element.

**Fix.** First 3–5 by total wait, then `(+N more)`. Both renderers, same helper.

**Test.** `ContentionReportTest` or `MainTest` on a lock with 20 waiters.

### T9 — derive the `Thresholds` line from the settings present

**Symptom.** `jfrq-live start` sets seven thresholds; the line reports five. `SocketWrite` and
`FileWrite` are missing from it while the event table below shows both at 1.00 ms.

**Where.** `Text.info` passes a fixed whitelist (`Text.java:70-71`), and `Text.stalls`
(`:266-267`) and `Text.locks` (`:203`) pass their own subsets. Verified.

**Fix.** In `info`, derive: every event type in `info.settings()` that carries a threshold. Keep
the curated subsets in `stalls` and `locks` — there the line says "what bounds *this* answer", a
different question — but make `info`'s complete. Same treatment for throttles, which is what tells
a reader an estimate is sampled.

**Test.** `MainTest` against a recording with a threshold outside the old whitelist.

### T10 — `jfrq-live start` echoes what it applied

**Symptom.** `start` prints the JVM line and `Recording 1 jfrq-live RUNNING …` and nothing about
the thresholds, throttles or sample periods it just set. Recoverable from `info` on the first
dump, one dump later.

**Where.** `Live.start` (`Live.java:241-270`) applies `RECOMMENDED` (`Live.java:54-68`) by
overlaying it on the profile, then prints `jvmLine` and `recordingLine` only.

**Fix.** A `Settings` line after the recording line: the profile name plus the overlay, folded —
`profile + 1 ms thresholds (7 events), Socket/File throttles off, samples 10 ms, alloc 1000/s`.
Print the overlay that was actually applied, so `--settings FILE.jfc` says "as given, not
modified" rather than listing a map it did not write. `docs/LIVE.md` §1.

**Test.** `LiveTest` already drives `start` against the test JVM; assert the line.

### T7b — a support signal on rate diffs

**Symptom.** `--baseline` between two quiet windows gave `TagStatusSnapshot +397 %`,
`ArrayList +469 %` on a base of 143 allocation samples. No row says how many samples support it.

**Where.** The counts do not exist anywhere: `Accumulator` keeps bytes only
(`AllocationCollector.java:196-266`), so `AllocationReport`'s maps and `AllocationDiff.Delta`
(`AllocationDiff.java:32`) have nothing to carry. This is the one item in the list that touches
the per-event path.

**Fix.** A parallel count map per aggregation (`byThread`, `byClass`, `bySite` and the two
per-thread maps), decremented in `dropFirsts` alongside the bytes; a `Samples` column on the diff
rows, and suppress — or mark — a percentage below a support threshold. Keep the counts in
`ObjLongHashMap` like the bytes; no `java.util` on the per-event path (`CODING-GUIDELINES.md` §1).

**Cost.** Doubles the per-event table probes in `alloc`. Measure with `--timing` before and after
against the numbers in `docs/DESIGN.md` §8; if it shows, put the counts behind `--sites`-style
opt-in rather than paying always.

**Test.** `AllocationTest` for the counts surviving `dropFirsts`; `HashTablesTest` needs nothing.

---

## P3

### T11a — `--timing` has no analyse number

`README.md:64` promises "where the time went"; `docs/DESIGN.md` §8 correctly says read and render.
The analysis runs inside `JfrReader.read` → `Sink.finish` (`Main.java:190`, `:218`), so it is
inside the `read` number and not separable from outside. Fix: time `finish()` separately in
`JfrReader.read` and report `parse` / `analyse` / `render`, or drop the README's implication.
Prefer the split — `docs/DESIGN.md` §8.1's before/after numbers would have been sharper with it.

### T11b — `--html` dark mode

Verified self-contained (24 KB, no external refs). `CSS` (`Html.java:533-549`) has no
`color-scheme` and no `prefers-color-scheme` block. Add both; tokens for the handful of colours
already in there, plus the SVG timeline's `.track` / `.lbl` / `.axis` fills.

---

## Do not regress

Named in the feedback as what made the findings possible. Any change above that touches these
needs a test that pins them:

- `alloc --sites` naming an application line in one command — E1 and E2 are entirely its work.
- The measured sampling-cadence warning (`StallAnalysis.java:151-157`), quoted as "the single most
  honest thing any profiler has told me".
- `stalls` eliding the middle of a stack but keeping the first application frame
  (`Stack.pretty:146-153`).
- The `Dumped / Window / Cursor` triple, and the cursor chaining six dumps with no overlap and no
  gap (`Live.check:335-371`, `Cursor`).
- `jfrq-live` attaching to a production-shaped JVM and starting a correctly configured recording in
  5.4 s, no restart.

## Ship order

1. **T3, T7a** — wrong numbers, both small, both in the report/render layer.
2. **T8 + T1**, then **T2** — one waiting-for-work classifier serves both; T2 turns the section
   T1 creates into something with a stack in it. This is the group that decides whether the tool
   finds the next E1 on its own.
3. **T6a** immediately (one line), **T6b**, **T9**, **T10**, **T4**, **T5**.
4. **T7b**, **T11a**, **T11b**.

Each step carries its own tests and its `docs/` paragraph; `./gradlew build` enforces the coverage
gates (85 % core, 80 % cli and live) and `-Xlint:all -Werror`.

## Open, from the source document's own "not covered"

Not backlog items, but they bound what this round proves about the tool: only OPC UA adapters were
exercised, no southbound or MQTT load ran concurrently, and no allocation profile was taken of the
v2 path. A second round after E1/E2 are fixed is what would show whether the `alloc` output stays
as sharp when the 67 % headline site is gone.
