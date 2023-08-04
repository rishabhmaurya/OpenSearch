/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.diagnostics.jmx;

import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import org.opensearch.test.OpenSearchTestCase;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.common.Attributes;
import org.opensearch.telemetry.metrics.Measurement;
import org.opensearch.telemetry.metrics.MetricPoint;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.opensearch.telemetry.diagnostics.DiagnosticsEventListener.ELAPSED_TIME;

public class JMXOTelMetricEmitterTests extends OpenSearchTestCase {

    private OpenTelemetry openTelemetry;
    private Meter meter;
    private JMXOTelMetricEmitter metricEmitter;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        openTelemetry = mock(OpenTelemetry.class);
        meter = mock(Meter.class);
        when(openTelemetry.getMeter(any())).thenReturn(meter);
        for (JMXMetricType metricType : JMXMetricType.values()) {
            when(meter.histogramBuilder(metricType.getName())).thenReturn(mock(DoubleHistogramBuilder.class));
            when(meter.histogramBuilder(metricType.getName()).ofLongs()).thenReturn(mock(LongHistogramBuilder.class));
            when(meter.histogramBuilder(metricType.getName()).ofLongs().build()).thenReturn(mock(LongHistogram.class));
        }

        when(meter.histogramBuilder(ELAPSED_TIME)).thenReturn(mock(DoubleHistogramBuilder.class));
        when(meter.histogramBuilder(ELAPSED_TIME).ofLongs()).thenReturn(mock(LongHistogramBuilder.class));
        when(meter.histogramBuilder(ELAPSED_TIME).ofLongs().build()).thenReturn(mock(LongHistogram.class));

        metricEmitter = JMXOTelMetricEmitter.getInstance(openTelemetry);
    }

    public void testEmitMetric() {
        Map<String, Measurement<Number>> measurements = new HashMap<>();
        for (JMXMetricType metricType : JMXMetricType.values()) {
            measurements.put(metricType.getName(), new Measurement<>(metricType.getName(), 100L));
        }

        MetricPoint metricPoint = new MetricPoint(measurements, null, System.currentTimeMillis());

        metricEmitter.emitMetric(metricPoint);

        for (JMXMetricType metricType : JMXMetricType.values()) {
            LongHistogram histogram = JMXOTelMetricEmitter.histograms.get(metricType.getName());
            verify(histogram).record(eq(100L), any(Attributes.class));
            verify(histogram).record(eq(0L), any(Attributes.class));
        }
    }
}
