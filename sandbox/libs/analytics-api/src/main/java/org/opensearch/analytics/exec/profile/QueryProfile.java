/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec.profile;

import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

/**
 * Query-level profile snapshot built from an execution graph plus the per-query
 * {@code TaskTracker}. Safe to emit on both success and failure paths — every field
 * is a plain value captured at snapshot time, not a live handle into the walker.
 *
 * @param queryId        per-query id from {@code QueryDAG.queryId()}
 * @param fullPlan       the CBO-output Calcite plan rendered as an array of lines,
 *                       captured before the DAG builder cut it at exchange boundaries;
 *                       one element per indent level of the tree. Empty list if not supplied.
 * @param planningTimeMs wall-clock time spent in the coordinator planning pipeline (PlannerImpl through FragmentConversionDriver)
 * @param executionTimeMs wall-clock span from the earliest stage start to the latest stage end (0 if nothing ran)
 * @param stages         per-stage profiles in DAG iteration order (root stage appears at whatever index the walker stored it)
 * @param peakArrowBytes high-water of the per-query coordinator Arrow allocator (JVM off-heap; inbound shard
 *                       batches + reduce-export staging; 0 when analytics.coordinator.buffer_limit &lt;= 0). (M1)
 * @param peakNativeBytes coordinator-reduce DataFusion (jemalloc) pool peak for this query, in bytes; 0 if not measured. (M1)
 *                        Disjoint from {@code peakArrowBytes} — never sum the two.
 */
public record QueryProfile(
    String queryId,
    List<String> fullPlan,
    long planningTimeMs,
    long executionTimeMs,
    List<StageProfile> stages,
    long peakArrowBytes,
    long peakNativeBytes
) implements ToXContentObject {

    /** Back-compat constructor (no memory numbers). */
    public QueryProfile(String queryId, List<String> fullPlan, long planningTimeMs, long executionTimeMs, List<StageProfile> stages) {
        this(queryId, fullPlan, planningTimeMs, executionTimeMs, stages, 0L, 0L);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("query_id", queryId);
        if (fullPlan != null && fullPlan.isEmpty() == false) {
            builder.startArray("full_plan");
            for (String line : fullPlan)
                builder.value(line);
            builder.endArray();
        }
        builder.field("planning_time_ms", planningTimeMs);
        builder.field("execution_time_ms", executionTimeMs);
        builder.startObject("memory");
        builder.field("peak_arrow_bytes", peakArrowBytes);
        builder.field("peak_native_bytes", peakNativeBytes);
        builder.endObject();
        builder.startArray("stages");
        for (StageProfile s : stages) {
            s.toXContent(builder, params);
        }
        builder.endArray();
        builder.endObject();
        return builder;
    }
}
