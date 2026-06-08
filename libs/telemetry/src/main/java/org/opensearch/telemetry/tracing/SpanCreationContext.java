/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.telemetry.tracing.attributes.Attributes;

import java.time.Instant;

/**
 * Context for span details.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public final class SpanCreationContext {
    private String spanName;
    private Attributes attributes;
    private SpanKind spanKind = SpanKind.INTERNAL;
    private SpanContext parent;
    private Instant startTimestamp;

    /**
     * Constructor.
     */
    private SpanCreationContext() {}

    /**
     * Sets the span type to server.
     * @return spanCreationContext
     */
    public static SpanCreationContext server() {
        SpanCreationContext spanCreationContext = new SpanCreationContext();
        spanCreationContext.spanKind = SpanKind.SERVER;
        return spanCreationContext;
    }

    /**
     * Sets the span type to client.
     * @return spanCreationContext
     */
    public static SpanCreationContext client() {
        SpanCreationContext spanCreationContext = new SpanCreationContext();
        spanCreationContext.spanKind = SpanKind.CLIENT;
        return spanCreationContext;
    }

    /**
     * Sets the span type to internal.
     * @return spanCreationContext
     */
    public static SpanCreationContext internal() {
        SpanCreationContext spanCreationContext = new SpanCreationContext();
        spanCreationContext.spanKind = SpanKind.INTERNAL;
        return spanCreationContext;
    }

    /**
     * Sets the span name.
     * @param spanName span name.
     * @return spanCreationContext
     */
    public SpanCreationContext name(String spanName) {
        this.spanName = spanName;
        return this;
    }

    /**
     * Sets the span attributes.
     * @param attributes attributes.
     * @return spanCreationContext
     */
    public SpanCreationContext attributes(Attributes attributes) {
        this.attributes = attributes;
        return this;
    }

    /**
     * Sets the parent for span
     * @param parent parent span context
     * @return spanCreationContext
     */
    public SpanCreationContext parent(SpanContext parent) {
        this.parent = parent;
        return this;
    }

    /**
     * Sets an explicit start timestamp for the span. When set, the span is recorded as having begun
     * at this instant rather than "now" — used to reconstruct spans for work that already happened
     * (e.g. native per-operator execution timed after the fact). Null (the default) means start-now.
     * @param startTimestamp explicit start instant, or null for start-now
     * @return spanCreationContext
     */
    public SpanCreationContext startTimestamp(Instant startTimestamp) {
        this.startTimestamp = startTimestamp;
        return this;
    }

    /**
     * Returns the span name.
     * @return span name
     */
    public String getSpanName() {
        return spanName;
    }

    /**
     * Returns the span attributes.
     * @return attributes.
     */
    public Attributes getAttributes() {
        return attributes;
    }

    /**
     * Returns the span kind.
     * @return spankind.
     */
    public SpanKind getSpanKind() {
        return spanKind;
    }

    /**
     * Returns the parent span
     * @return parent.
     */
    public SpanContext getParent() {
        return parent;
    }

    /**
     * Returns the explicit start timestamp, or null when the span should start "now".
     * @return start timestamp or null
     */
    public Instant getStartTimestamp() {
        return startTimestamp;
    }
}
