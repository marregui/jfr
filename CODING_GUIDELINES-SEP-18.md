# Coding Guidelines for High-Throughput Java (2026-09-18)

These rules describe how to write Java that stays fast under sustained load: a
hot path that allocates nothing, boxes nothing, builds no `String`, throws no
fresh exception and takes no lock. They are distilled from the practice of
mature zero-GC Java systems and stated here generically. Every code example in
this document is original and illustrative; none is copied from any codebase.

Each rule has an ID (`G-<section>.<n>`) so a diff or a review can cite it. Where
a rule only applies with off-heap memory or a worker pool it is marked
**Skip unless**. An **Apply** line says what the rule means for this project: a
JDK 25 command-line tool that reads a JFR recording in one pass, on-heap, and
mostly single-threaded.

The one principle every rule follows from: **allocate once, then re-point.**
Objects are created at setup and reused; text is bytes or `CharSequence`
windows; absence is a sentinel; failure is a reused exception or a return code.

---

## 1. Data on the hot path

**G-1.1 Primitives, never boxes.** No `Integer`, `Long`, `Double` as fields,
parameters, return values or collection elements on any path that runs per
event, per row or per byte. Absence is a sentinel value, not `null` and not
`Optional`:

```java
public final class Nulls {
    public static final int INT_NULL = Integer.MIN_VALUE;
    public static final long LONG_NULL = Long.MIN_VALUE;
    public static final double DOUBLE_NULL = Double.NaN;

    private Nulls() {}

    // widening preserves nullness
    public static long intToLong(int v) { return v == INT_NULL ? LONG_NULL : v; }
}
```

*Apply:* a `long` nanosecond timestamp with `LONG_NULL` for "none" beats
`Optional<Instant>`; a `Map<String, Long>` on a per-event path is a defect.

**G-1.2 Two sentinel families, kept apart.** A collection's "no entry" value
(`-1` by default) and the domain's null (`MIN_VALUE`) are different constants
for different jobs. When `-1` is a legal payload the collection takes its
no-entry value in the constructor, or the caller uses the two-step
`keyIndex()` / `valueAt()` API (G-1.5) instead of `get()`:

```java
public LongList(int capacity, long noEntryValue) { ... }

public long getLast() {
    return pos > 0 ? data[pos - 1] : noEntryValue;
}
```

**G-1.3 Primitive-specialised collections, one class per key/value shape.**
`IntList`, `LongList`, `IntIntHashMap`, `LongObjHashMap<V>`,
`CharSequenceObjHashMap<V>`, and so on. Storage is a flat `int[]` or `long[]`.
There is no `java.util.Map` on a hot path. Iteration is an indexed loop, never
an iterator:

```java
for (int i = 0, n = list.size(); i < n; i++) {
    consume(list.getQuick(i));
}
```

*Apply:* a `HashMap<Long, ThreadState>` keyed by thread id becomes a
`LongObjHashMap<ThreadState>`; a `Map<String, Integer>` of class names becomes
a `CharSequenceIntHashMap`.

**G-1.4 A growable object list over `T[]` and `ArrayList`.** `getQuick` is
unchecked, `remove` nulls the vacated slot, `clear()` nulls every slot so
references are released. Primitive lists do **not** zero on `clear()`, they just
reset the position; if stale values matter, use `clear(int)` or fill explicitly.

```java
public final class ObjList<T> implements Mutable {
    private T[] buffer;
    private int pos;

    public void add(T value) { checkCapacity(pos + 1); buffer[pos++] = value; }
    public T getQuick(int index) { assert index < pos; return buffer[index]; }
    public int size() { return pos; }

    @Override
    public void clear() { Arrays.fill(buffer, 0, pos, null); pos = 0; }
}
```

**G-1.5 Hash maps: open addressing, power-of-two mask, `keyIndex()` sign
convention, backward-shift delete.** One probe returns both the answer and the
slot, so get-or-insert costs one hash and no entry object:

```java
public int keyIndex(int key) {
    int index = key & mask;
    if (keys[index] == noEntryKey) return index;        // free slot:  >= 0
    if (keys[index] == key)        return -index - 1;   // present:    <  0
    return probe(key, index);
}

private int probe(int key, int index) {
    do {
        index = (index + 1) & mask;                     // no modulo
        if (keys[index] == noEntryKey) return index;
        if (keys[index] == key)        return -index - 1;
    } while (true);
}

// caller: one probe, no boxing, no Map.Entry
int i = map.keyIndex(k);
if (i < 0) {
    map.valueAtQuick(i).increment();
} else {
    map.putAt(i, k, newCounter());
}
```

Capacity is rounded up to a power of two from `capacity / loadFactor`; load
factor 0.5 by default. A `free` counter is decremented on insert so the rehash
check is one comparison. Delete re-homes keys that were probed past the freed
slot instead of leaving tombstones. Object hash codes are spread
(`h ^ (h >>> 16)`) before masking because masking discards high bits; `int`
keys are masked directly. `contains(k)` is `keyIndex(k) < 0`; provide
`excludes(k)` because `!contains(k)` reads badly under the sign convention.
When iteration order matters keep a parallel `ObjList` of keys; do not reach
for a linked map.

**Rule:** a `keyIndex` result is valid only until the next mutation.

**G-1.6 The `getQuick` / `get` / `getQuiet` triad.** `Quick` means the caller
has already proven the precondition and the method checks it with `assert`
only (nothing at runtime in production). `get` throws. `Quiet` returns the
sentinel instead of throwing (`getQuiet`, `parseIntQuiet`, `indexOfQuiet`).
Maps follow the same triad: `valueAt(i)` checks the sign, `valueAtQuick(i)`
does not.

**G-1.7 Growth primitives.** `checkCapacity(n)` doubles with a floor at the
request; `setPos(n)` pre-sizes for index writes; `extendAndSet(i, v)` is the
sparse write. Guard against `int` overflow when doubling. Document that
`setPos` reveals uninitialised or stale slots.

```java
public void checkCapacity(int capacity) {
    int len = data.length;
    if (capacity > len) {
        long doubled = Math.max((long) len << 1, capacity);
        data = Arrays.copyOf(data, (int) Math.min(doubled, Integer.MAX_VALUE - 8));
    }
}

public void extendAndSet(int index, long value) {
    checkCapacity(index + 1);
    if (index >= pos) pos = index + 1;
    data[index] = value;
}
```

**G-1.8 Flat records in primitive lists with slot-offset constants.** Instead
of an object per row, one `LongList` with a power-of-two stride and named
offsets. Packed handles (`(hi << 44) | lo`) and packed type tags
(`tagOf(type) = type & 0xFF`, flags in the upper bits) follow the same idea.

```java
// per-thread timeline: 4 longs per block
static final int SLOT_SIZE  = 4;              // power of two
static final int OFF_START  = 0;
static final int OFF_END    = 1;
static final int OFF_KIND   = 2;
static final int OFF_ID     = 3;

long start = blocks.getQuick(i * SLOT_SIZE + OFF_START);
```

*Apply:* a thread's `(startNanos, endNanos, kind, monitorId)` blocks are one
`LongList` with a 4-slot stride and an `int` kind, not a `List<Block>` of
records.

**G-1.9 Static lookup tables indexed by tag, not maps or enum switches.** When
tags form a dense ordered range, a `static final` array filled in a `static {}`
block is one load per lookup:

```java
private static final int[] TAG_SIZE = new int[Tag.COUNT];
static {
    TAG_SIZE[Tag.BYTE] = 1;
    TAG_SIZE[Tag.INT]  = 4;
    TAG_SIZE[Tag.LONG] = 8;
}
```

State the invariant (dense, ordered, `COUNT` last) in a comment where the tags
are defined; other code depends on it.

**G-1.10 `int` constants vs enums.** `int` when the value is persisted, sent on
a wire, or switched on a hot path; `enum` when in-memory only and carrying
behaviour or a label. Chain constants (`B = A + 1`) and provide `nameOf(int)`.
A bag of booleans that together encode a kind should become an enum so an
object cannot silently lose its kind. Use enhanced `switch` with arrows; an
`int` switch compiles to a jump table.

---

## 2. Text and formatting

**G-2.1 `CharSequence` at API boundaries; `String` only for identity or
storage.** A tokenizer hands out a reusable window over its input; the
consumer takes a snapshot window when it must keep a token, and copies only
when a token has to be *assembled* from parts. A collection that stores a
`CharSequence` copies it unless the method is explicitly named
`...WithBorrowed`, in which case the caller guarantees lifetime and
immutability.

```java
// reusable window over the input; re-pointed, never re-created
public final class Window implements CharSequence {
    private CharSequence base; private int lo, hi;
    public Window of(CharSequence base, int lo, int hi) { this.base = base; this.lo = lo; this.hi = hi; return this; }
    @Override public int length() { return hi - lo; }
    @Override public char charAt(int i) { return base.charAt(lo + i); }
    @Override public CharSequence subSequence(int s, int e) { throw new UnsupportedOperationException(); }
}
```

**G-2.2 Keyword and name matching allocates nothing.** Length check first,
then per-char comparison with `| 32` for ASCII case folding. Never
`equalsIgnoreCase`, never `toLowerCase()`. Every comparison and parse helper
has a `(seq, lo, hi)` overload so callers avoid `subSequence`. Lookup maps hash
the raw `CharSequence` and support `keyIndex(seq, lo, hi)`.

```java
public static boolean isMonitorEnter(CharSequence tok) {
    return tok.length() == 20
            && tok.charAt(0) == 'j' && tok.charAt(1) == 'd' && tok.charAt(2) == 'k' && tok.charAt(3) == '.'
            && (tok.charAt(4) | 32) == 'j' && (tok.charAt(5) | 32) == 'a'
            /* ... */;
}
```

*Apply:* thread-name glob matching and event-type dispatch compare
`CharSequence` content, or resolve each event type to an `int` id once and
switch on the id.

**G-2.3 Numbers are formatted and parsed by hand into sinks.** `append(sink,
int)` writes digits directly and prints the sentinel as `null`;
`parseInt(cs, lo, hi)` throws the reusable numeric exception (G-5.1);
`parseIntQuiet` returns the sentinel. No `Integer.toString`, `String.valueOf`,
`String.format`, `DecimalFormat` or `Duration.toString()` outside setup and
final reporting.

**G-2.4 A sink protocol is the printing contract; `toString()` is debug-only.**
A self-typed fluent `CharSink<T>` returns `this` from every `put`, and
`putAscii` skips encoding when the caller knows the input is ASCII. Anything
that appears in a report, a log line or an error implements
`toSink(CharSink)`; `toString()` delegates to a thread-local sink and caps its
output so a debugger does not hang on a large structure.

```java
public interface Sinkable { void toSink(CharSink<?> sink); }

public interface CharSink<T extends CharSink<?>> {
    T put(char c);
    T put(CharSequence cs);
    T putAscii(CharSequence cs);
    default T put(int v)      { Numbers.append(this, v); return self(); }
    default T put(long v)     { Numbers.append(this, v); return self(); }
    default T put(Sinkable s) { if (s != null) s.toSink(this); return self(); }
    @SuppressWarnings("unchecked") default T self() { return (T) this; }
}
```

The thread-local sink is cleared on every acquisition: never hold it across a
call that may acquire it too.

*Apply:* the text and HTML report writers stream into one reusable sink via
`toSink`; no per-row `String` concatenation.

**G-2.5 `assert (x = expensive()) != null` switches behaviour under `-ea`.**
Assertions are on in tests and off in production, so an assignment inside an
assert gives the test build a fresh object with a real stack trace and the
production build the pooled one:

```java
public static ParseException instance() {
    ParseException ex = TL_INSTANCE.get();
    assert (ex = new ParseException()) != null;   // fresh instance only with -ea
    ex.clear();
    return ex;
}

@Override
public StackTraceElement[] getStackTrace() {
    StackTraceElement[] trace = EMPTY;
    assert (trace = super.getStackTrace()) != null;
    return trace;
}
```

---

## 3. Object lifecycle and reuse

**G-3.1 Allocate once, re-point with `of(...)`.** Heavy objects expose
`of(...)`, `init(...)` or `reopen()` that reset state and return `this`; the
constructor runs once. Flyweight views are re-pointed. A factory that produces
cursors or iterators reuses the same cursor instance across calls and
documents that callers must not hold a copy.

```java
public Cursor cursor(Source src) {
    return cursor.of(src);        // same field every call
}
```

**G-3.2 `Mutable.clear()` resets logical state; `close()` releases resources.**
`clear()` must reset **every** field; put a comment on the constructor or the
field block reminding maintainers to extend `clear()` and any deep-copy method
when a field is added. A `QuietCloseable` narrows `close()` to not throw so
cleanup chains need no try/catch:

```java
public interface Mutable { void clear(); }

public interface QuietCloseable extends Closeable {
    @Override void close();       // no IOException
}
```

**G-3.3 Object pools: `ObjectPool<T extends Mutable>`, a factory, a private
constructor and a `public static final FACTORY`.** `next()` clears the object
it hands out; mass release is `pool.clear()` in O(1). Individual release is
O(n) and reserved for exceptional cases. A parser owns one pool per node type
and `parse()` starts by clearing all of them.

```java
public final class ObjectPool<T extends Mutable> implements Mutable {
    private final ObjList<T> list = new ObjList<>();
    private final Supplier<T> factory;
    private int pos;

    public T next() {
        if (pos == list.size()) list.add(factory.get());
        T o = list.getQuick(pos++);
        o.clear();
        return o;
    }

    @Override public void clear() { pos = 0; }
}
```

Thread-local scratch follows the same shape: a `ThreadLocal<StringSink>`
initialised with `StringSink::new`, and `acquire()` / `release()` paired in
`finally` when re-entrancy is possible.

*Apply:* per-event stack and frame model objects are pool candidates; per-query
scratch collections are cleared, never re-`new`ed.

**G-3.4 Flyweight records: a moving window, not a value.** One record instance
is reused across rows; `hasNext()` both tests and advances; `toTop()` is cheap
and does not discard cached state; nothing obtained from the record (including
a `CharSequence`) survives the next `hasNext()` unless copied into a sink. When
two values from the same record are compared, use A/B accessor pairs with one
flyweight per column, so reading column 2 does not invalidate column 1.

**G-3.5 Factory reusable, cursor per execution, capability flags with a safe
default polarity.** A factory is built once and executed many times.
Capability flags are boolean `default` methods. A flag consulted for
*optimisation safety* defaults to the unsafe-to-optimise answer; a flag
consulted for *semantic legality* defaults to permissive. The two questions
have opposite safe defaults, so they must never share one property.

**G-3.6 Mark non-retained parameters.** A source-retention `@Transient`
annotation on a parameter means "this method does not keep a reference"; the
caller may reuse the pooled or flyweight argument immediately. Its absence on
a parameter that takes a flyweight signals that the callee retains it and the
caller must pass an owned copy.

```java
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface Transient {}

void addFrame(@Transient CharSequence className, int line);   // copies className
```

**G-3.7 A null object instead of a null reference on data paths.** A
`NullSource.INSTANCE` that answers every read with the type's sentinel; column
or slot arrays pre-filled with it; a `NOOP_ROW` instead of a `null` row; a
`NullLogRecord.INSTANCE` when the log queue is full. Callers never test for
`null` in the loop.

---

## 4. Resource ownership and cleanup

**G-4.1 Every `close()` is idempotent and safe on a half-built object.** Key
it on an is-open sentinel (`handle != 0`, `buffer != null`) or on a boolean
that is set **before** the real close runs, so a throwing close cannot be
re-entered by a second owner:

```java
@Override
public final void close() {
    if (!closed) {
        closed = true;      // flip first
        doClose();
    }
}
```

**G-4.2 Constructors that acquire resources roll back on failure.** Assign
non-owning fields (configuration, clock, facades) before the `try`; acquire
owning fields inside it; on any throwable call your own `close()` and rethrow.

```java
public Reader(Config cfg) {
    this.cfg = cfg;
    try {
        this.index = new IndexBuffer(cfg.indexSize());
        this.input = cfg.files().open(cfg.path());
    } catch (Throwable e) {
        close();            // idempotent, null-tolerant
        throw e;
    }
}
```

**G-4.3 `x = free(x)` — null-safe, returns null, nulls the field in one
statement.** Never `if (x != null) x.close()`. A list variant nulls each slot.
A best-effort variant threads a failure chain so **every** resource is
attempted and later failures are added as suppressed to the first:

```java
public static <T extends Closeable> T free(T o) {
    if (o != null) {
        try { o.close(); } catch (IOException e) { throw new FatalError(e); }
    }
    return null;
}

public static Throwable freeBestEffort(Throwable primary, Closeable o) {
    try { free(o); } catch (Throwable t) {
        if (primary == null) return t;
        primary.addSuppressed(t);
    }
    return primary;
}
```

A failure in `close()` is not control flow; it becomes an `Error`.

**G-4.4 Detach, then free.** In the real close, copy owned references to
locals, null the fields, then close the locals. A re-entrant close then finds
nothing to do:

```java
private void doClose() {
    final Cursor c = cursor;   cursor = null;
    final Filter f = filter;   filter = null;
    Throwable failure = freeBestEffort(null, c);
    failure = freeBestEffort(failure, f);
    rethrow(failure);
}
```

**G-4.5 Ownership is a tree.** Composites forward `init`, `close` and `toTop`
to children through interface `default` methods; leaves do nothing. Whoever
builds a partial tree tears it down on failure; a builder that folds a subtree
into a constant closes the subtree it replaced.

**G-4.6 Recoverable vs poisoned.** A `RuntimeException` means the object is
still usable. An `Error` subtype means the object is dead: it sets a
`distressed` flag, refuses further use, and must be closed and reopened. A
half-written row (open-row parity bit set) or a failed rollback poisons the
writer.

**G-4.7 Off-heap memory.** *Skip unless* the code uses native memory. Every
allocation carries a tag and a size; `ptr = free(ptr, size, tag)` returns 0;
per-tag counters make leak reports name the subsystem; never cache an address
across a reallocation; fixed-width reads are assert-guarded, but any length
read from a file is range-checked before dereference because an out-of-bounds
native read crashes the process.

---

## 5. Errors

**G-5.1 Exceptions are reusable, message-building and stack-trace-free.** One
instance per thread from a `ThreadLocal`; `instance()` clears the owned sink;
the message is built with `put(...)` overloads for `CharSequence`, `char`,
`int`, `long`, `double` and `Sinkable` — never `Object`, never
`String.format`. `getStackTrace()` returns empty unless assertions are on
(G-2.5). Pure control-flow signals are `static final INSTANCE` with a private
constructor.

```java
public final class ParseException extends RuntimeException implements Sinkable {
    private static final ThreadLocal<ParseException> TL = ThreadLocal.withInitial(ParseException::new);
    private final StringSink message = new StringSink();
    private int position;

    private ParseException() { super(null, null, false, false); }   // no trace, no suppression

    public static ParseException at(int position) {
        ParseException ex = TL.get();
        assert (ex = new ParseException()) != null;
        ex.message.clear();
        ex.position = position;
        return ex;
    }

    public ParseException put(CharSequence cs) { message.put(cs); return this; }
    public ParseException put(long v)          { message.put(v);  return this; }
    public CharSequence getFlyweightMessage()  { return message; }
    @Override public void toSink(CharSink<?> s) { s.put('[').put(position).put("] ").put(message); }
}

public final class BufferFull extends Exception {
    public static final BufferFull INSTANCE = new BufferFull();
    private BufferFull() { super(null, null, false, false); }
}
```

**Rules:** a message read from a reusable exception is invalid after the next
`instance()` on that thread — copy or log it first. A factory that sets extra
state on the shared instance resets that state at the write site. Readers of
optional state gate on a discriminator (an error code), never on "the field
looks cleared".

*Apply:* `new IllegalArgumentException("gap must be positive")` at setup is
fine; anything thrown per event or per frame is not.

**G-5.2 Named static factories, not constructors.** `ParseException.unexpectedToken(pos, tok)`,
`Failure.critical(errno)`, `Failure.nonCritical()`. Semantic flags
(`isInterruption()`, `isOutOfMemory()`, `isCritical()`) are bit-set accessors
set by the factory together with the message, so callers never re-derive
meaning from message text.

**G-5.3 Message shape is `verb phrase [key=value, key=value]`, ASCII only.**

```java
throw Failure.critical(errno).put("could not open [file=").put(path).put(", errno=").put(errno).put(']');
```

An error position points at the offending character. A parser distinguishes
"wrong token" (position of that token) from "ran out of input" (end position).

**G-5.4 One checked exception per subsystem boundary; everything else
unchecked.** The checked one walks from the parser to the public entry point
and forces every layer to propagate or translate. Unchecked ones are caught at
the call site and converted into the domain error of that layer.

**G-5.5 `assert` for internal invariants; explicit throw for external input.**
Asserts document buffer-pointer arithmetic, state-machine preconditions and
"impossible" branches at zero production cost. Anything file-, user- or
peer-controlled gets a real check that throws. A `PARANOIA` flag gates
expensive checks that are worth running in some test runs.

**G-5.6 Capture the cause before cleanup.** Read `errno` (or the original
exception) first, then close, then throw; a cleanup call may overwrite it.

---

## 6. Logging

**G-6.1 A fluent chain terminated by `$()`; the record is a reserved slot.**
Typed `$(int|long|double|boolean|char|CharSequence|Throwable)` overloads
write straight into a buffer; no concatenation, no boxing. `$()` appends the
end of line and commits. An unterminated chain leaks the slot and must be
detected and reported.

```java
LOG.info().$("stall detected [thread=").$(threadName).$(", gapMs=").$(gapMs).$(']').$();
```

**G-6.2 Plain `$(CharSequence)` is ASCII-only; `$safe()` for untrusted
text.** Literal message text uses `$()`; anything from a file, the wire or an
exception message goes through `$safe()`, which encodes. A reusable exception
is logged as `$safe(e.getFlyweightMessage())`; a foreign throwable as `$(e)`,
which prints the trace into the same record.

**G-6.3 Default logging is lossy; waiting variants are opt-in.** When the queue
is full the chain becomes a no-op (a shared `NullLogRecord`). `errorW()` and
`infoW()` wait for a slot and are used only where losing the line is worse than
stalling the caller; compute all values before starting such a chain so an
exception cannot leak the slot. `critical()` always waits. `isEnabled()` guards
only *expensive* formatting done outside the chain; the record implements the
sink interface so `toSink(record)` works directly.

**G-6.4 One `private static final Log LOG = LogFactory.getLog(X.class)` per
class.**

---

## 7. Concurrency

*Skip unless* the code runs multi-threaded. When it does, these are the rules.

**G-7.1 Ring queue plus sequences; `-1` = blocked by the barrier (full or
empty), `-2` = lost the claim race, retry immediately.** Slots are
pre-allocated from a factory, capacity is a power of two, `get(cursor)` is
`buf[(int) (cursor & mask)]`. The canonical consumer:

```java
while (true) {
    long c = seq.next();
    if (c > -1) {
        try { process(queue.get(c)); } finally { seq.done(c); }
        return true;
    }
    if (c == -1) return false;
    Thread.yield();   // -2: lost a race
}
```

Every `next() > -1` is paired with `done()` in a `finally`. Producers do not
spin on `-1`: they do the work inline, retry a bounded number of times, or use
a blocking claim when the message must not drop. Choose the narrowest sequence
that is true: single-producer and single-consumer sequences have no CAS and
are unsafe with two callers; multi-owner sequences stamp a per-slot lap flag
because claims can complete out of order. Wire barriers (`pub.then(sub).then(pub)`)
before any `next()`; a fan-out barrier's available index is the minimum across
subscribers.

**G-7.2 Publish with a release store, read with an acquire load; CAS through
a field offset or `VarHandle` on a `volatile` primitive.** Keep the field
`volatile` so plain reads have acquire semantics; use release-only stores for
publication; keep `Atomic*` objects for lifecycle and control state, not for
data-path counters. Explicit fences only when handing a non-volatile structure
between threads. Comment any read ordering the algorithm depends on.

```java
private static final VarHandle VALUE = MethodHandles.lookup()
        .findVarHandle(Sequence.class, "value", long.class);
private volatile long value = -1;

boolean casValue(long expected, long next) { return VALUE.compareAndSet(this, expected, next); }
void publish(long v) { VALUE.setRelease(this, v); }
```

**G-7.3 Hot shared fields get their own cache line.** Pad by inheritance
(seven `long`s before and after the hot field, with the JVM laying out
superclass fields first) or use a 64-byte stride in arrays. Comment the
padding as false-sharing avoidance.

```java
abstract class LhsPad   { @SuppressWarnings("unused") long p1, p2, p3, p4, p5, p6, p7; }
abstract class HotValue extends LhsPad { volatile long value; }
abstract class RhsPad   extends HotValue { @SuppressWarnings("unused") long q1, q2, q3, q4, q5, q6, q7; }
public final class Cursor extends RhsPad { /* ... */ }
```

**G-7.4 A job returns "did useful work"; workers back off in tiers.** `true`
resets the idle counter; consecutive idle passes escalate from `yield` to a
1 ms sleep to the configured sleep. A job is non-blocking and short. A job
that clones itself per worker frees the clone idempotently and never throws
from cleanup. A serialised job uses a CAS run-guard so the same instance
assigned to N workers runs on at most one; losing the CAS returns `false`.

```java
@Override
public boolean run(WorkerContext ctx) {
    if (!LOCKED.compareAndSet(this, 0, 1)) return false;
    try { return runSerially(); } finally { locked = 0; }
}
```

**G-7.5 Per-worker state is an array indexed by the pool-local worker id;
stealers borrow a slot through a CAS probe and release it in `finally`.** A
slot leaked on an error path stays lost for as long as the owner lives. Spin
loops between probes yield and check for cancellation; never spin without a
cancellation check.

**G-7.6 Every park is bounded; timed waits loop on an absolute deadline;
interrupts are consumed during the wait and restored on exit.** A bounded
`parkNanos` means a lost unpark costs one interval instead of a hang. Pass a
blocker object so thread dumps say what the thread waits on. Clamp remaining
time to at least 1 ns because `parkNanos(<= 0)` returns immediately.

**G-7.7 Halt is a flag and a wake-up.** Workers observe the flag at the next
iteration, run their own cleanup in nested `try/finally` steps, and count down
the halt latch **last**; the pool frees shared resources only after that latch
releases. Nothing uses `Thread.interrupt()` to stop a worker.

**G-7.8 Locks are the last resort and never assumed reentrant.** Prefer a
spinning writer-priority read-write lock (one atomic increment per read
acquire, no queue allocation) for read-mostly structures; `synchronized` on a
small private lock object for cold structural paths; `ReentrantLock` only
where a timed or interruptible acquire is needed. Every `volatile` field
carries a comment naming the writer thread and the reader threads.

**G-7.9 Single-owner latches when exactly one thread waits.** No waiter queue;
the count may go negative so producers can finish before the owner decides
how many to wait for; the waiting thread keeps consuming its own tasks while
it waits — work stealing, not blocking.

---

## 8. State machines and resumable I/O

*Skip unless* the code parks and resumes on a socket. The parser shape (G-8.4)
transfers to any incremental reader.

**G-8.1 Resumable state is an `int` plus a dispatch table, not a call stack.**
State constants chained `+ 1`; a table of resume actions indexed by state;
`resume()` is `actions.getQuick(state).run()`. Set the state at the top of each
step, before anything that can throw the park signal, so the resume re-enters
the same step.

**G-8.2 Park signals are checked singleton exceptions that name the next
operation.** `throw registerForRead()` stores any payload on the context (a
disconnect reason as an `int`) and returns the singleton, because the singleton
cannot carry state. The outer loop maps each signal to a dispatcher operation.
Never catch and swallow a signal.

**G-8.3 Bookmark and reset for speculative writes into a fixed buffer.** Write
a record; on overflow rewind to the last bookmark, flush what fit, re-run the
step. A bookmark at buffer start means one value exceeds the whole buffer — a
hard error that names the configuration knob to raise.

**G-8.4 Byte-level parsers keep their position in fields and return an enum.**
`COMPLETE | UNDERFLOW | ERROR`. A 256-entry `boolean[]` control-byte table
keeps the common byte on one branch; entity views are `(lo, hi)` windows over
the buffer, never copies; after the caller compacts the buffer it calls
`shift(n)` on the parser so every held offset moves with the data.

```java
public ParseResult parse(byte[] buf, int limit) {
    while (at < limit) {
        byte b = buf[at];
        if (!CONTROL[b & 0xFF]) { at++; continue; }   // hot path: one branch
        switch (b) { /* ... */ }
    }
    return ParseResult.UNDERFLOW;   // call again with more bytes
}
```

*Apply:* this is the shape for a single-pass event reader that must not
materialise each event.

**G-8.5 A pooled context has four lifecycle verbs with distinct scopes.** A
cheap constructor (acceptance runs on one thread) → `of(handle)` / `init()` per
connection → `reset()` per request → `clear()` when returned to the pool (the
security reset) → `close()` to free resources. Pools are thread-local, no
locking.

---

## 9. Configuration and dependency boundaries

**G-9.1 Configuration is an interface of primitive and `CharSequence` getters,
one per subsystem, nested by composition, no setters.** Sizes and capacities
come from getters; production code has no literal pool sizes.

```java
public interface AnalysisConfiguration {
    long stallGapNanos();
    int topN();
    IoConfiguration io();
}
```

**G-9.2 OS, time and randomness sit behind small interfaces exposed by the
configuration and defaulting to stateless singletons.** A `FilesFacade`, a
`Clock` with one `ticks()` method, a seedable `Rnd`. Consumers pull them from
the configuration at use time. No DI container, no mocking framework: tests
substitute by overriding one getter with an anonymous subclass.

```java
public interface IoConfiguration {
    FilesFacade files();
    default NanoClock clock() { return SystemNanoClock.INSTANCE; }
}
```

*Apply:* `System.nanoTime()`, `Clock.systemUTC()`, `Files.*` and the JFR
reader entry point each sit behind an interface with a production `INSTANCE`.

**G-9.3 Three classes per configuration interface.** A `DefaultXxx` with
literal returns (tests and embedding), an `XxxWrapper` that delegates every
call to an `AtomicReference<Xxx>` (selective override and hot reload), and the
parsed production one. Components receive the whole interface by constructor
as a `private final` field; nothing reads a global.

**G-9.4 Property parsing.** Keys are an enum carrying path, environment
variable name and sensitivity; typed helpers `getInt(props, env, KEY,
default)` validate ranges and relations and throw one configuration exception
naming the key; values are parsed exactly once into final fields; unknown keys
are rejected in strict mode; cross-field validation runs at construction.

*Apply:* argument parsing resolves every option once into final primitives,
validates ranges and relations there, and exposes an interface — not a
`Map<String, String>`.

**G-9.5 A constructor must not call an overridable getter.** A subclass that
overrides it sees its own fields uninitialised. If unavoidable, document it
and null-check in the override.

---

## 10. Style

**G-10.1 Member order is mechanical.** Static final fields → static fields →
instance final fields → instance fields (each band public > protected >
package > private, alphabetical within the band) → constructors → public
static methods → public methods → private static methods → private methods →
nested types. Enforce with the IDE's arrangement rules or a formatter; do not
hand-order.

**G-10.2 `final` on every field that can be; optional on locals; not on
parameters.** Owned scratch objects are `private final X x = new X();` at the
declaration and reused for life. Mutable state (`data`, `pos`) is the
exception.

**G-10.3 Nullability annotations from one library, used sparsely.**
`@NotNull` / `@Nullable` on public API parameters where `null` has defined
behaviour and on returns that can be absent — not on every field. `@TestOnly`
marks production accessors that exist for tests. `@Override` everywhere,
including on interface `default` overrides. `@SuppressWarnings` uses the
inspection id, one per method, with a comment when the reason is not obvious.

**G-10.4 Javadoc only where the contract can surprise; comments say why.**
`getQuick` gets javadoc (no bounds check); `add` and `size` do not. Flags with
a safety polarity get long javadoc. Inline comments carry rationale, not
narration. Active voice naming the acting subject.

**G-10.5 Naming.** `is` / `has` for booleans; `notEmpty()` over `!isEmpty()`;
suffixes `Quick` (unchecked), `Quiet` (sentinel on failure), `At` (takes a raw
`keyIndex`), `0` (private worker behind a public overload); prefixes `Direct`
(off-heap); `Impl` (production implementation of a facade); `Facade` (OS
boundary); `Sink` (append-only writer); plural nouns (`Chars`, `Numbers`,
`Bytes`) for `final` static helper holders with private constructors;
`NO_ENTRY_VALUE` / `*_NULL` for sentinels; `tlXxx` for thread-local statics;
`size()` for a byte count, `length()` reserved for `CharSequence`. Tests are
`testXxx` in `<Class>Test`.

**G-10.6 Interfaces grow by `default` methods returning the conservative
answer** (`false`, `null`, `UnsupportedOperationException`) so implementors
compile unchanged. Per-type abstract bases implement the whole interface once;
unsupported type getters are `final` and throw so a subclass cannot
accidentally "support" the wrong type:

```java
public abstract class IntValue implements Value {
    @Override public final boolean getBool()   { throw new UnsupportedOperationException(); }
    @Override public long getLong()            { return Nulls.intToLong(getInt()); }   // widening allowed
    @Override public final int type()          { return Tag.INT; }
}
```

**G-10.7 No streams; lambdas only as one-time strategy objects or on setup
paths.** Method references are created once in a constructor and stored,
never at call sites (each site compiles to its own class and allocates).
`static class` for nested types unless they need the outer instance.
`equals` / `hashCode` only on value and key types. Varargs and `Object[]` only
in convenience and test factories.

**G-10.8 Modern syntax where it costs nothing:** enhanced `switch` with
arrows, pattern variables in `instanceof`, pattern `switch` on `Throwable`,
text blocks, records for immutable setup-time values (not for per-event
data). Braces always; single-class imports; multi-parameter signatures wrap one
per line with the closing paren on its own line; license header on every file.

---

## 11. Tests

**G-11.1 Every test that can acquire a tracked resource runs inside a leak
check.** The check snapshots the resource counters before the body and asserts
each unchanged after, naming the category that leaked:

```java
@Test
public void testReaderReleasesBuffers() throws Exception {
    assertNoLeak(() -> {
        try (Reader r = new Reader(cfg)) { r.readAll(); }
    });
}
```

*Apply:* for an on-heap tool the equivalent counts every `AutoCloseable`
opened and closed, and asserts pooled and scratch objects are back at their
baseline size.

**G-11.2 One fluent assertion builder whose default path is the strict one.**
`assertAnalysis(input).returns(expected)` runs the strongest battery (a second
pass, a size cross-check, property checks); opt-outs are named and require a
stated reason. Expected values are multiline text blocks. `.fails(pos, msg)`
asserts the error **position**.

**G-11.3 Fault injection is an anonymous subclass of the facade, failing on
the Nth call or on a specific name, installed via the configuration for one
test block.** Test doubles also *track* (handle → name) so leak reports name
the file. No mocking library.

```java
files = new TrackingFilesFacade() {
    @Override
    public long open(CharSequence name) {
        if (endsWith(name, ".idx") && failures.decrementAndGet() >= 0) return -1;
        return super.open(name);
    }
};
```

**G-11.4 Randomness is a two-seed generator, seeds printed on every run and
accepted back through system properties.** One instance per thread. Test
order is randomised so inter-test state leaks surface.

**G-11.5 Concurrency tests: a barrier to start, a latch with a timeout to
join, atomics for outcomes asserted once on the main thread, `assertEventually`
with backoff instead of `Thread.sleep`, races hunted with seeded repeat
loops.**

```java
CyclicBarrier start = new CyclicBarrier(threads);
CountDownLatch done = new CountDownLatch(threads);
AtomicInteger anomalies = new AtomicInteger();
for (int t = 0; t < threads; t++) {
    new Thread(() -> {
        try { start.await(); work(anomalies); }
        catch (Exception e) { anomalies.incrementAndGet(); }
        finally { done.countDown(); }
    }).start();
}
Assert.assertTrue(done.await(60, TimeUnit.SECONDS));
Assert.assertEquals(0, anomalies.get());
```

**G-11.6 Heavy fixtures are static per class; every mutable piece is reset in
`@Before`; overrides go through a typed `Overrides` object the test
configuration consults live.** `try (X x = ...)` for every `Closeable` in
tests. Parameterise over modes rather than copy tests. Assert meaning, not
representation. Narrow unit tests need no base class.

---

## 12. Applying these to this project

Transfers unchanged: G-1 (data layout), G-2 (text), G-3 (reuse), G-4.1–G-4.5
(ownership), G-5 (errors), G-6.1–G-6.2 (message shape), G-8.4 (incremental
parser shape), G-9 (configuration and facades), G-10, G-11.

Transfers in spirit: G-4.7 becomes "every pooled object has an owner and a
baseline the tests can assert"; G-7 applies only if a parallel pass over the
file is introduced, and then G-7.1, G-7.2, G-7.3 and G-7.5 are the ones to
follow.

Where the code currently departs (from a skim of `core/src/main/java`, not an
audit): `java.util` collections and `Duration` / `Instant` objects on the
per-event path, records allocated per event, streams and `String`
concatenation in analysis code, exceptions constructed on validation paths.
Each maps to a rule above. The refactor order is: introduce the primitive
collections, the sink protocol, the sentinel conventions and the `of()` /
`clear()` reuse cycle; then move per-event allocations onto them; measure with
`--timing` before and after each step.
