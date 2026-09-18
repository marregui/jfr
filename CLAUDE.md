# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`jfrq`: a JDK 25 command-line tool that asks a JFR recording one question (`stalls`, `locks`, `alloc`, `info`) and answers it in text or a self-contained HTML report. Version 0.1.0, `group = dev.jfrq`. `README.md` is the user-facing description; `docs/DESIGN.md` explains each detector, the JFR events it reads, the evidence hierarchy and the performance history — read it before changing a verdict or trusting an unexpected result.

## Build and test

Gradle (Kotlin DSL), three modules, no convention plugins: each `*/build.gradle.kts` is self-contained on purpose. JDK 25 toolchain is required; `core` has no runtime dependency beyond `jdk.jfr`.

```
./gradlew build                       # compile + tests + JaCoCo coverage gates (85 % core, 80 % cli)
./gradlew installDist                 # cli/build/install/jfrq/bin/jfrq, netty-demo/build/install/netty-demo/bin/netty-demo
./gradlew :core:test                  # one module
./gradlew :core:test --tests 'dev.jfrq.core.stalls.StallAnalysisTest'
./gradlew :core:test --tests '*StallAnalysisTest.methodName'
```

- Compilation runs with `-Xlint:all -Werror`: any warning fails the build.
- `check` depends on `jacocoTestCoverageVerification`; dropping coverage below the module gate fails `build`.
- Configuration cache, parallel and build cache are on (`gradle.properties`).
- Tests in `core/.../jfr/RecordingTest`, `cli/.../MainTest` and the stall/lock tests make **real JFR recordings in-process** (`JfrFixtures.record`), so they take seconds and assert verdicts and orders of magnitude, not exact durations. Pure-logic tests (`StallAnalysisTest`, `ContentionReportTest`, `AllocationTest`) use hand-built timelines with exact expectations.

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

`Sink` lifecycle: `begin(Interner)` → `accept(RecordedEvent)` × N → `finish(RecordingInfo)`.

**Per command:** a collector (sink) and a report.

| Command | Sink | Report / analysis |
|---|---|---|
| `alloc` | `alloc/AllocationCollector` | `AllocationReport`, `AllocationDiff` (`--baseline`, compares rates; both files parsed concurrently on virtual threads) |
| `locks` | `locks/ContentionCollector` | `ContentionReport` (holder walk-back through `previousOwner`, convoy search) |
| `stalls` | `stalls/StallCollector` | `StallAnalysis` + `IdleMatcher` + `Timeline` → `StallReport` |
| `info` | everything | `RecordingInfo` |

**Model (`core/model`).** Timestamps are epoch nanoseconds in `long`; `Interval` is half-open `[start, end)`. `Interner` dedups `Stack`/`Frame`/`ThreadRef` by identity of the JDK's constant-pool objects (a `RecordedObject` field lookup is a linear by-name scan, so every resolution is done once); its caches are bounded and rebuilt when full. `Stack` is a class with a cached hash, not a record, so aggregation maps compare by identity first. `core/jfr/Events` is the only place that reads fields off a `RecordedEvent`.

**Rendering.** Text (`cli/Text`, `core/util/TextTable`) and HTML (`core/report/Html`) are both derived from the same report objects; HTML is built behind a supplier only when `--html` is given.

**CLI (`cli/Main`, `cli/Args`).** Hand-rolled parsing, no library. Exit codes: 0 ok, 1 recording unreadable, 2 usage error. `Main` holds the `USAGE` text; keep it in sync with `README.md` when an option changes. The launcher adds `-XX:+AutoCreateSharedArchive` so an AppCDS archive lands in `lib/jfrq.jsa` after the first run (`cli/build.gradle.kts` patches the start script for `$APP_HOME`).

**`netty-demo`.** A Netty service with injected pathologies (`Scenario`, `RequestHandler`, `Background`), recorded in-process by `Recorder`; the walkthrough is `docs/TUTORIAL.md`. It is the only module with third-party dependencies.

## Coding rules for this repo

- `CODING_GUIDELINES-SEP-18.md` is the standard for a planned zero-allocation refactor of the per-event path; cite rules by `G-<section>.<n>`. §12 lists where the code currently departs (java.util collections, `Duration`/`Instant`, records and streams on the per-event path) — that list came from a skim, verify before acting on it.
- The guidelines doc, and anything derived from it here, must stay standalone: no references to the codebase they were distilled from, every example original.
- Anything a blocking event or pause explains is exact; anything resting on samples alone is bounded by the measured sampling cadence (`docs/DESIGN.md` §4.3). Do not add a sample-based verdict without the cadence check.
