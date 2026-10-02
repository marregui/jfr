# Design: `info`

Part of the [design](README.md). What `info` reports about the file and its threads: settings, thread families and their census, attached threads, CPU per family, and thread starts.

**Thresholds and throttles.** The thresholds and throttles it prints are derived from the settings in the
file, not from a list of event types written into the tool: the line exists to answer
"did the settings I asked for take effect", and a fixed list answers it only for the
events someone thought of. Derived, it first printed 41 entries, most of them JDK
defaults; a threshold of zero suppresses nothing and so is not a threshold,
and the line now leaves those out and sorts by name, so two runs of the same file produce
the same line and two reports diff. The zeroes are still in the per-type table below,
where they are a fact about one event type rather than a claim about the recording. The
thread count is the threads *seen in events*, which is why it moves with the window's activity rather than matching a thread dump, and it is
labelled as such; the `THREADS` section folds them into families by replacing each run
of digits with `N`, because that is what a pool varies per worker and what a `--thread`
glob has to match.

**The census.** Seen is not alive, and on a live node the difference read as a leak: one family was 28
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
two families that looked like leaks were virtual threads, which the census does not cover, and a pool
steady at 4 alive while 60 workers started and 60 ended. The census covers Java platform
threads only: a family with a thread no census row or start or end event names (a
virtual thread, a GC worker, the VM thread) prints a dash, not a zero, since zero is a
claim the recording does not make. Those dashes look alike, so when any family has a
virtual thread the table gains a `Virtual` column with how many are: 202 dashed
`opcua-metadata-preparation-churn-N` threads on an edge node read like a VM thread's until
it said they were virtual. All of it is exact; none of it is sampled.

**Attached threads.** A `jdk.ThreadStart` names the new thread, the thread that started it
(`parentThread`) and that thread's stack. A thread native code attaches to the JVM (JNI
`AttachCurrentThread`, an FFM upcall from a thread the JVM did not make) has neither: on a
broker whose RocksDB event listener attaches a thread for every callback, 792 of 794
`Thread-N` starts in 18 minutes had no parent and no stack, and nothing else in the file set
them apart from threads Java code started. The JVM's own threads have a parent and no stack
(the compiler threads), and Java's have both, so both conditions are required. The `java`
launcher attaches `main`, and `DestroyJavaVM` is attached the same way. A file without the
`parentThread` field cannot tell, and says nothing.

**CPU per family.** Each family's `CPU` is the sum of its threads' shares ([health.md](health.md)'s rule).
A reading covers the time since the JVM last evaluated the thread, which the event does not
carry. The JVM keeps a thread's wall-clock time from its start and from each evaluation
after it. The file shows an evaluation as an instant two or more readings share, or as the
thread's own previous reading; a thread's last reading, at its end, has an instant of its
own. So each reading is weighed by the time since the latest of: the start of its thread's
life (a compiler thread the JVM stops and starts again keeps its name: `C2 CompilerThread1`
started 43 times on one broker), the thread's previous reading, and the latest instant
before it that two or more readings share. That is the time between evaluations as it ran:
on one loaded broker 22 of 104 periods of 10 s ran over 10.5 s, up to 22.7 s. Weighing every
reading as a period overstated the threads that end between periods: six threads that each
ran a second read 5.6 % of a 12-core machine for 0.6 %. With every reading weighed by its own
stretch, the Java threads of the JDK 25 Edge summed to 0.93 to 1.03 times the JVM's own
figure in 15 of 22 recordings, and to less in the rest ([health.md](health.md)). One stretch the file cannot
bound: an evaluation at which a single other thread had a reading is not seen, and a reading after it is weighed from the shared
instant before, which overstates it.

A thread whose start is in the file has its first reading counted from that start, unless
native code attached the thread. Its CPU time then counts from the native thread's creation
and its wall-clock time from the attach, so its readings also hold the CPU the native thread
used before the attach, which the file does not have. The JVM caps a reading at one core and
carries the rest into the thread's next reading (`jfrThreadCPULoadEvent.cpp`, JDK 25), so that
CPU comes out at one core a reading until it is spent. A RocksDB callback thread reads a
whole core, the cap, over the 0.003 ms to 2.9 ms since its attach (0.005 ms to 0.08 ms for 90 %
of 777 on one broker); weighed from the periodic instant before them, those readings came to
38.4 % on a broker whose threads used 8.0 %, and weighed from the attach to almost nothing.
Weighing from the attach fails for a thread that stays attached past an evaluation: when
`main` returns, the `java` launcher attaches the same native thread again as `DestroyJavaVM`,
which only waits. On a live JDK 21 broker its first reading, 2.544 % of 12 CPUs 10.03 s after
the attach, was the 3.06 s of CPU `main` had used, counted a second time; on another broker
the first reading was at the cap 0.85 s after the attach and the next, 2.11 %, carried the
rest. So an attached life's readings are left out up to and including its first reading
below the cap, and the readings after it count. The cap is one core of the JVM's CPUs,
exactly 1/N, so it is the largest reading in the file when that is 1/N; when it is not, no
reading was capped, nothing was carried, and only the first reading after an attach is left
out. A thread spinning a whole core from its attach on reads just under the cap (an attached
spinner on 12 CPUs read 8.296 % and 8.307 % in its first two readings), so its own CPU ends the
leaving out within a reading or two.

The VM's own `main` is the exception: HotSpot attaches the thread that creates the VM under that name, and its native
thread ran only the launcher before, so its first reading is its own start-up work (3 s of
spinning in `main` read 8.3 %, the JVM's own figure, where leaving it out read 0.0 %). A reading
with no start of its thread, no earlier reading of it and no shared instant before it in the
file covers a stretch the file does not hold: a thread alive before the recording, at the
first evaluation, or any thread's first reading in a recording without `jdk.ThreadStart`. That
reading is left out too, and the line under the table counts both kinds.

**Starts of `--thread`.** `info --thread GLOB` counts the starts of the matching threads, the
most in any 100 ms and in any second (a window is half open, so two starts one window apart
are in different windows) and when, and groups them by creator: the innermost frame outside
the JDK in the starting thread's stack, the rule `alloc --sites` uses, since `Thread.start`
and a pool's `addWorker` are the same for every pool. The stack shown starts at that frame.
On an Edge under overload, its cached pool's threads started 470 times in 100 ms, and 1 890 of
1 891 starts came from `MoreExecutors$ListeningDecorator.execute`, a future listener
dispatched to a pool that makes a thread whenever none is idle.
