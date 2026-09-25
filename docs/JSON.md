# JSON output

`--json` makes any `jfrq` command print one JSON document on standard output instead of
the text report. It is built from the same report objects as the text and the HTML, and
`--top N` bounds its lists the same way, so the three never disagree; every list that was
cut has a count beside it. `--html` still writes its file. Under `jfrq-live`, a question
with `--json` after `--` gets standard output to itself: the dump's own lines (`Dumped`,
`Window`, `Cursor`) go to standard error.

## Conventions

- **Schema.** Every document starts with `tool` (`"jfrq"`), `version` (the tool's),
  `schema` (an integer, now `1`) and `command`. A field may be added without changing
  `schema`; renaming or removing one, or changing what it means, changes `schema`.
- **Units are in the names.** `…Nanos` is a duration or offset in nanoseconds, `bytes` and
  `…Bytes` are bytes, `bytesPerSecond…` is a rate, `share` is a fraction of 1, `ratio` is a
  relative change (`0.5` is +50 %).
- **Instants** are ISO-8601 strings in UTC (`2026-09-25T07:15:34.363375Z`). An event also
  has `offsetNanos`, its distance from the recording's start, which is what the text prints
  as `+13.682s`.
- **Absent is `null`, never 0.** A value the recording cannot give (a threshold that was
  not set, a cadence with no pair of samples, a life count for a virtual thread) is `null`.
- **Enumerations** are upper-case names: `verdict` (`BLOCKED_MONITOR`, `PARKED`,
  `OBJECT_WAIT`, `SLEEP`, `BLOCKING_IO`, `BUSY`, `SATURATED`, `GC_PAUSE`, `SAFEPOINT`,
  `UNEXPLAINED`), `evidence` (`EVENT`, `SAMPLES`, `SILENCE`), `sight` (`CLEAR`,
  `NATIVE_SAMPLER`, `JAVA_SAMPLER`, `OWN_ABSENCE`), lock `kind` (`MONITOR_ENTER`, `PARK`),
  finding `kind` (`OUT_OF_MEMORY`, `EVACUATION_FAILED`, `FULL_GC`, `GC_TIME_OVER_GOAL`,
  `PAUSE_OVER_TARGET`, `HUMONGOUS_ALLOCATION`, `METASPACE_GC`, `SYSTEM_GC`), trend `unit`
  (`BYTES`, `COUNT`, `FRACTION`).
- **A stack** is `{"frames": [...], "culprit": ..., "truncated": ...}`: the innermost
  frames as a stack trace prints them (twelve at most; six for a lock site, the depth sites
  are grouped at), the innermost frame outside the JDK as `package.Class.method` (named even
  when it lies below the frames kept; `null` when every frame is the JDK's), and whether
  frames were left out. A lambda is named without the address the JVM gave its class
  (`Handler$$Lambda.run`), so the same code has the same name in every run. A stall with no stack (a pause, an unexplained gap) has
  `"stack": null`.

## Every document

| Field | |
|---|---|
| `recording.file` | the file name |
| `recording.start`, `recording.end` | the data span, from the chunk headers |
| `recording.durationNanos` | its length |
| `recording.chunks` | how many chunks |
| `recording.warnings` | problems with the file itself (truncated, joined) |

## `info`

| Field | |
|---|---|
| `threadsSeen` | threads that are the thread of some event |
| `threadsAlive.atStart`, `.started`, `.ended`, `.atEnd` | the census of platform threads; alive at start plus started minus ended is alive at end |
| `settingsKnown` | whether the file carries its settings (`jdk.ActiveSetting`) |
| `thresholded`, `throttled` | the event types whose threshold suppresses something, and the throttled ones |
| `eventTypes[]` | `type`, `count`, `enabled`, `thresholdNanos`, `period` (as recorded, e.g. `"10 ms"`, `"everyChunk"`), `periodNanos`, `throttle` |
| `threadFamilies[]` | `family` (`pool-N-thread-N`), `glob` (the `--thread` value that matches the family and nothing else), `threads`, `seen`, `aliveAtStart`, `started`, `ended`, `aliveAtEnd`, `example` |

## `stalls`

| Field | |
|---|---|
| `gapNanos` | the stall threshold applied |
| `samplingPeriodNanos.java`, `.native` | the sampler periods |
| `thresholdNanos` | per blocking event type |
| `threadsMatched` | threads the filter matched that had something to judge |
| `unseen[]` | the `Unseen` sentences: what the recording cannot show on these threads, and why |
| `warnings[]` | the `WARNING` lines |
| `byVerdict[]` | `verdict`, `stalls`, `stalledNanos`, `worstNanos`, largest total first |
| `threadsWithStalls`, `threadsWithoutStalls` | the counts behind `threads` |
| `threads[]` | the threads that stalled, most stalled first: `thread`, `threadId`, `virtual`, `samples`, `javaCadenceNanos`, `nativeCadenceNanos`, `sight`, `unseenBelowNanos`, `stalls`, `stalledNanos`, `worstNanos` |
| `stallsFound`, `stalls[]` | the explained stalls, longest first: `thread`, `start`, `offsetNanos`, `durationNanos`, `verdict`, `evidence`, `detail`, `samples`, `stack` |
| `unexplainedFound`, `unexplained[]` | the unexplained gaps, the same shape |
| `pausesFound`, `pauses[]` | JVM-wide pauses at least the gap long: `start`, `offsetNanos`, `durationNanos`, `kind` (`GC`, `SAFEPOINT`), `detail` |

## `locks`

| Field | |
|---|---|
| `thresholdNanos` | for `jdk.JavaMonitorEnter` and `jdk.ThreadPark` |
| `noContention` | the sentence the text prints when every wait was a worker waiting for work, else `null` |
| `blockedNanos`, `waits`, `clippedWaits` | the totals, and the waits cut to the recording's span |
| `locks[]` | without `--by-site`: `lock`, `class`, `kind`, `totalNanos`, `waits`, `maxNanos`, `waiters`, `heldBy`, `stack` (the longest wait's) |
| `sites[]` | with `--by-site`, instead of `locks`: `kind`, `totalNanos`, `waits`, `maxNanos`, `locks` (the instances), `waiters`, `heldBy`, `stack` |
| `threads[]` | `thread`, `threadId`, `virtual`, `totalNanos`, `waits`, `maxNanos`, `share` |
| `convoys[]` | each an array of waits, outermost first: `waiter`, `start`, `offsetNanos`, `durationNanos`, `lock`, `heldBy`, `handedOnThrough` |
| `waitingForWork` | `threads`, `parks`, `totalNanos`, `byShape` (locks recognised by shape rather than name), `queues[]` shaped like `locks[]` |
| `longest[]` | the longest waits, shaped like a convoy link with a `stack` |

## `health`

| Field | |
|---|---|
| `findings[]` | most serious first: `kind`, `count`, `first`, `firstOffsetNanos`, `last`, `lastOffsetNanos` (all `null` for `GC_TIME_OVER_GOAL`, which is about the whole window), `text` |
| `gc` | `collections`, `byCollector` and `byCause` (objects, most first; a G1 concurrent cycle, `G1Old`, is in `byCollector` but not in `byCause`), `oldCycles`, `pauseNanos`, `pauseShare`, `longestPauseNanos`, `gcTimeRatio`, `pauseTargetNanos`, `maxHeapBytes` |
| `trends[]` | `series` (`Heap after GC`, `Resident set`, `Live threads`, `JVM CPU`, `Machine CPU`; one the recording has no events for is left out), `unit`, `points`, `start`, `end`, `min`, `max`, `mean`, `floorFirstThird`, `floorLastThird` (the lowest value in each; `null` under three points) |
| `threadsStarted`, `threadsPeak` | threads started in the window, and the most alive at once since the JVM started |
| `throwables` | `created` (exact, between the first and last `jdk.ExceptionStatistics`), `createdNanos` (that stretch), `perSecond`, `events` (`jdk.JavaExceptionThrow`), `throttle`, `errors` (`jdk.JavaErrorThrow` per class), `classesFound`, `byClass[]` (`class`, `events`, `share`, `perSecond`, `message`: one example), `sitesFound`, `bySite[]` (`site`, `class`, `events`, `share`, `stack`: from below the throwable's own construction) |

## `alloc`

| Field | |
|---|---|
| `warnings[]` | what to know before trusting the estimate |
| `source`, `samples`, `events` | the event the estimate rests on, and how much of it |
| `estimatedBytes`, `bytesPerSecond` | the estimate |
| `counted` | whether the JVM's own per-thread counters were in the file; when they were: `countedBytes`, `countedThreads`, `estimatedOnCountedThreads`, `estimateError` |
| `threads[]` | `thread`, `bytes`, `countedBytes`, `bytesPerSecond`, `share`, `samples`, `topClasses[]` (`class`, `bytes`) |
| `classes[]` | `class` (the JVM name, `[B` for `byte[]`), `bytes`, `bytesPerSecond`, `share`, `samples` |
| `siteKey`, `packages[]`, `sites[]` | with `--sites`: how sites are keyed, the package roots seen (`package`, `share`), and `site`, `bytes`, `bytesPerSecond`, `share`, `samples`, `stacks`, `stack` |

With `--baseline`, `recording` is the current file and the document instead has
`baseline` (its own `recording` object and the estimate fields above), `current` (the
estimate fields), and `change`, `threads[]`, `classes[]` and, with `--sites`, `sites[]`,
each with `bytesPerSecondBefore`, `bytesPerSecondAfter`, `bytesPerSecondChange` and
`ratio` (`null` when the baseline had nothing), plus `samplesBefore` and `samplesAfter`
where they apply: a change of several hundred percent on a handful of samples is noise.
