// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.Closeable;
import java.nio.file.Path;

/**
 * A second {@code jfrq-live} process, as far as the cursor lock is concerned: takes the lock,
 * says so on standard output, and holds it until its standard input closes.
 */
final class LockHolder {

    private LockHolder() {
    }

    static void main(final String[] argv) throws Exception {
        try (final Closeable _ = Cursor.lock(Path.of(argv[0]), argv[1], System.err)) {
            System.out.print("locked\n");
            System.out.flush();
            while (System.in.read() >= 0) {
                // Hold until the parent closes the pipe.
            }
        }
    }
}
