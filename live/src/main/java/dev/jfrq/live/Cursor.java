// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * What {@code jfrq-live} remembers about a JVM between runs: where the next delta starts
 * and what the previous window was, so {@code delta} continues where the last dump
 * stopped and {@code again} repeats it. One properties file per pid under the state
 * directory, keyed by the JVM's start time as well, so a pid reused after a restart is
 * a new JVM with no cursor.
 *
 * <p>The cursor is the stop time of the last dump plus one millisecond. A dump stops its
 * clone, which seals the chunk being written at instant {@code T} and starts the next
 * chunk at the same {@code T}; the JVM hands over every chunk whose range touches the
 * window, so a delta beginning at {@code T} would include the sealed chunk again. The
 * JVM reports {@code T} in milliseconds, hence one millisecond past it is strictly after
 * the sealed chunk and inside the new one: no event is counted twice and none is lost.
 */
public final class Cursor {

    private static final String KEY_BEGIN = "begin";
    private static final String KEY_CURSOR = "cursor";
    private static final String KEY_END = "end";
    private static final String KEY_JVM = "jvm";

    private final Path file;
    private final long jvmStart;
    private Window lastWindow;
    private Instant next;

    private Cursor(final Path file, final long jvmStart) {
        this.file = file;
        this.jvmStart = jvmStart;
    }

    /** The cursor for this JVM incarnation; empty when there is none or it belongs to a previous one. */
    public static Cursor load(final Path dir, final String pid, final long jvmStart) throws IOException {
        final Cursor c = new Cursor(dir.resolve(pid + ".properties"), jvmStart);
        if (!Files.isRegularFile(c.file)) {
            return c;
        }
        final Properties p = new Properties();
        try (final Reader in = Files.newBufferedReader(c.file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        if (!Long.toString(jvmStart).equals(p.getProperty(KEY_JVM))) {
            return c;
        }
        try {
            c.next = instant(p.getProperty(KEY_CURSOR));
            final Instant end = instant(p.getProperty(KEY_END));
            c.lastWindow = end == null ? null : new Window(instant(p.getProperty(KEY_BEGIN)), end);
        } catch (final DateTimeParseException e) {
            throw new IOException("cursor file " + c.file + " is damaged: " + e.getMessage(), e);
        }
        return c;
    }

    public Path file() {
        return file;
    }

    /** Where the next delta begins, or {@code null} before the first full or delta dump. */
    public Instant next() {
        return next;
    }

    /** The window of the last full or delta dump, or {@code null} before the first. */
    public Window lastWindow() {
        return lastWindow;
    }

    /** Records a full or delta dump that stopped at {@code stop} over {@code window}, and saves. */
    public void advance(final Window window, final Instant stop) throws IOException {
        next = stop.plusMillis(1);
        lastWindow = new Window(window.begin(), stop);
        save();
    }

    private void save() throws IOException {
        final Properties p = new Properties();
        p.setProperty(KEY_JVM, Long.toString(jvmStart));
        p.setProperty(KEY_CURSOR, next.toString());
        p.setProperty(KEY_BEGIN, lastWindow.begin() == null ? "" : lastWindow.begin().toString());
        p.setProperty(KEY_END, lastWindow.end().toString());
        Files.createDirectories(file.getParent());
        try (final Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(out, "jfrq-live cursor: cursor is where the next delta begins; begin/end is the last window");
        }
    }

    private static Instant instant(final String s) {
        return s == null || s.isEmpty() ? null : Instant.parse(s);
    }
}
