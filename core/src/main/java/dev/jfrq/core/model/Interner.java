package dev.jfrq.core.model;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/**
 * Canonicalises what one pass over a recording reads, and makes reading it cheap.
 *
 * <p>Two facts about the JFR consumer API drive the design. Every field access on a
 * {@code RecordedObject} is a by-name linear scan of its descriptors, and class names
 * are re-derived on each call; a stack of twenty frames costs a hundred such lookups.
 * And the objects a chunk's constant pools resolve to ({@link RecordedStackTrace},
 * {@link RecordedThread}, {@link RecordedClass}) are shared instances: every event that
 * refers to the same stack refers to the same object. So the first sight of a pool
 * object pays the lookups once and is remembered by identity; the value-keyed maps
 * behind it then make equal stacks from different chunks share one instance too.
 *
 * <p>Identity caches are bounded so that a many-chunk recording cannot pin every
 * chunk's pool objects; when full they are dropped and rebuilt, which costs at most one
 * slow resolution per object per refill.
 *
 * <p>Not thread-safe by design: one instance per reading thread.
 */
public final class Interner {

    /** Identity caches are cleared past this size; pools of a single chunk are far smaller. */
    static final int IDENTITY_LIMIT = 200_000;

    private final Map<Frame, Frame> frames = new HashMap<>(4096);
    private final Map<Stack, Stack> stacks = new HashMap<>(4096);
    private final Map<ThreadRef, ThreadRef> threadRefs = new HashMap<>(256);
    private Map<RecordedStackTrace, Stack> stacksByIdentity = new IdentityHashMap<>(4096);
    private Map<RecordedThread, ThreadRef> threadsByIdentity = new IdentityHashMap<>(256);
    private Map<RecordedClass, String> classNamesByIdentity = new IdentityHashMap<>(1024);
    private final Map<EventType, String> threadFieldByType = new IdentityHashMap<>(64);

    public Stack stack(RecordedStackTrace trace) {
        if (trace == null) {
            return Stack.EMPTY;
        }
        Stack cached = stacksByIdentity.get(trace);
        if (cached != null) {
            return cached;
        }
        List<RecordedFrame> recorded = trace.getFrames();
        Frame[] fs = new Frame[recorded.size()];
        for (int i = 0; i < fs.length; i++) {
            fs[i] = frame(Frame.of(recorded.get(i)));
        }
        Stack candidate = new Stack(fs, trace.isTruncated());
        Stack canonical = stacks.putIfAbsent(candidate, candidate);
        Stack result = canonical == null ? candidate : canonical;
        if (stacksByIdentity.size() >= IDENTITY_LIMIT) {
            stacksByIdentity = new IdentityHashMap<>(4096);
        }
        stacksByIdentity.put(trace, result);
        return result;
    }

    public Frame frame(Frame f) {
        Frame canonical = frames.putIfAbsent(f, f);
        return canonical == null ? f : canonical;
    }

    /** The thread a {@link RecordedThread} denotes, or {@code null}. */
    public ThreadRef thread(RecordedThread t) {
        if (t == null) {
            return null;
        }
        ThreadRef cached = threadsByIdentity.get(t);
        if (cached != null) {
            return cached;
        }
        ThreadRef ref = ThreadRef.of(t);
        ThreadRef canonical = threadRefs.putIfAbsent(ref, ref);
        ThreadRef result = canonical == null ? ref : canonical;
        if (threadsByIdentity.size() >= IDENTITY_LIMIT) {
            threadsByIdentity = new IdentityHashMap<>(256);
        }
        threadsByIdentity.put(t, result);
        return result;
    }

    /**
     * The thread an event belongs to: {@code sampledThread} for the sampler events,
     * {@code eventThread} otherwise, decided once per event type instead of per event.
     */
    public ThreadRef thread(RecordedEvent e) {
        EventType type = e.getEventType();
        String field = threadFieldByType.get(type);
        if (field == null) {
            field = e.hasField("sampledThread") ? "sampledThread" : e.hasField("eventThread") ? "eventThread" : "";
            threadFieldByType.put(type, field);
        }
        return field.isEmpty() ? null : thread(e.getThread(field));
    }

    /** The JVM name of a class ({@code [B}, {@code java.lang.Object}), or {@code null}. */
    public String className(RecordedClass c) {
        if (c == null) {
            return null;
        }
        String cached = classNamesByIdentity.get(c);
        if (cached != null) {
            return cached;
        }
        String name = c.getName();
        if (classNamesByIdentity.size() >= IDENTITY_LIMIT) {
            classNamesByIdentity = new IdentityHashMap<>(1024);
        }
        classNamesByIdentity.put(c, name);
        return name;
    }

    public int distinctStacks() {
        return stacks.size();
    }

    public int distinctFrames() {
        return frames.size();
    }
}
