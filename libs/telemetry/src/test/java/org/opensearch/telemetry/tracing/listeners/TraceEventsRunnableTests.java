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
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.test.OpenSearchTestCase;

import static org.mockito.Mockito.*;

public class TraceEventsRunnableTests extends OpenSearchTestCase {

    private Tracer tracer;
    private TraceEventsService traceEventsService;
    private TraceEventListener traceEventListener1;
    private TraceEventListener traceEventListener2;
    private Span span;
    private Thread currentThread;
    private Runnable delegate;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        tracer = mock(Tracer.class);
        traceEventsService = spy(new TraceEventsService()); // Use spy here
        traceEventListener1 = mock(TraceEventListener.class);
        traceEventListener2 = mock(TraceEventListener.class);
        span = mock(Span.class);
        currentThread = mock(Thread.class);
        delegate = mock(Runnable.class);

        when(traceEventsService.getTracer()).thenReturn(tracer);
        when(tracer.getCurrentSpan()).thenReturn(span);
        when(span.getParentSpan()).thenReturn(null);

        traceEventsService.registerTraceEventListener("listener1", traceEventListener1);
        traceEventsService.registerTraceEventListener("listener2", traceEventListener2);
        when(traceEventListener1.isEnabled(any(Span.class))).thenReturn(true);
        when(traceEventListener2.isEnabled(any(Span.class))).thenReturn(true);

        traceEventsService.setTracingEnabled(true);
    }

    @Test
    public void testRun_InvokeOnRunnableStartAndOnRunnableComplete() {
        Span span1 = mock(Span.class);
        Span span2 = mock(Span.class);
        when(traceEventsService.getTracer().getCurrentSpan()).thenReturn(span1, span1);
        when(span1.hasEnded()).thenReturn(false);
        when(span2.hasEnded()).thenReturn(false);
        when(span1.getParentSpan()).thenReturn(span2);
        when(span2.getParentSpan()).thenReturn(null);

        TraceEventsRunnable traceEventsRunnable = new TraceEventsRunnable(delegate, traceEventsService);

        traceEventsRunnable.run();

        verify(traceEventListener1, times(2)).onRunnableStart(any(Span.class), any(Thread.class));
        verify(traceEventListener2, times(2)).onRunnableStart(any(Span.class), any(Thread.class));
        verify(traceEventListener1, times(2)).onRunnableComplete(any(Span.class), any(Thread.class));
        verify(traceEventListener2, times(2)).onRunnableComplete(any(Span.class), any(Thread.class));

        // Ensure that delegate.run() was invoked
        verify(delegate).run();
    }

    @Test
    public void testRun_TracingNotEnabled_NoInteractionsWithListeners() {
        when(traceEventsService.isTracingEnabled()).thenReturn(false);

        TraceEventsRunnable traceEventsRunnable = new TraceEventsRunnable(delegate, traceEventsService);

        traceEventsRunnable.run();

        // Verify that no interactions with listeners occurred
        verifyNoInteractions(traceEventListener1);
        verifyNoInteractions(traceEventListener2);
    }

    @Test
    public void testRun_ExceptionInOnRunnableStart_NoImpactOnExecution() {
        doThrow(new RuntimeException("Listener 1 exception")).when(traceEventListener1).onRunnableStart(eq(span), eq(currentThread));
        TraceEventsRunnable traceEventsRunnable = new TraceEventsRunnable(delegate, traceEventsService);
        traceEventsRunnable.run();

        // Ensure that delegate.run() was invoked
        verify(delegate).run();
    }

    @Test
    public void testRun_ExceptionInOnRunnableComplete_NoImpactOnExecution() {
        // trace event listener to throw an exception in onRunnableComplete
        doThrow(new RuntimeException("Listener 1 exception")).when(traceEventListener1).onRunnableComplete(eq(span), eq(currentThread));
        TraceEventsRunnable traceEventsRunnable = new TraceEventsRunnable(delegate, traceEventsService);
        traceEventsRunnable.run();

        // Verify that onRunnableStart was called for the listener despite the exception
        verify(traceEventListener1).onRunnableStart(any(Span.class), any(Thread.class));
        verify(delegate).run();
    }

    @Test
    public void testUnwrap() {
        TraceEventsRunnable traceEventsRunnable = new TraceEventsRunnable(delegate, traceEventsService);

        Runnable unwrappedRunnable = traceEventsRunnable.unwrap();
        assertSame(delegate, unwrappedRunnable);
    }
}
