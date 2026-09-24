// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

/**
 * The event fields the analyses read, as dense {@code int} tags (G-1.10), so that whether
 * an event type has a field is one bit of a mask resolved once per {@code EventType}
 * object ({@link dev.jfrq.core.model.Interner#fields}) instead of a by-name scan of the
 * type's descriptors on every event. {@link Events} takes these tags.
 *
 * <p>Invariant other code depends on: the tags are dense, start at 0, are chained
 * {@code + 1}, {@link #COUNT} is last and at most 64 (the mask is a {@code long});
 * {@link #NAMES} is indexed by tag (G-1.9).
 */
public final class Fields {

    public static final int EVENT_THREAD = 0;
    public static final int SAMPLED_THREAD = EVENT_THREAD + 1;
    public static final int ADDRESS = SAMPLED_THREAD + 1;
    public static final int ALLOCATED = ADDRESS + 1;
    public static final int ALLOCATION_SIZE = ALLOCATED + 1;
    public static final int BYTES_READ = ALLOCATION_SIZE + 1;
    public static final int BYTES_WRITTEN = BYTES_READ + 1;
    public static final int GC_ID = BYTES_WRITTEN + 1;
    public static final int HOST = GC_ID + 1;
    public static final int MONITOR_CLASS = HOST + 1;
    public static final int NAME = MONITOR_CLASS + 1;
    public static final int OBJECT_CLASS = NAME + 1;
    public static final int OPERATION = OBJECT_CLASS + 1;
    public static final int PARKED_CLASS = OPERATION + 1;
    public static final int PATH = PARKED_CLASS + 1;
    public static final int PORT = PATH + 1;
    public static final int PREVIOUS_OWNER = PORT + 1;
    public static final int SAFEPOINT = PREVIOUS_OWNER + 1;
    public static final int SAFEPOINT_ID = SAFEPOINT + 1;
    public static final int STACK_TRACE = SAFEPOINT_ID + 1;
    public static final int THREAD = STACK_TRACE + 1;
    public static final int TLAB_SIZE = THREAD + 1;
    public static final int WEIGHT = TLAB_SIZE + 1;
    public static final int COUNT = WEIGHT + 1;

    private static final String[] NAMES = new String[COUNT];

    static {
        NAMES[EVENT_THREAD] = "eventThread";
        NAMES[SAMPLED_THREAD] = "sampledThread";
        NAMES[ADDRESS] = "address";
        NAMES[ALLOCATED] = "allocated";
        NAMES[ALLOCATION_SIZE] = "allocationSize";
        NAMES[BYTES_READ] = "bytesRead";
        NAMES[BYTES_WRITTEN] = "bytesWritten";
        NAMES[GC_ID] = "gcId";
        NAMES[HOST] = "host";
        NAMES[MONITOR_CLASS] = "monitorClass";
        NAMES[NAME] = "name";
        NAMES[OBJECT_CLASS] = "objectClass";
        NAMES[OPERATION] = "operation";
        NAMES[PARKED_CLASS] = "parkedClass";
        NAMES[PATH] = "path";
        NAMES[PORT] = "port";
        NAMES[PREVIOUS_OWNER] = "previousOwner";
        NAMES[SAFEPOINT] = "safepoint";
        NAMES[SAFEPOINT_ID] = "safepointId";
        NAMES[STACK_TRACE] = "stackTrace";
        NAMES[THREAD] = "thread";
        NAMES[TLAB_SIZE] = "tlabSize";
        NAMES[WEIGHT] = "weight";
        assert COUNT <= Long.SIZE : COUNT;
        for (int field = 0; field < COUNT; field++) {
            assert NAMES[field] != null : field;
        }
    }

    private Fields() {
    }

    /** The JFR field name of a tag. */
    public static String nameOf(final int field) {
        return NAMES[field];
    }

    /** The bit of {@code field} in a presence mask. */
    public static long bit(final int field) {
        return 1L << field;
    }
}
