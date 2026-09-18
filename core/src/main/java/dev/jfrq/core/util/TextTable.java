// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

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

    public TextTable(String... headers) {
        this.headers = headers.clone();
        this.numeric = new boolean[headers.length];
    }

    /** Marks columns (by index) as right-aligned. */
    public TextTable numeric(int... columns) {
        for (int c : columns) {
            numeric[c] = true;
        }
        return this;
    }

    public TextTable row(Object... cells) {
        if (cells.length != headers.length) {
            throw new IllegalArgumentException("expected " + headers.length + " cells, got " + cells.length);
        }
        String[] row = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            row[i] = cells[i] == null ? "" : String.valueOf(cells[i]);
        }
        rows.add(row);
        return this;
    }

    public int size() {
        return rows.size();
    }

    /** Renders with the given indent on every line. The last column is never padded. */
    public String render(String indent) {
        int[] width = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            width[i] = headers[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                width[i] = Math.max(width[i], row[i].length());
            }
        }
        StringBuilder sb = new StringBuilder();
        appendRow(sb, indent, headers, width);
        for (String[] row : rows) {
            appendRow(sb, indent, row, width);
        }
        return sb.toString();
    }

    public String render() {
        return render("");
    }

    private void appendRow(StringBuilder sb, String indent, String[] row, int[] width) {
        sb.append(indent);
        for (int i = 0; i < row.length; i++) {
            boolean last = i == row.length - 1;
            String cell = row[i];
            int pad = width[i] - cell.length();
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
