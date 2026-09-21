# jfrq-live

Ask a running JVM's flight recording one question at a time.

`jfrq` reads a finished `.jfr` file. A JVM that is recording has no finished file: the
chunk it is writing is open, and the JDK parser waits on it forever. `jfrq-live` attaches
to the JVM, takes a dump of a time window (a finished file), checks what the dump holds
against what was asked, and runs the `jfrq` question on it. A cursor per JVM remembers
where the last dump stopped, so the next one can be a delta.

```
$ jfrq-live 4242 start --max-age 10m
JVM        4242@host, started 2026-09-21 14:22:31, 25.0.4.1+1-LTS
Recording  1   jfrq-live        RUNNING  since 14:23:07.334, max-age 10m00s, to disk

$ jfrq-live 4242 full -- stalls --thread 'event-loop-*'
Dumped     4242-full-142321.jfr  1.03 MB, 1 chunk, 14:23:07.334 .. 14:23:21.688 (14.4 s)
Window     the start .. now (everything the recording kept)
Cursor     next delta from 14:23:21.689; last window the start .. 14:23:21.688 (~/.jfrq/live/4242.properties)

STALLS >= 50.0 ms: 42 found, showing 15, longest first
   1  event-loop-3-2  +9.798s  168 ms  BLOCKED_MONITOR blocked on monitor dev.jfrq.demo.SessionRegistry@714e97100 held by housekeeper
   ...

$ jfrq-live 4242 delta -- stalls --thread 'event-loop-*'      # only what happened since
$ jfrq-live 4242 delta --out t2.jfr -- alloc --baseline t1.jfr # rate change between two windows
$ jfrq-live 4242 again -- locks                                # the previous window, another question
$ jfrq-live 4242 stop
```

## Requirements

- JDK 25 to build and run. The module needs nothing beyond the JDK: `jdk.attach` to reach
  the process, `jdk.management.jfr` to drive its recorder over the local JMX connector.
- The target JVM: any JDK 17+ runtime that carries `jdk.management.agent` (every full JDK;
  a jlinked image may leave it out), started by the same OS user, not started with
  `-XX:+DisableAttachMechanism`.

## Build and run

```
./gradlew installDist
live/build/install/jfrq-live/bin/jfrq-live --help
```

Put `live/build/install/jfrq-live/bin` on your `PATH`, or call the script by path. The
launcher needs `JAVA_HOME` or a `java` on the `PATH` that is JDK 25.

Try it on the demo service in this repository, which runs without a recording of its own
when told to:

```
netty-demo/build/install/netty-demo/bin/netty-demo --scenario lock --duration 5m --no-jfr &
jfrq-live $! start --max-age 2m
jfrq-live $! full  -- stalls --thread 'event-loop-*'
sleep 30
jfrq-live $! delta -- locks
jfrq-live $! stop
```

The first attach to a JVM starts its local management agent, which can take a few
seconds; later attaches reuse it.

## Usage

```
jfrq-live <pid> <command> [options] [-- <jfrq command> [jfrq options]]
```

| Command | What it does |
|---|---|
| `status` | The JVM's recordings (id, name, state, since when, bounds) and the cursor kept for it. |
| `start` | Starts a recording: the JDK `profile` settings with the thresholds the top-level README recommends, or `--settings NAME` for another JDK profile treated the same way, or `--settings FILE.jfc` taken as it is. |
| `bound` | Sets `--max-age` and/or `--max-size` on the running recording; `0` removes a bound. |
| `full` | Dumps everything the recording holds; the cursor moves to the dump's end. |
| `delta` | Dumps what happened since the cursor; the cursor moves to the dump's end. |
| `again` | Dumps the previous window once more; the cursor stays. |
| `stop` | Stops and closes the recording; the JVM discards its data. |

| Option | Meaning |
|---|---|
| `--recording ID\|NAME` | Which recording, when the JVM runs more than one. |
| `--out FILE` | Where the dump goes (default `<pid>-<command>-<HHmmss>.jfr` in the current directory). |
| `--state DIR` | Where cursors are kept (default `~/.jfrq/live`). |
| `--max-age D` | Keep this much recent data: `10m`, `1h` (`start`, `bound`). |
| `--max-size SIZE` | Keep this much data: `200MB`, `1GB`; decimal units (`start`, `bound`). |
| `--settings NAME\|FILE` | JDK profile name (`default`, `profile`) or a `.jfc` file (`start`). |
| `--name NAME` | The recording's name (`start`; default `jfrq-live`). |

Everything after `--` is a `jfrq` command and its options, run on the dump with the file
inserted for you: `-- stalls --thread 'x'` becomes `jfrq stalls <dump> --thread 'x'`.
Only `full`, `delta` and `again` take one.

Exit status: 0 on success; 1 when the JVM cannot be attached, refuses the operation, or
the dump cannot be written or read back; 2 on a usage error. With a `jfrq` command after
`--`, its status is returned once the dump succeeded.

## Reading the output

Every dump prints three lines before the answer. `Dumped` is what the file holds (size,
chunks, span), read back with the same code as `jfrq info`. `Window` is what was asked.
`Cursor` is where the next `delta` starts. When the file and the window differ by more
than a second, a line says why:

- `Note  the file starts D before the window`: the JVM hands over whole chunks and the
  window did not begin at a dump boundary (never on a `delta`).
- `WARNING  the file starts D after the window: the JVM had already discarded that data`:
  the window's beginning aged out under `max-age` or `max-size`; the answer covers what
  is left.
- `WARNING  the recording has no bound`: a `full` dump of a recording that keeps every
  chunk since it started; `bound --max-age 10m` fixes it without restarting anything.

## How it works, in one paragraph

A dump is what `jcmd <pid> JFR.dump` does, driven over JMX: clone the recording and stop
the clone, which seals the chunk being written at instant `T` (so the newest events are
in a finished chunk) and leaves the recording running; stream the clone's chunks that
overlap the window into the file; close the clone. The cursor is `T + 1 ms`: the next
chunk starts at the same `T`, chunk selection is inclusive, and the JVM reports `T` in
milliseconds, so one millisecond past it is strictly inside the new chunk. A delta is
therefore exactly the chunks sealed since the previous dump. In-memory recordings
(`disk=false`) have no chunks to hand over and are refused.
[docs/LIVE.md](../docs/LIVE.md) has the full account: the dump mechanics, the bounds,
the span check, the cursor file and what can go wrong.

## Layout

```
Live.java      the command line: parsing, the commands, the span check
Jvm.java       attach to the process, connect to its FlightRecorderMXBean
Snapshot.java  one dump: clone, stop, stream the window, close
Cursor.java    the per-JVM state file: next delta start, last window
Window.java    a [begin, end] pair, either end open
```

Tests attach to the JVM they run in (`-Djdk.attach.allowAttachSelf=true` is set by the
build) and run the whole loop against a recording started there.
