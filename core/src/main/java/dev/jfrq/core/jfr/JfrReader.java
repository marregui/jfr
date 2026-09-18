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
 * <p>Before the pass, the chunk headers are read directly ({@link Chunks}). They give the
 * recording's data span independently of which events are subscribed, and they reveal a
 * truncated or still-being-written file, which the JDK parser would otherwise turn into
 * a silently empty answer. Consequences for {@link RecordingInfo}: event counts and
 * threads cover the event types that were read; the span is the file's own. A read with
 * an "everything" sink (as {@code jfrq info} does) sees the whole file.
 */
public final class JfrReader {

    private static final String ACTIVE_SETTING = "jdk.ActiveSetting";

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

    /**
     * @throws IOException when the file is missing, is not a recording, holds no complete
     *                     chunk, or ends in a chunk that is still being written. The last
     *                     case is refused outright because the JDK parser does not fail on
     *                     it: it waits for the chunk to finish, forever.
     */
    public static RecordingInfo read(Path file, Sink... sinks) throws IOException {
        Chunks chunks = Chunks.scan(file);
        List<String> warnings = new ArrayList<>();
        if (chunks.inProgress()) {
            Chunks.Header last = chunks.headers().getLast();
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

        Map<String, List<Sink>> byType = new HashMap<>();
        List<Sink> all = new ArrayList<>();
        for (Sink s : sinks) {
            if (s.eventTypes().isEmpty()) {
                all.add(s);
            } else {
                for (String t : s.eventTypes()) {
                    byType.computeIfAbsent(t, _ -> new ArrayList<>()).add(s);
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
        long[] lastEnd = {Long.MIN_VALUE};

        Consumer<RecordedEvent> bookkeeping = e -> {
            String type = e.getEventType().getName();
            counts.computeIfAbsent(type, _ -> new long[1])[0]++;
            long en = Events.endNanos(e);
            if (en > lastEnd[0]) {
                lastEnd[0] = en;
            }
            ThreadRef thread = interner.thread(e);
            if (thread != null) {
                threads.add(thread);
            }
            if (ACTIVE_SETTING.equals(type)) {
                // Settings can change between chunks; the last chunk's values are the ones reported.
                String owner = typeNames.get(e.getLong("id"));
                if (owner != null) {
                    settings.computeIfAbsent(owner, _ -> new HashMap<>())
                            .put(e.getString("name"), e.getString("value"));
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
                // Read on every pass so a filtered read still learns the thresholds and periods.
                byType.computeIfAbsent(ACTIVE_SETTING, _ -> new ArrayList<>());
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

        // The chunk headers bound the span; the last event read is a safety net for a header
        // whose duration undershoots (it never should, but the file is not ours).
        long start = chunks.startNanos();
        long end = Math.max(chunks.endNanos(), Math.max(start, lastEnd[0]));
        Map<String, Long> eventCounts = new HashMap<>(counts.size());
        counts.forEach((k, v) -> eventCounts.put(k, v[0]));
        RecordingInfo info = new RecordingInfo(file, new Interval(start, end), chunks.count(), eventCounts, settings,
                threads, warnings);
        for (Sink s : sinks) {
            s.finish(info);
        }
        return info;
    }
}
