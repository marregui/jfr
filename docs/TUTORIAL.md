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
  10 ms sampling, 1000 allocation samples per second).

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
scenario lock: 24016 requests; latency p50 0.2 ms  p90 0.5 ms  p99 2.5 ms  max 185.5 ms
```

Note what the percentiles hide. The loops were blocked for a quarter of the recording,
yet p99 is 2.5 ms: in a closed loop only the eight requests in flight during a stall pay
for it, and eight requests in 24,000 is 0.03 %. The `max` is the only number that says
something is wrong, and it does not say what. That is the gap this tool fills.

## 3. What is in the file

```
$ jfrq info demo-lock.jfr
Recording  demo-lock.jfr  15.1 s  starting 2026-09-15T21:46:22.168Z
Threads    30
Sampling   ExecutionSample 10.0 ms, NativeMethodSample 10.0 ms
Thresholds JavaMonitorEnter 1.00 ms, ThreadPark 1.00 ms, ThreadSleep 1.00 ms, SocketRead 1.00 ms, FileRead 1.00 ms
Allocation ObjectAllocationSample 1000/s

Event type                         Count  Enabled  Threshold  Period
jdk.ThreadPark                     18013  yes      1.00 ms
jdk.NativeMethodSample              1144  yes                 10.0 ms
...
```

Read the `Thresholds` line before anything else. It comes from the `jdk.ActiveSetting`
events in the file and bounds what any analysis can find: with a 20 ms monitor threshold,
a 15 ms wait never existed as far as the file is concerned. `jfrq` prints these
thresholds on every report and warns when one is coarser than the stall gap.

## 4. Scenario `lock`: who held it?

```
$ jfrq stalls demo-lock.jfr --thread 'event-loop-*' --gap 50ms
Recording  demo-lock.jfr  15.1 s  starting 2026-09-15T21:46:22.168Z
Sampling   ExecutionSample 10.0 ms, NativeMethodSample 10.0 ms
Thresholds JavaMonitorEnter 1.00 ms, ThreadPark 1.00 ms, ThreadSleep 1.00 ms, SocketRead 1.00 ms, FileRead 1.00 ms
Gap        50.0 ms
Threads    2 matched: event-loop-3-1 (233 samples, cadence 15.2 ms java / 52.2 ms native), event-loop-3-2 (217 samples, ...)
WARNING    event-loop-3-1: samples routinely up to 176 ms apart; unexplained silences shorter than ~528 ms cannot be seen, only ones a blocking event or a JVM pause explains
WARNING    event-loop-3-2: samples routinely up to 177 ms apart; unexplained silences shorter than ~532 ms cannot be seen, only ones a blocking event or a JVM pause explains

STALLS >= 50.0 ms: 47 found, showing 2, longest first
   1  event-loop-3-2         +0.497s    184 ms  BLOCKED_MONITOR blocked on monitor dev.jfrq.demo.SessionRegistry@a4a4adf80 held by housekeeper (handed on through event-loop-3-1)
        at dev.jfrq.demo.SessionRegistry.touch(SessionRegistry.java:26)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:51)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:22)
        at io.netty.channel.SimpleChannelInboundHandler.channelRead(SimpleChannelInboundHandler.java:99)
        at io.netty.channel.AbstractChannelHandlerContext.fireChannelRead(AbstractChannelHandlerContext.java:357)
        at io.netty.handler.codec.MessageToMessageDecoder.channelRead(MessageToMessageDecoder.java:107)
        ... 20 more
   2  event-loop-3-1         +0.497s    184 ms  BLOCKED_MONITOR blocked on monitor dev.jfrq.demo.SessionRegistry@a4a4adf80 held by housekeeper
        at dev.jfrq.demo.SessionRegistry.touch(SessionRegistry.java:26)
        ...

BY VERDICT
  Verdict          Stalls  Stalled    Worst
  BLOCKED_MONITOR      44   6.98 s   184 ms
  BUSY                  2   106 ms  55.0 ms

PER THREAD
  Thread          Stalls  Stalled  Share   Worst
  event-loop-3-1      23   3.54 s  23.5%  184 ms
  event-loop-3-2      24   3.64 s  24.2%  184 ms
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
   client threads also in native socket reads, an idle loop is seen every 170 ms or so.
   So a silence shorter than about half a second is not evidence of anything by itself.
   That does not weaken this result: every `BLOCKED_MONITOR` above comes from a
   `jdk.JavaMonitorEnter` event, which is exact.

Now the other side of the same story:

```
$ jfrq locks demo-lock.jfr --top 3
Recording  demo-lock.jfr  15.1 s  starting 2026-09-15T21:46:22.168Z
Thresholds JavaMonitorEnter 1.00 ms, ThreadPark 1.00 ms
Blocked    7.06 s across 51 waits

LOCKS BY TOTAL WAIT
  Lock                                     Kind       Total  Waits      Max  Waiters                         Held by
  dev.jfrq.demo.SessionRegistry@a4a4adf80  monitor   6.98 s     44   184 ms  event-loop-3-1, event-loop-3-2  housekeeper
  dev.jfrq.demo.Persistence@a4a804000      monitor  81.9 ms      6  26.5 ms  housekeeper                     persistence-flusher
  java.lang.Object@a4a449260               monitor  1.05 ms      1  1.05 ms  event-loop-3-2                  event-loop-3-1

THREADS BY TIME BLOCKED
  Thread            Total  Waits      Max  Share
  event-loop-3-1   3.49 s     22   184 ms  23.2%
  event-loop-3-2   3.49 s     23   184 ms  23.2%
  housekeeper     81.9 ms      6  26.5 ms   0.5%

CONVOYS (the holder was itself blocked)
  +0.497s  event-loop-3-2 waited 184 ms for dev.jfrq.demo.SessionRegistry@a4a4adf80 held by housekeeper (handed on through event-loop-3-1)
              -> housekeeper waited 26.5 ms for dev.jfrq.demo.Persistence@a4a804000 held by persistence-flusher
  ...
```

The convoy is the part no aggregate table gives you: while the loop waited for the
registry, the housekeeper holding the registry was itself waiting for the persistence
lock, held by the flusher. The fix is not "make the housekeeper faster"; it is "do not
flush while holding the registry", and the report says so.

Note what is *not* in this report: 18,013 `jdk.ThreadPark` events from the client
threads' pacing sleeps. A park with no blocker object is a sleep, not a lock, and
`jfrq locks` drops them so contention is not buried under timers.

## 5. Scenario `blocking-io`: the call that should have been async

```
$ netty-demo --scenario blocking-io --duration 15s --out demo-blocking-io.jfr
scenario blocking-io: 24009 requests; latency p50 0.2 ms  p90 0.4 ms  p99 19.3 ms  max 231.2 ms

$ jfrq stalls demo-blocking-io.jfr --thread 'event-loop-*'
...
STALLS >= 50.0 ms: 60 found, showing 1, longest first
   1  event-loop-3-1         +1.279s    229 ms  BLOCKING_IO     blocking socket read from localhost:56512 (27 bytes)
        at sun.nio.cs.StreamDecoder.readBytes(StreamDecoder.java:279)
        at sun.nio.cs.StreamDecoder.implRead(StreamDecoder.java:322)
        at sun.nio.cs.StreamDecoder.read(StreamDecoder.java:186)
        at java.io.InputStreamReader.read(InputStreamReader.java:183)
        at java.io.BufferedReader.fill(BufferedReader.java:166)
        at java.io.BufferedReader.readLine(BufferedReader.java:333)
        ... 1 more
        at dev.jfrq.demo.RequestHandler.lookup(RequestHandler.java:80)
        ... 25 more

BY VERDICT
  Verdict      Stalls  Stalled   Worst
  BLOCKING_IO      60   10.5 s  229 ms
```

The `jdk.SocketRead` event carries the peer and the byte count, so the verdict names the
backend by port. The stack printer always shows the first application frame even when it
lies below the cut, which is why `RequestHandler.lookup` appears after the elision: that
is the line to change.

## 6. Scenario `cpu`: nothing blocked, the loop just did not come back

```
$ netty-demo --scenario cpu --duration 15s --out demo-cpu.jfr
scenario cpu: 24011 requests; latency p50 0.2 ms  p90 0.3 ms  p99 1.0 ms  max 121.2 ms

$ jfrq stalls demo-cpu.jfr --thread 'event-loop-*'
...
STALLS >= 50.0 ms: 24 found, showing 1, longest first
   1  event-loop-3-1         +1.281s    152 ms  BUSY            busy in dev.jfrq.demo.CpuWork.burn (90% of 10 samples)
        at dev.jfrq.demo.CpuWork.burn(CpuWork.java:14)
        at dev.jfrq.demo.RequestHandler.channelRead0(RequestHandler.java:59)
        ...

BY VERDICT
  Verdict  Stalls  Stalled   Worst
  BUSY         24   2.88 s  152 ms
```

There is no event for "ran too long", so this verdict comes from the sampler: ten
consecutive samples, each within a few periods of the last, none at the idle point, nine
of them in `CpuWork.burn`. A thread executing Java is sampled at close to the configured
period (about 15 ms here), so this evidence is solid. Had the samples been spread out,
`jfrq` would not have chained them into a run at all: sparse samples prove nothing about
the time between them.

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
Recording  demo-alloc.jfr  15.1 s  starting 2026-09-15T21:47:11.533Z
Source     jdk.ObjectAllocationSample (14484 samples)
Estimate   400 GB over 15.1 s = 26.5 GB/s

BY THREAD
  Thread              Bytes       Rate  Share  Top classes
  bulk-allocator-1   200 GB  13.3 GB/s  50.0%  byte[] 99%, Long 1%, Object[] 0%
  bulk-allocator-2   200 GB  13.3 GB/s  50.0%  byte[] 100%, Long 0%, Object[] 0%
  main               150 MB  9.94 MB/s   0.0%  MemberName 100%, MethodTypeDescImpl 0%, ...
  event-loop-3-1    10.3 MB   682 KB/s   0.0%  byte[] 29%, HashMap$Node 27%, DirectByteBuffer 12%

BY CLASS
  Class                          Bytes       Rate  Share
  byte[]                        398 GB  26.4 GB/s  99.4%
  java.lang.Long               2.09 GB   138 MB/s   0.5%
  ...
```

The estimate sums the `weight` of each `jdk.ObjectAllocationSample`, never the sample
count; each sample stands for the bytes allocated since the previous one on that thread,
so the totals are statistically sound even at 1000 samples per second. Add `--sites` for
the allocating stacks.

The comparison is the feature you actually use when tuning:

```
$ jfrq alloc demo-alloc.jfr --baseline demo-clean.jfr --top 4
Baseline   demo-clean.jfr  15.1 s  11.3 MB/s
Current    demo-alloc.jfr  15.1 s  26.5 GB/s
Change     +26.5 GB/s (×2349)
Rates are bytes/second so recordings of different length compare.

BY THREAD
  Thread                Before      After      Change
  bulk-allocator-1       0 B/s  13.3 GB/s  +13.3 GB/s  new
  bulk-allocator-2       0 B/s  13.3 GB/s  +13.3 GB/s  new
  event-loop-3-1      342 KB/s   682 KB/s   +340 KB/s  +99%
  JFR Periodic Tasks  166 KB/s  6.43 KB/s   -159 KB/s  -96%

BY CLASS
  Class                           Before      After      Change
  byte[]                       10.3 MB/s  26.4 GB/s  +26.4 GB/s  ×2551
  java.lang.Long                   0 B/s   138 MB/s   +138 MB/s  new
  ...
```

Threads match by name, classes by name, sites by full stack. Everything is a rate, so a
one-minute recording compares with a ten-minute one.

Where does allocation show up on the loop? As GC pauses, which stop every thread:

```
$ jfrq stalls demo-alloc.jfr --thread 'event-loop-*' --gap 10ms
...
JVM-WIDE PAUSES >= gap (stop every thread)
  +0.003s   13.6 ms  GC pause: GC Pause (gcId 1)
  +0.021s   10.9 ms  GC pause: GC Pause (gcId 2)
  ... 2 more
```

On this machine G1 keeps young pauses under 15 ms, so at the default 50 ms gap there is
nothing to report; in the `all` scenario, where the loops are also blocked, pauses of
60 ms appear as `GC_PAUSE` stalls on each loop. A stall is attributed to a pause when
the pause covers at least half of a silence in that thread's samples.

## 8. Scenario `all`: sorting it out

```
$ netty-demo --scenario all --duration 15s --out demo-all.jfr
$ jfrq stalls demo-all.jfr --thread 'event-loop-*' --html demo-all.html
...
BY VERDICT
  Verdict          Stalls  Stalled    Worst
  BLOCKING_IO          59   10.1 s   224 ms
  BLOCKED_MONITOR      38   5.40 s   272 ms
  BUSY                 23   2.64 s   138 ms
  GC_PAUSE              2   119 ms  65.0 ms
```

`BY VERDICT` is the triage table: fix the blocking reads first, then the lock, and the
CPU work is third. The HTML report has the same tables plus a timeline per thread with
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
   expressions on `package.Class.method`, matched against the innermost three frames,
   and they replace the defaults.
5. For locks and allocation the thread filter is optional; both commands look at the
   whole recording by default.

## 10. Reading the warnings honestly

`jfrq` separates what it knows from what it infers:

- A stall with evidence `event` (monitor, park, sleep, socket, file) is exact to the
  event's timestamps.
- A stall with evidence `samples` (`BUSY`, `SATURATED`) is as good as the sampling
  density inside it, which is printed: "90% of 10 samples".
- A stall with evidence `silence` is an absence of samples explained by whatever covered
  it. When nothing covers it the verdict is `UNEXPLAINED`, and if several watched threads
  were silent at the same moment the detail says so, because that is usually the sampler
  rather than the threads.

The per-thread warning tells you the shortest unexplained silence the recording can
support. If it is longer than the stalls you are hunting, lower the blocking thresholds
so the explanation comes from events instead, or reduce the number of threads sitting in
native code during the recording.
