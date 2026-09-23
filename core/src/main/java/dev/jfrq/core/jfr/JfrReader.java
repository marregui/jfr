// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.jfrq.core.coll.IdentityObjObjHashMap;
import dev.jfrq.core.coll.LongObjHashMap;
import dev.jfrq.core.coll.ObjHashSet;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Durations;
import jdk.jfr.EventType;
import jdk.jfr.consumer.EventStream;
import jdk.jfr.consumer.RecordedEvent;

/**
 * One streaming pass over a recording that feeds every event to the sinks interested in
 * its type and collects the {@link RecordingInfo} on the way.
 *
 * <p>The pass goes through {@link EventStream} rather than {@code RecordingFile} because
 * the stream parser <em>skips</em> event types nobody subscribed to at the byte level,
 * instead of materialising them. A profile recording is dominated by events no analysis
 * reads ({@code jdk.GCPhaseParallel} alone can be most of the file), so for a filtered
 * read this is the difference between parsing the file and parsing a tenth of it.
 * Event objects are reused between callbacks ({@code setReuse(true)}), so sinks copy what
 * they keep, and delivery is in file order rather than time order
 * ({@code setOrdered(false)}); every sink sorts what it keeps at the end.
 *
 * <p>Per event, the reader resolves the {@link EventType} object once (by identity; the
 * parser hands out one instance per type per chunk) to the type's name, its
 * {@link EventKinds} tag, its counter and the sinks that want it, so the per-event path
 * hashes no strings (G-2.2).
 *
 * <p>Before the pass, the chunk headers are read directly ({@link Chunks}). They give the
 * recording's data span independently of which events are subscribed, and they reveal a
 * truncated or still-being-written file, which the JDK parser would otherwise turn into
 * a silently empty answer. Consequences for {@link RecordingInfo}: event counts and
 * threads cover the event types that were read; the span is the file's own. A read with
 * an "everything" sink (as {@code jfrq info} does) sees the whole file.
 */
public final class JfrReader {

    private static final String ACTIVE_SETTING = EventKinds.nameOf(EventKinds.ACTIVE_SETTING);
    private static final Sink[] NO_SINKS = new Sink[0];

    /**
     * A consumer of events of the types it names. An empty set means every event. The
     * event object is the parser's flyweight, recycled after the call: a sink copies what
     * it keeps and never retains the event.
     */
    public interface Sink {
        Set<String> eventTypes();

        void accept(@Transient RecordedEvent event);

        /**
         * {@link #accept(RecordedEvent)} with the event type's {@link EventKinds} tag,
         * resolved once per type rather than per event; sinks that dispatch on the type
         * override this one and switch on the tag.
         */
        default void accept(@Transient final RecordedEvent event, final int kind) {
            accept(event);
        }

        /** Called before the first event with the interner to build stacks through. */
        default void begin(final Interner interner) {
        }

        /** Called once after the last event, with the recording facts now known. */
        default void finish(final RecordingInfo info) {
        }
    }

    private JfrReader() {
    }

    /**
     * How long the last {@link #read} spent in the sinks' {@code finish()}, which is where
     * every analysis happens; the rest of the read is parsing. Set per call, read by
     * {@code --timing} right after it, so a program that reads two recordings concurrently
     * gets one of the two numbers.
     */
    public static long analyseNanos() {
        return analyseNanos;
    }

    private static volatile long analyseNanos;

    /**
     * @throws IOException when the file is missing, is not a recording, holds no complete
     *                     chunk, or ends in a chunk that is still being written. The last
     *                     case is refused outright because the JDK parser does not fail on
     *                     it: it waits for the chunk to finish, forever.
     */
    public static RecordingInfo read(final Path file, final Sink... sinks) throws IOException {
        final Chunks chunks = Chunks.scan(file);
        final List<String> warnings = new ArrayList<>();
        if (chunks.inProgress()) {
            final Chunks.Header last = chunks.headers().getLast();
            throw new IOException("recording is still being written: its last chunk (at byte " + last.offset()
                    + ") is not finished. Dump the recording from the JVM (jcmd <pid> JFR.dump) or, to read the "
                    + chunks.complete() + " complete chunk(s), truncate the file to " + last.offset() + " bytes");
        }
        if (chunks.complete() == 0) {
            throw new IOException("recording is truncated: the file ends inside its first chunk");
        }
        if (chunks.truncated()) {
            warnings.add("the file is truncated: it ends inside a chunk that started at "
                    + Durations.offset(chunks.endNanos() - chunks.startNanos())
                    + "; events after that point are missing, and " + chunks.count()
                    + " complete chunk(s) were read");
        }

        final Pass pass = new Pass(sinks);
        for (final Sink s : sinks) {
            s.begin(pass.interner);
        }
        try (final EventStream stream = EventStream.openFile(file)) {
            stream.setReuse(true);
            stream.setOrdered(false);
            stream.onMetadata(m -> {
                for (final EventType t : m.getEventTypes()) {
                    pass.typeNames.put(t.getId(), t.getName());
                }
            });
            // No sinks at all means "tell me about the file": read everything.
            if (pass.all.length > 0 || pass.subscribed.isEmpty()) {
                stream.onEvent(pass);
            } else {
                // Read on every pass so a filtered read still learns the thresholds and periods.
                pass.subscribe(ACTIVE_SETTING);
                for (int i = 0, n = pass.subscribed.size(); i < n; i++) {
                    stream.onEvent(pass.subscribed.getQuick(i), pass);
                }
            }
            stream.start();
        }

        // The chunk headers bound the span; the last event read is a safety net for a header
        // whose duration undershoots (it never should, but the file is not ours).
        final long start = chunks.startNanos();
        final long end = Math.max(chunks.endNanos(), Math.max(start, pass.lastEnd));
        final RecordingInfo info = new RecordingInfo(file, new Interval(start, end), chunks.count(), pass.eventCounts(),
                pass.settings(), pass.threads(), warnings);
        final long parsed = System.nanoTime();
        for (final Sink s : sinks) {
            s.finish(info);
        }
        // The analysis runs in finish(), so without this the two are one number and
        // "where did the time go" cannot be answered from the outside.
        analyseNanos = System.nanoTime() - parsed;
        return info;
    }

    /** What the reader knows about one event type name: its tag, its count and its sinks. */
    private static final class Dispatch {
        final String name;
        final int kind;
        final Sink[] targets;
        long count;

        Dispatch(final String name, final int kind, final Sink[] targets) {
            this.name = name;
            this.kind = kind;
            this.targets = targets;
        }
    }

    /** The state of one read: everything the per-event callback touches, allocated once. */
    private static final class Pass implements Consumer<RecordedEvent> {
        final Interner interner = new Interner();
        /** Sinks that want every event. */
        final Sink[] all;
        /** Event type names at least one sink asked for, in subscription order. */
        final ObjList<String> subscribed = new ObjList<>();
        /** Event type id (per the metadata event) to name, for {@code jdk.ActiveSetting}. */
        final LongObjHashMap<String> typeNames = new LongObjHashMap<>(512);
        long lastEnd = Long.MIN_VALUE;

        private final ObjObjHashMap<String, ObjList<Sink>> sinksByName = new ObjObjHashMap<>(64);
        private final ObjObjHashMap<String, Dispatch> byName = new ObjObjHashMap<>(256);
        /** Bounded like the interner's identity caches: event types are per chunk. */
        private final IdentityObjObjHashMap<EventType, Dispatch> byType = new IdentityObjObjHashMap<>(256);
        private final ObjObjHashMap<String, ObjObjHashMap<String, String>> settings = new ObjObjHashMap<>(256);
        private final ObjHashSet<ThreadRef> threads = new ObjHashSet<>(256);

        Pass(final Sink[] sinks) {
            final ObjList<Sink> everything = new ObjList<>();
            for (final Sink s : sinks) {
                if (s.eventTypes().isEmpty()) {
                    everything.add(s);
                } else {
                    for (final String t : s.eventTypes()) {
                        final int index = sinksByName.keyIndex(t);
                        final ObjList<Sink> targets = index < 0 ? sinksByName.valueAtQuick(index) : subscribe(t, index);
                        targets.add(s);
                    }
                }
            }
            all = everything.isEmpty() ? NO_SINKS : everything.toList().toArray(NO_SINKS);
        }

        void subscribe(final String type) {
            final int index = sinksByName.keyIndex(type);
            if (index >= 0) {
                subscribe(type, index);
            }
        }

        private ObjList<Sink> subscribe(final String type, final int index) {
            subscribed.add(type);
            return sinksByName.putAt(index, type, new ObjList<>(2));
        }

        @Override
        public void accept(final RecordedEvent e) {
            final EventType type = e.getEventType();
            final int index = byType.keyIndex(type);
            final Dispatch dispatch = index < 0 ? byType.valueAtQuick(index) : resolve(type, index);
            dispatch.count++;
            final long en = Events.endNanos(e);
            if (en > lastEnd) {
                lastEnd = en;
            }
            final ThreadRef thread = interner.thread(e);
            if (thread != null) {
                final int t = threads.keyIndex(thread);
                if (t >= 0) {
                    threads.addAt(t, thread);
                }
            }
            if (dispatch.kind == EventKinds.ACTIVE_SETTING) {
                setting(e);
            }
            final Sink[] targets = dispatch.targets;
            for (int i = 0; i < targets.length; i++) {
                targets[i].accept(e, dispatch.kind);
            }
        }

        /** Settings can change between chunks; the last chunk's values are the ones reported. */
        private void setting(final RecordedEvent e) {
            final String owner = typeNames.get(e.getLong("id"));
            if (owner == null) {
                return;
            }
            final int index = settings.keyIndex(owner);
            final ObjObjHashMap<String, String> values = index < 0 ? settings.valueAtQuick(index)
                    : settings.putAt(index, owner, new ObjObjHashMap<>(16));
            values.put(e.getString("name"), e.getString("value"));
        }

        /** First sight of an {@link EventType} object: one string hash, then identity. */
        private Dispatch resolve(final EventType type, int index) {
            final String name = type.getName();
            final int byNameIndex = byName.keyIndex(name);
            Dispatch dispatch;
            if (byNameIndex < 0) {
                dispatch = byName.valueAtQuick(byNameIndex);
            } else {
                final ObjList<Sink> targets = new ObjList<>(all.length + 2);
                for (final Sink s : all) {
                    targets.add(s);
                }
                final ObjList<Sink> named = sinksByName.get(name);
                if (named != null) {
                    targets.addAll(named);
                }
                dispatch = byName.putAt(byNameIndex, name, new Dispatch(name, EventKinds.kindOf(name),
                        targets.isEmpty() ? NO_SINKS : targets.toList().toArray(NO_SINKS)));
            }
            if (byType.size() >= Interner.IDENTITY_LIMIT) {
                byType.clear();
                index = byType.keyIndex(type);
            }
            return byType.putAt(index, type, dispatch);
        }

        Map<String, Long> eventCounts() {
            final Map<String, Long> counts = new HashMap<>(byName.size() * 2);
            for (int s = 0, n = byName.slots(); s < n; s++) {
                if (byName.hasKeyAtSlot(s)) {
                    final Dispatch d = byName.valueAtSlot(s);
                    counts.put(d.name, d.count);
                }
            }
            return counts;
        }

        Map<String, Map<String, String>> settings() {
            final Map<String, Map<String, String>> out = new HashMap<>(settings.size() * 2);
            for (int s = 0, n = settings.slots(); s < n; s++) {
                if (settings.hasKeyAtSlot(s)) {
                    final ObjObjHashMap<String, String> values = settings.valueAtSlot(s);
                    final Map<String, String> copy = new HashMap<>(values.size() * 2);
                    for (int v = 0, m = values.slots(); v < m; v++) {
                        if (values.hasKeyAtSlot(v)) {
                            copy.put(values.keyAtSlot(v), values.valueAtSlot(v));
                        }
                    }
                    out.put(settings.keyAtSlot(s), copy);
                }
            }
            return out;
        }

        Set<ThreadRef> threads() {
            final Set<ThreadRef> out = new HashSet<>(threads.size() * 2);
            for (int s = 0, n = threads.slots(); s < n; s++) {
                if (threads.hasKeyAtSlot(s)) {
                    out.add(threads.keyAtSlot(s));
                }
            }
            return out;
        }
    }
}
