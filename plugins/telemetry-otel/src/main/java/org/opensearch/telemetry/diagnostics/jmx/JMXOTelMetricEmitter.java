/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.diagnostics.jmx;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import org.opensearch.telemetry.metrics.Measurement;
import org.opensearch.telemetry.metrics.MetricPoint;
import org.opensearch.telemetry.metrics.MetricEmitter;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;

import static org.opensearch.telemetry.diagnostics.DiagnosticsEventListener.ELAPSED_TIME;

/**
 * JMXOTelMetricEmitter is a MetricEmitter implementation that emits metrics using OpenTelemetry.
 * It creates histograms for each {@link JMXMetricType} and records the metrics using the provided OpenTelemetry.
 * The emitted metrics include the measurements received from the MetricPoint along with their attributes.
 */
public class JMXOTelMetricEmitter implements MetricEmitter {

    private static JMXOTelMetricEmitter INSTANCE;
    static final Map<String, LongHistogram> histograms = new HashMap<>();

    private static Meter meter;

    /**
     * Private constructor for JMXOTelMetricEmitter.
     * It initializes the histograms for each JMX metric type using the provided OpenTelemetry.
     *
     * @param telemetry the OpenTelemetry instance to use for creating histograms
     */
    private JMXOTelMetricEmitter(OpenTelemetry telemetry) {
        JMXOTelMetricEmitter.meter = telemetry.getMeter(JMXOTelMetricEmitter.class.getName());

        for (JMXMetricType metricType : JMXMetricType.values()) {
            LongHistogram histogram = AccessController.doPrivileged(
                (PrivilegedAction<LongHistogram>) () -> meter.histogramBuilder(metricType.getName()).ofLongs().build()
            );
            histograms.put(metricType.getName(), histogram);
        }

        histograms.put(ELAPSED_TIME, meter.histogramBuilder(ELAPSED_TIME).ofLongs().build());
    }

    /**
     * Get the singleton instance of JMXOTelMetricEmitter.
     * If the instance does not exist, it creates a new one using the provided OpenTelemetry.
     *
     * @param telemetry the OpenTelemetry instance to use for creating the singleton instance
     * @return the singleton instance of JMXOTelMetricEmitter
     */
    synchronized public static JMXOTelMetricEmitter getInstance(OpenTelemetry telemetry) {
        if (INSTANCE == null) {
            INSTANCE = new JMXOTelMetricEmitter(telemetry);
        }
        return INSTANCE;
    }

    /**
     * Emit the given MetricPoint using OpenTelemetry.
     * The method creates attributes for the metrics from the MetricPoint and records them to the corresponding histograms.
     *
     * @param metric the MetricPoint to emit
     */
    @Override
    public void emitMetric(MetricPoint metric) {
        AttributesBuilder attributesBuilder = Attributes.builder();
        if (metric.getAttributes() != null) {
            metric.getAttributes().forEach((k, v) -> attributesBuilder.put(k, String.valueOf(v)));
        }
        Attributes oTelAttributes = attributesBuilder.build();

        for (String measurementName : metric.getMeasurements().keySet()) {
            recordToHistogram(metric.getMeasurement(measurementName), oTelAttributes);
        }
    }

    /**
     * Records the given measurement to the corresponding histogram using OpenTelemetry.
     *
     * @param measurement the Measurement to record
     * @param oTelAttributes the OpenTelemetry Attributes to associate with the recorded value
     */
    private void recordToHistogram(Measurement<Number> measurement, Attributes oTelAttributes) {
        LongHistogram histogram = JMXOTelMetricEmitter.histograms.get(measurement.getName());
        // assuming all measurements are of long type
        long value = measurement.getValue().longValue();
        histogram.record(value, oTelAttributes);
        // recording 0 value right after as it's the delta which we are emitting and not a gauge
        histogram.record(0, oTelAttributes);
    }
}
