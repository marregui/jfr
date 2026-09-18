package dev.jfrq.demo;

import java.util.HashMap;
import java.util.Map;

/**
 * A registry of client sessions guarded by one {@code synchronized} lock. The event loops
 * touch it on every request; the housekeeper in {@link Background} compacts it periodically and holds
 * the lock for the whole compaction, which is the bug: a long critical section on a lock
 * the hot path needs.
 *
 * <p>Compaction also flushes to {@link Persistence}, whose own lock is periodically held by
 * a flusher thread. That makes a two-link convoy: event loop waits for the registry,
 * held by the housekeeper, which waits for persistence, held by the flusher.
 */
final class SessionRegistry {

    private final Map<String, Long> lastSeen = new HashMap<>();
    private final Persistence persistence;

    SessionRegistry(Persistence persistence) {
        this.persistence = persistence;
    }

    synchronized void touch(String session) {
        lastSeen.put(session, System.nanoTime());
    }

    /** How long a compaction holds the lock. */
    static final long COMPACT_HOLD_MILLIS = 150;

    /** Holds the registry lock while "compacting" and while flushing to persistence. */
    synchronized void compact() throws InterruptedException {
        lastSeen.entrySet().removeIf(e -> e.getValue() < System.nanoTime() - 60_000_000_000L);
        persistence.flush(lastSeen.size());
        Thread.sleep(COMPACT_HOLD_MILLIS);
    }
}
