package dev.jfrq.core.jfr;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Durations;

/**
 * What a single pass over a recording learns about the recording itself: its time span,
 * how many events of each type it holds, which threads appear, and the settings
 * (thresholds, periods, throttles) that were active when it was made. The settings matter
 * because they bound what an analysis can see: a 20 ms monitor threshold means no wait
 * shorter than 20 ms exists in the file.
 *
 * @param file        the recording that was read
 * @param span        the data span: first chunk start to last chunk end (see {@link Chunks})
 * @param chunks      how many chunks the file holds
 * @param eventCounts events per type name, only for types present
 * @param settings    per event type, setting name to value as JFR recorded it ({@code "10 ms"})
 * @param threads     every thread that appeared as an event thread
 * @param warnings    structural problems with the file that limit every answer: truncation,
 *                    a chunk still being written
 */
public record RecordingInfo(
        Path file,
        Interval span,
        int chunks,
        Map<String, Long> eventCounts,
        Map<String, Map<String, String>> settings,
        Set<ThreadRef> threads,
        List<String> warnings) {

    public RecordingInfo {
        eventCounts = Map.copyOf(new TreeMap<>(eventCounts));
        Map<String, Map<String, String>> copy = new TreeMap<>();
        settings.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
        settings = Map.copyOf(copy);
        threads = Set.copyOf(threads);
        warnings = List.copyOf(warnings);
    }

    public long startNanos() {
        return span.start();
    }

    public long endNanos() {
        return span.end();
    }

    public Duration duration() {
        return Duration.ofNanos(span.length());
    }

    public Instant start() {
        return Instant.ofEpochSecond(0, span.start());
    }

    public long count(String eventType) {
        return eventCounts.getOrDefault(eventType, 0L);
    }

    public boolean has(String eventType) {
        return count(eventType) > 0;
    }

    /**
     * Whether the recording carries its own settings. They travel as {@code jdk.ActiveSetting}
     * events, which every JDK profile enables but a hand-built {@code Recording} may not;
     * without them every threshold and period is unknown.
     */
    public boolean hasSettings() {
        return !settings.isEmpty();
    }

    /** The raw setting value, e.g. {@code setting("jdk.JavaMonitorEnter", "threshold")}. */
    public Optional<String> setting(String eventType, String name) {
        Map<String, String> s = settings.get(eventType);
        return s == null ? Optional.empty() : Optional.ofNullable(s.get(name));
    }

    /** {@code true} when the event type was enabled in the recording's settings. */
    public boolean enabled(String eventType) {
        return setting(eventType, "enabled").map(Boolean::parseBoolean).orElse(false);
    }

    public Optional<Duration> threshold(String eventType) {
        return durationSetting(eventType, "threshold");
    }

    public Optional<Duration> period(String eventType) {
        return durationSetting(eventType, "period");
    }

    /**
     * The event's throttle ({@code "300/s"}) when one is set and is not "off": a throttled
     * event type is sampled, so not every occurrence is in the file.
     */
    public Optional<String> throttle(String eventType) {
        return setting(eventType, "throttle").filter(v -> !v.isBlank() && !"off".equalsIgnoreCase(v.trim()));
    }

    private Optional<Duration> durationSetting(String eventType, String name) {
        return setting(eventType, name).flatMap(v -> {
            try {
                return Optional.of(Durations.parse(v));
            } catch (IllegalArgumentException notADuration) {
                // "everyChunk", "beginChunk", "endChunk" are periods without a duration.
                return Optional.empty();
            }
        });
    }
}
