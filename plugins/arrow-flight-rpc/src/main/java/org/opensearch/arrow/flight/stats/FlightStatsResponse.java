/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.stats;

import org.opensearch.action.FailedNodeException;
import org.opensearch.action.support.nodes.BaseNodesResponse;
import org.opensearch.cluster.ClusterName;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.search.aggregations.bucket.terms.AggregatorProfiler;

import java.io.IOException;
import java.util.List;

class FlightStatsResponse extends BaseNodesResponse<FlightNodeStats> implements ToXContentObject {

    public FlightStatsResponse(StreamInput in) throws IOException {
        super(in);
    }

    public FlightStatsResponse(ClusterName clusterName, List<FlightNodeStats> nodes, List<FailedNodeException> failures) {
        super(clusterName, nodes, failures);
    }

    @Override
    protected List<FlightNodeStats> readNodesFrom(StreamInput in) throws IOException {
        return in.readList(FlightNodeStats::new);
    }

    @Override
    protected void writeNodesTo(StreamOutput out, List<FlightNodeStats> nodes) throws IOException {
        out.writeList(nodes);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("cluster_name", getClusterName().value());

        builder.startObject("nodes");
        for (FlightNodeStats nodeStats : getNodes()) {
            builder.startObject(nodeStats.getNode().getId());
            builder.field("name", nodeStats.getNode().getName());
            builder.field(
                "streamAddress",
                nodeStats.getNode().getStreamAddress() != null
                    ? nodeStats.getNode().getStreamAddress().toString()
                    : nodeStats.getNode().getAddress().toString()
            );
            nodeStats.getMetrics().toXContent(builder, params);
            builder.startObject("aggregator_profiling");
            addNodeAggregatorProfilingStats(builder, params, nodeStats.getAggregatorStats());
            builder.endObject();
            builder.endObject();
        }
        builder.endObject();

        builder.startObject("cluster_stats");
        aggregateClusterStats(builder, params);
        builder.endObject();

        builder.startObject("aggregator_profiling");
        aggregateClusterAggregatorStats(builder, params);
        builder.endObject();

        builder.endObject();
        return builder;
    }

    private void aggregateClusterStats(XContentBuilder builder, Params params) throws IOException {
        long totalClientCallsStarted = 0;
        long totalClientCallsCompleted = 0;
        long totalClientCallDuration = 0;
        long totalClientRequestBytes = 0;

        long totalClientBatchesRequested = 0;
        long totalClientBatchesReceived = 0;
        long totalClientBatchReceivedBytes = 0;
        long totalClientBatchProcessingTime = 0;

        long totalServerCallsStarted = 0;
        long totalServerCallsCompleted = 0;
        long totalServerCallDuration = 0;
        long totalServerRequestBytes = 0;

        long totalServerBatchesSent = 0;
        long totalServerBatchSentBytes = 0;
        long totalServerBatchProcessingTime = 0;

        for (FlightNodeStats nodeStats : getNodes()) {
            FlightMetrics metrics = nodeStats.getMetrics();

            FlightMetrics.ClientCallMetrics clientCallMetrics = metrics.getClientCallMetrics();
            totalClientCallsStarted += clientCallMetrics.getStarted();
            totalClientCallsCompleted += clientCallMetrics.getCompleted();
            totalClientCallDuration += clientCallMetrics.getDuration().getSum();
            totalClientRequestBytes += clientCallMetrics.getRequestBytes().getSum();

            FlightMetrics.ClientBatchMetrics clientBatchMetrics = metrics.getClientBatchMetrics();
            totalClientBatchesRequested += clientBatchMetrics.getBatchesRequested();
            totalClientBatchesReceived += clientBatchMetrics.getBatchesReceived();
            totalClientBatchReceivedBytes += clientBatchMetrics.getReceivedBytes().getSum();
            totalClientBatchProcessingTime += clientBatchMetrics.getProcessingTime().getSum();

            FlightMetrics.ServerCallMetrics serverCallMetrics = metrics.getServerCallMetrics();
            totalServerCallsStarted += serverCallMetrics.getStarted();
            totalServerCallsCompleted += serverCallMetrics.getCompleted();
            totalServerCallDuration += serverCallMetrics.getDuration().getSum();
            totalServerRequestBytes += serverCallMetrics.getRequestBytes().getSum();

            FlightMetrics.ServerBatchMetrics serverBatchMetrics = metrics.getServerBatchMetrics();
            totalServerBatchesSent += serverBatchMetrics.getBatchesSent();
            totalServerBatchSentBytes += serverBatchMetrics.getSentBytes().getSum();
            totalServerBatchProcessingTime += serverBatchMetrics.getProcessingTime().getSum();
        }

        builder.startObject("client");

        builder.startObject("calls");
        builder.field("started", totalClientCallsStarted);
        builder.field("completed", totalClientCallsCompleted);
        builder.humanReadableField(
            "duration_nanos",
            "duration",
            new TimeValue(totalClientCallDuration, java.util.concurrent.TimeUnit.NANOSECONDS)
        );
        if (totalClientCallsCompleted > 0) {
            long avgDurationNanos = totalClientCallDuration / totalClientCallsCompleted;
            builder.humanReadableField(
                "avg_duration_nanos",
                "avg_duration",
                new TimeValue(avgDurationNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
            );
        }
        builder.humanReadableField("request_bytes", "request", new ByteSizeValue(totalClientRequestBytes));
        builder.humanReadableField("response_bytes", "response", new ByteSizeValue(totalClientBatchReceivedBytes));
        builder.endObject();

        builder.startObject("batches");
        builder.field("requested", totalClientBatchesRequested);
        builder.field("received", totalClientBatchesReceived);
        builder.humanReadableField("received_bytes", "received_size", new ByteSizeValue(totalClientBatchReceivedBytes));
        if (totalClientBatchesReceived > 0) {
            long avgProcessingTimeNanos = totalClientBatchProcessingTime / totalClientBatchesReceived;
            builder.humanReadableField(
                "avg_processing_time_nanos",
                "avg_processing_time",
                new TimeValue(avgProcessingTimeNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
            );
        }
        builder.endObject();

        builder.endObject();

        builder.startObject("server");

        builder.startObject("calls");
        builder.field("started", totalServerCallsStarted);
        builder.field("completed", totalServerCallsCompleted);
        builder.humanReadableField(
            "duration_nanos",
            "duration",
            new TimeValue(totalServerCallDuration, java.util.concurrent.TimeUnit.NANOSECONDS)
        );
        if (totalServerCallsCompleted > 0) {
            long avgDurationNanos = totalServerCallDuration / totalServerCallsCompleted;
            builder.humanReadableField(
                "avg_duration_nanos",
                "avg_duration",
                new TimeValue(avgDurationNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
            );
        }
        builder.humanReadableField("request_bytes", "request", new ByteSizeValue(totalServerRequestBytes));
        builder.humanReadableField("response_bytes", "response", new ByteSizeValue(totalServerBatchSentBytes));
        builder.endObject();

        builder.startObject("batches");
        builder.field("sent", totalServerBatchesSent);
        builder.humanReadableField("sent_bytes", "sent_size", new ByteSizeValue(totalServerBatchSentBytes));
        if (totalServerBatchesSent > 0) {
            long avgProcessingTimeNanos = totalServerBatchProcessingTime / totalServerBatchesSent;
            builder.humanReadableField(
                "avg_processing_time_nanos",
                "avg_processing_time",
                new TimeValue(avgProcessingTimeNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
            );
        }
        builder.endObject();

        builder.endObject();
    }

    private void addNodeAggregatorProfilingStats(XContentBuilder builder, Params params, AggregatorProfiler.ProfileResult result)
        throws IOException {
        if (result == null) return;

        builder.field("segments_processed", result.segmentCount);
        builder.field("total_documents", result.totalDocuments);
        builder.field("bigarrays_memory_bytes", result.bigArraysMemoryUsage);
        builder.startObject("operation_timings_ns");
        result.operationTimes.forEach((op, time) -> {
            try {
                builder.field(op, time);
            } catch (IOException e) {}
        });
        builder.endObject();
        builder.startObject("operation_counts");
        result.operationCounts.forEach((op, count) -> {
            try {
                builder.field(op, count);
            } catch (IOException e) {}
        });
        builder.endObject();
        builder.startObject("operation_percentiles_ns");
        result.operationPercentiles.forEach((op, stats) -> {
            try {
                builder.startObject(op);
                builder.field("p50", stats.p50);
                builder.field("p95", stats.p95);
                builder.field("p99", stats.p99);
                builder.field("min", stats.min);
                builder.field("max", stats.max);
                builder.endObject();
            } catch (IOException e) {}
        });
        builder.endObject();
    }

    private void aggregateClusterAggregatorStats(XContentBuilder builder, Params params) throws IOException {
        long totalSegmentsProcessed = 0;
        long totalDocuments = 0;
        long maxBigArraysMemory = 0;

        java.util.Map<String, Long> totalOperationTimes = new java.util.HashMap<>();
        java.util.Map<String, Long> totalOperationCounts = new java.util.HashMap<>();
        java.util.Map<String, AggregatorProfiler.PercentileStats> maxPercentiles = new java.util.HashMap<>();

        for (FlightNodeStats nodeStats : getNodes()) {
            AggregatorProfiler.ProfileResult result = nodeStats.getAggregatorStats();
            if (result == null) continue;

            totalSegmentsProcessed += result.segmentCount;
            totalDocuments += result.totalDocuments;
            maxBigArraysMemory = Math.max(maxBigArraysMemory, result.bigArraysMemoryUsage);

            result.operationTimes.forEach((op, time) -> totalOperationTimes.merge(op, time, Long::sum));
            result.operationCounts.forEach((op, count) -> totalOperationCounts.merge(op, count, Long::sum));
            if (result.operationPercentiles != null) {
                result.operationPercentiles.forEach((op, stats) -> {
                    maxPercentiles.merge(
                        op,
                        stats,
                        (existing, newStats) -> new AggregatorProfiler.PercentileStats(
                            Math.max(existing.p50, newStats.p50),
                            Math.max(existing.p95, newStats.p95),
                            Math.max(existing.p99, newStats.p99),
                            Math.min(existing.min, newStats.min),
                            Math.max(existing.max, newStats.max)
                        )
                    );
                });
            }
        }
        builder.field("total_segments_processed", totalSegmentsProcessed);
        builder.field("total_documents", totalDocuments);
        builder.field("max_bigarrays_memory_bytes", maxBigArraysMemory);

        builder.startObject("total_operation_timings_ns");
        totalOperationTimes.forEach((op, time) -> {
            try {
                builder.field(op, time);
            } catch (IOException e) {}
        });
        builder.endObject();

        builder.startObject("total_operation_counts");
        totalOperationCounts.forEach((op, count) -> {
            try {
                builder.field(op, count);
            } catch (IOException e) {}
        });
        builder.endObject();

        builder.startObject("max_operation_percentiles_ns");
        maxPercentiles.forEach((op, stats) -> {
            try {
                builder.startObject(op);
                builder.field("p50", stats.p50);
                builder.field("p95", stats.p95);
                builder.field("p99", stats.p99);
                builder.field("min", stats.min);
                builder.field("max", stats.max);
                builder.endObject();
            } catch (IOException e) {}
        });
        builder.endObject();
    }
}
