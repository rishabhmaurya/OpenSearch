/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.diagnostics;

import org.mockito.ArgumentCaptor;
import org.opensearch.telemetry.metrics.Measurement;
import org.opensearch.telemetry.metrics.MetricEmitter;
import org.opensearch.telemetry.metrics.MetricPoint;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.test.OpenSearchTestCase;

import org.junit.Test;

import java.util.Collections;

import static org.mockito.Mockito.*;

import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.opensearch.telemetry.diagnostics.DiagnosticsEventListener.ELAPSED_TIME;

public class DiagnosticsEventListenerTests extends OpenSearchTestCase {

    private ThreadResourceRecorder<?> threadResourceRecorder;
    private MetricEmitter metricEmitter;
    private Span span;
    private DiagnosticSpan diagnosticSpan;
    private DiagnosticsEventListener diagnosticsEventListener;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadResourceRecorder = Mockito.mock(ThreadResourceRecorder.class);
        metricEmitter = Mockito.mock(MetricEmitter.class);
        span = Mockito.mock(Span.class);
        diagnosticSpan = Mockito.mock(DiagnosticSpan.class);
        diagnosticsEventListener = new DiagnosticsEventListener(threadResourceRecorder, metricEmitter);
    }

    @Test
    public void testOnSpanStart() {
        Thread t = Thread.currentThread();
        diagnosticsEventListener.onSpanStart(diagnosticSpan, t);
        // Verify expected interactions
        verify(threadResourceRecorder).startRecording(eq(diagnosticSpan), eq(t), eq(true));
        verify(diagnosticSpan).putMetric(eq(DiagnosticsEventListener.START_SPAN_TIME), any(MetricPoint.class));
    }

    @Test
    public void testOnSpanComplete() {
        Thread t = Thread.currentThread();
        MetricPoint diffMetric = new MetricPoint(Collections.emptyMap(), null, System.currentTimeMillis());
        MetricPoint startMetric = new MetricPoint(Collections.emptyMap(), null, System.currentTimeMillis());
        when(threadResourceRecorder.endRecording(any(DiagnosticSpan.class), eq(t), eq(true))).thenReturn(diffMetric);
        when(diagnosticSpan.removeMetric(anyString())).thenReturn(startMetric);
        ArgumentCaptor<MetricPoint> metricCaptor = ArgumentCaptor.forClass(MetricPoint.class);
        diagnosticsEventListener.onSpanComplete(diagnosticSpan, t);
        verify(metricEmitter).emitMetric(metricCaptor.capture());

        // Check if diffMetric contains "elapsed_time" measurement
        MetricPoint emittedMetric = metricCaptor.getValue();
        Measurement<Number> elapsedTimeMeasurement = emittedMetric.getMeasurement(ELAPSED_TIME);
        assertNotNull(elapsedTimeMeasurement);
    }

    @Test
    public void testOnRunnableStart() {
        Thread t = Thread.currentThread();
        diagnosticsEventListener.onRunnableStart(diagnosticSpan, t);
        verify(threadResourceRecorder).startRecording(eq(diagnosticSpan), eq(t), eq(false));
    }

    @Test
    public void testOnRunnableComplete() {
        Thread t = Thread.currentThread();
        MetricPoint diffMetric = new MetricPoint(Collections.emptyMap(), null, System.currentTimeMillis());
        when(threadResourceRecorder.endRecording(any(DiagnosticSpan.class), eq(t), eq(false))).thenReturn(diffMetric);

        diagnosticsEventListener.onRunnableComplete(diagnosticSpan, t);

        verify(metricEmitter).emitMetric(eq(diffMetric));
    }

    @Test
    public void testIsEnabled() {
        boolean isEnabled = diagnosticsEventListener.isEnabled(diagnosticSpan);
        assertTrue(isEnabled);

        isEnabled = diagnosticsEventListener.isEnabled(span);
        assertFalse(isEnabled);
    }
}
