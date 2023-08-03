/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing;

import java.io.Closeable;
import java.util.Map;

/**
 * Tracer is the interface used to create a {@link Span}
 * It automatically handles the context propagation between threads, tasks, nodes etc.
 *
 * All methods on the Tracer object are multi-thread safe.
 */
public interface Tracer extends Closeable {

    /**
     * Starts the {@link Span} with given name
     *
     * @param spanName span name
     * @return scope of the span, must be closed with explicit close or with try-with-resource
     */
    SpanScope startSpan(String spanName);

    /**
     * Starts the {@link Span} with given name
     *
     * @param spanName span name
     * @param attributes initial attributes
     * @return scope of the span, must be closed with explicit close or with try-with-resource
     */
    SpanScope startSpan(String spanName, Map<String, String> attributes);

    /**
     * Get the current span. Should return null if there is no active span
     */
    Span getCurrentSpan();

}
