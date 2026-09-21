# jfrq

Ask a JFR recording one question and get the answer.

```
$ jfrq stalls app.jfr --thread 'event-loop-*' --gap 50ms

STALLS >= 50.0 ms: 46 found, showing 3, longest first
   1  event-loop-3-2         +0.492s    173 ms  BLOCKED_MONITOR blocked on monitor dev.jfrq.demo.SessionRegistry@82c34db20 held by housekeeper (handed on through event-loop-3-1)
        at dev.jfrq.demo.SessionRegistry.touch(SessionRegistry.java:26)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:51)
        ...
```

## What JFR is

JFR is Java Flight Recorder: the JVM's built-in, low-overhead event recorder. It lives
inside HotSpot (JEP 328, open-sourced in JDK 11) and writes a binary `.jfr` file of
timestamped events, which is analysed offline.

What it records:

- Periodic samples: which threads are running and their stack traces
  (`jdk.ExecutionSample`, `jdk.NativeMethodSample`). This is the profiling side.
- Blocking events with exact durations: monitor contention (`jdk.JavaMonitorEnter`),
  waits, parks, socket and file I/O, safepoints, GC pauses; each with the thread, the
  stack and the object involved.
- Allocation events (`jdk.ObjectAllocationSample`), GC and heap statistics, thread start
  and end, compiler activity, OS and CPU load, JVM and GC configuration.
- Anchor metadata: the settings and thresholds that were active (`jdk.ActiveSetting`),
  so a reader knows what the recording could and could not see.

How it works:

- Per-thread buffers in the JVM, flushed to disk in chunks. Overhead is typically 1-2 %
  because the JVM emits the events itself; no agent instruments bytecode.
- Threshold-based: durations below a per-event threshold (20 ms for monitor enter in the
  default profile) are dropped, so the absence of an event is not the absence of the
  behaviour.
- Started with `-XX:StartFlightRecording`, `jcmd <pid> JFR.start`, or the `jdk.jfr` API
  in-process (which is what `netty-demo` does).
- Read with `jfr print` and `jfr summary`, JDK Mission Control, or programmatically via
  `jdk.jfr.consumer.RecordingFile` and `EventStream`; the latter is what `jfrq` uses.

`jfrq` exists because raw JFR output is a firehose of events with no verdict. It streams
the file once and turns those events into one answer per question.

JDK Mission Control shows you the data; `jfr view` shows you aggregate tables. Neither
tells you *why the event loop stopped at 14:03:07*, *who held the lock that thread was
waiting for*, or *what changed between the recording before the fix and the one after*.
`jfrq` answers those three questions and nothing else.

| Command | Question it answers |
|---|---|
| `jfrq stalls` | When did a thread not return to its idle point, and why: a lock (and who held it), a blocking socket or file call, a sleep, a GC pause, or CPU-bound code? |
| `jfrq locks` | Which locks did threads wait for, how long, who held them, and which holders were themselves blocked (convoys)? |
| `jfrq alloc` | Which threads, classes and sites allocate, in bytes per second; and with `--baseline`, what changed between two recordings? |
| `jfrq info` | What is in the file: span, threads, event counts, and the thresholds and periods that were active when it was made. |

Common options: `--top N`, `--html FILE`, `--timing`.

Every command prints plain text for a terminal or a ticket, and writes a self-contained
HTML report with `--html`. A 40 MB recording is answered in about a quarter of a second;
`--timing` shows where the time went ([docs/DESIGN.md](docs/DESIGN.md), section 8).

What it will not do is guess. Every number that rests on sampling says so and says how
far the sampling can be trusted; the allocation estimate is printed next to the JVM's own
counters; a truncated file is read as far as it goes and every report says where it
stops; a file still being written is refused with the command that produces a readable
one, instead of the parser hanging on it.

## Requirements

- JDK 25 to build and run (`jdk.jfr` is part of the JDK; the tool has no other dependency).
- Recordings from any JDK 17+ JVM. Allocation analysis prefers `jdk.ObjectAllocationSample`
  (JDK 16+) and falls back to the TLAB events on older files.

## Build and install

```
./gradlew build          # compiles, runs 130+ tests, checks coverage
./gradlew installDist    # cli/build/install/jfrq/bin/jfrq, live/build/install/jfrq-live/bin/jfrq-live,
                         # netty-demo/build/install/netty-demo/bin/netty-demo
```

Put `cli/build/install/jfrq/bin` (and `live/build/install/jfrq-live/bin`) on your `PATH`,
or call the scripts by path.
The launcher needs `JAVA_HOME` or a `java` on the `PATH` that is JDK 25. The first run
writes an AppCDS archive to `lib/jfrq.jsa` next to the jars, which makes every later run
start in about a tenth of a second; if the directory is not writable nothing is written
and start-up is merely ordinary.

## Usage

```
jfrq info   recording.jfr
jfrq alloc  recording.jfr [--baseline before.jfr] [--top N] [--sites] [--html out.html]
jfrq locks  recording.jfr [--min 10ms] [--thread GLOB] [--top N] [--html out.html]
jfrq stalls recording.jfr --thread GLOB [--gap 50ms] [--idle REGEX,...] [--top N] [--html out.html]
```

`GLOB` is a comma-separated list of shell globs on thread names: `'event-loop-*'`,
`'nioEventLoopGroup-*,worker-?'`. `jfrq info` lists the names in a file. Durations take
a unit (`50ms`, `1.5s`, `2m`); options belong to their command, so a `stalls` option on
`locks` is an error rather than silently ignored.

Every stall says how it was found: nothing after the detail means a blocking event,
exact to its timestamps; `[samples]` means a run of sampler observations; `[silence]`
means an absence of samples explained by what covered it.

Exit status is 0 on success, 1 when the recording cannot be read (missing, not a
recording, truncated inside its first chunk, still being written, or the HTML report
cannot be written), 2 on a usage error.

## Recording for jfrq

The JDK's `profile` settings are a good start. Lower the blocking thresholds so short
waits are in the file, and raise the allocation sample rate:

```
java -XX:StartFlightRecording=filename=app.jfr,settings=profile,\
jdk.JavaMonitorEnter#threshold=1ms,jdk.ThreadPark#threshold=1ms,jdk.ThreadSleep#threshold=1ms,\
jdk.SocketRead#threshold=1ms,jdk.SocketWrite#threshold=1ms,jdk.FileRead#threshold=1ms,jdk.FileWrite#threshold=1ms,\
jdk.SocketRead#throttle=off,jdk.SocketWrite#throttle=off,jdk.FileRead#throttle=off,jdk.FileWrite#throttle=off,\
jdk.ExecutionSample#period=10ms,jdk.NativeMethodSample#period=10ms,\
jdk.ObjectAllocationSample#throttle=1000/s \
-jar app.jar
```

`jfrq stalls` and `jfrq locks` print the thresholds that were active, because a 20 ms
monitor threshold means no wait shorter than 20 ms exists in the file, whatever the
application did. The `profile` settings also throttle socket and file events to 300 per
second across the JVM (JDK 25); `jfrq stalls` warns when a throttle is in force, and the
line above switches it off so no blocking call goes unrecorded.

## On a running JVM

A recording the JVM is still writing cannot be read in place (its last chunk is open), so
`jfrq-live` takes a dump of a window and asks the question of that: `full` for everything
the recording holds, `delta` for what happened since the previous dump, `again` for the
previous window once more. A cursor per JVM remembers where the last dump stopped, every
dump prints its real span next to the window asked for, and `start`/`bound` put a
`--max-age`/`--max-size` on the recording so it does not grow without end.

```
jfrq-live 4242 start --max-age 10m
jfrq-live 4242 full  -- stalls --thread 'event-loop-*'
jfrq-live 4242 delta -- stalls --thread 'event-loop-*'
jfrq-live 4242 delta --out t2.jfr -- alloc --baseline t1.jfr
```

[docs/LIVE.md](docs/LIVE.md) explains how a dump is taken, why a delta never counts an
event twice, and what the bounds do.

## Try it on the demo

The `netty-demo` module is a small Netty service with four deliberately injected
event-loop pathologies, recorded with JFR. [docs/TUTORIAL.md](docs/TUTORIAL.md) walks
through every one of them and shows what `jfrq` says about each.

```
netty-demo --scenario lock --duration 15s --out demo-lock.jfr
jfrq stalls demo-lock.jfr --thread 'event-loop-*'
jfrq locks  demo-lock.jfr
```

## How it works, and what it cannot see

[docs/DESIGN.md](docs/DESIGN.md) explains the detectors, the JFR events each one reads,
and the limits that follow from how the JFR sampler works. The short version: anything a
blocking event or a pause event explains is exact; anything that rests on samples alone
is only as good as the sampling cadence, and `jfrq` measures that cadence and warns when
it is too coarse to trust.

## Layout

```
core/         the analyses, one pass over the file, no dependencies
cli/          the jfrq command: argument parsing and text rendering
live/         the jfrq-live command: dumps from a running JVM, the cursor, the span check
netty-demo/   the demo service and its scenarios
docs/         TUTORIAL.md, DESIGN.md, LIVE.md
```

## Status

0.1.0. The command-line interface and the report layouts may change. The verdicts and
the numbers behind them are tested against synthetic timelines and against real
recordings made in the test suite.

## License

GNU Affero General Public License v3.0 ([LICENSE](LICENSE)). Copyright remains with the
author, who can offer other terms for commercial use; ask.
