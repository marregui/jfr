package dev.jfrq.demo;

/**
 * A stand-in for a store with one coarse lock. A background flusher thread takes the
 * lock for a long stretch at intervals, so anything else that needs it waits.
 */
final class Persistence {

    private long flushed;

    synchronized void flush(int records) {
        flushed += records;
    }

    synchronized long flushedRecords() {
        return flushed;
    }

    /** The flusher's long critical section. */
    synchronized void checkpoint(long holdMillis) throws InterruptedException {
        Thread.sleep(holdMillis);
    }
}
