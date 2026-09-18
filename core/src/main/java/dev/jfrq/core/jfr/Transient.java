// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: AGPL-3.0-only

package dev.jfrq.core.jfr;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a parameter the callee does not keep a reference to (G-3.6). The JFR parser
 * recycles the {@code RecordedEvent} it hands out ({@code setReuse(true)}), so a sink
 * that needs anything from an event copies it out before returning; the annotation is
 * the written form of that contract. Source retention only: it documents, it does not
 * enforce.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface Transient {
}
