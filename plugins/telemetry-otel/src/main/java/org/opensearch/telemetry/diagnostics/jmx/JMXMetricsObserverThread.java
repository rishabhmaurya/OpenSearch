/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.diagnostics.jmx;

import com.sun.management.ThreadMXBean;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.telemetry.diagnostics.ThreadResourceObserver;
import org.opensearch.telemetry.metrics.Measurement;
import org.opensearch.telemetry.metrics.MetricPoint;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of {@link ThreadResourceObserver} that observes various JMX metrics for a given thread.
 * It collects metrics such as CPU time, allocated bytes, blocked count, blocked time, waited count, and waited time
 * for the specified thread using {@link ThreadMXBean}.
 */
@SuppressForbidden(reason = "java.lang.management.ThreadMXBean#getThreadAllocatedBytes() not supported")
public class JMXMetricsObserverThread implements ThreadResourceObserver {
    private static final ThreadMXBean threadMXBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /**
     * Default constructor
     */
    public JMXMetricsObserverThread() {

    }

    /**
     * Observes JMX metrics for the given thread and creates a {@link MetricPoint} containing the measured values.
     *
     * @param t The thread for which to observe metrics.
     * @return A {@link MetricPoint} containing the observed JMX metrics for the specified thread.
     */
    @Override
    public MetricPoint observe(Thread t) {
        long measurementTime = System.currentTimeMillis();
        Map<String, Measurement<Number>> measurements = new HashMap<>();
        for (JMXMetricType measurement : JMXMetricType.values()) {
            measurements.put(measurement.getName(), new Measurement<>(measurement.getName(), measurement.getValue(threadMXBean, t)));
        }
        return new MetricPoint(measurements, null, measurementTime);
    }
}
