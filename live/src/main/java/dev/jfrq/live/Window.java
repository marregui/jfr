// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.live;

import java.time.Instant;

/**
 * The time range a dump asks the JVM for. {@code begin} is {@code null} for "everything
 * the recording still holds"; {@code end} is {@code null} for "up to the moment of the
 * dump". The JVM answers with whole chunks: every chunk that overlaps the range, so the
 * file can start a little before {@code begin} and end a little after {@code end}
 * (see {@code docs/LIVE.md}).
 */
public record Window(Instant begin, Instant end) {

    public static final Window EVERYTHING = new Window(null, null);
}
