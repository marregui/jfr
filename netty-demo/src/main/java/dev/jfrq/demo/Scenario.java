// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.demo;

import java.util.Locale;

/**
 * Which pathology the demo injects. Each one is a real bug class seen in production
 * event-loop services, reduced to its smallest reproducible form.
 */
public enum Scenario {
    /** One request in four hundred makes a synchronous call to a slow backend on the event loop thread. */
    BLOCKING_IO("blocking-io"),
    /** The handler takes a {@code synchronized} lock that a housekeeping thread holds for long stretches. */
    LOCK("lock"),
    /** One request in a thousand runs a 120 ms CPU-bound computation on the event loop thread. */
    CPU("cpu"),
    /** Background threads allocate heavily; the event loops allocate a little per request. */
    ALLOC("alloc"),
    /** All of the above at once. */
    ALL("all"),
    /** No pathology: a clean baseline recording. */
    CLEAN("clean");

    private final String flag;

    Scenario(final String flag) {
        this.flag = flag;
    }

    public String flag() {
        return flag;
    }

    public static Scenario parse(final String text) {
        for (final Scenario s : values()) {
            if (s.flag.equalsIgnoreCase(text) || s.name().equalsIgnoreCase(text)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown scenario '" + text + "'; one of " + flags());
    }

    public static String flags() {
        final StringBuilder sb = new StringBuilder();
        for (final Scenario s : values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(s.flag);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    public boolean blockingIo() {
        return this == BLOCKING_IO || this == ALL;
    }

    public boolean lock() {
        return this == LOCK || this == ALL;
    }

    public boolean cpu() {
        return this == CPU || this == ALL;
    }

    public boolean alloc() {
        return this == ALLOC || this == ALL;
    }
}
