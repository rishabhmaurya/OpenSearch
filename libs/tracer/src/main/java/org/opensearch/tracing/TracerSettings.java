/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.tracing;

import org.opensearch.common.unit.TimeValue;

public interface TracerSettings {

    void setTracerLevel(Level tracerLevel);

    void setExporterBatchSize(int exporterBatchSize);

    void setExporterMaxQueueSize(int exporterMaxQueueSize);

    void setExporterDelay(TimeValue exporterDelay);
    Level getTracerLevel();

    int getExporterBatchSize();

    int getExporterMaxQueueSize();

    TimeValue getExporterDelay();
}
