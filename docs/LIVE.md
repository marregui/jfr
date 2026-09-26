# Asking a running JVM: `jfrq-live`

`jfrq` reads a finished file. A JVM that is recording has no finished file: the recording
it writes ends in a chunk that is still open, and the JDK parser waits on that chunk
forever, so `jfrq` refuses it ([DESIGN.md](DESIGN.md), section 6). To watch something
evolve while the application runs, each question is asked of a dump instead, and
`jfrq-live` is the loop that takes the dumps: it attaches to the JVM, asks its flight
recorder for a window of data, writes the window to a file, checks what the file holds
against what was asked, and runs the `jfrq` question on it.

```
jfrq-live 4242 start --max-age 10m                            # a recording in the JVM, bounded
jfrq-live 4242 full  -- stalls --thread 'event-loop-*'         # everything so far
jfrq-live 4242 delta -- stalls --thread 'event-loop-*'         # what happened since
jfrq-live 4242 delta --out t2.jfr -- alloc --baseline t1.jfr   # the rate change between two windows
jfrq-live 4242 again -- locks                                  # the previous window, another question
jfrq-live 4242 stop
```

The module is `live/`; `./gradlew installDist` puts the launcher at
`live/build/install/jfrq-live/bin/jfrq-live`. It needs nothing beyond the JDK:
`jdk.attach` to reach the process and `jdk.management.jfr` to drive its recorder.

## 1. The loop

Every command takes the JVM's pid first. A `jfrq` command after `--` is run on the dump
with the file inserted for you, so `-- stalls --thread 'x'` becomes
`jfrq stalls <dump> --thread 'x'`.

| Command | What it does | Cursor |
|---|---|---|
| `status` | The JVM's recordings (id, name, state, since when, bounds) and the cursor kept for it. | reads |
| `start` | Starts a recording: the JDK `profile` settings with the thresholds [README.md](../README.md) recommends, or `--settings NAME` for another JDK profile treated the same way, or `--settings FILE.jfc` taken as it is. `--max-age`, `--max-size`, `--name`. | |
| `bound` | Sets `--max-age` and/or `--max-size` on the running recording; `0` removes a bound. | |
| `full` | Dumps everything the recording holds. | set to the dump's end |
| `delta` | Dumps what happened since the cursor. | moved to the dump's end |
| `again` | Dumps the previous window once more. | unchanged |
| `stop` | Stops and closes the recording; the JVM discards its data, unless the recording has a destination (`-XX:StartFlightRecording=filename=...`), where the JVM writes it before closing it. | |

`--recording ID\|NAME` picks the recording when the JVM runs several; with one running
recording nothing needs saying. A name two recordings share is a usage error that lists
their ids, and `start` refuses a name the JVM already has. `--out FILE` names the dump,
replacing a file of that name; the default, `<pid>-<command>-<HHmmss.SSS>Z.jfr` in the
current directory, is stamped to the millisecond in UTC and never replaces a file. A dump is
readable by its owner only (mode 0600): a recording can hold command lines, environment
variables and system properties. `--state DIR` is where cursors live (default
`~/.jfrq/live`).

Everything that can be checked without the JVM is checked before attaching: the options,
and the `jfrq` question after `--` with the dump standing in as its recording. A typo
there costs no dump and does not move the cursor.

A dump prints three lines before the question's answer:

```
Dumped     t1.jfr  804 KB, 1 chunk, 12:23:21.688Z .. 12:23:31.077Z (9.39 s)
Window     12:23:21.689Z .. now (since the previous dump)
Cursor     next delta from 12:23:31.078Z; last window 12:23:21.689Z .. 12:23:31.077Z (~/.jfrq/live/4242.properties)
```

With `--json` in the question after `--`, these lines go to standard error instead, so
that standard output is the JSON document alone.

`Dumped` is what the file holds, read from its chunk headers (the ones `jfrq info`
starts from; the events are not parsed): size, chunks, span. `Window` is what was asked. When they differ by more than a second a line
says why (section 4). `Cursor` is where the next `delta` starts.

`start` prints what it configured — the profile it started from and every threshold,
throttle and sample period it overlaid — because the operator is at the keyboard at that
moment and the only other way to check is `jfrq info` on the first dump, one dump later.
A `--settings FILE.jfc` is taken as it is and the line says so rather than listing an
overlay that was not applied.

## 2. How a dump is taken

This is what `jcmd <pid> JFR.dump` does, driven over JMX instead of a diagnostic command:

1. **Clone the recording and stop the clone.** Stopping seals the chunk the JVM is
   writing at that instant `T` and starts a fresh chunk at the same `T`; the sealed chunk
   goes to the clone. The newest events are therefore in a finished chunk, and the
   recording itself keeps running untouched.
2. **Stream the clone's chunks that overlap the window** into the file, in 1 MB blocks
   over the local JMX connector. The JVM selects whole chunks: every chunk whose
   `[start, end]` touches `[begin, end]` of the window. Events are never cut.
3. **Close the clone.** Nothing is left behind in the JVM, and a dump cut short by
   Ctrl-C or `SIGTERM` leaves nothing either: a shutdown hook, in place from before the
   clone exists until it is closed, removes the partial `.part` file and closes the clone.
   The hook waits for the JVM at most five seconds, so a target that does not answer
   (stopped with `SIGSTOP`, hung) cannot keep `jfrq-live` from exiting; the clone it still
   holds is then named on standard error with the command that closes it. A stop that lands
   while the JVM is still making the clone waits for its answer the same way, then closes
   the clone; if no answer comes, the line names the recording being cloned instead. Only `SIGKILL`,
   or a JVM that does not answer, can leave a stopped clone, which pins its chunks until
   the JVM exits; `status` lists it and `stop --recording <id>` closes it (a recording that
   is not running is closed, not stopped).

The stop instant `T` is the clone's stop time, which the JVM reports in milliseconds,
and it is the exact end of the last chunk in the file. `full` and `delta` record it.

**Why the cursor is `T + 1 ms`.** The next chunk starts at the same instant `T` as the
sealed one ends, and chunk selection is inclusive at both ends, so a delta beginning at
`T` would get the sealed chunk again. One millisecond past the millisecond the JVM
reported is strictly after the sealed chunk's end and strictly inside the next chunk,
whatever the sub-millisecond part of `T` was. A delta therefore holds exactly the chunks
sealed after the previous dump: no event twice, none lost. `LiveTest.theLoop` asserts
this against a real recording: the delta's first chunk starts where the full dump's last
chunk ended.

**`again`** re-asks the last window, `[begin, T]`, from the current state of the
recording: the same chunks, unless the JVM has since discarded some of them (section 3).
The chunk that started at `T` is excluded by the same millisecond argument in reverse.
After a `full`, `begin` is where the full dump's file began, so the span check of an
`again` can tell when the JVM has discarded part of it since.

## 3. Bounds: `--max-age` and `--max-size`

Without a bound a recording keeps every chunk since it started. Each `full` dump is then
bigger than the last, and a JVM left recording overnight has hours of chunks on disk.
`jfrq-live` prints a `WARNING` on `start` and on every `full` dump while the recording
is unbounded, judged by the bounds the JVM reports, so `start --max-age 0` warns as a
`start` without bounds does. `bound` fixes it without restarting anything:

```
jfrq-live 4242 bound --max-age 10m
jfrq-live 4242 bound --max-age 10m --max-size 500MB
```

The JVM applies a bound at chunk rotation: when a chunk is sealed, the oldest chunks
whose end is older than `max-age`, or that push the total past `max-size`, are dropped.
Whole chunks, so the recording keeps a little more than the bound says. A `delta` is
unaffected as long as the previous dump is younger than `max-age` and what was recorded
since fits in `max-size`: the chunks it needs were sealed after the previous dump. A
`full` dump gives the bounded window rather than the recording's whole life, and an
`again` for a window older than the bound comes back shorter, which the span check
reports.

Sizes are decimal (`200MB` is 200 000 000 bytes), as every size `jfrq` prints;
`--max-age` takes a unit (`10m`, `1h`). `0`, or `--max-age infinity`, removes a bound;
`bound --max-age 0` on a recording that has no `max-size` makes it unbounded again. The
JVM keeps the age bound in whole seconds: an age under a second is a usage error (the JVM
would take it as zero, which means no bound), and a fraction is rounded up to the next
second with a note saying so. Ten minutes of `max-age` is enough for a loop whose deltas
are a minute apart and leaves a `full` dump that answers "what happened
recently" without being a gigabyte.

## 4. The span check

After every dump the file is read back and its span compared with the window:

| Line | Meaning |
|---|---|
| `Note  the file starts D before the window` | The first chunk that overlaps the window started earlier than the window did. Expected for a window that did not begin at a dump boundary; a dump boundary is where every `delta` begins, so a delta never says this. |
| `WARNING  the file starts D after the window: the JVM had already discarded that data (max-age ..., max-size ...)` | The window's beginning is gone from the JVM. A `delta` older than the bound, or an `again` for a window that has aged out. The answer covers what is left. |
| `WARNING  the file starts D after the window: the recording started at ..., after the window began` | The recording never held the window's beginning: it was stopped and started again (or another one picked) since the cursor was set. |
| `Note  the file ends D after the window` | `again` only, and only when a chunk boundary fell inside the last millisecond of the window: the chunk after it was taken too. |
| `WARNING  the recording has no bound` | `full` on an unbounded recording (section 3). |
| `WARNING  the file is truncated` / `its last chunk is not finished` | A damaged dump, as the chunk headers show it; `jfrq` reports the same. |

Differences under a second are chunk granularity and are not reported.

## 5. The cursor file

One properties file per pid under the state directory:

```
jvm=1789993351707                    # the JVM's start time: a reused pid after a restart is a new JVM, no cursor
cursor=2026-09-21T12:23:49.945Z      # where the next delta begins (T + 1 ms)
begin=2026-09-21T12:23:31.078Z       # the last window, for `again`; after a full dump, where its file began
end=2026-09-21T12:23:49.944Z
```

Times are UTC instants; the tool prints them in local time. Deleting the file resets the
loop; so does restarting the JVM. A file that cannot be parsed is reported as damaged,
with the advice to delete it.

A save writes a temporary file beside the cursor and renames it over the old one, so a
reader sees one cursor or the other, never half of one. Two `jfrq-live` processes dumping
the same JVM take turns: a dump holds a lock on `<pid>.lock` in the same directory from
reading the cursor to advancing it, and the second one says it is waiting.

## 6. Requirements and what can go wrong

- **Same user, attach allowed.** Attaching needs the same OS user as the target and a
  target not started with `-XX:+DisableAttachMechanism`. The target's runtime must carry
  `jdk.management.agent` (every full JDK; a jlinked image may leave it out).
- **The first attach starts the JVM's local management agent**, which can take a few
  seconds; later attaches reuse it. A macOS host whose name does not resolve makes the
  JDK's own hostname lookup time out inside that start; `jfrq-live` avoids the same
  lookup on its side by naming its RMI endpoint `127.0.0.1`, but cannot do so for the
  target. The local connector talks over loopback whatever the name says.
- **No running recording**: `full` and `delta` need one. `start` one, or run the JVM
  with `-XX:StartFlightRecording` (the [README.md](../README.md) line is the right
  settings; add `maxage=10m`).
- **Several running recordings**: name one with `--recording`. A JVM often has a
  continuous recording next to an on-demand one; `status` lists them.
- **In-memory recordings** (`disk=false`) have no chunks for a clone to hand over, so a
  dump of one fails with "holds no data in the window" and leaves no file. Recordings
  `start` makes are to disk, as are `-XX:StartFlightRecording` ones by default.

Exit status: 0 on success; 1 when the JVM cannot be attached, refuses the operation, the
connection fails, or the dump cannot be written or read back; 2 on a usage error,
including an ambiguous `--recording NAME`, a duplicate `start --name` and an invalid
question after `--`. A `jfrq` command after `--` sets the status once the dump succeeded.
A connection that does not close cleanly after a successful command is a warning on
stderr, not a failure.

## 7. On the demo

```
netty-demo --scenario lock --duration 5m --no-jfr &      # the service, recording nothing itself
jfrq-live $! start --max-age 2m
jfrq-live $! full  --out t0.jfr -- stalls --thread 'event-loop-*'
sleep 30
jfrq-live $! delta --out t1.jfr -- locks
jfrq-live $! delta --out t2.jfr -- alloc --baseline t1.jfr
jfrq-live $! status
jfrq-live $! stop
```

The first `full` shows the `SessionRegistry` convoy that [TUTORIAL.md](TUTORIAL.md)
walks through; each `delta` shows whether it is still there, in the window since the
last look, without ever stopping the service or its recording.
