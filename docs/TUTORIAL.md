# Tutorial: finding what stalled the event loop

This walkthrough uses the `netty-demo` module: a small Netty service with a paced load
generator and four injected bugs, each selectable as a scenario. You record each scenario
with JFR, then ask `jfrq` what happened. Every output below is real, captured on a
MacBook with JDK 25; your numbers will differ, the shape will not.

Time needed: about fifteen minutes.

## 0. Build

```
./gradlew installDist
export PATH="$PWD/cli/build/install/jfrq/bin:$PWD/netty-demo/build/install/netty-demo/bin:$PATH"
```

Both launchers run the JVM in `JAVA_HOME` when it is set, and the `java` on the `PATH`
when it is not; either way it must be JDK 25.

## 1. The demo service

`netty-demo` starts:

- a Netty server with two event loops named `event-loop-*` and a line protocol
  (`REQ n` in, `OK n` out);
- eight client connections, each sending 200 requests per second in a closed loop and
  measuring the latency of every reply;
- depending on the scenario, a slow backend, a housekeeping thread, a persistence flusher,
  or two bulk allocators;
- an in-process JFR recording with the thresholds `jfrq` likes (1 ms for blocking events,
  no throttling of socket and file events, 10 ms sampling, 1000 allocation samples per
  second).

```
netty-demo --scenario lock --duration 15s --out demo-lock.jfr
```

The scenarios:

| Scenario | The bug | What the loop does |
|---|---|---|
| `lock` | a `synchronized` registry that a housekeeping thread holds for 150 ms every 500 ms, while itself flushing to a store whose lock a flusher thread holds | blocks entering the registry on every request |
| `blocking-io` | a synchronous lookup against a slow backend, on the event loop | blocks in `Socket.read` for 120–220 ms, one request in 400 |
| `cpu` | a 120 ms computation on the event loop | runs `CpuWork.burn` without yielding, one request in 1000 |
| `alloc` | two threads allocating tens of gigabytes per second | nothing wrong on the loop; the JVM pays in GC |
| `all` | all four | |
| `clean` | none | a baseline for `--baseline` |

## 2. What the client sees

```
$ netty-demo --scenario lock --duration 15s --out demo-lock.jfr
scenario lock: 18627 requests; latency p50 0.2 ms  p90 0.6 ms  p99 23.9 ms  max 181.2 ms
...
```

Note what the percentiles hide. The loops were blocked for nearly a quarter of the recording,
yet p99 is 24 ms, under a seventh of the worst stall: in a closed loop only the eight requests
in flight during a stall pay for all of it, and eight requests in 18,600 is 0.04 %. The
`max` is the only number that says something is wrong, and it does not say what. That is
the gap this tool fills.

## 3. What is in the file

```
$ jfrq info demo-lock.jfr
Recording  demo-lock.jfr  15.2 s  starting 2026-09-25T08:38:56.704523Z
Threads    31 seen in events; platform threads: 17 alive at start, 11 started, 11 ended, 17 alive at end
Chunks     1
Sampling   ExecutionSample 10.0 ms, NativeMethodSample 10.0 ms
Thresholds Compilation 100 ms, CompilerPhase 10.0 s, FileForce 10.0 ms, FileRead 1.00 ms, FileWrite 1.00 ms, JavaMonitorEnter 1.00 ms, JavaMonitorWait 1.00 ms, SocketRead 1.00 ms, SocketWrite 1.00 ms, ThreadPark 1.00 ms, ThreadSleep 1.00 ms, VirtualThreadPinned 20.0 ms, ZPageAllocation 1.00 ms
Throttled  JavaExceptionThrow 300/s, ObjectAllocationSample 1000/s
Allocation ObjectAllocationSample 1000/s

Event type                         Count  Enabled  Threshold  Period
jdk.ThreadPark                     18338  yes      1.00 ms
jdk.NativeMethodSample              1292  yes                 10.0 ms
jdk.NativeLibrary                   1052  yes                 everyChunk
...
THREADS (the names --thread matches)
  Family                    Threads  Seen  At start  Started  Ended  At end  Example
  ...
  GC Thread#N*                   10    10         —        —      —       —  GC Thread#0
  ...
  event-loop-N-N*                 2     2         0        2      0       2  event-loop-3-1
  housekeeper                     1     1         1        0      0       1
  load-client-N*                  8     8         0        8      8       0  load-client-1
  ...
```

`Threads` has two counts, and they answer different questions. *Seen in events* is how
many threads some event names in this window: it moves with the window's activity, so a
pool that starts a worker per task reads 39 in one window and 64 in the next without
leaking anything. The census after it counts platform threads *alive* when the recording
began and when it ended, and the starts and ends in between; alive at start, plus started,
minus ended, is alive at end. A leak is a family whose `At end` grows window after window.
The eight load clients started and ended inside this one; the event loops started and are
still alive. A dash is a count the recording cannot give: the census and the start and end
events cover Java platform threads only, not the JVM's own GC workers or virtual threads.

Read the `Thresholds` line before anything else. It comes from the `jdk.ActiveSetting`
events in the file and bounds what any analysis can find: with a 20 ms monitor threshold,
a 15 ms wait never existed as far as the file is concerned. It lists every event type
whose threshold suppresses something, in name order; a threshold of zero lets everything
through, so it is not one, and it stays in the per-type table below instead. `jfrq` prints these
thresholds on every `stalls` and `locks` report and warns when one is coarser than the
stall gap, and warns
too when the JDK's default *throttle* on socket and file events is in force (the
`profile` settings keep at most 300 of them per second, so a busy service can lose the
one long read that mattered; the demo switches it off).

`Chunks` is the file's structure. A recording is a sequence of self-contained chunks;
`jfrq` reads their headers before anything else, takes the recording's span from them,
and says so on every report if the file is cut short. A file copied while the JVM was
still writing it is refused, with the command that produces a readable one.

## 4. Scenario `lock`: who held it?

```
$ jfrq stalls demo-lock.jfr --thread 'event-loop-*' --gap 50ms --top 2
Recording  demo-lock.jfr  15.2 s  starting 2026-09-25T08:38:56.704523Z
Sampling   ExecutionSample 10.0 ms, NativeMethodSample 10.0 ms
Thresholds JavaMonitorEnter 1.00 ms, ThreadPark 1.00 ms, ThreadSleep 1.00 ms, SocketRead 1.00 ms, FileRead 1.00 ms
Gap        50.0 ms
Threads    2 matched
Unseen     on 2 of 2 threads, a stall no event explains is seen only from 151 ms to 173 ms: each is sampled in native code every ~36.1 ms, about 4 threads in native code sharing the sampler's one native slot per 10.0 ms period. Record with jdk.NativeMethodSample#period=1ms, the shortest the sampler takes, to see them from ~52.2 ms; shorter ones only blocking events can show

BY VERDICT
  Verdict          Stalls  Stalled   Worst
  BLOCKED_MONITOR      46   7.09 s  179 ms

PER THREAD (most stalled first; cadence: median interval between samples, which bounds what can be seen)
  Thread          Samples  Java cadence  Native cadence  Unseen below  Stalls  Stalled  Share   Worst
  event-loop-3-2      312       24.8 ms         36.1 ms        173 ms      23   3.55 s  23.4%  179 ms
  event-loop-3-1      334             —         35.8 ms        151 ms      23   3.54 s  23.4%  179 ms

STALLS >= 50.0 ms: 46 found, showing 2, longest first
   1  event-loop-3-2         +0.493s    179 ms  BLOCKED_MONITOR blocked on monitor dev.jfrq.demo.SessionRegistry@7d45ff2c0 held by housekeeper
        at dev.jfrq.demo.SessionRegistry.touch(SessionRegistry.java:29)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:49)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:25)
        at io.netty.channel.SimpleChannelInboundHandler.channelRead(SimpleChannelInboundHandler.java:99)
        at io.netty.channel.AbstractChannelHandlerContext.fireChannelRead(AbstractChannelHandlerContext.java:357)
        at io.netty.handler.codec.MessageToMessageDecoder.channelRead(MessageToMessageDecoder.java:107)
        ... 20 more
   2  event-loop-3-1         +0.493s    179 ms  BLOCKED_MONITOR blocked on monitor dev.jfrq.demo.SessionRegistry@7d45ff2c0 held by housekeeper (handed on through event-loop-3-2)
        same stack as #1
```

Three things to read off this:

1. **The verdict and the holder.** Both loops stalled on `SessionRegistry`, and the
   thread that held it was `housekeeper`. The stack says where the loop was:
   `SessionRegistry.touch` called from `channelRead0`, i.e. the hot path takes a lock
   that a background job holds for long stretches.
2. **"handed on through event-loop-3-2".** JFR only records the thread that *released*
   the monitor to the waiter, and under contention that is often another waiter that held
   it for microseconds. `jfrq` rebuilds who held the lock during the wait from the
   co-waiters' own waits (each got it when its own wait ended and kept it until it handed
   it on) and names the thread that held it longest; the others it passed through are
   listed after it, in the order they held it.
3. **The `Unseen` line.** The idle event loop sits in `kqueue`/`epoll`, which is native
   code, and the JFR sampler visits only one native thread per period, round-robin. With
   the client threads also in native socket reads, each loop is sampled about every 36 ms,
   and routinely unseen for longer, so a stall no event explains would have to last
   151–173 ms to be seen at all; on a busier machine far longer. The line says so before
   any stall, and what would help: a 1 ms native period would bring it to about 52 ms.
   That does not weaken this result: every `BLOCKED_MONITOR` above comes from a
   `jdk.JavaMonitorEnter` event, which is exact whatever the sampling.

Now the other side of the same story:

```
$ jfrq locks demo-lock.jfr --top 3
Recording  demo-lock.jfr  15.2 s  starting 2026-09-25T08:38:56.704523Z
Thresholds JavaMonitorEnter 1.00 ms, ThreadPark 1.00 ms
Blocked    7.16 s across 56 waits

LOCKS BY TOTAL WAIT
  Lock                                     Kind       Total  Waits      Max  Waiters                         Held by
  dev.jfrq.demo.SessionRegistry@7d45ff2c0  monitor   7.10 s     48   179 ms  event-loop-3-2, event-loop-3-1  housekeeper, event-loop-3-2, event-loop-3-1
  dev.jfrq.demo.Persistence@7d460ff00      monitor  54.9 ms      6  23.1 ms  housekeeper                     persistence-flusher
  java.lang.Object@7d0c12d80               monitor  1.55 ms      1  1.55 ms  event-loop-3-1                  event-loop-3-2

WHERE THEY WAITED (the longest wait for each lock above)
  dev.jfrq.demo.SessionRegistry@7d45ff2c0  179 ms
        at dev.jfrq.demo.SessionRegistry.touch(SessionRegistry.java:29)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:49)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:25)
        at io.netty.channel.SimpleChannelInboundHandler.channelRead(SimpleChannelInboundHandler.java:99)
        at io.netty.channel.AbstractChannelHandlerContext.fireChannelRead(AbstractChannelHandlerContext.java:357)
        at io.netty.handler.codec.MessageToMessageDecoder.channelRead(MessageToMessageDecoder.java:107)
        ... 20 more
  dev.jfrq.demo.Persistence@7d460ff00  23.1 ms
        at dev.jfrq.demo.Persistence.flush(Persistence.java:15)
        at dev.jfrq.demo.SessionRegistry.compact(SessionRegistry.java:38)
        at dev.jfrq.demo.Background.lambda$housekeeper$0(Background.java:35)
        at dev.jfrq.demo.Background$$Lambda.run(lambda)
        at dev.jfrq.demo.Background.lambda$start$0(Background.java:86)
        at dev.jfrq.demo.Background$$Lambda.run(lambda)
        ... 2 more
  java.lang.Object@7d0c12d80  1.55 ms
        at jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(BuiltinClassLoader.java:590)
        at jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:578)
        at java.lang.ClassLoader.loadClass(ClassLoader.java:490)
        at io.netty.channel.AbstractChannel$AbstractUnsafe.deregister(AbstractChannel.java:667)
        at io.netty.channel.AbstractChannel$AbstractUnsafe.fireChannelInactiveAndDeregister(AbstractChannel.java:627)
        at io.netty.channel.AbstractChannel$AbstractUnsafe.close(AbstractChannel.java:610)
        ... 16 more

THREADS BY TIME BLOCKED
  Thread            Total  Waits      Max  Share
  event-loop-3-2   3.56 s     24   179 ms  23.4%
  event-loop-3-1   3.55 s     26   179 ms  23.4%
  housekeeper     54.9 ms      6  23.1 ms   0.4%

CONVOYS (the holder was itself blocked)
  +0.493s  event-loop-3-2 waited 179 ms for dev.jfrq.demo.SessionRegistry@7d45ff2c0 held by housekeeper
              -> housekeeper waited 23.1 ms for dev.jfrq.demo.Persistence@7d460ff00 held by persistence-flusher
  +0.493s  event-loop-3-1 waited 179 ms for dev.jfrq.demo.SessionRegistry@7d45ff2c0 held by housekeeper (handed on through event-loop-3-2)
              -> housekeeper waited 23.1 ms for dev.jfrq.demo.Persistence@7d460ff00 held by persistence-flusher
  +11.047s  event-loop-3-2 waited 162 ms for dev.jfrq.demo.SessionRegistry@7d45ff2c0 held by housekeeper (handed on through event-loop-3-1)
              -> housekeeper waited 8.42 ms for dev.jfrq.demo.Persistence@7d460ff00 held by persistence-flusher
...
```

The convoy is the part no aggregate table gives you: while the loop waited for the
registry, the housekeeper holding the registry was itself waiting for the persistence
lock, held by the flusher. The fix is not "make the housekeeper faster"; it is "do not
flush while holding the registry", and the report says so.

Note what is *not* in this report: 18,338 `jdk.ThreadPark` events from the client
threads' pacing sleeps. A park with no blocker object is a sleep, not a lock, and
`jfrq locks` drops them so contention is not buried under timers.

`--thread` and `--min` narrow what is listed, not what is known: the holder walk and the
convoy following still go through every thread's waits, so asking only about
`event-loop-*` names the same holders as asking about everything, and a convoy headed
by a loop's wait is still followed into the housekeeper and the flusher. Only waits
that pass the filters are listed and head convoys.

## 5. Scenario `blocking-io`: the call that should have been async

```
$ netty-demo --scenario blocking-io --duration 15s --out demo-blocking-io.jfr
scenario blocking-io: 17763 requests; latency p50 0.1 ms  p90 0.3 ms  p99 18.1 ms  max 227.6 ms; 44 synchronous backend lookups on the event loops
...

$ jfrq stalls demo-blocking-io.jfr --thread 'event-loop-*' --top 1
...
BY VERDICT
  Verdict      Stalls  Stalled   Worst
  BLOCKING_IO      44   7.98 s  227 ms

...
STALLS >= 50.0 ms: 44 found, showing 1, longest first
   1  event-loop-3-2         +11.412s    227 ms  BLOCKING_IO     blocking socket read from localhost:64138 (28 B)
        at sun.nio.cs.StreamDecoder.readBytes(StreamDecoder.java:279)
        at sun.nio.cs.StreamDecoder.implRead(StreamDecoder.java:322)
        at sun.nio.cs.StreamDecoder.read(StreamDecoder.java:186)
        at java.io.InputStreamReader.read(InputStreamReader.java:183)
        at java.io.BufferedReader.fill(BufferedReader.java:166)
        at java.io.BufferedReader.readLine(BufferedReader.java:333)
        ... 1 more
        at dev.jfrq.demo.RequestHandler.lookup(RequestHandler.java:77)
        ... 25 more
...
```

Forty-four lookups, forty-four stalls: nothing missed, nothing invented. The
`jdk.SocketRead` event carries the peer and the byte count, so the verdict names the
backend by port. The stack printer always shows the first application frame even when it
lies below the cut, which is why `RequestHandler.lookup` appears after the elision: that
is the line to change.

Had the reads been short and many instead of long and few, they would still be found:
a silence in the samples is explained by every read from the same peer that falls
inside it, whatever their byte counts, as `12 × blocking socket read from backend:9000
(1.27 KB)`.

## 6. Scenario `cpu`: nothing blocked, the loop just did not come back

```
$ netty-demo --scenario cpu --duration 15s --out demo-cpu.jfr
scenario cpu: 21971 requests; latency p50 0.1 ms  p90 0.2 ms  p99 2.1 ms  max 120.4 ms
...

$ jfrq stalls demo-cpu.jfr --thread 'event-loop-*' --top 1
...
BY VERDICT
  Verdict  Stalls  Stalled   Worst
  BUSY         21   2.46 s  156 ms

...
STALLS >= 50.0 ms: 21 found, showing 1, longest first
   1  event-loop-3-2         +2.057s    156 ms  BUSY            busy in dev.jfrq.demo.CpuWork.burn (89% of 9 samples) [samples]
        at dev.jfrq.demo.CpuWork.burn(CpuWork.java:20)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:59)
        ...
...
```

One request in a thousand, 21,971 requests, 21 stalls. There is no event for "ran too
long", so this verdict comes from the sampler: nine consecutive samples, each within
a few periods of the last, none at the idle point, eight of them in `CpuWork.burn`. A
thread executing Java is sampled at close to the configured period (about 15 ms here),
so this evidence is solid. Had the samples been spread out, `jfrq` would not have
chained them into a run at all: sparse samples prove nothing about the time between
them.

Two further verdicts belong to this family. `SATURATED` means the loop never returned
to idle but no single frame dominates, which is a loop that has too much work rather
than one long task; it needs at least five samples before `jfrq` will say so. On a clean
run you may see one `SATURATED` stall in the first 100 ms of the recording: that is class
loading and JIT warm-up, and it is real.

## 7. Scenario `alloc`: who is allocating, and what changed

```
$ netty-demo --scenario alloc --duration 15s --out demo-alloc.jfr
$ netty-demo --scenario clean --duration 15s --out demo-clean.jfr

$ jfrq alloc demo-alloc.jfr --top 4
Recording  demo-alloc.jfr  15.1 s  starting 2026-09-24T10:53:21.774585Z
Source     jdk.ObjectAllocationSample (14636 samples; the first sample of each of 5 threads already running when the recording began is left out, as its weight reaches back before it)
Estimate   422 GB over 15.1 s = 28.0 GB/s
Counted    424 GB by the JVM's own counters on the 15 threads that have one; the estimate for those, over the same stretches, is 422 GB (-0%), 100.0% of the estimate above

BY THREAD
  Thread              Bytes  Counted       Rate  Share  Samples  Top classes
  bulk-allocator-1   211 GB   212 GB  14.0 GB/s  50.0%     6891  byte[] 100%, Object[] 0%, Long 0%
  bulk-allocator-2   211 GB   212 GB  14.0 GB/s  50.0%     7142  byte[] 100%, Long 0%, Object[] 0%
  event-loop-3-2    9.48 MB            628 KB/s   0.0%      223  byte[] 52%, DirectByteBuffer 21%, String 8%
  event-loop-3-1    9.48 MB            628 KB/s   0.0%      241  byte[] 36%, DirectByteBuffer 19%, HashMap$Node 12%

BY CLASS
  Class                        Bytes       Rate  Share  Samples
  byte[]                      422 GB  28.0 GB/s  99.9%    14296
  java.lang.Long              126 MB  8.33 MB/s   0.0%       13
  java.lang.Object[]         83.4 MB  5.52 MB/s   0.0%       12
  java.nio.DirectByteBuffer  3.77 MB   250 KB/s   0.0%       68
```

The estimate sums the `weight` of each `jdk.ObjectAllocationSample`, never the sample
count; each sample stands for the bytes allocated since the previous one on that thread,
so the totals are statistically sound even at 1000 samples per second. The `Counted`
line and column are the JVM's own per-thread allocation counters, which JFR writes at
every chunk boundary: exact for every thread alive at both ends of the file, and the
number to trust when the two disagree. Here they agree to the percent, on threads that
carry all of the estimate: the line says what share of the estimate the comparison covers,
because a pool whose threads start and end inside the recording has no counters, and a
percentage measured on the rest says nothing about them. `Samples` is how many samples each
row rests on: `Long` at 126 MB is thirteen of them, a size worth knowing and a share not
worth quoting. Add `--sites` for the allocating stacks, one row per allocating method (the
innermost frame outside the JDK) with every path through it summed, or `--app PREFIX` to
rank them by your own code instead.

One sample per thread is not in the estimate: the first. Its weight is the bytes
allocated since the thread was *last* sampled, and for a thread that was never sampled
before that is its lifetime. An earlier version of this tutorial showed `main` at
150 MB and 9.94 MB/s, all `MemberName`: start-up work from before the recording began,
reported as if it had happened during it. The counters would have said 67 KB. Virtual
threads lose their first sample too, and there it costs more: the JVM counts allocation
per carrier, so a virtual thread's first sample can carry its carrier's history from
before the recording, and since most virtual threads are sampled once, most of their
allocation is left out. The report says so on a `WARNING` line with the number of samples
and bytes dropped (docs/DESIGN.md, section 2).

The comparison is the feature you actually use when tuning:

```
$ jfrq alloc demo-alloc.jfr --baseline demo-clean.jfr --top 4
Baseline   demo-clean.jfr  15.1 s  816 KB/s
Current    demo-alloc.jfr  15.1 s  28.0 GB/s
Change     +28.0 GB/s (×34276)
Rates are bytes/second so recordings of different length compare. The sample counts are the evidence behind each
change: a few hundred percent on a handful of samples is noise, not a finding.

BY THREAD
  Thread                Before      After      Change         Samples
  bulk-allocator-1       0 B/s  14.0 GB/s  +14.0 GB/s  new    0 -> 6891
  bulk-allocator-2       0 B/s  14.0 GB/s  +14.0 GB/s  new    0 -> 7142
  event-loop-3-1         0 B/s   628 KB/s   +628 KB/s  new    0 -> 241
  JFR Periodic Tasks  172 KB/s      0 B/s   -172 KB/s  -100%  4 -> 0

BY CLASS
  Class                        Before      After      Change           Samples
  byte[]                     236 KB/s  28.0 GB/s  +28.0 GB/s  ×118603  3 -> 14296
  java.lang.Long                0 B/s  8.33 MB/s  +8.33 MB/s  new      0 -> 13
  java.lang.Object[]            0 B/s  5.52 MB/s  +5.52 MB/s  new      0 -> 12
  java.nio.DirectByteBuffer     0 B/s   250 KB/s   +250 KB/s  new      0 -> 68
```

Threads match by name, classes by name, and with `--sites` sites by the same fold the
single report ranks them by, so one method reached down many paths is one row of the diff.
Everything is a rate, so a one-minute recording compares with a ten-minute one. The
`Samples` column is the evidence on each side: the clean baseline holds 13 samples in all,
so `event-loop-3-1` is `new` only because the baseline never sampled it, and `JFR Periodic
Tasks` at -100 % rests on four; either rate before is a guess, not a measurement.

Where does allocation show up on the loop? As GC pauses, which stop every thread:

```
$ jfrq stalls demo-alloc.jfr --thread 'event-loop-*' --gap 10ms
...
JVM-WIDE PAUSES >= gap (stop every thread)
  +0.015s   22.1 ms  GC pause: GC Pause (gcId 1)
  +0.042s   15.9 ms  GC pause: GC Pause (gcId 2)
  +0.153s   15.9 ms  GC pause: GC Pause (gcId 7)
  +0.581s   19.4 ms  GC pause: GC Pause (gcId 11)
  +1.166s   10.2 ms  GC pause: GC Pause (gcId 12)
```

On this machine G1 keeps young pauses under 50 ms, so at the default gap there is
nothing to report; when a pause is longer than the gap it is listed here and, if it
covers at least half of a silence in a loop's samples, attributed to that loop as a
`GC_PAUSE` stall from its start to its end. Collections too short to list add up the
same way: run the demo with `JAVA_OPTS="-Xmx300m -XX:+UseSerialGC"` and a thread goes
silent for 1.59 s under a stream of short collections, reported as one stall that counts
them, `167 × GC pause, 1.40 s stopped in total, longest 14.0 ms (GC Pause (gcId 1126))`.
Safepoints that are not collections are treated the same way and named after the VM
operation that ran inside them (`VM operation ThreadDump`), because the JDK's own
settings disable the event that would otherwise say when a safepoint ended.

## 8. Scenario `all`: sorting it out

```
$ netty-demo --scenario all --duration 15s --out demo-all.jfr
$ jfrq stalls demo-all.jfr --thread 'event-loop-*' --html demo-all.html
...
BY VERDICT
  Verdict          Stalls  Stalled   Worst
  BLOCKING_IO          33   5.87 s  217 ms
  BLOCKED_MONITOR      40   5.87 s  274 ms
  BUSY                 13   1.48 s  123 ms
...
```

`BY VERDICT` is the triage table: the blocking reads and the lock cost about the same, so
fix both, and the CPU work is a distant third. Every read here is an event stall of its
own. Two reads back to back, with no return to the selector between them, are also a
silence in the samples, but not a third row: the two event stalls already account for
that time. A thread's stalls never overlap — a busy run with a short read inside it is
the read, and what is left of the run — so a per-thread total never exceeds wall time.
The HTML report has the same tables plus a timeline per thread with one coloured box per
stall, so a burst of stalls at a particular moment is visible at a glance. Open
`demo-all.html` in a browser; it is one file with no external resources, safe to attach
to a ticket.

## 9. Using it on your own service

1. Record with the thresholds shown in the README, or at least with the `profile`
   settings. With the `default` settings the monitor threshold is 20 ms and the sampler
   runs at 20 ms; both work, `jfrq` will just tell you what it could not see.
2. Find the loop's thread names with `jfrq info`. Netty names them after the
   `DefaultThreadFactory` you gave it, or `nioEventLoopGroup-N-M` if you gave none.
3. `jfrq stalls app.jfr --thread '<glob>'`. Read the warnings, then `BY VERDICT`, then
   the top stalls.
4. If your loop idles somewhere other than a JDK selector, a Netty native transport, a
   park or `Object.wait`, tell `jfrq` what idle looks like:
   `--idle 'com\.acme\.Loop\.poll,com\.acme\.Queue\.take'`. The patterns are regular
   expressions that must match a whole `package.Class.method` (so `poll` alone matches
   nothing; `.*\.poll` does), tried against the innermost three frames; the list is
   comma-separated, so a pattern cannot contain a comma; and they replace the defaults.
   A sleep, `Object.wait` or park whose stack shows one of your frames is idle too, so a
   loop that sleeps between polls is not reported as stalled in its own sleep.
5. For locks and allocation the thread filter is optional; both commands look at the
   whole recording by default.
6. `jfrq health app.jfr` before you have a question: what the JVM reported about itself
   (a failed evacuation, a full collection, a collection a humongous allocation forced),
   how heap after GC, memory and threads moved, and which throwables your code creates
   and where. On the demo it shows the leak detector making a stack trace for 97 % of
   the throwables, 41 a second.
7. If a report opens with a `WARNING` about the file itself, read it first: a truncated
   recording is answered as far as it goes, and a file copied while the JVM was still
   writing it is refused with the command that produces a readable one.

## 10. Reading the warnings honestly

`jfrq` separates what it knows from what it infers:

- A stall with evidence `event` (monitor, park, sleep, socket, file) is exact to the
  event's timestamps. These carry no tag in the text output.
- A stall tagged `[samples]` (`BUSY`, `SATURATED`) is as good as the sampling density
  inside it, which is printed: "89% of 9 samples".
- A stall tagged `[silence]` is an absence of samples explained by whatever covered it.
  When nothing covers it the verdict is `UNEXPLAINED`, and if several watched threads
  were silent at the same moment the detail says so, because that is usually the sampler
  rather than the threads.

A `throttled events` warning means the recording's settings capped socket or file
events at so many per second across the JVM (the JDK's `profile` settings do, at 300);
a long read that lost the draw is not in the file, and the silence it caused stays
unexplained. Record with `throttle=off` on those events, as the README's line does.

The `Unseen` line, right under `Threads`, is the verdict on the question before any
answer to it: on how many of the watched threads a stall that no event explains must be
long to be seen at all, and why. When the sampler's pace is the limit, it gives the
`NativeMethodSample` or `ExecutionSample` period that would help and how far it would
get; when the thread's own absences are (parked, blocked, idle), it says no period helps
much and blocking events are what will show the stalls. If what it says is longer than
the stalls you are hunting, record again with what it names, and lower the blocking
thresholds so the explanation comes from events. `PER THREAD` prints the same number per
thread as `Unseen below`.
