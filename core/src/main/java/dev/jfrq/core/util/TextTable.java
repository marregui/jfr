// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal fixed-width table renderer for terminal output. Columns are left-aligned
 * unless marked numeric, in which case they are right-aligned. No borders, no colour:
 * the output is meant to be pasted into tickets and chat.
 */
public final class TextTable {

    private final String[] headers;
    private final boolean[] numeric;
    private final List<String[]> rows = new ArrayList<>();

    public TextTable(final String... headers) {
        this.headers = headers.clone();
        this.numeric = new boolean[headers.length];
    }

    /** Marks columns (by index) as right-aligned. */
    public TextTable numeric(final int... columns) {
        for (final int c : columns) {
            numeric[c] = true;
        }
        return this;
    }

    public TextTable row(final Object... cells) {
        if (cells.length != headers.length) {
            throw new IllegalArgumentException("expected " + headers.length + " cells, got " + cells.length);
        }
        final String[] row = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            row[i] = cells[i] == null ? "" : String.valueOf(cells[i]);
        }
        rows.add(row);
        return this;
    }

    public int size() {
        return rows.size();
    }

    /** Renders with the given indent on every line. A left-aligned last column carries no trailing spaces. */
    public String render(final String indent) {
        final int[] width = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            width[i] = headers[i].length();
        }
        for (final String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                width[i] = Math.max(width[i], row[i].length());
            }
        }
        final StringBuilder sb = new StringBuilder();
        appendRow(sb, indent, headers, width);
        for (final String[] row : rows) {
            appendRow(sb, indent, row, width);
        }
        return sb.toString();
    }

    public String render() {
        return render("");
    }

    private void appendRow(final StringBuilder sb, final String indent, final String[] row, final int[] width) {
        sb.append(indent);
        for (int i = 0; i < row.length; i++) {
            final boolean last = i == row.length - 1;
            final String cell = row[i];
            final int pad = width[i] - cell.length();
            if (numeric[i]) {
                sb.repeat(' ', pad).append(cell);
            } else {
                sb.append(cell);
                if (!last) {
                    sb.repeat(' ', pad);
                }
            }
            if (!last) {
                sb.append("  ");
            }
        }
        sb.append('\n');
    }
}
