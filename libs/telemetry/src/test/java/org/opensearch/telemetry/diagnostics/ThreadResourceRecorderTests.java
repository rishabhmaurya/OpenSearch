/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.diagnostics;

import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import org.opensearch.telemetry.metrics.MetricPoint;
import org.opensearch.test.OpenSearchTestCase;

public class ThreadResourceRecorderTests extends OpenSearchTestCase {

    private ThreadResourceObserver observer;
    private DiagnosticSpan span;
    private Thread thread;

    private static class TestThreadResourceRecorder extends ThreadResourceRecorder<ThreadResourceObserver> {
        public TestThreadResourceRecorder(ThreadResourceObserver observer) {
            super(observer);
        }

        @Override
        protected MetricPoint computeDiff(MetricPoint startMetric, MetricPoint endMetric) {
            // We simply return a new MetricPoint object with the same values as endMetric
            return new MetricPoint(endMetric.getMeasurements(), null, endMetric.getObservationTime());
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();

        observer = mock(ThreadResourceObserver.class);
        span = mock(DiagnosticSpan.class);
        thread = mock(Thread.class);
    }

    @Test
    public void testStartRecording() {
        MetricPoint observation = new MetricPoint(Collections.emptyMap(), null, System.currentTimeMillis());
        when(observer.observe(thread)).thenReturn(observation);

        ThreadResourceRecorder<ThreadResourceObserver> recorder = new TestThreadResourceRecorder(observer);

        recorder.startRecording(span, thread, true);

        verify(span).putMetric(eq(String.valueOf(thread.getId())), eq(observation));
    }

    @Test
    public void testEndRecording() {
        MetricPoint startMetric = new MetricPoint(Collections.emptyMap(), null, System.currentTimeMillis());
        MetricPoint endMetric = new MetricPoint(Collections.emptyMap(), null, System.currentTimeMillis() + 1000);
        when(observer.observe(thread)).thenReturn(endMetric);

        ThreadResourceRecorder<ThreadResourceObserver> recorder = new TestThreadResourceRecorder(observer);

        when(span.removeMetric(String.valueOf(thread.getId()))).thenReturn(startMetric);

        MetricPoint diffMetric = recorder.endRecording(span, thread, true);

        assertEquals(endMetric.getObservationTime(), diffMetric.getObservationTime());
    }
}
