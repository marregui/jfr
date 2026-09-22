# Houston on Edge 2 — jfrq quality-control round, 2026-09-22

Live QC of the EDG-1065 branch under load, using `jfrq` / `jfrq-live` against a running node.
Two deliverables: findings on Edge, and feedback on the tool from having used it.

---

## 1. Setup (reproducible)

| | |
|---|---|
| Node | `~/HMQ/houston-edge2`, pid 34866, admin 8091, JDK 25.0.4.1, `-Xmx2g`, up 21 h |
| Build | `feature/houston-on-edge2` @ `f630eb360` (= PR hivemq-edge2#57) |
| Adapters | 6 × `opcua` (v1) + 6 × `opcua-v2`: `big` (63 k asyncua rig, :54840), `proxy` (via TCP proxy :53531), `melocoton` (Prosys), `sugus` (S7-1500), `cas` (CAS gateway), `wago` (PFC200) |
| Recording | started on the live JVM, no restart: `jfrq-live 34866 start --max-age 15m` — JDK `profile` + 1 ms blocking thresholds, I/O throttles off, alloc 1000/s. Attach + start took **5.4 s** |

Windows dumped with `jfrq-live … delta` (cursor chained, no event counted twice):
`idle0` 2m09s baseline → `w1` single export → `w2` 6 adapters × 6 concurrent rounds (105 s) →
`w2b` v1/v2 A/B → `w3` imports → `idle1` 10m18s idle.

Concurrent-round latencies (6 rounds, n=6 each, seconds):

| adapter | rows | min | max | avg |
|---|---|---|---|---|
| big | 62 939 | 2.62 | 3.31 | 2.94 |
| melocoton | 887 | 0.30 | 3.76 | 1.62 |
| proxy | 887 | 0.55 | **16.85** | 6.43 |
| sugus | 261 | 6.73 | 16.87 | 10.80 |
| cas | 270 | 14.57 | 19.60 | 17.22 |
| wago | — | — | — | **HTTP 500, 6/6** |

---

## 2. Edge findings

### 2.1 Scope first

**PR #57 does not touch `OpcUaNodeBrowser.java`** — it is byte-identical to `origin/main`
(`git diff origin/main...HEAD` on that path is empty). #57 adds the **v2** browse path
(`opcua/v2/*`, `adapter-sdk .../api/v2/model/Browse*`, `OperationLimits`, `ServiceFaults`,
`AttributeResolver`) plus the shared REST/serializer layer.

So: **nothing below is a regression introduced by #57.** E1–E4 are pre-existing defects in the
**v1** browse path that landed on `main` with the #1498/#1759 port — the "unify when it lands"
debt, now with a measured cost. E5–E8 are in the shared layer #57 does touch.
Every v2-path behaviour I probed was correct.

### 2.2 E1 — the browse result accumulates into a `CopyOnWriteArrayList`: **67 % of all JVM allocation**, quadratic

`OpcUaNodeBrowser.java:154` — `final List<DiscoveredVariable> variables = new CopyOnWriteArrayList<>();`
`OpcUaNodeBrowser.java:282` — one `variables.add(…)` per discovered variable node.

Every `add` copies the whole backing array under COW's monitor. On `big` that is 62 936 adds,
each copying an average of 31 k references.

`jfrq alloc w2.jfr --sites` over the 2m39s concurrent window:

```
Estimate   48.0 GB over 2m39s = 300 MB/s
BY CLASS   java.lang.Object[]   33.2 GB   207 MB/s   69.1%
BY SITE  1   24.5 GB  153 MB/s  51.1%   \
         2   5.24 GB  32.8 MB/s 10.9%    >  all three: Arrays.copyOf
         3   2.54 GB  15.9 MB/s  5.3%   /   <- CopyOnWriteArrayList.add:472
                                            <- OpcUaNodeBrowser.lambda$handleBrowseResult$0:282
```

**32.3 GB / 67.3 %** of everything the JVM allocated in that window is COW array copying.

The same monitor is the contention: `jfrq locks w2.jfr --thread 'milo-shared-thread-pool-*'`
shows five `java.lang.Object` monitors (one per concurrent browse) with
**33.8 s / 1 028 waits / 53.1 ms max**, 10.4 s, 9.78 s, 5.62 s, 3.92 s, and hand-off chains a
dozen threads long in the CONVOYS section. `jfr print --events jdk.JavaMonitorEnter` confirms
every waiter's stack is `CopyOnWriteArrayList.add → OpcUaNodeBrowser:282`.

Phase 1 completes before phase 2 reads `variables`, so no concurrent read exists to protect:
`ConcurrentLinkedQueue` drained into an `ArrayList`, or `Collections.synchronizedList`, removes
both the copying and the monitor. One line.

### 2.3 E2 — `sanitize()` compiles three regexes per path segment: **25 % of allocation, on the 2 API threads**

`OpcUaNodeBrowser.java:575-580`:

```java
static String sanitize(final String input) {
    return input.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]", "-")   // Pattern.compile
            .replaceAll("-+", "-")          // Pattern.compile
            .replaceAll("^-|-$", "");       // Pattern.compile
}
```

`sanitize` runs per **path segment**, and each node goes through `generateTagNameDefault` +
`generateNorthboundTopicDefault` + `generateSouthboundTopicDefault`. At ~4 segments that is
~36 `Pattern.compile` per node.

Measured on `pool-36-thread-1/2` (the admin API pool, §2.6): **12.15 GB combined, 25.3 % of all
allocation**; `Pattern` 795 MB + `Matcher` 746 MB + the `int[]`/`boolean[]` of `Pattern$BitClass`.
`jfrq alloc --sites` names `Pattern.compile:1946 ← OpcUaNodeBrowser.sanitize:577/578/579`.

Three `static final Pattern` constants. Zero risk.

### 2.4 E3 — the v1 attribute read ignores the server's `MaxNodesPerRead`: WAGO PFC200 export fails **7/7**

`OpcUaNodeBrowser.java:80` — `READ_BATCH_SIZE = 100`, hard-coded — and `readNextBatch` (line 401)
issues `batch.size() * 3` = **300 ReadValueIds** in one Read. The PFC200 enforces a lower limit:

```
Caused by: UaServiceFaultException: status=Bad_TooManyOperations
  at OpcUaNodeBrowser$BatchAttributeSpliterator.readNextBatch(OpcUaNodeBrowser.java:420)
```

The fix already exists — in the **v2** package, added by this PR, documenting this exact device:

```java
// OperationLimits.java:97
// MaxNodesPerRead. Never below 1. WAGO PFC200 / Codesys servers advertise 100 and enforce it…
return Math.max(1, Math.min(READ_BATCH_SIZE, maxNodesPerRead / ATTRIBUTES_PER_NODE));
```

A/B on the same device, same minute:

| | v1 route | v2 route |
|---|---|---|
| `wago` / `wago-v2` | **HTTP 500, 7/7** | **200, 87 rows, 0.70 s** |

This is the clearest evidence for unifying the two slices: the v1 path is broken on real hardware
that the v2 path handles.

### 2.5 E4 — the v1 full browse never descends below a Variable node: **55 nodes silently missing**

`browseRecursive` opens with (line 199):

```java
if (!visited.add(browseRoot)) { return CompletableFuture.completedFuture(null); }
```

but `handleBrowseResult` has already inserted the node when it classified it (lines 277-289):

```java
if (rd.getNodeClass() == NodeClass.Variable) {
    if (visited.add(nodeId)) { variables.add(new DiscoveredVariable(…)); }   // inserts
}
if (remainingDepth > 1) {
    childFutures.add(browseRecursive(nodeId, …));                            // always short-circuits
}
```

For a Variable, `browseRecursive` always returns immediately. Structured variables lose their
sub-variables.

Two independent proofs on the 63 k rig:

- Full browse, same server: v1 62 936 distinct node ids, v2 62 992. **v1 is a strict subset**
  (0 nodes in v1 and not v2). The 55 missing are exactly children of Variables:
  `/Server/PublishSubscribe/Diagnostics/*` (28), `ServerDiagnosticsSummary/*` (12),
  `/Server/ServerStatus/BuildInfo/*` (7), `ServerStatus/{StartTime,CurrentTime,State,…}` (5),
  `/Nasty/Struct/{A,B}`.
- Rooted at `ServerStatus`: **v1 returns 6 rows, v2 returns 14** — and v1's 6 are exactly
  ServerStatus's direct children, with `BuildInfo`'s own children missing one level further down.

The lane note records "visited-before-depth (v1 rule restored)" from the 09-21 round. If this
*is* the intended v1 rule, then v1 and v2 export different node sets from the same device and
that needs to be a stated decision rather than a silent difference.

### 2.6 E5 — the whole admin REST API is two threads, each held for the full browse

`InternalConfigurations.HTTP_API_THREAD_COUNT = 2` →
`JaxrsBootstrapFactory:56  Executors.newFixedThreadPool(2)`.

`jfrq stalls w2.jfr --thread 'pool-36-thread-*' --gap 200ms` shows both threads parked
**12.9 s and 12.8 s** inside

```
CompletableFuture.timedGet:1960
  … at com.hivemq.edge.adapters.opcua.browse.OpcUaNodeBrowser.browse(OpcUaNodeBrowser.java:163)
```

— the phase-1 `.get(TIMEOUT_SECONDS)`. So an export occupies one of the two API threads for its
whole duration; 6 concurrent exports queue 3 deep, and **every other admin API call queues behind
them**. That is the latency spread in §1 (melocoton 0.30 s → 3.76 s, proxy 0.55 s → 16.85 s), not
device slowness. With `maxRspTime=45` (D16) already on the table, this is the same conversation.

### 2.7 E6 — a classified OPC UA fault raised during streaming loses its classification

The pre-stream path classifies properly (409 / 503+Retry-After / 504 per the spec). Once
streaming has begun, `DeviceTagBrowsingResourceImpl.serializeSafely:306-310` converts
`UncheckedBrowseException` to a bare `IOException`, and the client gets:

```json
{"title":"InternalError","detail":"Internal error","status":500,
 "errors":[{"detail":"An unexpected error occurred, check the logs"}]}
```

for what the log records as `Bad_TooManyOperations`. The 500-vs-truncated decision itself is
correct (0 bytes were committed, so a clean 500 was the right call) — only the *reason* is lost.
`ServiceFaults`, added by this PR, is exactly the classifier this path does not use.

### 2.8 E7 — an import that selects nothing returns 200 with all zeros and no signal

Round-tripping an export straight back (887 rows, v1; 87 rows, v2) gives

```json
{"tagsCreated":0,"tagsUpdated":0,"tagsDeleted":0,
 "northboundMappingsCreated":0,…,"tagActions":[]}
```

in ~20 ms. This is *correct* — a browse export leaves `tag_name` empty and the user fills in the
rows they want (filling 50 in produced the expected `TAG_CONFLICT` refusals under `MERGE_SAFE`) —
but 887 rows in and silence out is the shape of a bug. A `rowsRead` / `rowsSkippedWithoutTagName`
pair in `ImportResult` costs nothing and removes the ambiguity.

### 2.9 E8 — every validation error carries `"row": null`

Both routes:

```json
{"code":"TAG_CONFLICT","message":"Tag '…' exists on the adapter and the file …",
 "row":null,"column":"tag_name","value":"…"}
```

The field exists and is never populated. On a 63 k-row CSV that is the difference between a
fixable error and an unfixable one.

### 2.10 Adjacent, not Houston

`wago-v2` logs a tag-read failure at **ERROR every 5 s**, currently `has failed 14 066 times`.
Handled, counted, recoverable — and at ERROR forever. Worth a ticket against the v2 adapter.

### 2.11 Not a defect: the browse-root parameter changed name *and* encoding

| | v1 | v2 |
|---|---|---|
| name | `rootId` | `rootNode` |
| value | bare device id, `i=2256` | **JSON string**, `"i=2256"` |

Both documented, and v2 rejects a malformed value with a good message
(`400 BrowseFilterInvalidError: invalid browse root: Unrecognized token 'not'…`). But unknown
query parameters are ignored, so a ported v1 caller sending `rootId=i=2256` to v2 gets **200 with
the entire address space** — 62 995 rows / 13.1 MB here — instead of the 14-row subtree. A line in
the migration notes, or an alias.

*(I initially recorded this as "v2 ignores rootId" and the CSV `# end count=N` trailer as a defect.
Both were wrong: the trailer is the documented completeness marker, and the parameter is renamed,
not broken. Retracted.)*

---

## 3. Memory and retention: clean

| | before | peak | after 10 min idle |
|---|---|---|---|
| threads | 182 | 235 | **141** |
| `milo-shared-thread-pool-*` | 33 | 87 | **3** |
| heap after full GC | — | 75.1 MB | **79.2 MB** |

No thread leak (the cached pool reaps below its own starting point), no heap growth. No
`DiscoveredVariable` or `BrowsedNode` instances survive a full GC — the browse pipeline releases
everything, including after the 8.6 MB / 62 936-row export. `NodeId` sits at exactly 55 389
instances before and after, i.e. steady-state adapter state, not accumulation.

Idle allocation 56.7 KB/s → 84.3 KB/s across the round (`jfrq alloc idle1.jfr --baseline idle0.jfr`);
at those rates and that sample count the difference is not supported (see T7).

The 300 MB/s of §2.2 is therefore pure garbage, not retention — which is why it never showed up as
a memory problem and needed a profiler to find.

---

## 4. Feedback on jfrq, from this session

Ranked by what it cost me. Every item has the evidence above behind it.

### What worked, and is the reason the findings exist

- **`alloc --sites` went from "the node is slow" to `OpcUaNodeBrowser.java:282` in one command.**
  That is the whole value proposition and it delivered. E1 and E2 are both entirely its work.
- **`jfrq-live` attached to a 21-hour-old production-shaped JVM and started a correctly configured
  recording in 5.4 s, no restart, no flags.** The cursor chained six dumps with no overlap and no
  gap, exactly as `LIVE.md` claims. The `Dumped / Window / Cursor` triple is the right three lines.
- **The sampling-cadence warning in `stalls`** — *"samples routinely up to 2.90 s apart;
  unexplained silences shorter than ~8.70 s cannot be seen"* — is the single most honest thing any
  profiler has told me. Keep it.
- **`stalls` elides the middle of a stack but keeps the first application frame**
  (`… 1 more` / `OpcUaNodeBrowser.browse:163` / `… 37 more`). That is what I actually wanted to see.

### T1 — `locks` is unusable on a server without idle classification *(the big one)*

On `w2.jfr` (2m39s, a real node under load):

```
Blocked    319m15s across 61064 waits          <- in a 159-second window
LOCKS BY TOTAL WAIT: 12/12 rows are pool threads parked waiting for work
LONGEST WAITS:       12/12 are ForkJoinPool.awaitWork, SynchronousQueue.poll,
                     logback's queue, ScheduledThreadPoolExecutor
```

The one actionable lock in the recording — the COW monitor, 33.8 s / 1 028 waits — is ranked
**below twelve idle parks** and never reaches a section that prints a stack. I only found it
because the CONVOYS section happens not to rank by duration.

`stalls` already knows how to do this (`--idle`). `locks` has only `--min` (useless: the noise is
the *longest* waits) and `--thread` (requires already knowing the answer). Proposal: give `locks`
the same idle notion, defaulted to the standard JDK "waiting for work" shapes —
`ThreadPoolExecutor.getTask`, `SynchronousQueue.poll/take`, `LinkedBlockingQueue.take`,
`ForkJoinPool.awaitWork`, `ScheduledThreadPoolExecutor$DelayedWorkQueue.take` — and split the
report into *contended* and *waiting for work*. That one change turns 61 064 waits into the five
that matter.

### T2 — there is no way to ask for the stack of a specific lock

`LOCKS BY TOTAL WAIT` names `java.lang.Object@714697020` and gives its totals. No section prints
a stack for it, and duration ranking guarantees it never will. I had to leave the tool:

```
jfr print --events jdk.JavaMonitorEnter --stack-depth 22 w2.jfr | awk '…/714697020/…'
```

A `--lock <address|class>` filter, or one representative stack per row in
`LOCKS BY TOTAL WAIT`, closes the gap. This and T1 are the difference between the tool finding E1
and me finding E1.

### T3 — waits are not clipped to the window, so `Share` exceeds 100 %

```
THREADS BY TIME BLOCKED
  logback-4    3m00s   1   3m00s   112.5%      <- window is 2m39s
```

`Text.java:226` — `pct(t.totalNanos() / span)` where `span` is the recording span, while
`totalNanos` counts the whole wait including the part before the window. Same cause inflates the
`Blocked 319m15s` headline. Clip each wait to `[windowStart, windowEnd]`. This matters more for
`jfrq-live` than for files, because every `delta` window slices mid-wait by construction.

### T4 — the `Waiters` / `Held by` columns print every distinct name

**3 630 characters on one line.** The `locks` output for a 100-thread server is 66 KB of which
most is thread-name lists. Cap at 3–5 plus `(+N more)`; the count is the information, the names
are not.

### T5 — `BY SITE` printed three rows that are identical in everything it shows

Ranks 1, 2 and 3 (51.1 %, 10.9 %, 5.3 %) display the same six frames and the same `… 12 more`.
They differ only in elided frames. A reader has to guess they are one site and add them up — and
the sum, 67.3 %, is the actual headline. Either merge sites whose visible prefix matches, or print
the frame that distinguishes them.

### T6 — `info` does not list thread names, but `stalls --thread` is mandatory

`README.md` says *"`jfrq info` lists the names in a file"*. It prints `Threads 118` and nothing
else; there is no thread name anywhere in its 80 lines. Since `--thread GLOB` is required, the
tool cannot tell you what to pass it — I had to use `jcmd Thread.print` on a JVM I happened to
still have. A `THREADS` section grouped into families (`milo-shared-thread-pool-N ×33`) would have
been the first thing I ran and the fastest orientation the tool could give.

Also: `Threads 118` / `149` / `235` varied with window activity, so it counts threads *seen in
events*, not live threads. Worth labelling.

### T7 — the allocation calibration line is easy to misread, and rate diffs have no support signal

```
Estimate   48.0 GB over 2m39s = 300 MB/s
Counted    14.3 GB by the JVM's own counters on the 116 threads seen at both ends of the file;
           the estimate for those is 14.0 GB (-2%)
```

The ±2 % validates **29 %** of the estimate; the transient pool threads that did two-thirds of the
allocating are by construction not "seen at both ends". Say the covered fraction.

Related, on `--baseline` between two quiet windows: `TagStatusSnapshot +397 %`, `ArrayList +469 %`
— on a base of 143 allocation samples. No row says how many samples support it. A sample count per
row, or suppressing percentages below a support threshold, would stop a reader chasing noise.

### T8 — the `stalls` default idle set misses the standard JDK pool waits

`pool-36-thread-1` parked in `ThreadPoolExecutor` → `ConditionObject.await` was reported as a
`1m10s PARKED` stall. It is a fixed pool with no work. Same list as T1 fixes it.

### T9 — `info`'s `Thresholds` line under-reports what is in force

`Text.java:70` passes a fixed whitelist: `JavaMonitorEnter, ThreadPark, ThreadSleep, SocketRead,
FileRead`. `jfrq-live start` sets seven — `SocketWrite` and `FileWrite` too, and the event table
below shows both at 1.00 ms. Checking "did my recommended settings take effect" is exactly what
that line is for. Derive it from the settings present rather than a list.

### T10 — `jfrq-live start` does not echo what it configured

```
Recording  1   jfrq-live   RUNNING  since 13:06:29.820, max-age 15m00s, to disk
```

Nothing about the thresholds, the throttles or the sample periods it just applied. Recoverable via
`info` on the first dump, but the operator is at the keyboard *now*, and this is the moment the
answer is cheap.

### T11 — small

- `--timing` printed `timing: read 207 ms` and no analysis line; README implies a breakdown.
- `--html` is correct and genuinely self-contained (24 KB, zero external refs, verified), but has
  no dark-mode block.

---

## 5. Not covered

- Only OPC UA adapters. No other protocol was exercised.
- No southbound / write path; no MQTT publish load running concurrently with the exports.
- JSON / YAML / NDJSON export formats untested — CSV only.
- `validateNodes=true` on import untested.
- The 55-node v1/v2 divergence (E4) is proven; whether the v1 rule is *intended* is a decision I
  cannot make from the code.
- No allocation profiling of the v2 path under the same load — the A/B in §2.4 was correctness
  only. Worth a second round if E1/E2 get fixed, to confirm v2 does not carry the same shapes.
