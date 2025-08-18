/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.stats;

import org.opensearch.action.support.nodes.BaseNodeResponse;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.search.aggregations.bucket.terms.AggregatorProfiler;

import java.io.IOException;

/**
 * Flight transport statistics for a single node
 */
class FlightNodeStats extends BaseNodeResponse {

    private final FlightMetrics metrics;
    private final AggregatorProfiler.ProfileResult aggregatorStats;

    public FlightNodeStats(StreamInput in) throws IOException {
        super(in);
        this.metrics = new FlightMetrics(in);
        this.aggregatorStats = in.readOptionalWriteable(AggregatorProfiler.ProfileResult::new);
    }

    public FlightNodeStats(DiscoveryNode node, FlightMetrics metrics, AggregatorProfiler.ProfileResult aggregatorStats) {
        super(node);
        this.metrics = metrics;
        this.aggregatorStats = aggregatorStats;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        metrics.writeTo(out);
        out.writeOptionalWriteable(aggregatorStats);
    }

    public FlightMetrics getMetrics() {
        return metrics;
    }

    public AggregatorProfiler.ProfileResult getAggregatorStats() {
        return aggregatorStats;
    }
}
