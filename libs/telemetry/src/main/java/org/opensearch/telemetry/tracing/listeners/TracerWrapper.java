/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.listeners;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanScope;
import org.opensearch.telemetry.tracing.Tracer;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * This class invokes all events associated with {@link SpanEventListener}.
 * The TracerWrapper acts as a wrapper around an underlying Tracer implementation and
 * provides additional functionality to manage TraceEventListeners and trace-related settings.
 *
 * @opensearch.internal
 */
public class TracerWrapper implements Tracer {

    private static final Logger logger = LogManager.getLogger(TracerWrapper.class);

    private final Tracer tracer;

    private final TraceEventsService traceEventsService;

    /**
     * Constructs a TracerWrapper with the provided TraceEventService
     *
     * @param delegate the underlying Tracer implementation
     * @param traceEventsService  traceEventListenerService
     */
    TracerWrapper(Tracer delegate, TraceEventsService traceEventsService) {
        assert delegate != null;
        this.tracer = delegate;
        this.traceEventsService = traceEventsService;
    }

    /**
     * Starts a new span with the specified name and no attributes.
     *
     * @param spanName the name of the new span
     * @return the created SpanScope for the new span
     */
    @Override
    public SpanScope startSpan(String spanName) {
        return this.startSpan(spanName, Collections.emptyMap());
    }

    /**
     * Starts a new span with the specified name and attributes.
     *
     * @param spanName   the name of the new span
     * @param attributes the attributes to be associated with the new span
     * @return the created SpanScope for the new span
     */
    @Override
    public SpanScope startSpan(String spanName, Map<String, String> attributes) {
        SpanScope scope = tracer.startSpan(spanName, attributes);
        if (!traceEventsService.isTracingEnabled()) {
            return scope;
        }
        Span span = tracer.getCurrentSpan();
        try {
            traceEventsService.executeListeners(span, traceEventListener -> traceEventListener.onSpanStart(span, Thread.currentThread()));
            return new SpanScopeWrapper(span, scope, traceEventsService);
        } catch (Exception e) {
            // failing silently
            logger.debug("Exception while invoking TraceEventListener for span {} {}", span, e);
        }
        return scope;
    }

    /**
     * Retrieves the current active span.
     *
     * @return the current active span
     */
    @Override
    public Span getCurrentSpan() {
        return tracer.getCurrentSpan();
    }

    /**
     * Closes the TracerWrapper and releases any resources associated with it.
     *
     * @throws IOException if an I/O error occurs while closing the TracerWrapper
     */
    @Override
    public void close() throws IOException {
        tracer.close();
    }

    /**
     * Unwraps and returns the underlying Tracer instance.
     *
     * @return the underlying Tracer instance
     */
    public Tracer unwrap() {
        return tracer;
    }

    private static class SpanScopeWrapper implements SpanScope {
        private final SpanScope scope;
        private final Span span;
        private final TraceEventsService traceEventsService;

        SpanScopeWrapper(Span span, SpanScope delegate, TraceEventsService traceEventsService) {
            this.span = span;
            this.scope = delegate;
            this.traceEventsService = traceEventsService;
        }

        @Override
        public void addSpanAttribute(String key, String value) {
            scope.addSpanAttribute(key, value);
        }

        @Override
        public void addSpanAttribute(String key, long value) {
            scope.addSpanAttribute(key, value);
        }

        @Override
        public void addSpanAttribute(String key, double value) {
            scope.addSpanAttribute(key, value);
        }

        @Override
        public void addSpanAttribute(String key, boolean value) {
            scope.addSpanAttribute(key, value);
        }

        @Override
        public void addSpanEvent(String event) {
            scope.addSpanEvent(event);
        }

        @Override
        public void setError(Exception exception) {
            scope.setError(exception);
        }

        @Override
        public void close() {
            scope.close();
            try {
                if (traceEventsService.isTracingEnabled()) {
                    traceEventsService.executeListeners(
                        span,
                        traceEventListener -> traceEventListener.onSpanComplete(span, Thread.currentThread())
                    );
                }
            } catch (Exception e) {
                logger.debug("Exception on Scope close while invoking TraceEventListener for span:{} {}", span, e);
            }
        }
    }
}
