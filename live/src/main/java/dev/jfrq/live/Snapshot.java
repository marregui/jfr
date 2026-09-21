// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.live;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;

/**
 * One dump of a window of a running recording, the way {@code jcmd JFR.dump} does it:
 * clone the recording and stop the clone, which seals the chunk being written so the
 * newest events are in a finished chunk; stream the clone's chunks that overlap the
 * window into a file; close the clone. The recording itself is not touched.
 *
 * @param file  where the dump was written
 * @param bytes its size
 * @param stop  when the clone was stopped: the exact end of the last chunk in the file,
 *              and the point the next delta continues from
 */
public record Snapshot(Path file, long bytes, Instant stop) {

    /** JMX moves the file in blocks; the JDK's default is 50 KB, which is a lot of round trips for a 40 MB dump. */
    private static final int BLOCK_SIZE = 1 << 20;

    /**
     * @throws IOException when the window holds no data, or the file cannot be written; a
     *                     partial file is removed
     */
    public static Snapshot take(final FlightRecorderMXBean fr, final long recordingId, final Window window, final Path file) throws IOException {
        final long clone = fr.cloneRecording(recordingId, true);
        try {
            final Instant stop = stopTime(fr, clone);
            final Map<String, String> options = new HashMap<>();
            options.put("blockSize", Integer.toString(BLOCK_SIZE));
            if (window.begin() != null) {
                options.put("startTime", window.begin().toString());
            }
            if (window.end() != null) {
                options.put("endTime", window.end().toString());
            }
            long stream;
            try {
                stream = fr.openStream(clone, options);
            } catch (final IOException e) {
                // "No recording data available": no chunk overlaps the window.
                throw new IOException("the recording holds no data in the window " + Live.describe(window)
                        + " (" + e.getMessage() + ")", e);
            }
            long bytes = 0;
            try (final OutputStream out = Files.newOutputStream(file)) {
                byte[] block;
                while ((block = fr.readStream(stream)) != null) {
                    out.write(block);
                    bytes += block.length;
                }
            } catch (final IOException e) {
                Files.deleteIfExists(file);
                throw e;
            } finally {
                fr.closeStream(stream);
            }
            return new Snapshot(file, bytes, stop);
        } finally {
            fr.closeRecording(clone);
        }
    }

    private static Instant stopTime(final FlightRecorderMXBean fr, final long clone) throws IOException {
        for (final RecordingInfo r : fr.getRecordings()) {
            if (r.getId() == clone) {
                return Instant.ofEpochMilli(r.getStopTime());
            }
        }
        throw new IOException("the JVM lost the recording clone " + clone + " before it could be read");
    }
}
