package dev.jfrq.demo;

/** A CPU-bound computation with a wall-clock budget, so its cost is predictable. */
final class CpuWork {

    private CpuWork() {
    }

    /**
     * Burns roughly {@code millis} of CPU on the calling thread. The checksum is folded into
     * a volatile sink so the JIT cannot remove the work.
     */
    static void burn(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long acc = 0x9E3779B97F4A7C15L;
        do {
            for (int i = 0; i < 10_000; i++) {
                acc = mix(acc + i);
            }
        } while (System.nanoTime() < deadline);
        sink = acc;
    }

    @SuppressWarnings("unused") // written so the loop above has an observable result
    private static volatile long sink;

    private static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }
}
