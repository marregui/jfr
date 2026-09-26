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
size and exact time span, so the headers give the recording's *data span* (earliest chunk
start to latest chunk end) without depending on which events were subscribed, on which
settings were active, or on any other recording that happened to be running in the JVM.
Nothing widens it, so every command reports the same span for the same file: an event
that sticks out of the last chunk (a safepoint the final rotation ends a millisecond
late) is clipped by the analyses like a wait that began before the recording.
That last point matters: a JVM usually runs a continuous recording next to an on-demand
one, and the on-demand file carries `jdk.ActiveRecording` events for both; anchoring the
span on the earliest recording start, as an earlier version did, stretched a one-second
dump to the age of the continuous recording and divided every rate by it.

The headers also say whether the file is whole. A chunk whose declared size runs past
the end of the file is a truncated recording; the JDK parser stops silently at it, which
would turn "the disk filled up" into "no stalls found". A chunk whose size fits but whose
last constant pool and metadata are not where its header points (both are events of known
type at offsets the header gives) was cut short and had something appended: JFR files
concatenate, and `head -c` of one recording followed by another reads, to the JDK parser,
as a two-minute chunk with no events and nothing after it. Behind a damaged chunk the scan
looks for the next intact one. The magic alone does not find it: every flush writes a
copy of the chunk header into the chunk, marked as still being written, so a flushed chunk
is full of headers that are not chunks. `jfrq` reads every complete chunk, from a
temporary copy without the damage when complete chunks sit behind it, prints a warning on
every report naming the damaged bytes and the time span their header declared, and
refuses the file when no chunk is complete. A chunk whose file-state byte is not zero is one still being written: a copy
of a live recording, or what a killed JVM left behind. The JVM rewrites the chunk's size
and duration at every flush, so those say nothing on their own; the state byte is what
it clears when it closes the chunk, and the JDK parser keys on the same byte. It does
not fail on an open chunk; it polls the header until the byte clears, forever. `jfrq`
refuses such a file before the parser sees it and says how to get a readable one
(`jcmd <pid> JFR.dump`, or, when complete chunks precede the open one, truncate to them).

The settings come from `jdk.ActiveSetting` events, which carry an event-type id, a
setting name and a value (`"10 ms"`, `"300/s"`, `"true"`). Every JDK profile
(`default.jfc`, `profile.jfc`) enables them; a `Recording` built by hand may not, in
which case `RecordingInfo.hasSettings()` is false and every report says so instead of
printing thresholds it does not know.

Memory is bounded by what the sinks keep, not by the file. The allocation sink keeps
aggregates; the contention sink keeps one small record per wait; the stall sink keeps
samples and blocking events only for the threads that match the filter, plus every
thread's monitor waits (one small record each, however many the file holds) to resolve
lock holders and every thread's parks (three longs, a thread and a stack each) to decide
which locks are a loop's perch. Stacks
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

**The first sample of every thread not born in the file is discarded.** A sample's weight is
the bytes the thread allocated since it was *last sampled*, and for a thread that was never
sampled, or not since a recording hours earlier, that is its lifetime allocation. Left in, a
main thread that allocated 150 MB of `MemberName` at start-up and nothing since is reported
as allocating 150 MB during the recording; the tutorial's first draft showed exactly that.
Dropping the sample loses at most the bytes between the previous sample and this one,
nothing for a thread sampled hundreds of times a second and unknowable for one sampled
once. A platform thread born in the file keeps its first sample: its lifetime began inside
the recording, so all of that weight is in the window, and dropping it lost most of what
short-lived pool threads allocated. Born means a `jdk.ThreadStart` no later than the thread's
first counter reading and its first sample, because a `jdk.ThreadStart` in the file is not
proof: the JVM that starts a recording with `-XX:StartFlightRecording` writes one for `main`
up to seconds after main has been sampled and counted, and taken at its word main's 21 MB of
start-up allocation became the recording's. The `Source` line says how many first samples
were left out, which is why the sample count is short of the events (the JSON has both). The
TLAB events carry no such history and are used as they are.

**Virtual threads lose theirs too, and the report says how much that was.** The JVM
counts allocation per carrier, not per virtual thread, so the weight of a sample taken on
a virtual thread is what its *carrier* allocated since the carrier was last sampled,
under whichever virtual threads it ran in between. A carrier that has not been sampled
since it started, which is every carrier the first time the event is enabled, puts its
whole history on its first sample: on a JVM that had run virtual threads before the
recording, 64 of them allocating 67 MB inside a recording were reported at 3.35 GB when
their first samples were kept. The event names the virtual thread, not the carrier, so
that sample cannot be told apart from an honest one. Dropping every virtual thread's
first sample removes it whenever it lands on one, and the estimate stays below the truth
at the price of most virtual-thread allocation: a virtual thread is typically sampled
once or never, and the same run was reported at 4.2 MB. `alloc` therefore prints a
warning whenever a virtual thread lost a sample, with the count and the bytes left out
(`61 first samples of virtual threads, 3.35 GB, not counted`); those bytes include the
carriers' history, so they can be far more than what was left out.

The rule does not make the estimate a floor. A virtual thread that blocks is unmounted
and can resume on another carrier; when that carrier had not been sampled in the
recording yet, its history lands on the virtual thread's second or later sample and is
kept. With 64 virtual threads that sleep between allocations, 268 MB allocated in the
recording was estimated at 403 MB and 466 MB in two runs, one virtual thread carrying
337 MB on a single sample. There are about as many carriers as cores, so at most that
many samples can do it; the warning says so. A single virtual thread's row is the
carrier bytes it happened to be sampled on, not what it allocated.

**Calibration.** `jdk.ThreadAllocationStatistics`, written at every chunk boundary by
the JDK's own settings, is each thread's exact allocation counter. The difference between
a thread's last counter and its first is what it allocated in between, and a thread whose
`jdk.ThreadStart` is in the file had a counter of zero then; the report prints that next to
the estimate, overall and per thread, so the reader knows how far the sampling is from the
truth for the threads that matter. The two only compare over the same stretch, so the
estimate set against a counter is the thread's samples between those two points, which is
why the collector keeps every platform thread's samples with their times (the samples are
throttled, some 17 MB an hour at 300 a second; the unthrottled TLAB events, which run to
millions a minute, are kept per 100 ms a thread allocated in, so each end of their stretch is
off by at most 50 ms of allocation; virtual threads, which have no counter, keep none). Set against
the whole file instead, a pool thread that started after the first chunk had its early
allocation in the estimate and not in its counter: a 26-minute node recording in six
chunks read as an estimate 28 % high whose samples were right, and is 2 % low measured
over the stretches. A thread's row shows its counter only when that stretch holds 95 % of
the row's estimate; beside the whole-file figure, one pool thread's counter read 0 against
212 MB. A thread that started and ended between two counter events has no counter; the
comparison is restricted to the threads that have one, so the two
numbers compare like for like, and the percentage is only printed when those threads carry at least 1 % of the
estimate: below that the two differ by start-up noise (the counters are read a few
milliseconds after sampling begins, and on the thread that starts the recording those
milliseconds are JFR's own initialisation), and a percentage would only alarm. The share
is the *estimate* on those threads, not their counters: a thread whose counter grew by a
gigabyte while it was sampled for 1 MB of a 200 MB estimate is half a percent of the
report, and so is any error measured on it.

`jdk.ThreadAllocationStatistics` is written per platform thread, so there is no counter for
a virtual thread and its row has an empty `Counted` cell. The carriers
(`ForkJoinPool-1-worker-N`) do have counters, and theirs include every virtual thread they
ran, but the samples name the virtual threads, so a counted carrier
adds its counter to the comparison with no estimate against it and pulls the percentage
down. When most of the allocation is on virtual threads the share rule keeps the
percentage off the line; in a mix of the two, the per-thread `Counted` column of the
platform threads is the comparison to read.

The line also says what share of the estimate those threads carry, because that is what
the percentage validates and nothing more. On a server whose work runs on pool threads
that live and die inside the window, the counted threads can be a third of the estimate
while the transient ones did the other two thirds; an unqualified "±2 %" reads as the
error of the whole report, which it is not.

**A site is a method, not a path to it.** One logical allocation reaches the sampler down
many paths: the same method allocating on two of its own lines, the same line under a
different depth of library frames, a string built by `substring` here and `copyOfRange`
there. Folded by what they print, `BY SITE` on a loaded node showed `NodeId.parse` as four
rows of about 2 % each — noise to any reader — when the method was 20.8 % of everything
the JVM allocated. The key is now the **culprit method**: the innermost frame outside the
JDK, without its line number. Every path through it is one row, the row says how many
stacks it summed, and the stack printed under it is the biggest of them. The raw per-stack
map is untouched underneath, and so is the per-stack comparison `--baseline` is built on.

A stack is its frames' classes, methods and lines, plus whether each frame is native (the
one part of the frame type that is printed, as `Native Method`). JFR also tags every frame
`Interpreted`, `JIT compiled` or `Inlined`, which says what the JIT had done with the method
at that instant; kept in the key, the same line sampled before and after compilation was
two stacks that print identically. On the loaded node above, `NodeId.toParseableString`
said "215 stacks" for 98 distinct ones, and the stack printed under it carried 23.4 MB
while the stack as printed carried 36.5 MB. The same identity is what `locks` and `stalls`
compare stacks by.

**`--app` when the culprit is a library.** The culprit rule stops at the innermost non-JDK
frame, which for `NodeId.parse` is a third-party class the reader cannot change; what they
want is their own line that called it. jfrq cannot infer which packages are theirs — a JVM
started from a jar records `sun.java.command = app.jar` and no package name anywhere — so
`--app com.example` says it, and the fold then keys on the innermost frame in those
packages, falling back to the culprit for a stack that never enters them. A prefix ends
at a name boundary, `.` or `$`, not anywhere in the string: `io.netty` is `io.netty` and
everything under `io.netty.`, and `io.nett` matches neither `io.netty` nor `io.nettyx`; a
class prefix `com.example.Handler` covers its nested classes and lambdas
(`com.example.Handler$Inner`, `com.example.Handler$$Lambda`). So the option
cannot be guessed at, `BY SITE` prints the non-JDK package roots it saw, by bytes; the
report says what to pass it.

**Support.** Every row also carries the number of samples behind it, **summed over every
stack in the row** — a row whose bytes are ten stacks' and whose support is one of them
said the report's most important row rested on 13 samples when it rested on 7 662. The
estimate weights each sample by the bytes it stands for, so two rows of equal size can
rest on 2 000 samples and on 3, and only the count says which; a `--baseline` between two
quiet windows once reported `+397 %` and `+469 %` on a base of 143 samples, which reads as
a finding and is noise. The counts cost three more table probes per allocation event: on a
1.3 MB recording of a loaded node the read went from 54.3 ms to 57.2 ms and the analysis
from 0.62 ms to 1.00 ms, measured with `--timing`.

**Aggregation.** Bytes by thread name, by allocated class, and by full stack, plus the
class and stack breakdown per thread. Rates divide by the recording span. Byte units are
decimal (`1 MB` is 1,000,000 bytes), as in `jfr view`; `jfr print` uses binary units.

**Diff.** `--baseline` compares rates, not totals, so recordings of different length are
comparable; threads match by name, classes by name, sites by the same fold the single
report is ranked by, so `--app` groups a diff exactly as it groups one recording. Matched
per stack instead, one site that moved appeared once per path it had been sampled down: a
diff of two loaded windows opened with the same six frames twice, at 302 MB/s and
216 MB/s, and neither number was the change — the site had moved 628 MB/s. Each row also
carries the samples behind both sides, because several hundred percent on a handful of
them is noise. A key missing on one side is reported against zero. Sorting is by absolute
change in rate.

A hidden class is named without the address the JVM gave it:
`Pattern$$Lambda.0x800000030` is `Pattern$$Lambda`, as a class and in a site's method
name, and `LambdaForm$MH.0x…` is `LambdaForm$MH`. The address differs from one JVM to the
next, so a before-and-after pair from two runs listed every lambda twice, as gone on one
side and new on the other: 46 such rows in one A/B diff of two edge nodes, which the fold
takes to none (and the rows that read `new` or `-100 %` from 77 to 43). The price is that
every lambda of one class is one row. A warning both recordings carry, such as the
virtual-thread one, is said once with both counts.

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
`ForkJoinPool.awaitWork`, `DelayedWorkQueue.take`, the common pool's
`DelayScheduler.loop` (its thread only hands due tasks to the pool, so it parks nowhere
else), Netty's `SingleThreadEventExecutor.takeTask`, logback's
`AsyncAppenderBase$Worker.run`. Not the
queue class: a request thread waiting for a reply on a `SynchronousQueue` is a real wait
and looks identical one frame up. Not the worker loop either: `runWorker` is on the
stack while a task is running too. Not `ForkJoinPool.managedBlock`: on JDK 25 every
`CompletableFuture.get` and `join` and every untimed `Condition.await` blocks through it,
so it is under a caller waiting for a result as often as under a worker waiting for work;
listed, it filed 660 stalls of one loaded node's dispatchers as idleness. The frame that
decides sits below the park, the queue and that managed-blocker machinery —
`ThreadPoolExecutor.getTask` is the eighth frame of an idle fixed pool on JDK 25 — so the
innermost ten frames are examined rather than the three a sampled stack needs.
`--idle` replaces the list, `--idle none` turns the split off.

**Holder resolution.** `previousOwner` is the thread that released the monitor to the
waiter. Under contention that is frequently another waiter that got the lock a
microsecond earlier, so the field alone misattributes the hold. `Holders` rebuilds the
chain of holds inside the wait, from its end backwards. The waiter got the lock when its
wait ended. Each thread on the chain got it when its own wait for that lock ended, taking
the one of its waits that ended last at or before its successor (the next thread toward
the waiter) got the lock, and held it from then, or from the start of the waiter's wait
if that is later, until the successor got it; the owner of that wait is the next thread
back. The chain ends at a thread with no such wait, which held the lock from before the
wait began, at an unknown owner, and at a cycle (the waiter cannot hold what it waits for,
and no wait is walked twice). The thread that held the lock longest inside the wait,
summed when it appears more than once, is the holder; the others that held it are the
`via`, the threads it was *handed on through*, in the order they held it, and the text
names four of them and counts the rest (one loaded node had chains of 88 threads). A tie
names the thread nearer the waiter. Two earlier rules named the wrong thread. Walking
back to the first thread that had not itself waited named a thread that held the lock for
the first half-millisecond of a 300 ms wait, not the one that held it for the other
299.5; stopping instead at the first intermediary that got the lock before the middle of
the wait measured its hold from the end of its *longest* wait rather than the last one,
and, two hops back, counted every later thread's hold as its own. On that node the
longest holder was named for 890 of 2 704 waits with more than one holder inside them;
with the chain it is named for all of them. `ContentionReport` and `StallCollector` run
the same `Holders`, so `locks` and `stalls` name the same thread.

`--thread`, `--min` and `--lock` apply *after* resolution, to what is listed, never to
what is walked: the wait that names the real holder is usually a short one by a thread
the question was not about, and a convoy is followed into whichever thread holds the
lock and whichever lock that thread was waiting for. Convoys are listed for the waits
that pass the filters; their links may belong to any thread and any lock.

**Convoys.** For each wait with a known owner, follow the owner into its own wait for a
*different* lock that overlaps in time, up to a depth of five. A chain of two or more
links is a convoy: the thing the loop waited for was held by a thread that was itself
waiting. Co-waiters for the same lock are not links; they are already folded into `via`.

**Every row carries a stack.** `LOCKS BY TOTAL WAIT` ranks by total, `LONGEST WAITS`
by duration, so a lock made of thousands of short waits tops the first and never appears
in the second: its row was a name and an address with no way to act on it. Each row now
takes the stack of its own longest wait, listed under `WHERE THEY WAITED`, and `--lock`
filters the whole report down to one lock by class or by `class@address`.

**`--by-site` ranks the stack, not the instance.** One queue per in-flight request is as
many locks as requests: on a loaded node, fifteen `ConditionObject` addresses held fifteen
rows of `LOCKS BY TOTAL WAIT` and the section below them said "15 locks with this stack".
With `--by-site` the ranking table is grouped the same way the stacks are — one row per
stack, with the instance count, the summed wait and the longest of them — and the grouping
runs over *every* lock rather than the top N, so a site spread across seventy-four
instances outranks one big lock instead of being lost below it. It is an option rather
than the default because the addresses are what `--lock` takes, and a report that never
prints them cannot be narrowed to one. A site is the stack as it prints to six frames,
whichever report asks, and both print it to that depth: when the text grouped at the six
frames it prints and the HTML at its twelve, the same file gave the two reports different
rows and different totals.

**A thread's own perch, measured rather than named.** The idle list above recognises a
pool's own frame, which works only for the runtimes someone thought to add. A service with
its own worker loop is in nobody's list: on one recording, eight of the eight most
contended locks were dispatcher threads parked on their own mailbox, and an operator who
did not already know the codebase had no way to know to name that frame. A perch has a
shape no list is needed to see — exactly one thread ever waits there, no thread was ever
found holding it, and that thread is parked there for most of the recording. The margin is
wide: on that file the mailboxes covered 76.7 % to 99.9 % of the window while the busiest
real queue in it, a consumer genuinely waiting for data another thread had to produce,
covered 9.8 %. Half the window is the line, with five times the margin either side.

Two parks are required as well as the share, because one park covering the window is a
thread that is *stuck*, which is the most important thing the report can say and must never
be filed away as idleness; a lock with a holder is contention whatever its shape. What the
measurement finds is a lock, but what it identifies is the loop above it, so the stack of
each perch answers for every other lock waited on from the same place: one worker out of
thirteen that was busy for two thirds of the recording parks on its own mailbox exactly
like the other twelve, and a threshold deciding between them would leave that one lock,
alone, at the top of the contention it is not part of. `--idle none` turns this off with
the name list, since an escape hatch that leaves a rule running is not one. The loop is
compared as it prints, to eight frames, deep enough to reach below the park and the queue
into the loop itself: two stacks that differ only in how a frame was compiled are one loop
to a reader, so they are one loop here. A
perch whose parks carry no stack names no loop, and answers for no other lock: every
stackless lock prints the same nothing, and one of them may be a real wait. The report
says how many of the locks it lists under `WAITING FOR WORK` were recognised by shape; the
count is of those rows, after the filters, and leaves out a lock the idle list named. On the
recording that raised it, `Blocked` fell from 32m42s to 2m32s and the browse consumer that
mattered took the top five rows; `stalls`, which asks the same question of its blocks, its
silences and its sample runs, went from 832 stalls on those threads to none.

**One stack per stack, not per lock.** A server that gives every worker its own mailbox
has as many locks as workers and a single stack between them: thirteen dispatcher threads
printed the same seven frames eight times, 66 lines of a 177-line report. `WHERE THEY
WAITED` groups the ranked locks by what their stack *renders to* at the depth being
printed — the same rule the allocation sites are folded by, so whatever prints the same is
one entry — and names the locks the entry stands for, capped like every other name list.
Locks whose longest wait carries no stack are one group too, for the same reason: there is
nothing to tell them apart.

**Lock identity** is class plus address. Addresses are stable only until a collection
moves the object, so the class is always shown and the address only disambiguates. A
move splits a lock in two, which also splits the evidence for a perch: a dispatcher's
mailbox that young collections moved eight times in three minutes was nine locks of about
twenty seconds each, none of them over the line, and one idle thread filled the report
with contention. JFR carries no identity that survives a move, and joining one thread's
locks by class and loop alone would also join distinct objects: on one loaded recording
it would have moved 153 s of listed waits, spread over 32 and 42 addresses on two threads,
into waiting for work, and those were browse consumers waiting for data. What tells the
two apart is when the address changes. An object gets a new address only under a moving
collection, so every change of a moved lock, from one wait to the next, spans a pause;
a thread that waits on a new object per request changes whenever the request does. That
alone is not evidence when the waits are long against the time between pauses: a
consumer waiting 800 ms on a new future per request, with a collection every 200 ms, has a
pause inside every change because it has one inside every wait. So the changes must also
be unlikely to have met their pauses by chance. A change of length `L`, in a stretch whose
`k` pauses come over `T`, meets one by chance at odds of at most `L k / T`; the product
over every change has to be one in a thousand or less. On the recordings above, the moved
locks scored between 10<sup>-5</sup> (a monitor polling every 5 s, eight changes in eight
collections) and 10<sup>-19</sup>, and every thread waiting on a new object per request
either had changes with no pause in them (20 of 31, 28 of 41) or scored 1.

That is evidence, not proof, and a review built the case that defeats it: a consumer
waiting on a new future per request, whose own work between two waits allocates enough to
set off a young collection every time. Every change then has a pause in it by cause, not
chance, and 40 futures on 40 addresses scored 10<sup>-4.9</sup>; folded into waiting for
work, 11.9 s of a slow downstream became "no contention", the worst answer this command
can give. A pause inside the new wait rather than between waits would stop that case and
not the next (a backend that allocates while it produces each result). So the pieces are
never set aside on this evidence. They are gathered per thread and loop (the park locks
one thread alone waited on, from one loop, whose total has a perch's shape and whose
changes pass both tests; gathering by loop alone would find two waiters in a pool whose
workers each have a moved mailbox) and labelled: `locks` keeps every wait in `Blocked`,
says how much of it is on such locks, and lists them under `MOVED BY THE COLLECTOR` with
the number of addresses, the odds and the stack; `stalls` keeps the parked stalls and
names each such thread in a warning with the same evidence. On the ten recordings of that
node `Blocked` stays about 7m54s and the label accounts for 5m54s of it, all but two 60 s
waits (two waits on two addresses score 1: the recording cannot tell a moved lock from two
objects there); on one of them 35 s more stays unlabelled, a monitor's poll whose address a
logger's condition took later (section 9). The reader decides; the report says what the file shows. `WHERE THEY WAITED` shows
the pieces of a split lock under one stack.

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
transports (`epoll.Native.epollWait*`, `kqueue.Native.keventWait`, `uring.Native.*Wait*` and `*Enter*`),
`Unsafe.park` / `LockSupport.park*` and `Object.wait*`. `--idle` replaces them with a
comma-separated list of regular expressions, each of which must match a whole
`package.Class.method`; a hand-written poll loop or a queue `take` is one pattern away.

A frame `--idle` names is the thread's idle point for blocking events too: a sleep, an
`Object.wait` or a park whose innermost ten frames show it is the loop with nothing to do
and joins the waiting-for-work rule of section 4.5, since a loop that sleeps between polls
would otherwise be stalled in its own sleep. The default patterns are not applied this way,
nor is any pattern that names the wait itself (`Unsafe.park`, `LockSupport.park*`,
`Object.wait*`, `Thread.sleep*`): those frames are under every wait of their kind, and a
sample there is the thread at rest only because the sampler cannot see what it waits for.

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
of the previous one, capped at the gap or at three periods, whichever is longer. A run at
least the gap long, with at least two samples, is a candidate. Samples further apart do
not chain: nothing proves the thread was busy between them, and a loop that briefly
returned to the selector between two sparse Java samples would otherwise be reported as
busy. The cap stops at three periods because that is the sampler's own resolution: capped
at the gap alone, a gap at or below the period chained nothing, and `--gap 10ms` on a
10 ms recording found one busy run where `--gap 50ms` found twenty-two. Because the limit
can then reach past the gap, two chained samples at least a gap apart end the run when a
JVM pause or the thread's blocking events explain the stretch between them as they would
a silence: a 52 ms collection between two busy samples 56 ms apart, at a 20 ms period,
was folded into a 60 ms `BUSY` instead. An unexplained stretch still chains, so a smaller
gap finds fewer runs only where a pause or blocking events explain the stretch between two
samples, which is then that stall instead. A thread with no
two consecutive Java samples has no Java cadence (`—`) and chains by the period: the
spacing of its native samples is not a measure of running Java. The verdict is `BUSY`
when one culprit frame (the innermost non-JDK frame) owns at least half the samples, and
at least two of them, naming it and the share — "50 % of 2 samples" is one sample and a
guess; `SATURATED` when no frame dominates and there are at least five samples, which is
a loop with too much work rather than one long task. If blocking events, together and
whatever their kind, cover at least half the run (a synchronous read shows as native
samples in `read0` *and* as `jdk.SocketRead` events), the events win and the verdict is
the largest group's; the detail counts the others when they were needed to reach half.

**Silence** (weakest). Two consecutive samples further apart than the thread's routine
absence indicate a state the sampler cannot observe: blocked, in the VM, at a
safepoint. The silence is attributed to whichever group of blocking events, then GC
pauses, then safepoints covers at least half of it; otherwise it is `UNEXPLAINED`. A
group is one kind of I/O on one peer or path, whatever the byte counts: twelve
20 ms reads from one backend explain a 300 ms silence together, as
`12 × blocking socket read from backend:9000 (1.27 KB)`; lock waits group by instance, so one
lock taken from several places is one answer. What that leaves unexplained is tried again
with lock waits grouped by stack, because an instance is an address and the collector moves
the object a thread parks on: a pool worker idle on its own queue for 3m52s of a 26-minute
node recording parked 122 times on three addresses of one `SynchronousQueue`, none of them
half the silence, and was reported as the recording's worst stall, `UNEXPLAINED`, while its
parks covered 230.6 s of 232.9; by stack they are the worker at rest. Neither key alone
does: by stack only, one monitor taken from two lines of a method split into halves that
covered nothing; by lock class, an idle park on a `ConditionObject` stood for three busy
waits on another and dropped a real stall. A stack group whose waits named several
instances names the class (`178 × parked on dev.app.Queue`), and for monitors every thread
that held one of them. Pauses group the same way, and
a silence they explain is cut to them, from the start of the first to the end of the
last: between pauses the thread may have been running, and a stall longer than what
stopped it overstates it. When that stretch is shorter than a gap while the pauses still
cover the silence by the rule blocks are held to (half of it, for a silence past the routine
absence and under two gaps), the silence is theirs whole and the detail says how much of it
they stopped: a 70 ms silence with a 45 ms collection in it read `UNEXPLAINED`, "no blocking
event", beside the collection that explains it. A 300 MB heap under the serial collector silenced a thread for
1.18 s under 128 collections of at most 12.8 ms, and one collection's name on that row
read as a single pause of more than a second; the detail now reads
`128 × GC pause, 1.02 s stopped in total, longest 12.8 ms (GC Pause (gcId 439))`. If
several watched threads have an unexplained silence at the same moment, the detail says
so: that is the sampler, or a pause the recording did not capture, far more often than
independent bad luck.

**The ends of a thread's life.** A call still in progress when the recording stopped is
not in the file (section 6), so a thread stuck from the middle of the recording to its end
leaves nothing but its last sample: between two samples there is no silence to find. When
the recording carries `jdk.ThreadStart` and `jdk.ThreadEnd` (both JDK profiles enable
them), the thread's life inside the window is known — from the span's start or its start
event, to the span's end or its end event — and the stretch before its first sample and
after its last are silences like any other, labelled as reaching the thread's first or
last moment in the recording; a thread never sampled is one silence, its whole life.
Without those events a thread's birth or death cannot be told from a stall, and the ends
are not judged. Two more conditions keep the ends honest. An unexplained silence needs a
routine absence to be longer than, which a thread seen fewer than twice does not have, so
for it only an explained one is reported. And the stretch after the last sample of a
thread seen waiting for work where the sampler cannot see it (section 4.5) is not called
unexplained: its last park has most likely not ended yet.

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
  chaining) and between consecutive native samples (the *native cadence*, printed); a
  thread with no such pair has none, printed `—`;
- the 90th percentile of each (the *routine absence*), over every consecutive pair when a
  thread has no pair of that kind, since an absence is about any observation.

An *unexplained* silence counts as evidence only when it exceeds
`3 × max(Java p90, native p90)`, whatever the neighbouring samples showed, because
between any two samples the thread may have passed through the other state unseen. The
PER THREAD table prints that threshold as `Unseen below`, and a dash when it is past the
recording's length, where nothing the samples could show would count. An explained silence does not
depend on it, with one condition: below the threshold, what explains it must itself cover
a whole gap, because the thread may have been idle for the rest; above it, half of the
silence is enough, because the silence is evidence in its own right. Event stalls never
depend on it.

**The verdict comes first.** On a live node, `stalls` on 24 event loops printed `0 found`,
above three per-thread warnings, "21 more", and a table of zeros; the answer to "did the
loops stall" was really "this recording cannot see a loop stall shorter than 1.75 s unless
an event explains it", and nothing said so up front. So an `Unseen` line leads the report,
before the warnings and the stalls, one per kind of limit, and an empty stall list points
at it. The kind matters, because it decides the remedy:

- *The sampler's pace.* A thread where one slot can see it for at least half its life,
  whose routine absence is mostly that slot's round trip (at most twice it), is limited by
  the sampler. Its share is estimated from the round-robin: when a slot is contended, every
  thread that sat in its state all along gets the same, highest, sample rate, so a thread's
  rate against the highest rate of that kind, over every thread in the recording, watched
  or not, is the share of its life the slot could see it in (inferred from the sampler's
  design; threads with fewer than ten samples set no rate). The round trip is the gap
  between samples of the most-sampled thread. The line says how often each is sampled, how
  many threads share the slot (round trip over period), and the period that would do:
  a thread's absence is the round trip, which scales with the period, plus time of its own,
  which does not, so the shortest visible stall at period *p* is
  `3 × (absence − round trip × (1 − p / period))`. The JFR sampler takes no period shorter
  than 1 ms: on JDK 25, with twenty threads in native socket reads, 20 ms sampled each
  every ~454 ms, 10 ms every ~233 ms, 1 ms every ~25 ms, and 0.5 ms the same as 1 ms. The
  prediction errs on the safe side: 10 ms predicted ~97 ms at 1 ms, and a recording at
  1 ms measured 76.8 ms, because the part the model calls the thread's own is taken from
  the 90th percentile. On the node, 1 ms would take the loops from 1.75 s to ~123 ms.
- *The thread's own absences.* Everything else with a blind spot: idle workers and timers
  parked where no slot sees them, and threads seen in long bursts, like the demo's loops
  blocked on the registry for stretches of a quarter of a second. No period helps much,
  the line says so, and blocking events are what show their stalls.

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

A thread's stalls are disjoint: no moment of its life is counted twice, so its
"stalled" total never exceeds its life in the window. Where evidence overlaps, the stronger
claims the time and the weaker keeps only what is left:

1. Event stalls, exact. A blocking event that begins inside an earlier one is part of it:
   nested it adds nothing, overlapping it keeps only what it adds past the end, if that is
   still a gap long. Joined files (section 6) are where two copies of one sleep met.
2. Silences a JVM pause explains, cut to the pauses, which are exact too.
3. Runs of samples, as good as the sampling, up to their last sample.
4. Silences explained by blocks, and unexplained ones: the weakest evidence.

A silence or a run that the event stalls already cover by half between them is dropped
whole: two one-minute waits inside a silence of 2m22s, each a stall of its own, otherwise
came back as a third stall the length of both. Anything else that overlaps a stronger
stall is cut to the time left, and each piece at least a gap long is judged again, as the
same kind of candidate, rather than trimmed: the explanation of the whole may rest on the
very events that now stand as stalls of their own. On one load-test thread a
`29 × parked` silence of 649 ms held one 127 ms park listed as its own stall; on the
demo, a client thread whose short parks and socket reads interleave was stalled 18.0 s in
a 15.2 s recording, each read counted once as an event and again inside the silence of
parks around it. A run's tail, one sampler period estimated past its last sample, gives
way to a stall that starts inside it. The report sorts by duration, summarises by verdict
and by thread, and lists the JVM-wide pauses.

The same waiting-for-work rule as section 3 applies to blocking events: a park, a wait
or a sleep whose stack shows a pool's own idle frame, a frame `--idle` names (section 4.1),
or the loop that section's shape rule recognised in this recording, is not a stall,
however long it is, and a warning says how many were left out and what they totalled.
The shape rule is decided as `locks` decides it, on every thread's parks and before any
filter: watched alone, one of two threads sharing a queue looked like the queue's only
waiter, and one dispatcher out of thirteen went from 86 stalls to none when its twelve
siblings were watched with it. A park with no blocker object never makes a perch: every
`parkNanos` in the JVM shares that one "lock", and a retry loop's backoff would be filed
away as its idle point. Weighing every thread's parks costs about 15 ms of reading (8 %)
on a 5.7 MB recording with 55 thousand of them when `--thread` selects a few threads. The rule runs at all three doors: on the event, on a silence's
explanation, and on the explanation of a run of samples. That last one matters because the
sampler sees a park as native code rather than as the thread's idle point, so a worker's
own waiting chains into runs and would otherwise come back as a stall after being kept out
of the other two. A stack is matched there on what its frames say, not on the stack object:
a blocking event and a sample taken in the same park are two stacks with one meaning. The block stays in the timeline,
because it is still what explains the silence in the samples — dropping it outright
would turn a 1m10s idle worker into a 1m10s `UNEXPLAINED` stall, which is a worse answer
than the one being rejected. The same check therefore runs on the explanation of a
silence as well as on the event itself.

**Timer loops are scheduled idle.** With `--thread '*'` on a live node, every one of the
top 25 stalls and 99 % of the stalled time were threads waiting on purpose: a cleaner, a
configuration poll in `wait(60 s)`, two `java.util.Timer` threads, a wheel timer's 2,634
sleeps of 100 ms. The recording says which waits were the thread's own choice of time:
`jdk.JavaMonitorWait` carries `timedOut`, `jdk.ThreadPark` its `timeout` (nanoseconds) or
`until` (an epoch-millisecond deadline) to hold its duration against, and `jdk.ThreadSleep`
its `time`. The deadline is read from the wall clock and the event's end is JFR's tick
clock placed on it at the chunk's start, and the two can disagree by a clock tick: on
Windows a 150 ms `parkUntil` that ran its course was seen ending before its deadline, so an
end within 16 ms of the deadline counts. A wait that ran out its own timeout was not held
up by anyone. It is not idle
by that alone, though: an event loop that sleeps is the bug this command exists to find,
and a caller whose `get` with a timeout gave up waited the whole time for nothing. So the
rule takes the shape `Perch` takes (section 3), per thread: waits from one place (the loop
as its stack prints) that ran out their timeout at least twice and, those waits alone,
for more than half the thread's life in the window. Every wait from that place is then the
thread at rest, including one something woke early, since a timer thread is woken whenever
a sooner task is scheduled, and it is kept out at all three doors, like a worker waiting
for work, with one warning that counts the waits and names the threads. On that node the
rule set aside 2,930 waits, 20m41s, on five threads, exactly the count of those waits in
the file, and the stall count fell from 3,147 to 206; across seven recordings of that
service every thread it named was a timer. An event loop at its selector cannot meet the
half-life line with its sleeps, and one timed-out `get` cannot meet the count. Being events
only, the rule is exact. `--idle none` turns it off.

Every stall is clipped to the recording's span, for the reason section 3 gives for
waits: a blocking event that began before the file, or was still running at its end, is
in the file whole, and counted whole it puts more time in the window than the window
holds. The gap is then applied to the clipped length — 700 ms of blocking with 20 ms of
it inside the window is not a 50 ms stall — and a warning says how many stalls were cut.
A busy run's tail, which is an estimate (one sampler period past its last sample), stops
at the end of the recording for the same reason. Silences need no clipping: they are
bounded by two samples, or by a sample and an end of the thread's life inside the span,
all inside it by construction.

**Unexplained gaps are ranked apart.** A gap with no event and too few samples is the
longest number the report can produce and the one that says least: a 47.4 s `UNEXPLAINED`
outranked an actionable 17.1 s park on the same page. The verdict is honest and stays;
the ranking was the mistake, because a gap and a park are different kinds of claim. They
are listed under their own heading, after the stalls with an explanation, with the count
of `jdk.SocketWrite` events in the file beside them — a recording that streamed gigabytes
can hold sixteen, because an HTTP stack that buffers its own writes produces none, and a
gap on a thread that was writing a response is then all the evidence there is. Both kinds
still count in `BY VERDICT` and in the per-thread totals.

**`info`.** The thresholds and throttles it prints are derived from the settings in the
file, not from a list of event types written into the tool: the line exists to answer
"did the settings I asked for take effect", and a fixed list answers it only for the
events someone thought of. Derived, it first printed 41 entries, most of them JDK
defaults nobody chose; a threshold of zero suppresses nothing and so is not a threshold,
and the line now leaves those out and sorts by name, so two runs of the same file produce
the same line and two reports diff. The zeroes are still in the per-type table below,
where they are a fact about one event type rather than a claim about the recording. The
thread count is the threads *seen in events*, which is
why it moves with the window's activity rather than matching a thread dump, and it is
labelled as such; the `THREADS` section folds them into families by replacing each run
of digits with `N`, because that is what a pool varies per worker and what a `--thread`
glob has to match.

Seen is not alive, and on a live node the difference read as a leak: one family was 28
threads in one window and 42 in the next, another 39 then 64, while `jstack` a minute
apart showed 132 and 133 threads. So `info` also takes a census. At every chunk boundary
the JVM writes `jdk.ThreadAllocationStatistics`, one row per live Java thread, every row
of a batch under one timestamp: the earliest batch is who was alive when the recording
began, the latest who was alive when it ended, including threads parked through the whole
window that no other event names. `jdk.ThreadStart` and `jdk.ThreadEnd` give the lives in
between. Three details make the counts add up, and alive at start plus started minus ended
equals alive at end on every recording it was checked on (eleven, from JVM boot to a
four-minute window): starts and ends are counted as events, not threads, because the JVM
stops and restarts its dynamic compiler threads under one id; only those between the two
batches count, because a recording taken from boot starts threads before its first
census; and a start is not a new life for a thread already alive, because `main`, in the
first census of such a recording, gets a `jdk.ThreadStart` afterwards. On that node the
two worrying families were virtual threads, which the census does not cover, and a pool
steady at 4 alive while 60 workers started and 60 ended. The census covers Java platform
threads only: a family with a thread no census row or start or end event names (a
virtual thread, a GC worker, the VM thread) prints a dash, not a zero, since zero is a
claim the recording does not make. Those dashes look alike, so when any family has a
virtual thread the table gains a `Virtual` column with how many are: 202 dashed
`opcua-metadata-preparation-churn-N` threads on an edge node read like a VM thread's until
it said they were virtual. All of it is exact; none of it is sampled.

## 5. Output

Text goes to standard output in fixed-width tables meant for tickets and chat; each
stall line ends with `[samples]` or `[silence]` unless it came from an event. A stall
list prints each distinct stack once and later rows say `same stack as #n`: every stall
keeps its own row, because they are separate occurrences and not one aggregate, but one
lock convoying two event loops filled fifteen rows with the same seven lines — 138 lines
of report where 54 say the same thing. Both renderers do it, each keyed on its own
rendering, since the text list elides at six frames and the HTML table at twelve. `--html`
writes one self-contained file: no scripts, no external resources, inline SVG
timelines with a box per stall (or per wait) and a tooltip with the detail. A timeline
row draws at most 2,000 boxes, the longest ones: a recording with a 1 ms threshold can
hold hundreds of thousands of waits, and a file of hundreds of megabytes helps nobody.
`--json` writes one document for a program to read, with the field names as the contract
(docs/JSON.md). Text, HTML and JSON come from the same report objects and take the same
`--top`, so they never disagree.

The summary comes first. A `stalls --thread '*'` run on a live node was 235 lines, and
its `PER THREAD` table listed all 134 threads alphabetically, most with no stall, after
the stall list: the reader, human or agent, read the evidence before learning what it
added up to. The text report now opens with `BY VERDICT` and `PER THREAD`, as the HTML
page always did, and lists only the threads that stalled, most stalled first, bounded by
`--top`, with one line counting the rest; what the others cannot show is the `Unseen`
line's to say (section 4.3). The same run is 93 lines.

Times are UTC, and say so: the report header prints an ISO instant, `jfrq-live` its clock
times as `12:23:21.688Z`, and a dump's default file name is stamped in UTC. A recording is
read on other machines and next to other tools' output, and a clock time without a zone
is a guess; `jfrq-live` used to print local time beside a UTC header.

## 6. Damaged and unusual files

- **Not a recording, empty, or a future format version:** refused with a one-line
  message and exit status 1. The chunk header's magic and major version are checked
  before the JDK parser is involved.
- **Truncated** (the disk filled up, a copy was cut short): the complete chunks are read
  and every report carries a warning naming the chunk the file ends inside. A file with no
  complete chunk is refused. A chunk whose size field claims more bytes than the file has
  left, however large (a damaged header), is the same case.
- **Cut and joined** (a truncated file with another recording appended, or bytes that are
  no chunk between two that are): the complete chunks on either side are read and the
  warning gives the damaged byte range, with the time span its header declared when it
  has one. A join across the damage is judged against that declared span (section 1).
- **Still being written** (a live recording copied from under the JVM): refused. When
  complete chunks precede the open one, the message gives the size to truncate the file
  to; when the open chunk is the only one there is nothing to keep, and the message says
  to dump the recording instead. The JDK parser would otherwise spin forever waiting
  for the chunk to finish; see section 1. Questioning a running JVM is what `jfrq-live`
  is for ([LIVE.md](LIVE.md)): it takes windowed dumps, which are finished files.
- **Several recordings in one JVM:** the span is the file's own (section 1); the
  settings reported are the last chunk's, since settings can change between chunks.
- **Files joined** (`cat a.jfr b.jfr`, which the JDK parser reads without a word): the
  JVM starts each chunk of a recording exactly where the previous one ended, so a chunk
  that starts more than a millisecond after that is a hole no run recorded, and one that
  starts before it comes from another run. Either is a warning on every report: the span
  covers both files, so rates over it are diluted, and a silence across the hole is not
  a stall.
- **A blocking call still in progress when the recording stopped** is not in the file at
  all: JFR writes an event when it ends. The stall it caused ends with the recording as
  far as any tool can tell, and `stalls` reports it as the silence after the thread's
  last sample when the recording bounds the thread's life (section 4.2); its detail says
  it runs to the thread's last moment in the recording.

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
- `health`'s evacuation-failure finding needs a heap with no room left, which the test
  JVM does not have: the test starts a JVM of its own with a 48 MB heap held nearly full,
  records it, and checks the count of failed collections against the file's own `gcId`s.
- JaCoCo enforces line coverage of 85 % on `core` and 80 % on `cli` and `live` in `./gradlew check`.
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

`--timing` prints three phases: `parse`, `analyse` and `render`. The analysis runs
inside the read, in the sinks' `finish()`, so a single "read" number could not say which
of the two a change had moved. Under `alloc --baseline` the two files are read at once,
and `analyse` is the `finish()` time of whichever read ended last (the reader keeps one
number per process), with `parse` the rest of the wall-clock read: the sum is exact, the
split only indicative. What produced the numbers,
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

Taking the span from the chunk headers alone (2026-09-24, section 1) also removed the
reader's own end-time read on every event, one `Instant` each: `info` on a 33 MB demo
recording went from 235 ms to 213 ms of `parse` (median of four alternating runs).

On a loaded node's recording whose `locks` report lists 13 000 waits (three times the
4 000 it listed before the 2026-09-24 changes to what counts as waiting for work moved
9 000 parks back into contention), `render` went from 52 ms to 68 ms: every section is computed
from the waits, and two were computed twice, the ranked locks (for the table and for its
stacks) and the waits sorted longest first (for the convoys and for `LONGEST WAITS`).
`ContentionReport` now computes each once and sums a lock's totals in one map lookup per
wait instead of four: 42 ms (medians of fifteen alternating runs). The holder chain of
section 3 is indexed by lock, then thread, in end order, so each step is one binary
search; on a node whose chains reach 88 threads (56 000 steps over 2 900 monitor waits)
`locks` `analyse` went from 159 ms to 138 ms and `stalls` `analyse` from 72 ms to 80–88 ms.

Field reads were the next cost once `stalls` read every thread's parks (2026-09-24). Each
optional field was read as `hasField` then a getter, and the JDK's typed getters
(`getThread`, `getClass`, `getString`, `getStackTrace`) check the declared type with one
more by-name scan before the one that reads, so a lock's class name cost three linear
scans of the event's descriptors on every park and monitor event. The fields the
analyses read are now `int` tags (`Fields`); which of them an event type has is one
`long` mask, resolved with `hasField` on the first event of each `EventType` object and
then by identity (the interner checks the last type first, so every sink's lookups on
one event hit that entry). A present field is read with one `getValue` scan and an
`instanceof`; the typed getter runs only when that yields `null` or an unexpected type,
so results and exceptions are the JDK's. The consumer API has no read by index, so one
scan per value read remains. Median `parse` of fifteen alternating cold runs, and median
in-process time of the last twenty of thirty runs (warm, whole command), same JVM flags:

| Command | Cold, before | Cold, after | Warm, before | Warm, after |
|---|---|---|---|---|
| `stalls --thread 'milo-*'`, 5.7 MB v2 load recording | 157 ms | 134 ms | 39 ms | 35 ms |
| `stalls --thread '*'`, same file | 157 ms | 132 ms | 47 ms | 41 ms |
| `stalls --thread '*'`, 33 MB demo recording | 124 ms | 110 ms | 36 ms | 36 ms |
| `locks`, 33 MB demo recording | 98 ms | 90 ms | 23 ms | 22 ms |
| `alloc`, 33 MB demo recording | 196 ms | 175 ms | 44 ms | 38 ms |

Output is byte-identical before and after on `stalls`, `locks`, `alloc` and `info` of
both files.

Labelling the pieces of a moved lock (section 3, 2026-09-25) costs `locks` its read of
`jdk.GCPhasePause` and both commands a pass over every park lock one thread alone waited
on. Median of three alternating cold runs on a 19.8 MB, 22-minute recording of a loaded
node, `--thread '*'`: `locks` analyse 121 → 129 ms and `stalls` analyse 149 → 164 ms;
parse within noise for both (58 pause events against 277 thousand parks).

## 9. Known limits, in one place

- A wait shorter than the recording's threshold for its event does not exist in the
  file. Record with 1 ms thresholds when hunting sub-20 ms stalls.
- `PARKED` never names an owner: JFR does not know who holds a `java.util.concurrent`
  lock.
- A wait or a stall that straddles an end of the recording is counted only for the part
  inside it (sections 3 and 4.5), so the same wait reads shorter in a narrow window than
  in a wide one. The `locks` note and the `stalls` warning say when this happened.
  Neither a `stalls` per-thread share nor a `locks` one can exceed 100 %: a thread's
  stalls are disjoint (section 4.5).
- Unexplained silences below `3 × routine absence` are invisible; the `Unseen` line says on
  how many threads, why, and which sampling period would help (section 4.3). A thread is
  sampled at best every 1 ms times the threads sharing its slot; below that, lowering the
  blocking thresholds so the explanation comes from events, or running fewer threads in
  native code during the recording, are the remedies.
- A throttled event type (`jdk.SocketRead` at 300/s in the JDK's `profile` settings) is
  sampled, not recorded in full; the warning names it, and `throttle=off` in the
  recording settings removes the limit.
- The allocation estimate omits each thread's first sample (section 2); a thread
  that was sampled once in the whole recording contributes nothing to the estimate, and
  its JVM counter, when there is one, is the number to read.
- Allocation on virtual threads is mostly missing from the estimate: each virtual thread
  loses its first sample, which carries its carrier's history, and most are sampled once.
  The warning prints how many samples and bytes were left out. It is not a floor either:
  a virtual thread that moved to a carrier not yet sampled in the recording carries that
  carrier's history on a later sample, at most one sample per carrier. A single virtual
  thread's row is carrier-scoped, and there is no JVM counter for a virtual thread; a
  carrier's counter covers the virtual threads it ran (section 2).
- `SATURATED` needs five samples and `BUSY` two on its culprit; with the `default`
  settings' 20 ms period and a 50 ms gap that is most of the run, so short saturated
  bursts go unreported rather than misreported.
- A thread blocked through the whole recording, with nothing ending inside it, is in no
  event at all; a matching thread the recording saw only in other events is named in a
  warning, with nothing to judge it by.
- A thread that waits for a result with a timeout, from one place, times out at least twice
  and spends more than half its life in those timed-out waits looks exactly like a timer
  loop, and its waits are set aside as scheduled idle (section 4.5). The warning names the five
  threads with the most time set aside and counts the rest; `--idle none` reports them as stalls.
- Lock addresses move with the objects; a lock that was compacted mid-recording appears
  under several addresses, and each piece can fall under a perch's half-window line. Such
  pieces are labelled as probably one moved lock (section 3) but never set aside, so an
  idle thread's moved mailbox stays in `Blocked` and in the parked stalls, with the label
  and its odds next to it. A short recording, a few long waits, or a collector that moves
  objects between its pauses (ZGC, Shenandoah) can leave too little evidence for the label.
  A thread whose own allocation sets off a collection per request can earn the label
  without a moved lock, which is why it is only a label.
- An address a second object takes later merges the two into one lock. `locks` then judges
  it by the stack of its longer wait, and `stalls`, which matches each wait's own stack,
  can disagree: on one recording three 5 s polls of a directory monitor shared an address
  with a logger's 60 s wait, and were contention in `locks` and idle in `stalls`.
- `health` cannot see the JVM's own `OutOfMemoryError` (the heap, metaspace) or any
  `StackOverflowError`: JFR does not record them (section 10). For the heap, a failed
  evacuation or a full collection is the warning it can give.
- `health`'s count of throwables created covers the stretch between the first and last
  `jdk.ExceptionStatistics` reading, once a second in both settings files; a burst in the
  last second of a recording is in the events but not the count.
- Verdicts name threads by the name JFR recorded for them. On JDK 25 a thread renamed
  after it started keeps the name it started with in every event (verified on a 21-chunk
  recording of a thread renamed a thousand times), so a pool that renames its workers per
  task shows none of the task names.

## 10. `health`: what the JVM reports about itself

The other commands answer a question about a problem already seen. A four-minute window
of a soak test held 2,829 throwables, a collection forced by a humongous allocation, and
heap, resident-set and thread counts once a second, and no command read any of it.
`health` reads what the others leave, in one pass: the collector's events
(`jdk.GarbageCollection`, `jdk.OldGarbageCollection`, `jdk.GCHeapSummary`,
`jdk.GCConfiguration`, `jdk.GCHeapConfiguration`, `jdk.EvacuationFailed`), the
once-a-second statistics (`jdk.CPULoad`, `jdk.JavaThreadStatistics`,
`jdk.ResidentSetSize`, `jdk.ExceptionStatistics`) and the throwables
(`jdk.JavaExceptionThrow`, `jdk.JavaErrorThrow`). Both JDK 25 settings files enable all of
them. On the 39-minute soak recording it takes 274 ms, against 327 ms for `info`.

**Findings are only what the JVM reported.** Each is one of a fixed list, ranked in this
order: an `OutOfMemoryError` created; a collection that failed to evacuate; a full
collection (`G1Full`, `SerialOld`, `ParallelOld`); pause time over the JVM's own goal of
`1 / (1 + GCTimeRatio)` of the time (7.7 % for G1's default 12); a pause over
`MaxGCPauseMillis`; a collection caused by a humongous allocation, by the metaspace
threshold, or by `System.gc()`. Each carries its count and when it first and last
happened. The times do work a rule would otherwise do badly: five metaspace collections
in the first 1.2 s are a JVM starting, and the same five spread over an hour are classes
loaded faster than they are unloaded. `GCConfiguration` reports a pause target only when
one was set; it was unset in every recording examined, so the pause finding is usually
absent rather than passed.

The out-of-memory finding sees only part of what its name says. JFR emits its throwable
events from the constructors, and the JVM makes its own `OutOfMemoryError` (the heap,
metaspace, an array over the size limit) and every `StackOverflowError` without running
one: a JVM driven out of heap three times, past the array limit and out of direct memory
recorded only the last, which `java.nio` constructs in Java. So the finding reports
direct-memory exhaustion, with its message, and says what it cannot see; there is no
stack-overflow finding, since it would only ever report a `new StackOverflowError()` in
someone's code. The heap's warning is an evacuation failure instead: G1 found no room to
copy live objects and left them in place. It is counted once per collection (`gcId`), because one collection can report
it more than once. In a 48 MB heap held nearly full, 379 of 779 failed collections
reported it twice.

A cause is counted once per trigger. G1 reports its concurrent marking cycle (`G1Old`)
as a collection of its own, with the cause of the young pause that started it, a
millisecond earlier. Counting both counted every humongous and metaspace trigger twice
(eight humongous collections on the soak recording that were four), so a `G1Old` event
counts as a collection and adds its pauses (remark and cleanup), but not a cause.

**Trends are numbers without a verdict.** Heap after GC, resident set, live threads and
JVM and machine CPU each get their start, end, range, mean, and the floor (the lowest
value) of their first and last thirds. A heap that leaks has a floor that climbs; one
that is merely busy has peaks that come and go. A "rising" rule on those floors was tried
and rejected as noisy: the 39-minute soak recording starts with the JVM, and its heap
floor climbs from 18 MB to 61 MB as the application warms up. Whether 3 MB of resident growth in four minutes matters
is the reader's call.

**Throwables are counted exactly and ranked from a sample.** `jdk.ExceptionStatistics`
carries the JVM's running total of throwables created. The difference between its first
and last reading is exact, but it covers only that stretch, so on a short recording the
events can outnumber it: the demo recording has 650 events against 596 created in 14.1 s
of its 15.2 s. An `Error` is traced twice in JDK 25, from `Throwable`'s constructor and
again from `Error`'s (`OutOfMemoryError` excepted), each time adding one to the running
total and emitting a `jdk.JavaExceptionThrow`; the second also emits the
`jdk.JavaErrorThrow`. So an event with `Error.<init>` on top is skipped, and the total loses
one per `jdk.JavaErrorThrow` inside its stretch. The test checks the corrected total
against the events inside the stretch, exactly. A `java.lang.NoSuchMethodError` whose message names a
`Holder` class in `java.lang.invoke` (`Invokers$Holder.linkToTargetMethod(...)`) is not a
fault: the JDK links a method handle by looking for a form generated ahead of time,
creates that error when there is none, catches it, and generates the form. JFR records the
creation, so any service that uses lambdas or method handles shows a few, at start-up or
when a call shape is first used (`MemberName.Factory.resolveOrNull` in JDK 25); the demo
shows seven in its first half second, five of them under Netty's cleaner. A count of them
is not a finding. `jdk.JavaExceptionThrow` fires in the `Throwable` constructor, so it counts
creations: an object made only for its stack trace counts, and a rethrow does not. It is
throttled (100/s in `default`, 300/s in `profile`), so the class and site shares are of
the events, and a class's rate is the exact total's rate times its share. That rate is an
average over the window, which a start-up burst and a steady trickle can share: 341
`ClassNotFoundException`s from +5.1 s to +605.6 s read as 0.3/s, half of them made by
+6.0 s. So each class also prints when its first, median and last were made; the median is
near the first for a burst and near the middle for a steady rate, whatever straggler comes
last, which the first and last alone cannot say. A site is the
code that made the throwable. The top of every such stack is its own construction:
`Throwable.<init>`, the superclass constructors (an application's own base exception
among them), the class's constructor, and sometimes a static factory of the class. The
site is the first frame outside the JDK below all that, and the stack shown starts there.
Naming the innermost frame outside the JDK instead would put every subclass of an
application's base exception on the base class's constructor.
