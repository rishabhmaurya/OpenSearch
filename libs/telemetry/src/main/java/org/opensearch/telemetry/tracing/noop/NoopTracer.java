/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.noop;

import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanScope;
import org.opensearch.telemetry.tracing.Tracer;

import java.util.Map;

/**
 * No-op implementation of Tracer.
 * This class provides a Tracer implementation that does nothing and is used as a placeholder
 * when actual tracing functionality is not required.
 *
 * @opensearch.internal
 */
public class NoopTracer implements Tracer {

    /**
     * The singleton instance of the NoopTracer.
     */
    public static final Tracer INSTANCE = new NoopTracer();

    /**
     * Private constructor for the NoopTracer to prevent external instantiation.
     * Use the provided INSTANCE constant to access the singleton instance.
     */
    private NoopTracer() {}

    /**
     * Starts a new no-op span with the given spanName.
     * The method always returns a {@link SpanScope#NO_OP} instance, indicating that no actual
     * span-scoping is required.
     *
     * @param spanName the name of the no-op span.
     * @return a {@link SpanScope#NO_OP} instance.
     */
    @Override
    public SpanScope startSpan(String spanName) {
        return SpanScope.NO_OP;
    }

    /**
     * Starts a new no-op span with the given spanName and attributes.
     * The method always returns a {@link SpanScope#NO_OP} instance, indicating that no actual
     * span-scoping is required.
     *
     * @param spanName   the name of the no-op span.
     * @param attributes a map of attributes to be associated with the no-op span.
     * @return a {@link SpanScope#NO_OP} instance.
     */
    @Override
    public SpanScope startSpan(String spanName, Map<String, String> attributes) {
        return SpanScope.NO_OP;
    }

    /**
     * Returns the current no-op span.
     * Since the NoopTracer does not perform any actual tracing, this method always returns null.
     *
     * @return null, as there is no current span in the NoopTracer.
     */
    @Override
    public Span getCurrentSpan() {
        return null;
    }

    /**
     * Closes the NoopTracer.
     * Since the NoopTracer does not have any resources that need to be released, this method does nothing.
     */
    @Override
    public void close() {

    }
}
