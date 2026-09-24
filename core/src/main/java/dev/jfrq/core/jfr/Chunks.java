// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import dev.jfrq.core.coll.Nulls;

/**
 * The chunk headers of a recording, read directly from the file. A recording is a
 * sequence of self-contained chunks, each starting with a 68-byte header that carries the
 * chunk's size and its exact time span. Reading them costs a few small reads per chunk and
 * gives two things the event stream cannot:
 * <ul>
 *   <li>the recording's <em>data span</em>: from the first chunk's start to the last
 *       chunk's end, independent of which events were subscribed and of any other
 *       recording that happened to be active in the JVM;</li>
 *   <li>a structural check: a chunk whose declared size runs past the end of the file,
 *       or whose last constant pool and metadata do not sit where its header says, was cut
 *       off or damaged, and a chunk whose file-state byte is not zero is one still being
 *       written. The JDK parser stops silently at the first, reading nothing after it,
 *       and waits forever at the second, which would turn a damaged file into an empty
 *       answer or a hung process.</li>
 * </ul>
 *
 * <p>A damaged chunk is not the end of the file: the scan looks for the next chunk header
 * behind it, so a cut-off recording with another appended (JFR files concatenate) still
 * has its complete chunks found, and {@link #copyComplete(Path, Path)} hands them to the
 * parser without the damage in front of them.
 *
 * @param headers one entry per readable chunk, in file order
 * @param damaged the stretches of the file that are not a readable chunk, in file order; the
 *                JDK parser reads nothing of them, nor anything after the first
 */
public record Chunks(List<Header> headers, List<Damage> damaged) {

    /** Chunk header size, magic and layout, as written by the JVM since JFR 2.0 (JDK 9). */
    static final int HEADER_SIZE = 68;
    private static final int MAGIC = ('F' << 24) | ('L' << 16) | ('R' << 8);
    private static final int MAJOR = 2;
    /** The event type ids of a chunk's metadata and constant pool events. */
    private static final long METADATA = 0;
    private static final long CONSTANT_POOL = 1;
    /** Flag bit in the header's last byte: integers in the chunk are LEB128-compressed. */
    private static final int COMPRESSED_INTS = 1;
    /** An event's size and type, compressed: two LEB128 numbers of at most nine bytes each. */
    private static final int EVENT_HEADER_MAX = 18;
    private static final int SCAN_BLOCK = 64 * 1024;

    /**
     * One chunk.
     *
     * @param offset        position of the header in the file
     * @param size          declared chunk size in bytes; 0 while the chunk is being written
     * @param startNanos    chunk start, epoch nanoseconds
     * @param durationNanos chunk length; 0 until the first flush
     * @param fileState     the JVM's generation byte: 0 once the chunk is finished, otherwise
     *                      the number of flushes so far. The JDK parser keys on the same byte.
     */
    public record Header(long offset, long size, long startNanos, long durationNanos, int fileState) {
        public long endNanos() {
            return startNanos + durationNanos;
        }

        /**
         * A chunk whose header has not been finalised: the recording was copied while live, or
         * the JVM died. Size and duration are rewritten at every flush, so they say nothing
         * on their own; the file-state byte is what the JVM clears when it closes the chunk.
         */
        public boolean isInProgress() {
            return fileState != 0 || size == 0 || durationNanos == 0;
        }
    }

    /**
     * A stretch of the file that is not a readable chunk.
     *
     * @param offset        where it starts in the file
     * @param length        how many bytes, up to the next readable chunk or the end of the file
     * @param startNanos    the start its chunk header declares, or {@link Nulls#LONG_NULL} when
     *                      there is no whole header to read one from
     * @param durationNanos the duration the header declares, 0 without one
     */
    public record Damage(long offset, long length, long startNanos, long durationNanos) {
        public long end() {
            return offset + length;
        }

        /** Whether the stretch starts with a chunk header, so its time span is known. */
        public boolean hasHeader() {
            return startNanos != Nulls.LONG_NULL;
        }
    }

    public Chunks {
        headers = List.copyOf(headers);
        damaged = List.copyOf(damaged);
    }

    public static Chunks scan(final Path file) throws IOException {
        final List<Header> headers = new ArrayList<>();
        final List<Damage> damaged = new ArrayList<>();
        try (final FileChannel ch = FileChannel.open(file)) {
            final long length = ch.size();
            if (length < HEADER_SIZE) {
                throw new IOException("not a Flight Recorder file: " + file + " is only " + length + " bytes");
            }
            final ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
            long offset = 0;
            while (offset < length) {
                if (length - offset < HEADER_SIZE) {
                    // Fewer than a header's worth of bytes left: the next chunk had started when
                    // the file was cut.
                    damaged.add(new Damage(offset, length - offset, Nulls.LONG_NULL, 0));
                    break;
                }
                readFully(ch, buf, offset);
                if (buf.getInt(0) != MAGIC) {
                    if (offset == 0) {
                        throw new IOException("not a Flight Recorder file: " + file);
                    }
                    final long next = nextChunk(ch, offset + 1, length);
                    damaged.add(new Damage(offset, next - offset, Nulls.LONG_NULL, 0));
                    offset = next;
                    continue;
                }
                final int major = buf.getShort(4);
                if (major != MAJOR) {
                    throw new IOException("unsupported Flight Recorder file version " + major + "." + buf.getShort(6)
                            + " in " + file + " (JDK 9 or later writes version 2)");
                }
                final Header header = header(buf, offset);
                if (header.isInProgress()) {
                    // Still being written: nothing after this header can be trusted, and the JDK
                    // parser would wait for the chunk to finish rather than fail.
                    headers.add(header);
                    break;
                }
                if (!isIntact(ch, buf, header, length)) {
                    // Cut off mid-write or mid-copy, possibly with another file appended, or a
                    // damaged size field. What follows may still be whole chunks.
                    final long next = nextChunk(ch, offset + 1, length);
                    damaged.add(new Damage(offset, next - offset, header.startNanos(), header.durationNanos()));
                    offset = next;
                    continue;
                }
                headers.add(header);
                offset += header.size();
            }
        }
        return new Chunks(headers, damaged);
    }

    private static void readFully(final FileChannel ch, final ByteBuffer buf, final long offset) throws IOException {
        buf.clear();
        while (buf.hasRemaining() && ch.read(buf, offset + buf.position()) >= 0) {
            // A file channel reads what is there; the loop only covers a short read.
        }
        buf.flip();
    }

    private static Header header(final ByteBuffer buf, final long offset) {
        return new Header(offset, buf.getLong(8), buf.getLong(32), buf.getLong(40), buf.get(64) & 0xff);
    }

    /**
     * Whether a finished chunk is what its header says: it fits in the file, and its last
     * constant pool and its metadata, which the header points at, are events of those types
     * that end inside the chunk. A chunk cut short and followed by another file has the right
     * size by accident and the wrong bytes at both places. The size is compared as a
     * remainder so a huge one cannot overflow.
     */
    private static boolean isIntact(final FileChannel ch, final ByteBuffer headerBuf, final Header h, final long length)
            throws IOException {
        if (h.size() < HEADER_SIZE || h.size() > length - h.offset()) {
            return false;
        }
        final boolean compressed = (headerBuf.get(HEADER_SIZE - 1) & COMPRESSED_INTS) != 0;
        return isEvent(ch, h, headerBuf.getLong(16), CONSTANT_POOL, compressed)
                && isEvent(ch, h, headerBuf.getLong(24), METADATA, compressed);
    }

    private static boolean isEvent(final FileChannel ch, final Header h, final long position, final long type,
            final boolean compressed) throws IOException {
        if (position < HEADER_SIZE || position >= h.size()) {
            return false;
        }
        final ByteBuffer buf = ByteBuffer.allocate(EVENT_HEADER_MAX);
        buf.limit((int) Math.min(EVENT_HEADER_MAX, h.size() - position));
        while (buf.hasRemaining() && ch.read(buf, h.offset() + position + buf.position()) >= 0) {
            // As in readFully.
        }
        buf.flip();
        final long size;
        final long actual;
        if (compressed) {
            size = leb128(buf);
            actual = leb128(buf);
        } else {
            size = buf.remaining() >= 4 ? buf.getInt() : -1;
            actual = buf.remaining() >= 8 ? buf.getLong() : -1;
        }
        return actual == type && size > 0 && size <= h.size() - position;
    }

    /** JFR's compressed integer: seven bits per byte, low first, and all eight in a ninth. -1 when cut short. */
    private static long leb128(final ByteBuffer buf) {
        long value = 0;
        for (int i = 0; i < 9; i++) {
            if (!buf.hasRemaining()) {
                return -1;
            }
            final int b = buf.get() & 0xff;
            if (i == 8) {
                return value | ((long) b << 56);
            }
            value |= (long) (b & 0x7f) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return value;
    }

    /**
     * The first offset at or after {@code from} where a finished and intact chunk starts; the
     * file length when there is none. The magic alone proves nothing: every flush writes a
     * copy of the chunk header into the chunk, with the file-state byte of a chunk still being
     * written, so a damaged chunk is full of headers that are not chunks. Those, and any four
     * bytes of event data that happen to spell the magic, fail the checks a real chunk passes.
     */
    private static long nextChunk(final FileChannel ch, final long from, final long length) throws IOException {
        final ByteBuffer block = ByteBuffer.allocate(SCAN_BLOCK);
        final ByteBuffer candidate = ByteBuffer.allocate(HEADER_SIZE);
        long base = from;
        while (length - base >= HEADER_SIZE) {
            block.clear();
            block.limit((int) Math.min(SCAN_BLOCK, length - base));
            while (block.hasRemaining() && ch.read(block, base + block.position()) >= 0) {
                // As in readFully.
            }
            block.flip();
            // Stop three bytes short so a magic that straddles two blocks is found in the second.
            final int last = block.limit() - 4;
            for (int i = 0; i <= last; i++) {
                if (block.getInt(i) != MAGIC) {
                    continue;
                }
                final long at = base + i;
                if (length - at < HEADER_SIZE) {
                    return length;
                }
                readFully(ch, candidate, at);
                if (candidate.getShort(4) != MAJOR) {
                    continue;
                }
                final Header h = header(candidate, at);
                if (!h.isInProgress() && isIntact(ch, candidate, h, length)) {
                    return at;
                }
            }
            base += Math.max(1, last + 1);
        }
        return length;
    }

    /** Whether some part of the file is not a readable chunk. */
    public boolean isTruncated() {
        return !damaged.isEmpty();
    }

    /**
     * Whether the JDK parser, reading the file from its first byte, reaches every complete
     * chunk: the parser stops at the first damage, so damage is harmless only behind them.
     */
    public boolean readableInPlace() {
        return damaged.isEmpty() || headers.isEmpty() || damaged.getFirst().offset() > headers.getLast().offset();
    }

    /**
     * Copies the complete chunks, in file order and nothing else, to {@code target}: a file
     * the JDK parser reads from end to end, for when {@link #readableInPlace()} is false.
     */
    public void copyComplete(final Path file, final Path target) throws IOException {
        try (final FileChannel in = FileChannel.open(file);
             final FileChannel out = FileChannel.open(target, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            for (final Header h : headers) {
                if (h.isInProgress()) {
                    continue;
                }
                long done = 0;
                while (done < h.size()) {
                    final long n = in.transferTo(h.offset() + done, h.size() - done, out);
                    if (n <= 0) {
                        throw new IOException("could not copy the chunk at byte " + h.offset() + " of " + file);
                    }
                    done += n;
                }
            }
        }
    }

    public int count() {
        return headers.size();
    }

    /** Earliest chunk start, or 0 without chunks. */
    public long startNanos() {
        long start = Long.MAX_VALUE;
        for (final Header h : headers) {
            start = Math.min(start, h.startNanos());
        }
        return start == Long.MAX_VALUE ? 0 : start;
    }

    /**
     * Latest chunk end (start + duration), or 0 without chunks. An in-progress chunk
     * contributes the end as of its last flush, which is its start before the first one.
     */
    public long endNanos() {
        long end = Long.MIN_VALUE;
        for (final Header h : headers) {
            end = Math.max(end, h.endNanos());
        }
        return end == Long.MIN_VALUE ? 0 : end;
    }

    /** Whether the last chunk has not been finalised. */
    public boolean isInProgress() {
        return !headers.isEmpty() && headers.getLast().isInProgress();
    }

    /** Chunks the JDK parser will read completely: every finalised chunk that fits in the file. */
    public int complete() {
        int n = 0;
        for (final Header h : headers) {
            if (!h.isInProgress()) {
                n++;
            }
        }
        return n;
    }
}
