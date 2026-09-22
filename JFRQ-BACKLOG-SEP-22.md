# jfrq backlog from the Houston/Edge 2 round, 2026-09-22

Source: `HOUSTON-EDGE2-JFRQ-SEP-22.md` §4, written after using `jfrq` / `jfrq-live` against a
21-hour-old Edge 2 node under load. All thirteen items shipped; what is left is on this page.

## Open

- **`--min` and `--gap` now apply to the in-window part of a wait or a block** (T3). Both follow
  from counting only what happened inside the recording, and both change what a command returns.
  `USAGE` and `README.md` still describe `--min` as "ignore waits shorter than D" without the
  qualifier, because adding it to a one-line option description seemed worse than the small
  imprecision. Miguel's call.
- **A second round on a fixed Edge**, which is the only thing that shows whether the tool now
  finds the next E1 on its own. The first round's own "not covered" list bounds it: only OPC UA
  adapters were exercised, no southbound or MQTT load ran concurrently, and the v2 path was never
  allocation-profiled. Worth re-running `alloc --sites`, `locks` and `stalls` on a node where
  E1/E2 are fixed, to confirm the 67 % headline site is gone and that nothing else was hiding
  behind it.
- **The waiting-for-work pattern list is a judgement call, not a measurement** (T1, T8). It names
  the pool's own idle frame — `ThreadPoolExecutor.getTask`, `ForkJoinPool.awaitWork`,
  `ForkJoinPool.managedBlock`, `DelayedWorkQueue.take`, Netty's `SingleThreadEventExecutor.takeTask`,
  logback's `AsyncAppenderBase$Worker.run` — and deliberately not the queue class, because a
  request thread waiting for a reply on a `SynchronousQueue` looks identical one frame up. A
  framework with its own worker loop will need adding; `--idle` replaces the list and
  `--idle none` turns the split off, so nothing is hidden that a flag cannot bring back.

## What shipped

| # | Item | Commit |
|---|---|---|
| T3 | every wait and stall counted only for the part inside the span; `stalls` had the same defect | `cd45013` |
| T7a | the calibration line says what share of the estimate it validates | `1f1dcdc` |
| T8, T1 | a worker parked on its own queue is not a stall and not contention; `--idle`, `--idle none` | `12e1787` |
| T2 | a stack for every lock row, and `--lock` to ask about one lock | `26c83a6` |
| T4, T9, T6 | name lists capped; thresholds and throttles derived from the file; `info` lists thread families | `ea46524` |
| T5 | allocation sites that print identically are one row | `4fb9386` |
| T10, T11 | `jfrq-live start` echoes its settings; `--timing` splits parse from analyse; HTML dark mode | `65439d0` |
| T7b | per-row sample counts in `alloc` and in `--baseline` | `dae7b6f` |

Measured on the same 1.3 MB live dump before and after T7b: parse 54.3 ms → 57.2 ms, analyse
0.62 ms → 1.00 ms (`--timing`). The other twelve items are in the report and render layer and cost
nothing on the read path.

Verified on a real `jfrq-live delta` dump of the demo under load: `Blocked` fell from 33.3 s to
10.8 s across the same 22.6 s window as a 22.5 s idle RMI park moved to `WAITING FOR WORK`, and the
lock that matters is rank 1 with `SessionRegistry.touch:29` under it.

## Do not regress

Named in the feedback as what made the findings possible, and each now pinned by a test:

- `alloc --sites` naming an application line in one command.
- The measured sampling-cadence warning (`StallAnalysis`), quoted as "the single most honest thing
  any profiler has told me".
- `stalls` eliding the middle of a stack but keeping the first application frame (`Stack.pretty`).
- The `Dumped / Window / Cursor` triple, and the cursor chaining dumps with no overlap and no gap.
- `jfrq-live` attaching to a production-shaped JVM and starting a correctly configured recording in
  a few seconds, no restart.
