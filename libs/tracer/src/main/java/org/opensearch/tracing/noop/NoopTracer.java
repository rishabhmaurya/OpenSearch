/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.tracing.noop;

import org.opensearch.tracing.Level;
import org.opensearch.tracing.Span;
import org.opensearch.tracing.Tracer;

/**
 * No-op implementation of Tracer
 */
public class NoopTracer implements Tracer {

    public static final Tracer INSTANCE = new NoopTracer();

    private NoopTracer() {}

    @Override
    public void startSpan(String spanName, Level level) {

    }

    @Override
    public void endSpan() {}

    @Override
    public Span getCurrentSpan() {
        return null;
    }

    /**
     * @param key   attribute key
     * @param value attribute value
     */
    @Override
    public void addSpanAttribute(String key, String value) {

    }

    /**
     * @param key   attribute key
     * @param value attribute value
     */
    @Override
    public void addSpanAttribute(String key, long value) {

    }

    /**
     * @param key   attribute key
     * @param value attribute value
     */
    @Override
    public void addSpanAttribute(String key, double value) {

    }

    /**
     * @param key   attribute key
     * @param value attribute value
     */
    @Override
    public void addSpanAttribute(String key, boolean value) {

    }

    @Override
    public void addSpanEvent(String event) {

    }

    @Override
    public void close() {

    }
}
