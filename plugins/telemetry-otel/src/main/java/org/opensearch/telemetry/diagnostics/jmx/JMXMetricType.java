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

import java.util.function.BiFunction;

/**
 * Enum representing different types of JMX metrics and their corresponding value functions.
 * Each metric type has a name and a value function that calculates the metric's value
 * using the provided {@link ThreadMXBean} and {@link Thread}.
 */
@SuppressForbidden(reason = "java.lang.management.ThreadMXBean#getThreadAllocatedBytes() not supported")
public enum JMXMetricType {

    /**
     * CPU time used by a thread.
     */
    CPU_TIME("cpu_time", (threadMXBean, t) -> threadMXBean.getThreadCpuTime(t.getId())),

    /**
     * Number of bytes allocated by a thread.
     */
    ALLOCATED_BYTES("allocated_bytes", (threadMXBean, t) -> threadMXBean.getThreadAllocatedBytes(t.getId())),

    /**
     * Number of times a thread has been blocked.
     */
    BLOCKED_COUNT("blocked_count", (threadMXBean, t) -> threadMXBean.getThreadInfo(t.getId()).getBlockedCount()),

    /**
     * Amount of time a thread has spent blocked.
     */
    BLOCKED_TIME("blocked_time", (threadMXBean, t) -> threadMXBean.getThreadInfo(t.getId()).getBlockedTime()),

    /**
     * Number of times a thread has waited.
     */
    WAITED_COUNT("waited_count", (threadMXBean, t) -> threadMXBean.getThreadInfo(t.getId()).getWaitedCount()),

    /**
     * Amount of time a thread has spent waiting.
     */
    WAITED_TIME("waited_time", (threadMXBean, t) -> threadMXBean.getThreadInfo(t.getId()).getWaitedTime());

    private final String name;
    private final BiFunction<ThreadMXBean, Thread, Long> valueFunction;

    JMXMetricType(String name, BiFunction<ThreadMXBean, Thread, Long> valueFunction) {
        this.name = name;
        this.valueFunction = valueFunction;
    }

    /**
     * Get the name of the JMX metric type.
     *
     * @return the name of the metric type
     */
    public String getName() {
        return name;
    }

    /**
     * Get the value of the JMX metric for the given thread using the provided {@link ThreadMXBean}.
     *
     * @param threadMXBean the ThreadMXBean used to calculate the metric value
     * @param t the Thread for which the metric value is calculated
     * @return the calculated value of the metric
     */
    public long getValue(ThreadMXBean threadMXBean, Thread t) {
        return valueFunction.apply(threadMXBean, t);
    }
}
