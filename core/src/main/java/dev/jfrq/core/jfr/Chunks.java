// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.jfr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The chunk headers of a recording, read directly from the file. A recording is a
 * sequence of self-contained chunks, each starting with a 68-byte header that carries the
 * chunk's size and its exact time span. Reading them costs one small read per chunk and
 * gives two things the event stream cannot:
 * <ul>
 *   <li>the recording's <em>data span</em>: from the first chunk's start to the last
 *       chunk's end, independent of which events were subscribed and of any other
 *       recording that happened to be active in the JVM;</li>
 *   <li>a structural check: a chunk whose declared size runs past the end of the file
 *       is a truncated recording, and a chunk whose file-state byte is not zero is one
 *       still being written. The JDK's parser stops silently at the first and waits
 *       forever at the second, which would turn a damaged file into an empty answer or
 *       a hung process.</li>
 * </ul>
 *
 * @param headers   one entry per readable chunk, in file order
 * @param truncated true when a chunk's declared size runs past the end of the file; that
 *                  chunk is not in {@code headers}, and the JDK parser reads nothing of it
 */
public record Chunks(List<Header> headers, boolean truncated) {

    /** Chunk header size, magic and layout, as written by the JVM since JFR 2.0 (JDK 9). */
    static final int HEADER_SIZE = 68;
    private static final int MAGIC = ('F' << 24) | ('L' << 16) | ('R' << 8);
    private static final int MAJOR = 2;

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
        public boolean inProgress() {
            return fileState != 0 || size == 0 || durationNanos == 0;
        }
    }

    public Chunks {
        headers = List.copyOf(headers);
    }

    public static Chunks scan(final Path file) throws IOException {
        final List<Header> headers = new ArrayList<>();
        boolean truncated = false;
        try (final FileChannel ch = FileChannel.open(file)) {
            final long length = ch.size();
            if (length < HEADER_SIZE) {
                throw new IOException("not a Flight Recorder file: " + file + " is only " + length + " bytes");
            }
            final ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
            long offset = 0;
            while (offset + HEADER_SIZE <= length) {
                buf.clear();
                ch.read(buf, offset);
                if (buf.hasRemaining()) {
                    truncated = true;
                    break;
                }
                buf.flip();
                if (buf.getInt(0) != MAGIC) {
                    if (offset == 0) {
                        throw new IOException("not a Flight Recorder file: " + file);
                    }
                    truncated = true;
                    break;
                }
                final int major = buf.getShort(4);
                if (major != MAJOR) {
                    throw new IOException("unsupported Flight Recorder file version " + major + "." + buf.getShort(6)
                            + " in " + file + " (JDK 9 or later writes version 2)");
                }
                final long size = buf.getLong(8);
                final long start = buf.getLong(32);
                final long duration = buf.getLong(40);
                final int fileState = buf.get(64) & 0xff;
                final Header header = new Header(offset, size, start, duration, fileState);
                if (header.inProgress()) {
                    // Still being written: nothing after this header can be trusted, and the JDK
                    // parser would wait for the chunk to finish rather than fail.
                    headers.add(header);
                    break;
                }
                if (size < HEADER_SIZE || offset + size > length) {
                    // The chunk claims more bytes than the file has: cut off mid-write or mid-copy.
                    truncated = true;
                    break;
                }
                headers.add(header);
                offset += size;
            }
            if (offset < length && !truncated && (headers.isEmpty() || !headers.getLast().inProgress())) {
                // Fewer than a header's worth of bytes after the last complete chunk: the next one
                // had started when the file was cut.
                truncated = true;
            }
        }
        return new Chunks(headers, truncated);
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

    /** Latest chunk end (start + duration), or 0 without chunks; an in-progress chunk contributes its start. */
    public long endNanos() {
        long end = Long.MIN_VALUE;
        for (final Header h : headers) {
            end = Math.max(end, h.endNanos());
        }
        return end == Long.MIN_VALUE ? 0 : end;
    }

    /** Whether the last chunk has not been finalised. */
    public boolean inProgress() {
        return !headers.isEmpty() && headers.getLast().inProgress();
    }

    /** Chunks the JDK parser will read completely: every finalised chunk that fits in the file. */
    public int complete() {
        int n = 0;
        for (final Header h : headers) {
            if (!h.inProgress()) {
                n++;
            }
        }
        return n;
    }
}
