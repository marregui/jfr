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

import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
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
 * <p>Consequences for {@link RecordingInfo}: event counts and threads cover the event
 * types that were read; the time span is the recording's own, anchored on events read
 * on every pass. A read with an "everything" sink (as {@code jfrq info} does) sees the
 * whole file.
 */
public final class JfrReader {

    private static final String ACTIVE_SETTING = "jdk.ActiveSetting";
    private static final String ACTIVE_RECORDING = "jdk.ActiveRecording";
    /**
     * Read on every pass so the span is the recording's, not the subscribed events'.
     * {@code jdk.ActiveRecording} carries the exact start; {@code jdk.PhysicalMemory} is a
     * single event at the beginning and end of every chunk, so its last one is the end.
     */
    private static final List<String> ANCHORS = List.of(ACTIVE_SETTING, ACTIVE_RECORDING, "jdk.PhysicalMemory");

    /** A consumer of events of the types it names. An empty set means every event. */
    public interface Sink {
        Set<String> eventTypes();

        void accept(RecordedEvent event);

        /** Called before the first event with the interner to build stacks through. */
        default void begin(Interner interner) {
        }

        /** Called once after the last event, with the recording facts now known. */
        default void finish(RecordingInfo info) {
        }
    }

    private JfrReader() {
    }

    public static RecordingInfo read(Path file, Sink... sinks) throws IOException {
        Map<String, List<Sink>> byType = new HashMap<>();
        List<Sink> all = new ArrayList<>();
        for (Sink s : sinks) {
            if (s.eventTypes().isEmpty()) {
                all.add(s);
            } else {
                for (String t : s.eventTypes()) {
                    byType.computeIfAbsent(t, k -> new ArrayList<>()).add(s);
                }
            }
        }

        Interner interner = new Interner();
        for (Sink s : sinks) {
            s.begin(interner);
        }

        Map<Long, String> typeNames = new HashMap<>();
        Map<String, long[]> counts = new HashMap<>();
        Map<String, Map<String, String>> settings = new HashMap<>();
        Set<ThreadRef> threads = new HashSet<>();
        long[] span = {Long.MAX_VALUE, Long.MIN_VALUE};

        Consumer<RecordedEvent> bookkeeping = e -> {
            String type = e.getEventType().getName();
            counts.computeIfAbsent(type, k -> new long[1])[0]++;
            long s = Events.startNanos(e);
            long en = Math.max(s, Events.endNanos(e));
            if (s < span[0]) {
                span[0] = s;
            }
            if (en > span[1]) {
                span[1] = en;
            }
            ThreadRef thread = interner.thread(e);
            if (thread != null) {
                threads.add(thread);
            }
            if (ACTIVE_SETTING.equals(type)) {
                String owner = typeNames.get(e.getLong("id"));
                if (owner != null) {
                    settings.computeIfAbsent(owner, k -> new HashMap<>())
                            .put(e.getString("name"), e.getString("value"));
                }
            } else if (ACTIVE_RECORDING.equals(type) && e.hasField("recordingStart")) {
                long recordingStart = Events.nanos(e.getInstant("recordingStart"));
                if (recordingStart > 0 && recordingStart < span[0]) {
                    span[0] = recordingStart;
                }
            }
        };

        try (EventStream stream = EventStream.openFile(file)) {
            stream.setReuse(true);
            stream.setOrdered(false);
            stream.onMetadata(m -> {
                for (EventType t : m.getEventTypes()) {
                    typeNames.put(t.getId(), t.getName());
                }
            });
            // No sinks at all means "tell me about the file": read everything.
            if (!all.isEmpty() || byType.isEmpty()) {
                stream.onEvent(e -> {
                    bookkeeping.accept(e);
                    for (Sink sink : all) {
                        sink.accept(e);
                    }
                    List<Sink> targets = byType.get(e.getEventType().getName());
                    if (targets != null) {
                        for (Sink sink : targets) {
                            sink.accept(e);
                        }
                    }
                });
            } else {
                for (String anchor : ANCHORS) {
                    byType.computeIfAbsent(anchor, k -> new ArrayList<>());
                }
                for (Map.Entry<String, List<Sink>> entry : byType.entrySet()) {
                    List<Sink> targets = entry.getValue();
                    stream.onEvent(entry.getKey(), e -> {
                        bookkeeping.accept(e);
                        for (Sink sink : targets) {
                            sink.accept(e);
                        }
                    });
                }
            }
            stream.start();
        }

        if (span[0] == Long.MAX_VALUE) {
            span[0] = 0;
            span[1] = 0;
        }
        Map<String, Long> eventCounts = new HashMap<>(counts.size());
        counts.forEach((k, v) -> eventCounts.put(k, v[0]));
        RecordingInfo info = new RecordingInfo(file, new Interval(span[0], span[1]), eventCounts, settings, threads);
        for (Sink s : sinks) {
            s.finish(info);
        }
        return info;
    }
}
