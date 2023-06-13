/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.tracing.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.tracing.Level;
import org.opensearch.tracing.Span;
import org.opensearch.tracing.SpanHolder;
import org.opensearch.tracing.Telemetry;
import org.opensearch.tracing.Tracer;
import org.opensearch.tracing.TracerSettings;
import org.opensearch.tracing.opentelemetry.SpanFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

/**
 *
 * The default tracer implementation. This class implements the basic logic for span lifecycle and its state management.
 * It also handles tracing context propagation between spans.
 *
 * It internally uses OpenTelemetry tracer.
 *
 */
public class DefaultTracer implements Tracer {

    private static final Logger logger = LogManager.getLogger(DefaultTracer.class);
    static final String TRACE_ID = "trace_id";
    static final String SPAN_ID = "span_id";
    static final String SPAN_NAME = "span_name";
    static final String PARENT_SPAN_ID = "p_span_id";
    static final String THREAD_NAME = "th_name";
    static final String PARENT_SPAN_NAME = "p_span_name";

    private final ThreadContext threadContext;
    private final TracerSettings tracerSettings;
    private final Telemetry telemetry;
    private final SpanFactory spanFactory;

    /**
     * Creates DefaultTracer instance
     *
     * @param telemetry Otel global Opentelemetry instance
     * @param threadContext Thread pool
     * @param tracerSettings tracer related settings
     */
    public DefaultTracer(Telemetry telemetry, ThreadContext threadContext, TracerSettings tracerSettings) {
        this.telemetry = telemetry;
        this.threadContext = threadContext;
        this.tracerSettings = tracerSettings;
        this.spanFactory = new SpanFactory(tracerSettings, telemetry);
    }

    @Override
    public void startSpan(String spanName, Level level) {
        Span span = createSpan(spanName, getCurrentSpan(), level);
        setCurrentSpanInContext(span);
        addDefaultAttributes(span);
    }

    @Override
    public void endSpan() {
        Span currentSpan = getCurrentSpan();
        if (currentSpan != null) {
            currentSpan.endSpan();
            setCurrentSpanInContext(currentSpan.getParentSpan());
        }
    }

    @Override
    public void addSpanAttribute(String key, String value) {
        Span currentSpan = getCurrentSpan();
        currentSpan.addAttribute(key, value);
    }

    @Override
    public void addSpanAttribute(String key, long value) {
        Span currentSpan = getCurrentSpan();
        currentSpan.addAttribute(key, value);
    }

    @Override
    public void addSpanAttribute(String key, double value) {
        Span currentSpan = getCurrentSpan();
        currentSpan.addAttribute(key, value);
    }

    @Override
    public void addSpanAttribute(String key, boolean value) {
        Span currentSpan = getCurrentSpan();
        currentSpan.addAttribute(key, value);
    }

    @Override
    public void addSpanEvent(String event) {
        Span currentSpan = getCurrentSpan();
        currentSpan.addEvent(event);
    }

    @Override
    public void close() {
        try {
            ((Closeable) telemetry).close();
        } catch (IOException e) {
            logger.warn("Error while closing tracer", e);
        }
    }

    // Visible for testing
    public Span getCurrentSpan() {
        Optional<Span> optionalSpanFromContext = spanFromThreadContext();
        return optionalSpanFromContext.orElse(spanFromHeader());
    }

    private Span spanFromHeader() {
        return telemetry.extractSpanFromHeader(threadContext.getHeaders());
    }

    private Optional<Span> spanFromThreadContext() {
        SpanHolder spanHolder = threadContext.getTransient(Tracer.CURRENT_SPAN);

        return (spanHolder == null) ? Optional.empty() : Optional.ofNullable(spanHolder.getSpan());
    }

    private Span createSpan(String spanName, Span parentSpan, Level level) {
        return spanFactory.createSpan(spanName, parentSpan, level);
    }

    private void setCurrentSpanInContext(Span span) {
        if (span == null) {
            return;
        }
        SpanHolder spanHolder = threadContext.getTransient(Tracer.CURRENT_SPAN);
        if (spanHolder == null) {
            threadContext.putTransient(Tracer.CURRENT_SPAN, new SpanHolder(span));
        } else {
            spanHolder.setSpan(span);
        }
    }

    private void addDefaultAttributes(Span span) {
        span.addAttribute(SPAN_ID, span.getSpanId());
        span.addAttribute(TRACE_ID, span.getTraceId());
        span.addAttribute(SPAN_NAME, span.getSpanName());
        span.addAttribute(THREAD_NAME, Thread.currentThread().getName());
        if (span.getParentSpan() != null) {
            span.addAttribute(PARENT_SPAN_ID, span.getParentSpan().getSpanId());
            span.addAttribute(PARENT_SPAN_NAME, span.getParentSpan().getSpanName());
        }
    }

}
