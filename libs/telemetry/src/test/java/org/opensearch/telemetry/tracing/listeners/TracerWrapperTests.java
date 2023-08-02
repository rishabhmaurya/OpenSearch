/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.tracing.listeners;

import org.junit.Test;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanScope;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;

import static org.mockito.Mockito.*;

public class TracerWrapperTests extends OpenSearchTestCase {

    private Tracer tracer;
    private TraceEventsService traceEventsService;
    private TraceEventListener traceEventListener1;
    private TraceEventListener traceEventListener2;
    private Span span;
    private SpanScope spanScope;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        tracer = mock(Tracer.class);
        traceEventsService = spy(new TraceEventsService());
        traceEventListener1 = mock(TraceEventListener.class);
        traceEventListener2 = mock(TraceEventListener.class);
        span = mock(Span.class);
        spanScope = mock(SpanScope.class);

        when(tracer.startSpan(anyString(), anyMap())).thenReturn(spanScope);
        when(tracer.getCurrentSpan()).thenReturn(span);
        when(traceEventsService.isTracingEnabled()).thenReturn(true);
        when(traceEventsService.getTracer()).thenReturn(tracer);

        traceEventsService.registerTraceEventListener("listener1", traceEventListener1);
        traceEventsService.registerTraceEventListener("listener2", traceEventListener2);
    }

    @Test
    public void testStartSpan_WithTracingEnabled_InvokeOnSpanStartAndOnSpanComplete() {
        TracerWrapper tracerWrapper = new TracerWrapper(tracer, traceEventsService);
        when(traceEventListener1.isEnabled(any(Span.class))).thenReturn(true);
        when(traceEventListener2.isEnabled(any(Span.class))).thenReturn(true);

        SpanScope scope = tracerWrapper.startSpan("test_span", Collections.emptyMap());

        verify(traceEventListener1).onSpanStart(eq(span), any(Thread.class));
        verify(traceEventListener2).onSpanStart(eq(span), any(Thread.class));

        scope.close();

        verify(traceEventListener1).onSpanComplete(eq(span), any(Thread.class));
        verify(traceEventListener2).onSpanComplete(eq(span), any(Thread.class));
    }

    @Test
    public void testStartSpan_WithTracingDisabled_NoInteractionsWithListeners() {
        when(traceEventsService.isTracingEnabled()).thenReturn(false);

        TracerWrapper tracerWrapper = new TracerWrapper(tracer, traceEventsService);
        SpanScope scope = tracerWrapper.startSpan("test_span", Collections.emptyMap());

        scope.close();

        verifyNoInteractions(traceEventListener1);
        verifyNoInteractions(traceEventListener2);
    }

    @Test
    public void testUnwrap() {
        TracerWrapper tracerWrapper = new TracerWrapper(tracer, traceEventsService);
        Tracer unwrappedTracer = tracerWrapper.unwrap();
        assertSame(tracer, unwrappedTracer);
    }
}
