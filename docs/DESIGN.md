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

The time span is anchored on two events read on every pass, so that a filtered read
reports the same span and the same `+offsets` as a full one: `jdk.ActiveRecording`
carries the recording's exact start, and `jdk.PhysicalMemory` is a single event at the
beginning and end of every chunk, so its last one marks the end. A recording made with
settings that lack them falls back to the first and last event read.

The settings come from `jdk.ActiveSetting` events, which carry an event-type id, a
setting name and a value (`"10 ms"`, `"300/s"`, `"true"`). Every JDK profile
(`default.jfc`, `profile.jfc`) enables them; a `Recording` built by hand may not, in
which case `RecordingInfo.hasSettings()` is false and every report says so instead of
printing thresholds it does not know.

Memory is bounded by what the sinks keep, not by the file. The allocation sink keeps
aggregates; the contention sink keeps one small record per wait; the stall sink keeps
samples and blocking events only for the threads that match the filter, plus every
thread's monitor waits (a few thousand records at most) to resolve lock holders. Stacks
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

**Aggregation.** Bytes by thread name, by allocated class, and by full stack, plus the
class and stack breakdown per thread. Rates divide by the recording span.

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
sleeps and pacing loops. A park with a blocker of
`AbstractQueuedSynchronizer$ConditionObject` is kept but is usually a worker waiting for
work on a queue; the class is printed so a reader can tell.

**Holder resolution.** `previousOwner` is the thread that released the monitor to the
waiter. Under contention that is frequently another waiter that got the lock a
microsecond earlier, so the field alone misattributes the hold. `ContentionReport`
walks back: while the recorded owner was itself waiting for the same lock during an
overlapping interval, take that wait's owner instead, and remember the intermediaries
as `via`. The walk stops at an unknown owner and at a cycle (a thread cannot hold what
it is waiting for). The same walk runs in `StallCollector` for the stall verdicts.

**Convoys.** For each wait with a known owner, follow the owner into its own wait for a
*different* lock that overlaps in time, up to a depth of five. A chain of two or more
links is a convoy: the thing the loop waited for was held by a thread that was itself
waiting. Co-waiters for the same lock are not links; they are already folded into `via`.

**Lock identity** is class plus address. Addresses are stable only until a collection
moves the object, so the class is always shown and the address only disambiguates.

## 4. `stalls`: when a thread did not return to idle

The question is "why was this event loop not at its selector between t1 and t2", and
the answer is assembled from three kinds of evidence with different reliability.

### 4.1 Idle detection

A sample is *idle* when one of its innermost three frames matches an idle pattern. The
defaults cover the JDK selectors on every platform (`KQueue.poll`, `EPoll.wait`,
`WEPoll.wait`, `*SelectorImpl.doSelect`, `SelectorImpl.select`), Netty's native
transports (`epoll.Native.epollWait*`, `kqueue.Native.keventWait`, `uring.Native.*`),
`Unsafe.park` / `LockSupport.park*` and `Object.wait*`. `--idle` replaces them with a
comma-separated list of regular expressions on `package.Class.method`; a hand-written
poll loop or a queue `take` is one pattern away.

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
| `jdk.FileRead` / `FileWrite` / `FileForce` | `BLOCKING_IO` | path |

Each of these has a threshold in the recording settings; a block shorter than the
threshold is not in the file. `jfrq` warns when a threshold exceeds the gap.

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
pauses, then safepoints covers at least half of it; otherwise it is `UNEXPLAINED`. If
several watched threads have an unexplained silence at the same moment, the detail says
so: that is the sampler, or a pause the recording did not capture, far more often than
independent bad luck.

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

A silence counts as evidence only when it exceeds `3 × max(Java p90, native p90)`,
whatever the neighbouring samples showed, because between any two samples the thread
may have passed through the other state unseen. The per-thread warning prints that
threshold. Explained silences and event stalls do not depend on it.

### 4.4 Pauses

`jdk.GCPhasePause` gives the stop-the-world interval of each collection.
`jdk.SafepointBegin` / `jdk.SafepointEnd`, joined on `safepointId`, give the other
safepoints; one that overlaps a GC pause is dropped as a duplicate. Pauses at least the
gap long are listed once, globally, rather than once per watched thread; they explain
silences per thread.

### 4.5 Merging and reporting

Per thread: event stalls first; then silences, skipping any that an event stall already
covers by half; then runs, likewise. Stalls may therefore nest (a 500 ms busy run with
a 100 ms socket read inside it is two stalls), and per-thread "stalled" totals can
exceed wall time. The report sorts by duration, summarises by verdict and by thread,
and lists the JVM-wide pauses.

## 5. Output

Text goes to standard output in fixed-width tables meant for tickets and chat. `--html`
writes one self-contained file: no scripts, no external resources, inline SVG
timelines with a box per stall (or per wait) and a tooltip with the detail. Both come
from the same report objects, so they never disagree.

## 6. Testing

- Pure logic (`StallAnalysis`, `ContentionReport`, `AllocationDiff`, the formatters,
  the matchers) is tested on hand-built timelines with exact expectations.
- The JFR path is tested on recordings made in-process by the test itself: a named
  thread that idles in a known Java method, sleeps, burns CPU, blocks on a monitor,
  parks on a `ReentrantLock`, waits on an object and reads from a slow socket; an
  allocating thread; a contended monitor with a known holder. Assertions are on
  verdicts, holders and orders of magnitude, not on exact durations, so they hold on a
  loaded CI machine.
- The CLI is tested end to end, command by command, on one such recording, including
  usage errors and exit codes.
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

## 9. Known limits, in one place

- A wait shorter than the recording's threshold for its event does not exist in the
  file. Record with 1 ms thresholds when hunting sub-20 ms stalls.
- `PARKED` never names an owner: JFR does not know who holds a `java.util.concurrent`
  lock.
- Unexplained silences below `3 × routine absence` are invisible; the warning prints the
  number. Reducing the count of threads in native code during the recording, or lowering
  the blocking thresholds so the explanation comes from events, are the two remedies.
- `SATURATED` needs five samples and `BUSY` two; with the `default` profile's 20 ms
  period and a 50 ms gap that is most of the run, so short saturated bursts go
  unreported rather than misreported.
- Lock addresses move with the objects; a lock that was compacted mid-recording appears
  twice under the same class.
- Verdicts name threads by the name they had when the event was written; a pool that
  renames or recycles threads will show the recycled name.
