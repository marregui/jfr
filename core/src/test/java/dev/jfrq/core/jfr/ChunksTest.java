package dev.jfrq.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The chunk scan is what stands between a damaged file and a silently empty answer, so
 * every damage shape gets a real file: cut mid-chunk, cut after a complete chunk, header
 * only, wrong magic, wrong version, empty.
 */
class ChunksTest {

    @TempDir
    Path dir;

    private Path recording() throws Exception {
        return JfrFixtures.record(dir, "chunks", r -> r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO),
                () -> JfrFixtures.sleep(30));
    }

    @Test
    void aFinishedRecordingIsOneOrMoreCompleteChunks() throws Exception {
        Path file = recording();
        Chunks chunks = Chunks.scan(file);

        assertFalse(chunks.truncated());
        assertFalse(chunks.inProgress());
        assertTrue(chunks.count() >= 1);
        assertEquals(chunks.count(), chunks.complete());
        long sum = 0;
        for (Chunks.Header h : chunks.headers()) {
            assertTrue(h.size() >= Chunks.HEADER_SIZE);
            assertTrue(h.durationNanos() > 0);
            assertEquals(sum, h.offset());
            sum += h.size();
        }
        assertEquals(Files.size(file), sum);
        assertTrue(chunks.endNanos() - chunks.startNanos() >= 25_000_000L, "span " + (chunks.endNanos() - chunks.startNanos()));
        // The reader takes its span from the headers and reports the count.
        RecordingInfo info = JfrReader.read(file);
        assertEquals(chunks.startNanos(), info.startNanos());
        assertEquals(chunks.endNanos(), info.endNanos());
        assertEquals(chunks.count(), info.chunks());
        assertTrue(info.warnings().isEmpty(), info.warnings().toString());
    }

    @Test
    void aFileCutInsideItsOnlyChunkIsRefusedInsteadOfReadAsEmpty() throws Exception {
        Path file = recording();
        byte[] bytes = Files.readAllBytes(file);
        Path cut = dir.resolve("cut.jfr");
        Files.write(cut, Arrays.copyOf(bytes, bytes.length / 2));

        Chunks chunks = Chunks.scan(cut);
        assertTrue(chunks.truncated());
        assertEquals(0, chunks.count());
        IOException e = assertThrows(IOException.class, () -> JfrReader.read(cut));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());

        // Header only: the same.
        Path headerOnly = dir.resolve("header.jfr");
        Files.write(headerOnly, Arrays.copyOf(bytes, Chunks.HEADER_SIZE));
        assertTrue(Chunks.scan(headerOnly).truncated());
        assertThrows(IOException.class, () -> JfrReader.read(headerOnly));
    }

    @Test
    void aFileCutAfterACompleteChunkReadsThatChunkAndWarns() throws Exception {
        // Two recordings: stopping the inner one rotates the chunk, so the outer file has
        // at least two, and the second can be cut off.
        Path file = Files.createTempFile(dir, "two", ".jfr");
        try (Recording outer = new Recording()) {
            outer.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            outer.setDestination(file);
            outer.start();
            JfrFixtures.onThread("first-chunk-sleeper", () -> JfrFixtures.sleep(30));
            try (Recording inner = new Recording()) {
                inner.start();
                JfrFixtures.sleep(10);
                inner.stop();
            }
            JfrFixtures.onThread("second-chunk-sleeper", () -> JfrFixtures.sleep(30));
            outer.stop();
        }
        Chunks whole = Chunks.scan(file);
        assertTrue(whole.count() >= 2, "chunks " + whole.count());
        Chunks.Header first = whole.headers().getFirst();

        byte[] bytes = Files.readAllBytes(file);
        Path cut = dir.resolve("cut2.jfr");
        Files.write(cut, Arrays.copyOf(bytes, (int) (first.size() + Chunks.HEADER_SIZE + 10)));
        Chunks chunks = Chunks.scan(cut);
        assertTrue(chunks.truncated());
        assertEquals(1, chunks.count());

        RecordingInfo info = JfrReader.read(cut);
        assertEquals(1, info.chunks());
        assertEquals(1, info.warnings().size(), info.warnings().toString());
        assertTrue(info.warnings().getFirst().startsWith("the file is truncated"), info.warnings().getFirst());
        assertTrue(info.threads().stream().anyMatch(t -> t.name().equals("first-chunk-sleeper")), info.threads().toString());
        assertFalse(info.threads().stream().anyMatch(t -> t.name().equals("second-chunk-sleeper")), info.threads().toString());
        assertEquals(first.startNanos(), info.startNanos());
        assertEquals(first.endNanos(), info.endNanos());
    }

    /**
     * The JDK parser does not fail on an unfinished chunk: it polls the header until the
     * file-state byte reads zero, forever when the file is a copy. The JVM rewrites size and
     * duration at every flush, so only that byte says whether the chunk is closed; the
     * fixture is a copy of a real repository chunk taken while the JVM is writing it.
     */
    @Test
    void aCopyOfALiveChunkIsRefusedBeforeTheParserCanHangOnIt() throws Exception {
        Path live = dir.resolve("live.jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            r.setToDisk(true);
            r.start();
            // The first flush (about a second in) writes size and duration into the header
            // while the chunk stays open; a copy taken then is the interesting one.
            JfrFixtures.sleep(1_500);
            Path repository = Path.of(System.getProperty("jdk.jfr.repository"));
            Path chunk;
            try (var files = Files.list(repository)) {
                chunk = files.filter(f -> f.toString().endsWith(".jfr"))
                        .max(java.util.Comparator.comparingLong(f -> f.toFile().lastModified())).orElseThrow();
            }
            Files.copy(chunk, live, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            r.stop();
        }
        Chunks chunks = Chunks.scan(live);
        assertEquals(1, chunks.count());
        Chunks.Header h = chunks.headers().getFirst();
        assertTrue(h.fileState() != 0, "file state " + h.fileState());
        assertTrue(h.inProgress());
        assertTrue(chunks.inProgress());
        assertEquals(0, chunks.complete());
        IOException e = assertThrows(IOException.class, () -> JfrReader.read(live));
        assertTrue(e.getMessage().contains("still being written"), e.getMessage());
        assertTrue(e.getMessage().contains("truncate the file to 0 bytes"), e.getMessage());

        // A header with no size at all (the JVM's very first write) is refused the same way.
        byte[] bytes = Files.readAllBytes(recording());
        ByteBuffer.wrap(bytes).putLong(8, 0).putLong(40, 0);
        Path unsized = dir.resolve("unsized.jfr");
        Files.write(unsized, bytes);
        assertTrue(Chunks.scan(unsized).inProgress());
        assertThrows(IOException.class, () -> JfrReader.read(unsized));
    }

    @Test
    void aFinishedFileHasEveryFileStateByteAtZero() throws Exception {
        for (Chunks.Header h : Chunks.scan(recording()).headers()) {
            assertEquals(0, h.fileState());
        }
    }

    @Test
    void aFileCutInsideTheNextChunksHeaderIsTruncated() throws Exception {
        byte[] bytes = Files.readAllBytes(recording());
        Path cut = dir.resolve("cut-header.jfr");
        Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length + 30));
        Chunks chunks = Chunks.scan(cut);
        assertEquals(1, chunks.count());
        assertTrue(chunks.truncated());
        assertEquals(1, JfrReader.read(cut).warnings().size());
    }

    @Test
    void junkAndEmptyFilesAreRefusedWithAPlainMessage() throws Exception {
        Path junk = dir.resolve("junk.jfr");
        Files.writeString(junk, "x".repeat(200));
        assertTrue(assertThrows(IOException.class, () -> Chunks.scan(junk)).getMessage().contains("not a Flight Recorder"));

        Path empty = dir.resolve("empty.jfr");
        Files.write(empty, new byte[0]);
        assertTrue(assertThrows(IOException.class, () -> Chunks.scan(empty)).getMessage().contains("not a Flight Recorder"));

        byte[] bytes = Files.readAllBytes(recording());
        ByteBuffer.wrap(bytes).putShort(4, (short) 3);
        Path future = dir.resolve("v3.jfr");
        Files.write(future, bytes);
        assertTrue(assertThrows(IOException.class, () -> Chunks.scan(future)).getMessage().contains("version 3"));
    }
}
