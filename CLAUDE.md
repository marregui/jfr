# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`jfrq`: a JDK 25 command-line tool that asks a JFR recording one question (`stalls`, `locks`, `alloc`, `info`) and answers it in text or a self-contained HTML report. `jfrq-live` asks the same questions of a running JVM by taking windowed dumps (`full`, `delta`, `again`) with a per-JVM cursor. Version 0.1.0, `group = dev.jfrq`. `README.md` is the user-facing description; `docs/DESIGN.md` explains each detector, the JFR events it reads, the evidence hierarchy and the performance history — read it before changing a verdict or trusting an unexpected result; `docs/LIVE.md` explains the dump mechanics (clone-and-stop, whole-chunk windows, the `T + 1 ms` cursor rule, bounds) — read it before touching `live/`.

## Build and test

Gradle (Kotlin DSL), four modules, no convention plugins: each `*/build.gradle.kts` is self-contained on purpose. JDK 25 everywhere: the module toolchains, the Gradle daemon (`gradle/gradle-daemon-jvm.properties`, so the launching `java` may be older), the `.sdkmanrc` for the shell that runs the installed launcher. No toolchain auto-download: a local Temurin 25 must be installed. `core` has no runtime dependency beyond `jdk.jfr`.

```
./gradlew build                       # compile + tests + JaCoCo coverage gates (85 % core, 80 % cli and live)
./gradlew installDist                 # cli/build/install/jfrq/bin/jfrq, live/build/install/jfrq-live/bin/jfrq-live, netty-demo/build/install/netty-demo/bin/netty-demo
./gradlew :core:test                  # one module
./gradlew :core:test --tests 'dev.jfrq.core.stalls.StallAnalysisTest'
./gradlew :core:test --tests '*StallAnalysisTest.methodName'
```

- Compilation runs with `-Xlint:all -Werror`: any warning fails the build.
- `check` depends on `jacocoTestCoverageVerification`; dropping coverage below the module gate fails `build`.
- Configuration cache, parallel and build cache are on (`gradle.properties`).
- Tests in `core/.../jfr/RecordingTest`, `cli/.../MainTest` and the stall/lock tests make **real JFR recordings in-process** (`JfrFixtures.record`), so they take seconds and assert verdicts and orders of magnitude, not exact durations. Pure-logic tests (`StallAnalysisTest`, `ContentionReportTest`, `AllocationTest`) use hand-built timelines with exact expectations. `live/.../LiveTest` **attaches to the test JVM itself** (`-Djdk.attach.allowAttachSelf=true` in `live/build.gradle.kts`) and runs the whole loop against a recording it starts there; the recorder is JVM-global, so every test names its recording and addresses it with `--recording`.

Run it after `installDist`:

```
netty-demo --scenario lock --duration 15s --out demo-lock.jfr
jfrq stalls demo-lock.jfr --thread 'event-loop-*' --timing
```

`--timing` prints read/analyse phases to stderr; measure with it before and after any performance change (numbers in `docs/DESIGN.md` §8).

## Architecture

**One streaming pass, several sinks.** `core/jfr/JfrReader` opens the file with `jdk.jfr.consumer.EventStream`, subscribes only to the event types its `Sink`s declare (`Sink.eventTypes()`; empty set = everything, used by `info`), and dispatches each event. Three consequences every sink must respect:

1. `setReuse(true)`: the `RecordedEvent` is recycled between callbacks — copy what you keep, never retain the event.
2. `setOrdered(false)`: delivery is file order, not time order — sinks sort what they keep in `finish()`.
3. Anchor events (`jdk.ActiveSetting`, `jdk.ActiveRecording`, `jdk.PhysicalMemory`) are always read so `RecordingInfo` (span, settings/thresholds, threads, counts) is the same on a filtered pass as on a full one.

`Sink` lifecycle: `begin(Interner)` → `accept(RecordedEvent, int kind)` × N → `finish(RecordingInfo)`. The `kind` is the `EventKinds` tag of the event type, resolved once per `EventType` object (by identity) rather than per event; collectors switch on it, and the one-argument `accept(RecordedEvent)` is the fallback for sinks that do not (tests). A `@Transient` parameter is one the callee does not retain.

**Per command:** a collector (sink) and a report.

| Command | Sink | Report / analysis |
|---|---|---|
| `alloc` | `alloc/AllocationCollector` | `AllocationReport`, `AllocationDiff` (`--baseline`, compares rates; both files parsed concurrently on virtual threads) |
| `locks` | `locks/ContentionCollector` | `ContentionReport` (holder walk-back through `previousOwner`, convoy search) |
| `stalls` | `stalls/StallCollector` | `StallAnalysis` + `IdleMatcher` + `Timeline` → `StallReport` |
| `info` | everything | `RecordingInfo` |
| `jfrq-live` (`live` module) | none: dumps a window from a running JVM over JMX, then delegates to `Main.run` | `Snapshot`, `Cursor`, the span check in `Live.check` |

**Model (`core/model`).** Timestamps are epoch nanoseconds in `long`; absence is `Nulls.LONG_NULL`, not `Optional` (the `Optional`/`Duration` accessors on `RecordingInfo` and `Stack` exist for reports and tests; per-event and analysis code uses `periodNanos`, `culpritOrNull`, `depth()`/`frameQuick(i)`). `Interval` is half-open `[start, end)`. `Interner` dedups `Stack`/`Frame`/`ThreadRef`/method names by identity of the JDK's constant-pool objects (a `RecordedObject` field lookup is a linear by-name scan, so every resolution is done once), and its value tables are probed with raw components (a frame's type/method/line/kind, a stack's frame buffer) so a hit allocates nothing; the identity caches are bounded and cleared when full. `Stack` is a class with a cached hash, not a record, so aggregation maps compare by identity first. `core/jfr/Events` is the only place that reads fields off a `RecordedEvent`.

**Collections (`core/coll`).** The per-event path runs on the project's own open-addressing tables and flat lists, not `java.util`: `ObjObjHashMap`, `IdentityObjObjHashMap`, `ObjLongHashMap`, `LongObjHashMap`, `ObjHashSet`, `ObjList`, `LongList`. They follow `CODING_GUIDELINES-SEP-18.md` §1: `keyIndex()` returns a negative index for a hit and the free slot for a miss, so get-or-insert is one probe (`int i = m.keyIndex(k); v = i < 0 ? m.valueAtQuick(i) : m.putAt(i, k, newValue())`); `getQuick`/`valueAtQuick` are assert-only; iteration is `for (s = 0; s < slots(); s++) if (hasKeyAtSlot(s))`; deletes backward-shift, never tombstone. Each table is a self-contained class (no shared base with virtual hooks: that made the probe loop megamorphic). `java.util` collections appear only in report objects (`AllocationReport`'s maps, `List`s on the records) and are built once in `finish()`. `HashTablesTest` cross-checks every table against `java.util` with a printed, replayable seed.

**Rendering.** Text (`cli/Text`, `core/util/TextTable`) and HTML (`core/report/Html`) are both derived from the same report objects; HTML is built behind a supplier only when `--html` is given.

**CLI (`cli/Main`, `cli/Args`).** Hand-rolled parsing, no library. Exit codes: 0 ok, 1 recording unreadable, 2 usage error. `Main` holds the `USAGE` text; keep it in sync with `README.md` when an option changes. The launcher adds `-XX:+AutoCreateSharedArchive` so an AppCDS archive lands in `lib/jfrq.jsa` after the first run (`cli/build.gradle.kts` patches the start script for `$APP_HOME`). `Main.run(argv, out, err)` and `Args` are public because `live` reuses them.

**`live` (`live/Live`, `Jvm`, `Snapshot`, `Cursor`, `Window`).** `jfrq-live <pid> <command> [options] [-- <jfrq command>]`. `Jvm.attach` uses `com.sun.tools.attach` to start the target's local management agent and drives `jdk.management.jfr.FlightRecorderMXBean` over that JMX connector (it sets `java.rmi.server.hostname=127.0.0.1` on its own side: an unresolvable macOS hostname otherwise costs 5 s per connection). `Snapshot.take` is `jcmd JFR.dump` over JMX: `cloneRecording(id, stop=true)` seals the current chunk at instant `T`, `openStream` with `startTime`/`endTime` hands over every chunk overlapping the window (whole chunks, never cut), `closeRecording` on the clone. `Cursor` keeps `T + 1 ms` per JVM incarnation (`pid` + JVM start time) in `~/.jfrq/live/<pid>.properties`; the rule and why it makes deltas exact at chunk level is in `Cursor`'s javadoc and `docs/LIVE.md` §2. After each dump `Live.check` reads the file back with `JfrReader.read` and prints the span against the window; warnings when data was discarded by `max-age`/`max-size` or the recording is unbounded. In-memory recordings (`disk=false`) have no chunks and fail with "holds no data in the window".

**`netty-demo`.** A Netty service with injected pathologies (`Scenario`, `RequestHandler`, `Background`), recorded in-process by `Recorder`; the walkthrough is `docs/TUTORIAL.md`. It is the only module with third-party dependencies.

## Coding rules for this repo

- `CODING_GUIDELINES-SEP-18.md` is the coding standard; cite rules by `G-<section>.<n>`. It was applied to the per-event path on 2026-09-18 (see §12 for what transfers, what was applied and what deliberately was not); the report/rendering layer (`Text`, `Html`, the report records) is final reporting and stays on `java.util`, `String.format` and `Optional` by the guidelines' own exemption (G-2.3).
- Per-event allocations that remain are the `Sample`/`Block`/`Wait` records and their `Interval`s (the analysis API and its tests are built on those records) and the `Instant` inside `RecordedEvent.getStartTime()`/`getEndTime()` (the only way the JDK exposes event timestamps). Everything else on the per-event path is a probe into a pre-allocated table: thread filter verdicts, lock and peer detail strings, idle verdicts per frame, event kinds per `EventType`.
- The guidelines doc, and anything derived from it here, must stay standalone: no references to the codebase they were distilled from, every example original.
- Anything a blocking event or pause explains is exact; anything resting on samples alone is bounded by the measured sampling cadence (`docs/DESIGN.md` §4.3). Do not add a sample-based verdict without the cadence check.
