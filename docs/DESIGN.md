# Design

How `jfrq` turns a recording into a verdict, which JFR events each answer rests on, and
where the evidence runs out. Read this before trusting a result you did not expect.

## 1. One pass, several sinks

A recording is read once, event by event, through `jdk.jfr.consumer.EventStream`.
`JfrReader` subscribes to exactly the event types its sinks want, dispatches each event
to them and, on the way, collects the `RecordingInfo`: the time span, event counts per
type, the set of threads, and the active settings. The choice of `EventStream` over
`RecordingFile` is a performance one, explained in section 8: the stream parser skips
unsubscribed event types at the byte level instead of materialising them.

Before the pass, the chunk headers are read directly from the file (`Chunks`). A
recording is a sequence of chunks, each with a 68-byte header that carries the chunk's
size and exact time span, so the headers give the recording's *data span* (first chunk
start to last chunk end) without depending on which events were subscribed, on which
settings were active, or on any other recording that happened to be running in the JVM.
That last point matters: a JVM usually runs a continuous recording next to an on-demand
one, and the on-demand file carries `jdk.ActiveRecording` events for both; anchoring the
span on the earliest recording start, as an earlier version did, stretched a one-second
dump to the age of the continuous recording and divided every rate by it.

The headers also say whether the file is whole. A chunk whose declared size runs past
the end of the file is a truncated recording; the JDK parser stops silently at it, which
would turn "the disk filled up" into "no stalls found". `jfrq` reads the complete chunks
and prints a warning on every report, or refuses the file when not even the first chunk
is complete. A chunk whose file-state byte is not zero is one still being written: a copy
of a live recording, or what a killed JVM left behind. The JVM rewrites the chunk's size
and duration at every flush, so those say nothing on their own; the state byte is what
it clears when it closes the chunk, and the JDK parser keys on the same byte. It does
not fail on an open chunk; it polls the header until the byte clears, forever. `jfrq`
refuses such a file before the parser sees it and says how to get a readable one
(`jcmd <pid> JFR.dump`, or truncate to the complete chunks).

The settings come from `jdk.ActiveSetting` events, which carry an event-type id, a
setting name and a value (`"10 ms"`, `"300/s"`, `"true"`). Every JDK profile
(`default.jfc`, `profile.jfc`) enables them; a `Recording` built by hand may not, in
which case `RecordingInfo.hasSettings()` is false and every report says so instead of
printing thresholds it does not know.

Memory is bounded by what the sinks keep, not by the file. The allocation sink keeps
aggregates; the contention sink keeps one small record per wait; the stall sink keeps
samples and blocking events only for the threads that match the filter, plus every
thread's monitor waits (one small record each, however many the file holds) to resolve
lock holders. Stacks
and frames are interned for the duration of a pass, so a million samples over a few
thousand distinct stacks retain a few thousand stacks.

Timestamps are carried as nanoseconds since the epoch in `long`s. `Interval` is
half-open, `[start, end)`.

## 2. `alloc`: allocation pressure

**Events.** `jdk.ObjectAllocationSample` (JDK 16+): a throttled sample whose `weight`
is the number of bytes it stands for, i.e. the bytes allocated on that thread since the
previous sample. Summing weights gives an unbiased estimate of bytes allocated; counting
samples does not, and is the classic mistake. When the sampled event is absent the
analysis falls back to `jdk.ObjectAllocationInNewTLAB` (weight: the TLAB size) and
`jdk.ObjectAllocationOutsideTLAB` (weight: the allocation size), which is how JDK 11-15
recordings and explicitly configured profiles report allocation.

**The first sample of every thread is discarded.** A sample's weight is the bytes the
thread allocated since it was *last sampled*, and for a thread that was never sampled,
or not since a recording hours earlier, that is its lifetime allocation. Left in, a main
thread that allocated 150 MB of `MemberName` at start-up and nothing since is reported as
allocating 150 MB during the recording; the tutorial's first draft showed exactly that.
Dropping the sample loses at most the bytes between the previous sample and this one,
nothing for a thread sampled hundreds of times a second and unknowable for one sampled
once. The TLAB events carry no such history and are used as they are.

**Calibration.** `jdk.ThreadAllocationStatistics`, written at every chunk boundary by
the JDK's own settings, is each thread's exact allocation counter. For a thread seen at
least twice, the difference between its last and first counter is what it allocated in
between, and the report prints it next to the estimate, overall and per thread, so the
reader knows how far the sampling is from the truth for the threads that matter. A
thread that started and ended between two counter events has no counter; the
comparison is restricted to the threads that do, so the two numbers compare like for
like, and the percentage is only printed when those threads carry at least 1 % of the
estimate: below that the two differ by start-up noise (the counters are read a few
milliseconds after sampling begins, and on the thread that starts the recording those
milliseconds are JFR's own initialisation), and a percentage would only alarm.

The line also says what share of the estimate those threads carry, because that is what
the percentage validates and nothing more. On a server whose work runs on pool threads
that live and die inside the window, the counted threads can be a third of the estimate
while the transient ones did the other two thirds; an unqualified "±2 %" reads as the
error of the whole report, which it is not.

**Aggregation.** Bytes by thread name, by allocated class, and by full stack, plus the
class and stack breakdown per thread. Rates divide by the recording span. Byte units are
decimal (`1 MB` is 1,000,000 bytes), as in `jfr view`; `jfr print` uses binary units.

**Diff.** `--baseline` compares rates, not totals, so recordings of different length are
comparable; threads match by name, classes by name, sites by full stack. A key missing
on one side is reported against zero. Sorting is by absolute change in rate.

**Limits.** The estimate is statistical; at the JDK's default 150-300 samples per second
it ranks threads and classes reliably and gets shares within a few percent, but it will
not tell you that a site allocating 0.1 % of the total grew by half. Class names are
JVM names in the file (`[B`) and are printed in source form (`byte[]`).

## 3. `locks`: contention

**Events.** `jdk.JavaMonitorEnter` (a contended `synchronized` entry; carries the
monitor class, its address, the previous owner, the duration and the waiter's stack) and
`jdk.ThreadPark` (`LockSupport.park`, which every `java.util.concurrent` lock and queue
ends in; carries the blocker class and address, but no owner, because the JVM does not
know who owns a `ReentrantLock`).

**What is deliberately excluded.** `jdk.JavaMonitorWait` (`Object.wait()`): a thread
in `wait()` chose to wait for a notification; counting it would drown contention under
idle worker pools. Parks with no blocker object: those are `LockSupport.parkNanos`
sleeps and pacing loops.

**Waiting for work is not contention.** On a server most parks are workers sitting on
their own empty queue. Ranked by duration they beat every real lock: in one 2m39s window
of a loaded node, twelve idle pools filled both `LOCKS BY TOTAL WAIT` and `LONGEST
WAITS`, and the monitor that mattered — 33.8 s across 1 028 waits — appeared in neither.
So a park whose stack shows the *pool's own* idle frame goes to a `WAITING FOR WORK`
section with its own total, and the contention sections are what is left.

The patterns name that frame and nothing else: `ThreadPoolExecutor.getTask`,
`ForkJoinPool.awaitWork`, `ForkJoinPool.managedBlock`, `DelayedWorkQueue.take`, Netty's
`SingleThreadEventExecutor.takeTask`, logback's `AsyncAppenderBase$Worker.run`. Not the
queue class: a request thread waiting for a reply on a `SynchronousQueue` is a real wait
and looks identical one frame up. Not the worker loop either: `runWorker` is on the
stack while a task is running too. The frame sits below the park and the queue, so the
innermost eight frames are examined rather than the three a sampled stack needs.
`--idle` replaces the list, `--idle none` turns the split off.

**Holder resolution.** `previousOwner` is the thread that released the monitor to the
waiter. Under contention that is frequently another waiter that got the lock a
microsecond earlier, so the field alone misattributes the hold. `ContentionReport`
walks back: while the recorded owner was itself waiting for the same lock during an
overlapping interval, take that wait's owner instead, and remember the intermediaries
as `via`. The walk stops at an unknown owner and at a cycle (a thread cannot hold what
it is waiting for). The same walk runs in `StallCollector` for the stall verdicts.

`--thread` and `--min` apply *after* resolution, to what is listed, never to what is
walked: the wait that names the real holder is usually a short one by a thread the
question was not about, and a convoy is followed into whichever thread holds the lock.
Convoys are listed for the waits that pass the filters; their links may belong to any
thread.

**Convoys.** For each wait with a known owner, follow the owner into its own wait for a
*different* lock that overlaps in time, up to a depth of five. A chain of two or more
links is a convoy: the thing the loop waited for was held by a thread that was itself
waiting. Co-waiters for the same lock are not links; they are already folded into `via`.

**Lock identity** is class plus address. Addresses are stable only until a collection
moves the object, so the class is always shown and the address only disambiguates.

**Window semantics.** JFR writes a blocking event when the wait *ends*, so a file holds
waits that began before its first chunk, and a `jfrq-live delta` window slices waits at
both ends by construction. Every wait is therefore counted only for the part inside the
recording's span: a wait of three minutes in a window of two and a half contributes two
and a half. Without that, one thread could be reported as blocked for 112 % of a window,
and the totals said more time was spent waiting than the window contains. The clipping
happens once, in `ContentionReport`, after holder resolution, which runs on the true
intervals — who held a lock does not depend on where the window starts. `--min` applies
to the clipped duration, because it is the duration the report is about, and `LONGEST
WAITS` ranks on it for the same reason; a line under the `Blocked` total says how many
waits were cut. The same rule is applied to `stalls` in section 4.5.

## 4. `stalls`: when a thread did not return to idle

The question is "why was this event loop not at its selector between t1 and t2", and
the answer is assembled from three kinds of evidence with different reliability.

### 4.1 Idle detection

A sample is *idle* when one of its innermost three frames matches an idle pattern. The
defaults cover the JDK selectors on every platform (`KQueue.poll`, `EPoll.wait`,
`WEPoll.wait`, `*SelectorImpl.doSelect`, `SelectorImpl.select`), Netty's native
transports (`epoll.Native.epollWait*`, `kqueue.Native.keventWait`, `uring.Native.*`),
`Unsafe.park` / `LockSupport.park*` and `Object.wait*`. `--idle` replaces them with a
comma-separated list of regular expressions, each of which must match a whole
`package.Class.method`; a hand-written poll loop or a queue `take` is one pattern away.

### 4.2 Evidence

**Events** (exact). A blocking event on the thread at least `--gap` long is a stall by
itself:

| Event | Verdict | Detail |
|---|---|---|
| `jdk.JavaMonitorEnter` | `BLOCKED_MONITOR` | lock, resolved holder, intermediaries |
| `jdk.ThreadPark` | `PARKED` | blocker class, or "no blocker object" |
| `jdk.JavaMonitorWait` | `OBJECT_WAIT` | monitor class |
| `jdk.ThreadSleep` | `SLEEP` | |
| `jdk.SocketRead` / `SocketWrite` | `BLOCKING_IO` | peer host and port, bytes |
| `jdk.FileRead` / `FileWrite` / `FileForce` | `BLOCKING_IO` | path, bytes |

Each of these has a threshold in the recording settings; a block shorter than the
threshold is not in the file. `jfrq` warns when a threshold exceeds the gap. The JDK's
`default` and `profile` settings (JDK 25) also *throttle* the socket and file events to
100 or 300 per second across the JVM; a service doing thousands of short reads a second
can then lose the one long read that mattered. `jfrq` warns when a throttle is in
force, and the README's recording line switches it off.

**Sample runs** (as good as the sampling density). Consecutive non-idle samples chain
into a run when each is within `3 × max(configured period, measured Java cadence)`
of the previous one, capped at the gap. A run at least the gap long, with at least two
samples, is a candidate. Samples further apart do not chain: nothing proves the thread
was busy between them, and a loop that briefly returned to the selector between two
sparse Java samples would otherwise be reported as busy. The verdict is `BUSY` when one
culprit frame (the innermost non-JDK frame) owns at least half the samples, naming it
and the share; `SATURATED` when no frame dominates and there are at least five samples,
which is a loop with too much work rather than one long task. If blocking events cover
at least half the run (a synchronous read shows as native samples in `read0` *and* as
`jdk.SocketRead` events), the events win and the verdict is theirs.

**Silence** (weakest). Two consecutive samples further apart than the thread's routine
absence indicate a state the sampler cannot observe: blocked, in the VM, at a
safepoint. The silence is attributed to whichever group of blocking events, then GC
pauses, then safepoints covers at least half of it; otherwise it is `UNEXPLAINED`. A
group is one kind of block on one lock, peer or path, whatever the byte counts: twelve
20 ms reads from one backend explain a 300 ms silence together, as
`12 × blocking socket read from backend:9000 (1.27 KB)`. If several watched threads
have an unexplained silence at the same moment, the detail says so: that is the
sampler, or a pause the recording did not capture, far more often than independent bad
luck.

### 4.3 Sampling cadence is measured, not assumed

This is the part that decides whether a silence means anything.

The JFR sampler (`jfrThreadSampler.cpp`, unchanged in JDK 25) visits at most
**five threads executing Java and one thread in native code per period**, round-robin
over the thread list. A thread's effective sampling interval therefore depends on how
many threads compete for the same slot. An idle event loop sits in `kqueue`/`epoll`,
which is native code, and shares the single native slot with every thread blocked in a
socket read, a file read, or any other native call. In the demo, eight client threads
in `SocketInputStream.read` push an idle loop's native cadence to 170 ms and more. On
the machine used for the tutorial, consecutive native samples across *all* threads were
about 55 ms apart despite a configured 10 ms period, so the native slot is also slower
than the period suggests.

Threads executing Java are a different story: a CPU-bound loop is sampled at close to
the configured period as long as fewer than five threads are in Java at once.

`jfrq` therefore computes per thread, from the recording itself:

- the median spacing between consecutive Java samples (the *Java cadence*, used for run
  chaining) and between consecutive native samples (the *native cadence*, printed);
- the 90th percentile of each (the *routine absence*).

An *unexplained* silence counts as evidence only when it exceeds
`3 × max(Java p90, native p90)`, whatever the neighbouring samples showed, because
between any two samples the thread may have passed through the other state unseen. The
per-thread warning prints that threshold (for up to three threads; the rest are
counted). An explained silence does not depend on it, with one condition: below the
threshold, what explains it must itself cover a whole gap, because the thread may have
been idle for the rest; above it, half of the silence is enough, because the silence is
evidence in its own right. Event stalls never depend on it.

### 4.4 Pauses

`jdk.GCPhasePause` gives the stop-the-world interval of each collection. Every other
safepoint is assembled from `jdk.SafepointBegin` (the time to bring the threads to a
halt) and `jdk.ExecuteVMOperation` (the operation that ran while they were halted),
joined on `safepointId`; the operation names the pause, `VM operation ThreadDump`,
`VM operation Deoptimize`, which is the part a reader can act on. `jdk.SafepointEnd`
is used when present, but the JDK's own `default` and `profile` settings disable it,
which is why the operation event is the one that matters: with the end event alone a
safepoint would be only its sync phase, and a 300 ms thread dump would explain nothing.
An operation whose begin event fell under the recording's threshold stands alone. A
safepoint that overlaps a GC pause is the GC's own and is dropped as a duplicate. Pauses
at least the gap long are listed once, globally, rather than once per watched thread;
they explain silences per thread.

### 4.5 Merging and reporting

Per thread: event stalls first; then silences, skipping any that an event stall already
covers by half; then runs, likewise. Stalls may therefore nest (a 500 ms busy run with
a 100 ms socket read inside it is two stalls), and per-thread "stalled" totals can
exceed wall time. The report sorts by duration, summarises by verdict and by thread,
and lists the JVM-wide pauses.

The same waiting-for-work rule as section 3 applies to blocking events: a park whose
stack shows a pool's own idle frame is not a stall, however long it is, and a warning
says how many were left out and what they totalled. The block stays in the timeline,
because it is still what explains the silence in the samples — dropping it outright
would turn a 1m10s idle worker into a 1m10s `UNEXPLAINED` stall, which is a worse answer
than the one being rejected. The same check therefore runs on the explanation of a
silence as well as on the event itself.

Every stall is clipped to the recording's span, for the reason section 3 gives for
waits: a blocking event that began before the file, or was still running at its end, is
in the file whole, and counted whole it puts more time in the window than the window
holds. The gap is then applied to the clipped length — 700 ms of blocking with 20 ms of
it inside the window is not a 50 ms stall — and a warning says how many stalls were cut.
A busy run's tail, which is an estimate (one sampler period past its last sample), stops
at the end of the recording for the same reason. Silences need no clipping: they are
bounded by two samples, both inside the span by construction.

## 5. Output

Text goes to standard output in fixed-width tables meant for tickets and chat; each
stall line ends with `[samples]` or `[silence]` unless it came from an event. `--html`
writes one self-contained file: no scripts, no external resources, inline SVG
timelines with a box per stall (or per wait) and a tooltip with the detail. A timeline
row draws at most 2,000 boxes, the longest ones: a recording with a 1 ms threshold can
hold hundreds of thousands of waits, and a file of hundreds of megabytes helps nobody.
Both text and HTML come from the same report objects, so they never disagree.

## 6. Damaged and unusual files

- **Not a recording, empty, or a future format version:** refused with a one-line
  message and exit status 1. The chunk header's magic and major version are checked
  before the JDK parser is involved.
- **Truncated** (the disk filled up, a copy was cut short): the complete chunks are read
  and every report carries a warning naming the point after which events are missing. A
  file that ends inside its first chunk is refused.
- **Still being written** (a live recording copied from under the JVM): refused, with
  the size to truncate the file to. The JDK parser would otherwise spin forever waiting
  for the chunk to finish; see section 1. Questioning a running JVM is what `jfrq-live`
  is for ([LIVE.md](LIVE.md)): it takes windowed dumps, which are finished files.
- **Several recordings in one JVM:** the span is the file's own (section 1); the
  settings reported are the last chunk's, since settings can change between chunks.
- **A blocking call still in progress when the recording stopped** is not in the file at
  all: JFR writes an event when it ends. The stall it caused ends with the recording as
  far as any tool can tell.

## 7. Testing

- Pure logic (`StallAnalysis`, `ContentionReport`, `AllocationDiff`, the formatters,
  the matchers) is tested on hand-built timelines with exact expectations.
- The JFR path is tested on recordings made in-process by the test itself: a named
  thread that idles in a known Java method, sleeps, burns CPU, blocks on a monitor,
  parks on a `ReentrantLock`, waits on an object and reads from a slow socket; an
  allocating thread; a contended monitor with a known holder. Assertions are on
  verdicts, holders and orders of magnitude, not on exact durations, so they hold on a
  loaded CI machine.
- The CLI is tested end to end, command by command, on one such recording, including
  usage errors and exit codes, and on damaged copies of it: cut mid-chunk, cut after a
  complete chunk, cut inside the next header, and junk. The live-file refusal is tested
  on a copy of a real repository chunk taken while the JVM is writing it.
- The two-recordings span and the safepoint-by-VM-operation cases are tested on
  recordings made for them: an on-demand recording dumped while an older one runs, and
  two hundred parked threads dumped with `SafepointEnd` disabled.
- JaCoCo enforces line coverage of 85 % on `core` and 80 % on `cli` in `./gradlew check`.
  At the time of writing `core` is at 97 % and `cli` at 90 %.

## 8. Performance

Measured on a 38 MB, 1.4-million-event recording of the demo (`all`, unpaced, 90 s;
1.08 M of the events are `jdk.GCPhaseParallel`) and on the 0.6 MB tutorial recording,
wall-clock, warm file cache, Apple silicon, JDK 25. "Before" is the first working
version; "after" is the current one.

| Command | Before | After |
|---|---|---|
| `stalls`, 38 MB | 0.68 s | 0.25 s |
| `locks`, 38 MB | 0.53 s | 0.16 s |
| `alloc`, 38 MB | 0.72 s | 0.24 s |
| `alloc --baseline`, 38 MB × 2 | 1.29 s | 0.36 s |
| `info`, 38 MB (reads everything) | 0.48 s | 0.38 s |
| `stalls`, 0.6 MB | 0.45 s | 0.15 s |
| `info`, 0.6 MB | 0.48 s | 0.11 s |

`--timing` prints the read and render phases of any run. What produced the numbers,
in order of effect:

1. **Parse only what is asked for.** `EventStream` with a handler per subscribed type
   makes the JDK parser skip the payload of every other event; `setReuse(true)` recycles
   the event object and `setOrdered(false)` skips the per-chunk time sort. For the
   filtered commands this halved the read. `info` still parses everything and is bound
   by the JDK parser; the JDK's own `jfr summary` is faster only because it reads chunk
   headers instead of events.
2. **Resolve pool objects once.** Every field access on a `RecordedObject` is a linear
   by-name scan of its descriptors, and `RecordedClass.getName()` re-derives the name
   on each call; a twenty-frame stack costs about a hundred such lookups. The objects a
   chunk's constant pools resolve to (`RecordedStackTrace`, `RecordedThread`,
   `RecordedClass`) are shared instances, so `Interner` remembers the first resolution
   by identity. That took `alloc` from 440 ms to 180 ms of reading, and `info` from
   580 ms to 310 ms just from the thread lookup. Identity caches are bounded and
   rebuilt when full, so a long recording cannot pin every chunk's pools.
3. **Hash stacks once.** `Stack` is a class with a cached hash rather than a record, and
   interning makes equal stacks one instance, so aggregation maps compare by identity
   first.
4. **Decide idleness per frame, not per sample.** `IdleMatcher` runs its regular
   expressions once per distinct frame.
5. **Window every interval lookup.** Blocks, pauses, event stalls and per-thread waits
   are sorted by start; with the longest element remembered, a lookup binary-searches
   to the first candidate that can overlap and stops at the first that starts too late.
   Convoy search walks heads in descending duration and stops once `top` are found.
   These do not show in the table above (the analysis is under 40 ms on this file); they
   keep it that way on files with hundreds of thousands of waits or stalls.
6. **Build the HTML only when asked.** The report used to be rendered and discarded
   when `--html` was absent; it is now built behind a supplier, lists at most
   `max(--top, 100)` stalls with stacks, and escapes text without a regex per cell.
7. **Read two files at once.** `--baseline` parses both recordings on virtual threads;
   the parser is single-threaded per file.
8. **Start faster.** The launcher runs the JVM with `-XX:+AutoCreateSharedArchive`, so
   the first run writes a dynamic AppCDS archive next to the jars and later runs map it
   instead of loading and verifying `jdk.jfr` and the tool again: 0.45 s to 0.11 s on a
   small file. An unwritable install directory degrades silently to a normal start
   (`-Xlog:cds*=off`). `-XX:TieredStopAtLevel=1` was tried and rejected: it saves a few
   milliseconds on small files and costs 25 % on a full parse.

### 8.1 The per-event path after the coding-guidelines pass (2026-09-18)

`CODING-GUIDELINES.md` was applied to everything that runs per event: the reader
resolves an `EventType` object once (by identity) to its name, its `int` tag, its
counter and its sinks, so no string is hashed per event; the interner's value tables are
probed with a frame's components and a stack's frame buffer, so a hit allocates nothing;
thread-filter verdicts, idle verdicts, lock and peer detail strings are decided once and
looked up by probe; aggregation runs on primitive-valued open-addressing maps; safepoints
and GC pauses are kept flat until `finish()`. What still allocates per event is the
`Sample`/`Block`/`Wait` record the analysis consumes and the `Instant` the JDK returns for
an event timestamp.

Measured A/B on a 22 MB, 60-second `netty-demo --scenario all --rate 0` recording (58 k
allocation samples, 11 k sampler events, 3.6 k socket reads), same JVM flags, alternating
runs, read phase as `--timing` reports it (read plus `finish()`), cold start on the left
and the median of 20 in-process iterations on the right:

| Command | Cold, before | Cold, after | Warm, before | Warm, after |
|---|---|---|---|---|
| `alloc` | 151 ms | 137 ms | 37 ms | 28 ms |
| `stalls` | 166 ms | 150 ms | 22 ms | 19 ms |
| `locks` | 109 ms | 98 ms | 14 ms | 13 ms |
| `info` | 225 ms | 212 ms | 95 ms | 89 ms |
| `alloc --baseline` | 296 ms | 269 ms | | |

The warm numbers say where the floor is: the JDK parser. `info`, which parses every
event and does nothing with it, costs 89 ms warm; the analyses add 15–30 ms on top of a
filtered parse. The refactor's value is the allocation it removed from that 15–30 ms
(and the cold-start time, which class loading and JIT of a smaller working set improve),
not a change in the order of magnitude. Text and HTML output are byte-identical before
and after on every command; the one exception is the order of rows with an identical
delta in `alloc --baseline`, which was hash-map order before and is hash-map order now.

## 9. Known limits, in one place

- A wait shorter than the recording's threshold for its event does not exist in the
  file. Record with 1 ms thresholds when hunting sub-20 ms stalls.
- `PARKED` never names an owner: JFR does not know who holds a `java.util.concurrent`
  lock.
- A wait or a stall that straddles an end of the recording is counted only for the part
  inside it (sections 3 and 4.5), so the same wait reads shorter in a narrow window than
  in a wide one. The `locks` note and the `stalls` warning say when this happened.
  A `stalls` per-thread share can still exceed 100 % because stalls nest; a `locks`
  share cannot.
- Unexplained silences below `3 × routine absence` are invisible; the warning prints the
  number. Reducing the count of threads in native code during the recording, or lowering
  the blocking thresholds so the explanation comes from events, are the two remedies.
- A throttled event type (`jdk.SocketRead` at 300/s in the JDK's `profile` settings) is
  sampled, not recorded in full; the warning names it, and `throttle=off` in the
  recording settings removes the limit.
- The allocation estimate omits each thread's first sample (section 2); a thread that
  was sampled once in the whole recording contributes nothing to the estimate, and its
  JVM counter, when there is one, is the number to read.
- `SATURATED` needs five samples and `BUSY` two; with the `default` settings' 20 ms
  period and a 50 ms gap that is most of the run, so short saturated bursts go
  unreported rather than misreported.
- Lock addresses move with the objects; a lock that was compacted mid-recording appears
  twice under the same class.
- Verdicts name threads by the name they had when the event was written; a pool that
  renames or recycles threads will show the recycled name.
