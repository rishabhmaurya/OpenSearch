/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.listeners.wrappers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.telemetry.tracing.listeners.RunnableEventListener;
import org.opensearch.telemetry.tracing.listeners.TraceEventListener;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.listeners.TraceEventsService;

/**
 * Runnable implementation that wraps another Runnable and adds trace event listener functionality.
 */
public class TraceEventsRunnable implements Runnable {
    private static final Logger logger = LogManager.getLogger(TraceEventsRunnable.class);

    private final Runnable delegate;
    private final TraceEventsService traceEventsService;

    /**
     * Constructs a TraceEventsRunnable with the provided delegate Runnable.
     * Tracer is used to get current span information and
     * {@link RunnableEventListener} events are invoked for all traceEventListeners.
     * @param delegate            the underlying Runnable to be executed
     * @param traceEventsService  traceEventListenerService
     */
    public TraceEventsRunnable(Runnable delegate, TraceEventsService traceEventsService) {
        this.delegate = delegate;
        this.traceEventsService = traceEventsService;
    }

    /**
     * Wraps the delegate runnable run method with {@link TraceEventListener#onRunnableStart} and
     * {@link TraceEventListener#onRunnableComplete}
     */
    @Override
    public void run() {
        try {
            if (traceEventsService.isTracingEnabled()) {
                Span span = traceEventsService.getTracer().getCurrentSpan();
                // repeat it for all the spans in the hierarchy
                while (span != null) {
                    if (span.hasEnded()) {
                        logger.debug("TraceEventsRunnable is invoked post span completion", new Throwable());
                    } else {
                        Span finalSpan = span;
                        traceEventsService.executeListeners(
                            span,
                            traceEventListener -> traceEventListener.onRunnableStart(finalSpan, Thread.currentThread())
                        );
                    }
                    span = span.getParentSpan();
                }
            }
        } catch (Exception e) {
            logger.debug("Error in onRunnableStart", e);
        } finally {
            delegate.run();
        }
        try {
            if (traceEventsService.isTracingEnabled()) {
                Span span = traceEventsService.getTracer().getCurrentSpan();
                while (span != null) {
                    if (span.hasEnded()) {
                        logger.debug("TraceEventsRunnable is invoked post span completion", new Throwable());
                    } else {
                        Span finalSpan = span;
                        traceEventsService.executeListeners(
                            span,
                            traceEventListener -> traceEventListener.onRunnableComplete(finalSpan, Thread.currentThread())
                        );
                    }
                    span = span.getParentSpan();
                }
            }
        } catch (Exception e) {
            logger.debug("Error in onRunnableEnd", e);
        }
    }

    /**
     * Unwraps and returns the underlying Runnable instance.
     *
     * @return the underlying Runnable instance
     */
    public Runnable unwrap() {
        return delegate;
    }
}
