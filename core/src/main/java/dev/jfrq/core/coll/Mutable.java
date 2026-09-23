// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

/**
 * Something whose logical state can be reset for reuse without releasing it. Scratch
 * collections are cleared between uses, never re-created (G-3.2, G-3.3).
 */
public interface Mutable {

    /** Resets every field to its initial state. */
    void clear();
}
