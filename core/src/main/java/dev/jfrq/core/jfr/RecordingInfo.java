package dev.jfrq.core.jfr;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 * @param span        first event start to last event end
 * @param eventCounts events per type name, only for types present
 * @param settings    per event type, setting name to value as JFR recorded it ({@code "10 ms"})
 * @param threads     every thread that appeared as an event thread
 */
public record RecordingInfo(
        Path file,
        Interval span,
        Map<String, Long> eventCounts,
        Map<String, Map<String, String>> settings,
        Set<ThreadRef> threads) {

    public RecordingInfo {
        eventCounts = Map.copyOf(new TreeMap<>(eventCounts));
        Map<String, Map<String, String>> copy = new TreeMap<>();
        settings.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
        settings = Map.copyOf(copy);
        threads = Set.copyOf(threads);
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
