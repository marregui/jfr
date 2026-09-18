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

Both launchers need a JDK 25 `java`. If yours is not on the `PATH`, set `JAVA_HOME`.

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
scenario lock: 23976 requests; latency p50 0.2 ms  p90 0.5 ms  p99 6.0 ms  max 174.0 ms
```

Note what the percentiles hide. The loops were blocked for a quarter of the recording,
yet p99 is 6 ms: in a closed loop only the eight requests in flight during a stall pay
for it, and eight requests in 24,000 is 0.03 %. The `max` is the only number that says
something is wrong, and it does not say what. That is the gap this tool fills.

## 3. What is in the file

```
$ jfrq info demo-lock.jfr
Recording  demo-lock.jfr  15.2 s  starting 2026-09-18T11:02:11.875063Z
Threads    31
Chunks     1
Sampling   ExecutionSample 10.0 ms, NativeMethodSample 10.0 ms
Thresholds JavaMonitorEnter 1.00 ms, ThreadPark 1.00 ms, ThreadSleep 1.00 ms, SocketRead 1.00 ms, FileRead 1.00 ms
Allocation ObjectAllocationSample 1000/s

Event type                         Count  Enabled  Threshold  Period
jdk.ThreadPark                     17966  yes      1.00 ms
jdk.NativeMethodSample              1265  yes                 10.0 ms
...
```

Read the `Thresholds` line before anything else. It comes from the `jdk.ActiveSetting`
events in the file and bounds what any analysis can find: with a 20 ms monitor threshold,
a 15 ms wait never existed as far as the file is concerned. `jfrq` prints these
thresholds on every `stalls` and `locks` report and warns when one is coarser than the
stall gap, and warns
too when the JDK's default *throttle* on socket and file events is in force (the
`profile` settings keep at most 300 of them per second, so a busy service can lose the
one long read that mattered; the demo switches it off).

`Chunks` is the file's structure. A recording is a sequence of self-contained chunks;
`jfrq` reads their headers before anything else, takes the recording's span from them,
and says so on every report if the file is cut short or was copied while still being
written.

## 4. Scenario `lock`: who held it?

```
$ jfrq stalls demo-lock.jfr --thread 'event-loop-*' --gap 50ms
Recording  demo-lock.jfr  15.2 s  starting 2026-09-18T11:02:11.875063Z
Sampling   ExecutionSample 10.0 ms, NativeMethodSample 10.0 ms
Thresholds JavaMonitorEnter 1.00 ms, ThreadPark 1.00 ms, ThreadSleep 1.00 ms, SocketRead 1.00 ms, FileRead 1.00 ms
Gap        50.0 ms
Threads    2 matched: event-loop-3-1 (302 samples, cadence 10.4 ms java / 36.8 ms native), event-loop-3-2 (280 samples, ...)
WARNING    event-loop-3-1: samples routinely up to 50.1 ms apart; unexplained silences shorter than ~150 ms cannot be seen, only ones a blocking event or a JVM pause explains
WARNING    event-loop-3-2: samples routinely up to 65.2 ms apart; unexplained silences shorter than ~196 ms cannot be seen, only ones a blocking event or a JVM pause explains

STALLS >= 50.0 ms: 46 found, showing 2, longest first
   1  event-loop-3-2         +0.492s    173 ms  BLOCKED_MONITOR blocked on monitor dev.jfrq.demo.SessionRegistry@82c34db20 held by housekeeper (handed on through event-loop-3-1)
        at dev.jfrq.demo.SessionRegistry.touch(SessionRegistry.java:26)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:51)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:22)
        at io.netty.channel.SimpleChannelInboundHandler.channelRead(SimpleChannelInboundHandler.java:99)
        at io.netty.channel.AbstractChannelHandlerContext.fireChannelRead(AbstractChannelHandlerContext.java:357)
        at io.netty.handler.codec.MessageToMessageDecoder.channelRead(MessageToMessageDecoder.java:107)
        ... 20 more
   2  event-loop-3-1         +0.492s    173 ms  BLOCKED_MONITOR blocked on monitor dev.jfrq.demo.SessionRegistry@82c34db20 held by housekeeper
        at dev.jfrq.demo.SessionRegistry.touch(SessionRegistry.java:26)
        ...

BY VERDICT
  Verdict          Stalls  Stalled   Worst
  BLOCKED_MONITOR      46   7.09 s  173 ms

PER THREAD
  Thread          Stalls  Stalled  Share   Worst
  event-loop-3-1      23   3.54 s  23.3%  173 ms
  event-loop-3-2      23   3.55 s  23.4%  173 ms
```

Three things to read off this:

1. **The verdict and the holder.** Both loops stalled on `SessionRegistry`, and the
   thread that held it was `housekeeper`. The stack says where the loop was:
   `SessionRegistry.touch` called from `channelRead0`, i.e. the hot path takes a lock
   that a background job holds for long stretches.
2. **"handed on through event-loop-3-1".** JFR only records the thread that *released*
   the monitor to the waiter, and under contention that is often another waiter that held
   it for microseconds. `jfrq` walks back through the co-waiters' own waits to name the
   thread that actually held the lock for the duration.
3. **The warnings.** The idle event loop sits in `kqueue`/`epoll`, which is native code,
   and the JFR sampler visits only one native thread per period, round-robin. With eight
   client threads also in native socket reads, an idle loop is routinely unseen for
   50–65 ms, and on a busier machine for far longer. So a silence shorter than about
   150–200 ms is not evidence of anything by itself. That does not weaken this result:
   every `BLOCKED_MONITOR` above comes from a `jdk.JavaMonitorEnter` event, which is
   exact.

Now the other side of the same story:

```
$ jfrq locks demo-lock.jfr --top 3
Recording  demo-lock.jfr  15.2 s  starting 2026-09-18T11:02:11.875063Z
Thresholds JavaMonitorEnter 1.00 ms, ThreadPark 1.00 ms
Blocked    7.14 s across 52 waits

LOCKS BY TOTAL WAIT
  Lock                                     Kind       Total  Waits      Max  Waiters                         Held by
  dev.jfrq.demo.SessionRegistry@82c34db20  monitor   7.09 s     46   173 ms  event-loop-3-1, event-loop-3-2  housekeeper
  dev.jfrq.demo.Persistence@82c86fb80      monitor  54.2 ms      6  19.8 ms  housekeeper                     persistence-flusher

THREADS BY TIME BLOCKED
  Thread            Total  Waits      Max  Share
  event-loop-3-2   3.55 s     23   173 ms  23.4%
  event-loop-3-1   3.54 s     23   173 ms  23.3%
  housekeeper     54.2 ms      6  19.8 ms   0.4%

CONVOYS (the holder was itself blocked)
  +0.492s  event-loop-3-2 waited 173 ms for dev.jfrq.demo.SessionRegistry@82c34db20 held by housekeeper (handed on through event-loop-3-1)
              -> housekeeper waited 19.8 ms for dev.jfrq.demo.Persistence@82c86fb80 held by persistence-flusher
  ...
```

The convoy is the part no aggregate table gives you: while the loop waited for the
registry, the housekeeper holding the registry was itself waiting for the persistence
lock, held by the flusher. The fix is not "make the housekeeper faster"; it is "do not
flush while holding the registry", and the report says so.

Note what is *not* in this report: 17,966 `jdk.ThreadPark` events from the client
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
scenario blocking-io: 24014 requests; latency p50 0.1 ms  p90 0.3 ms  p99 33.7 ms  max 223.9 ms; 60 synchronous backend lookups on the event loops

$ jfrq stalls demo-blocking-io.jfr --thread 'event-loop-*'
...
STALLS >= 50.0 ms: 60 found, showing 1, longest first
   1  event-loop-3-2         +15.088s    224 ms  BLOCKING_IO     blocking socket read from localhost:55657 (28 B)
        at sun.nio.cs.StreamDecoder.readBytes(StreamDecoder.java:279)
        at sun.nio.cs.StreamDecoder.implRead(StreamDecoder.java:322)
        at sun.nio.cs.StreamDecoder.read(StreamDecoder.java:186)
        at java.io.InputStreamReader.read(InputStreamReader.java:183)
        at java.io.BufferedReader.fill(BufferedReader.java:166)
        at java.io.BufferedReader.readLine(BufferedReader.java:333)
        ... 1 more
        at dev.jfrq.demo.RequestHandler.lookup(RequestHandler.java:84)
        ... 25 more

BY VERDICT
  Verdict      Stalls  Stalled   Worst
  BLOCKING_IO      60   10.3 s  224 ms
```

Sixty lookups, sixty stalls: nothing missed, nothing invented. The `jdk.SocketRead`
event carries the peer and the byte count, so the verdict names the backend by port. The
stack printer always shows the first application frame even when it lies below the cut,
which is why `RequestHandler.lookup` appears after the elision: that is the line to
change.

Had the reads been short and many instead of long and few, they would still be found:
a silence in the samples is explained by every read from the same peer that falls
inside it, whatever their byte counts, as `12 × blocking socket read from backend:9000
(1.27 KB)`.

## 6. Scenario `cpu`: nothing blocked, the loop just did not come back

```
$ netty-demo --scenario cpu --duration 15s --out demo-cpu.jfr
scenario cpu: 24011 requests; latency p50 0.2 ms  p90 0.4 ms  p99 1.6 ms  max 121.4 ms

$ jfrq stalls demo-cpu.jfr --thread 'event-loop-*'
...
STALLS >= 50.0 ms: 24 found, showing 1, longest first
   1  event-loop-3-2         +8.785s    145 ms  BUSY            busy in dev.jfrq.demo.CpuWork.burn (91% of 11 samples) [samples]
        at dev.jfrq.demo.CpuWork.burn(CpuWork.java:17)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:63)
        ...

BY VERDICT
  Verdict  Stalls  Stalled   Worst
  BUSY         24   2.86 s  145 ms
```

One request in a thousand, 24,011 requests, 24 stalls. There is no event for "ran too
long", so this verdict comes from the sampler: eleven consecutive samples, each within
a few periods of the last, none at the idle point, ten of them in `CpuWork.burn`. A
thread executing Java is sampled at close to the configured period (about 10 ms here),
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
Recording  demo-alloc.jfr  15.1 s  starting 2026-09-18T11:03:01.616720Z
Source     jdk.ObjectAllocationSample (14662 samples)
Estimate   492 GB over 15.1 s = 32.6 GB/s
Counted    492 GB by the JVM's own counters on the 7 threads seen at both ends of the file; the estimate for those is 492 GB (+0%)

BY THREAD
  Thread              Bytes  Counted       Rate  Share  Top classes
  bulk-allocator-1   246 GB   246 GB  16.3 GB/s  50.0%  byte[] 100%, Long 0%, Object[] 0%
  bulk-allocator-2   246 GB   246 GB  16.3 GB/s  50.0%  byte[] 100%, Long 0%
  event-loop-3-1    8.48 MB            562 KB/s   0.0%  byte[] 37%, DirectByteBuffer 16%, String 14%
  event-loop-3-2    8.41 MB            558 KB/s   0.0%  byte[] 25%, DirectByteBuffer 25%, String 13%

BY CLASS
  Class                        Bytes       Rate  Share
  byte[]                      492 GB  32.6 GB/s  99.9%
  java.lang.Long              295 MB  19.5 MB/s   0.1%
  ...
```

The estimate sums the `weight` of each `jdk.ObjectAllocationSample`, never the sample
count; each sample stands for the bytes allocated since the previous one on that thread,
so the totals are statistically sound even at 1000 samples per second. The `Counted`
line and column are the JVM's own per-thread allocation counters, which JFR writes at
every chunk boundary: exact for every thread alive at both ends of the file, and the
number to trust when the two disagree. Here they agree to the percent. Add `--sites`
for the allocating stacks.

One sample per thread is not in the estimate: the first. Its weight is the bytes
allocated since the thread was *last* sampled, and for a thread that was never sampled
before that is its lifetime. An earlier version of this tutorial showed `main` at
150 MB and 9.94 MB/s, all `MemberName`: start-up work from before the recording began,
reported as if it had happened during it. The counters would have said 67 KB.

The comparison is the feature you actually use when tuning:

```
$ jfrq alloc demo-alloc.jfr --baseline demo-clean.jfr --top 4
Baseline   demo-clean.jfr  15.0 s  802 KB/s
Current    demo-alloc.jfr  15.1 s  32.6 GB/s
Change     +32.6 GB/s (×40692)
Rates are bytes/second so recordings of different length compare.

BY THREAD
  Thread              Before      After      Change
  bulk-allocator-1     0 B/s  16.3 GB/s  +16.3 GB/s  new
  bulk-allocator-2     0 B/s  16.3 GB/s  +16.3 GB/s  new
  event-loop-3-1    262 KB/s   562 KB/s   +300 KB/s  +115%
  event-loop-3-2    268 KB/s   558 KB/s   +290 KB/s  +108%

BY CLASS
  Class                      Before      After      Change
  byte[]                  66.8 KB/s  32.6 GB/s  +32.6 GB/s  ×487994
  java.lang.Long              0 B/s  19.5 MB/s  +19.5 MB/s  new
  ...
```

Threads match by name, classes by name, sites by full stack. Everything is a rate, so a
one-minute recording compares with a ten-minute one.

Where does allocation show up on the loop? As GC pauses, which stop every thread:

```
$ jfrq stalls demo-alloc.jfr --thread 'event-loop-*' --gap 10ms
...
JVM-WIDE PAUSES >= gap (stop every thread)
  +0.582s   24.5 ms  GC pause: GC Pause (gcId 10)
  +0.813s   47.3 ms  GC pause: GC Pause (gcId 11)
```

On this machine G1 keeps young pauses under 50 ms, so at the default gap there is
nothing to report; when a pause is longer than the gap it is listed here and, if it
covers at least half of a silence in a loop's samples, attributed to that loop as a
`GC_PAUSE` stall. Safepoints that are not collections are treated the same way and
named after the VM operation that ran inside them (`VM operation ThreadDump`), because
the JDK's own settings disable the event that would otherwise say when a safepoint
ended.

## 8. Scenario `all`: sorting it out

```
$ netty-demo --scenario all --duration 15s --out demo-all.jfr
$ jfrq stalls demo-all.jfr --thread 'event-loop-*' --html demo-all.html
...
BY VERDICT
  Verdict          Stalls  Stalled   Worst
  BLOCKING_IO          64   11.2 s  334 ms
  BLOCKED_MONITOR      33   4.92 s  274 ms
  BUSY                 24   2.99 s  150 ms
```

`BY VERDICT` is the triage table: fix the blocking reads first, then the lock, and the
CPU work is third. Sixty of the reads are the events themselves; the other four are
silences in which two reads followed each other with no return to the selector in
between, reported as `2 × blocking socket read … [silence]` on top of the two event
stalls they contain, which is why stalls can nest and per-thread totals can exceed wall
time. The HTML report has the same tables plus a timeline per thread with
one coloured box per stall, so a burst of stalls at a particular moment is visible at a
glance. Open `demo-all.html` in a browser; it is one file with no external resources,
safe to attach to a ticket.

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
5. For locks and allocation the thread filter is optional; both commands look at the
   whole recording by default.
6. If a report opens with a `WARNING` about the file itself, read it first: a truncated
   recording is answered as far as it goes, and a file copied while the JVM was still
   writing it is refused with the command that produces a readable one.

## 10. Reading the warnings honestly

`jfrq` separates what it knows from what it infers:

- A stall with evidence `event` (monitor, park, sleep, socket, file) is exact to the
  event's timestamps. These carry no tag in the text output.
- A stall tagged `[samples]` (`BUSY`, `SATURATED`) is as good as the sampling density
  inside it, which is printed: "91% of 11 samples".
- A stall tagged `[silence]` is an absence of samples explained by whatever covered it.
  When nothing covers it the verdict is `UNEXPLAINED`, and if several watched threads
  were silent at the same moment the detail says so, because that is usually the sampler
  rather than the threads.

A `throttled events` warning means the recording's settings capped socket or file
events at so many per second across the JVM (the JDK's `profile` settings do, at 300);
a long read that lost the draw is not in the file, and the silence it caused stays
unexplained. Record with `throttle=off` on those events, as the README's line does.

The per-thread warning tells you the shortest unexplained silence the recording can
support. If it is longer than the stalls you are hunting, lower the blocking thresholds
so the explanation comes from events instead, or reduce the number of threads sitting in
native code during the recording.
