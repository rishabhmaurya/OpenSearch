/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.diagnostics.jmx;

import org.opensearch.telemetry.diagnostics.ThreadResourceRecorder;
import org.opensearch.telemetry.metrics.Measurement;
import org.opensearch.telemetry.metrics.MetricPoint;

import java.util.HashMap;
import java.util.Map;

/**
 * A subclass of ThreadResourceRecorder that records thread resource metrics using a JMXMetricsObserverThread.
 * It computes the difference between two MetricPoints and returns a new MetricPoint containing the differences
 * in the measurements.
 *
 */
public class JMXThreadResourceRecorder extends ThreadResourceRecorder<JMXMetricsObserverThread> {

    /**
     * Constructs a JMXThreadResourceRecorder with the provided JMXMetricsObserverThread instance.
     *
     * @param observer The JMXMetricsObserverThread used for observing thread resource metrics.
     */
    public JMXThreadResourceRecorder(JMXMetricsObserverThread observer) {
        super(observer);
    }

    /**
     * Computes the difference between two MetricPoints and returns a new MetricPoint containing
     * the differences in the measurements.
     *
     * @param startMetric The MetricPoint gauge observed at start
     * @param endMetric   The MetricPoint gauge observed at the end
     * @return A MetricPoint containing the differences in the measurements between startMetric and endMetric.
     */
    @Override
    protected MetricPoint computeDiff(MetricPoint startMetric, MetricPoint endMetric) {
        Map<String, Measurement<Number>> measurements = new HashMap<>();
        for (String measurementName : endMetric.getMeasurements().keySet()) {
            long startValue = startMetric.getMeasurement(measurementName).getValue().longValue();
            long endValue = endMetric.getMeasurement(measurementName).getValue().longValue();
            measurements.put(measurementName, new Measurement<>(measurementName, endValue - startValue));
        }
        return new MetricPoint(measurements, null, endMetric.getObservationTime());
    }
}
