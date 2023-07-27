/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.telemetry.tracing.listeners.TraceEventListener;
import org.opensearch.telemetry.tracing.listeners.TraceEventsRunnable;
import org.opensearch.telemetry.tracing.listeners.TraceEventsService;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportResponseHandler;

import java.io.IOException;

/**
 * This class acts as a wrapper around a given TransportResponseHandler to handle missed runnable start trace events
 * {@link TraceEventListener#onRunnableStart} when a response is received and handled in the TransportService.
 * A thread will only have thread context once TransportService.ContextRestoreResponseHandler is invoked and thus
 * {@link TraceEventsRunnable#run()} will miss the onRunnableStart event as Span information will not be present.
 * Eventually, in TraceEventsRunnable#run()'s delegate.run(), when thread context is restored by {@link org.opensearch.transport.TransportService.ContextRestoreResponseHandler}
 * then this class will invoke missed trace event listener's the onRunnableStart() event. Whereas, onRunnableComplete will not
 * be missed and doesn't need any special handling here.
 */
public final class TraceEventTransportResponseHandler<T extends TransportResponse> implements TransportResponseHandler<T> {
    private static final Logger logger = LogManager.getLogger(TraceEventTransportResponseHandler.class);

    private final TransportResponseHandler<T> delegate;

    private final TraceEventsService traceEventsService;

    public TraceEventTransportResponseHandler(TransportResponseHandler<T> delegate,
                                              TraceEventsService traceEventsService) {
        this.delegate = delegate;
        this.traceEventsService = traceEventsService;
    }

    @Override
    public T read(StreamInput in) throws IOException {
        return delegate.read(in);
    }

    @Override
    public void handleResponse(T response) {
        try {
            TraceEventsRunnable.invokeOnRunnableStart(traceEventsService);
        } catch (Exception e) {
            logger.debug("Error in invoking onRunnableStart while TraceEventTransportResponseHandler handleResponse", e);
        } finally {
             delegate.handleResponse(response);
        }
    }

    @Override
    public void handleException(TransportException exp) {
        try {
            TraceEventsRunnable.invokeOnRunnableStart(traceEventsService);
        } catch (Exception e) {
            logger.debug("Error in invoking onRunnableStart while TraceEventTransportResponseHandler handleResponse", e);
        } finally {
            delegate.handleException(exp);
        }
    }

    @Override
    public String executor() {
        return delegate.executor();
    }
}
