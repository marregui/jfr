package dev.jfrq.demo;

/**
 * A stand-in for a store with one coarse lock. A background flusher thread takes the
 * lock for a long stretch at intervals, so anything else that needs it waits.
 */
final class Persistence {

    private long flushed;

    synchronized void flush(int records) {
        flushed += records;
        if (flushed < 0) {
            throw new IllegalStateException("record count overflowed");
        }
    }

    /** How long a checkpoint holds the lock. */
    static final long CHECKPOINT_HOLD_MILLIS = 120;

    /** The flusher's long critical section. */
    synchronized void checkpoint() throws InterruptedException {
        Thread.sleep(CHECKPOINT_HOLD_MILLIS);
    }
}
