/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import org.opensearch.analytics.backend.EngineResultBatch;
import org.opensearch.analytics.backend.EngineResultStream;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanContext;
import org.opensearch.telemetry.tracing.SpanCreationContext;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.telemetry.tracing.attributes.Attributes;
import org.opensearch.telemetry.tracing.noop.NoopTracer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Self-contained data-node tracing for a single shard fragment's result stream (T2). Keeping the
 * per-batch instrumentation here — rather than inline in {@code AnalyticsSearchService} — confines
 * the tracing logic to one new file so the hot drain loop in the service stays a thin call. The
 * fragment timeline is tiled with contiguous child spans whose durations add up:
 * <ul>
 *   <li>{@code datafusion.batch.produce} — the native {@code streamNext} pull (scan/filter/aggregate)
 *       that materializes one batch; this is where the real per-batch time lives.</li>
 *   <li>{@code datafusion.batch.send} — pushing that batch to the stream transport.</li>
 * </ul>
 * Per-batch spans are capped at {@link AnalyticsTracing#DEFAULT_MAX_BATCH_SPANS} per fragment; past
 * the cap the loop keeps draining and counting but stops opening spans. All span work is gated on a
 * real tracer ({@code tracingOn}) so the noop path pays nothing.
 *
 * @opensearch.internal
 */
final class FragmentTracingProbe {

    private FragmentTracingProbe() {}

    /** Outcome of draining one fragment stream: row + batch counts the caller tags onto the fragment span. */
    record DrainResult(long rowsProduced, long batchCount, long batchSpansEmitted) {}

    /**
     * Drains {@code stream}, pushing each batch to {@code responseHandler} and bracketing the native
     * produce and the transport send with child spans of {@code fragmentSpan}. Exceptions propagate
     * to the caller unchanged (the caller owns fragment-span error tagging + resource close), so the
     * produce/send {@code finally} blocks here only end their own spans.
     */
    static DrainResult drain(
        Tracer tracer,
        Span fragmentSpan,
        EngineResultStream stream,
        AnalyticsSearchService.StreamingFragmentResponseHandler responseHandler,
        boolean tracingOn
    ) throws Exception {
        Iterator<EngineResultBatch> it = stream.iterator();
        long rowsProduced = 0;
        long batchOrdinal = 0;
        long batchSpansEmitted = 0;
        while (true) {
            boolean cap = tracingOn && batchSpansEmitted < AnalyticsTracing.DEFAULT_MAX_BATCH_SPANS;
            // PRODUCE span brackets hasNext()+next() — the native pull that materializes the batch.
            Span produceSpan = cap ? AnalyticsTracing.startBatchSpan(tracer, fragmentSpan, AnalyticsTracing.SPAN_BATCH_PRODUCE, batchOrdinal) : null;
            long produceStartNanos = System.nanoTime();
            EngineResultBatch batch = null;
            boolean hasNext = false;
            try {
                hasNext = it.hasNext();
                batch = hasNext ? it.next() : null;
            } finally {
                if (produceSpan != null) {
                    produceSpan.addAttribute("batch.produce_nanos", System.nanoTime() - produceStartNanos);
                    if (hasNext && batch != null) {
                        produceSpan.addAttribute("batch.rows", (long) batch.getRowCount());
                        produceSpan.addAttribute("batch.arrow_bytes", arrowBatchBytes(batch));
                    }
                    long peakNow = stream.currentNativePeakBytes();
                    if (peakNow >= 0) {
                        produceSpan.addAttribute("mem.shard_native.peak_bytes_running", peakNow);
                    }
                    produceSpan.endSpan();
                }
            }
            if (hasNext == false) {
                break;
            }
            int batchRows = batch.getRowCount();
            rowsProduced += batchRows;
            // SEND span brackets the transport push only.
            Span sendSpan = cap ? AnalyticsTracing.startBatchSpan(tracer, fragmentSpan, AnalyticsTracing.SPAN_BATCH_SEND, batchOrdinal) : null;
            long sendStartNanos = System.nanoTime();
            try {
                responseHandler.onBatch(batch);
            } finally {
                if (sendSpan != null) {
                    sendSpan.addAttribute("batch.rows", (long) batchRows);
                    sendSpan.addAttribute("batch.send_nanos", System.nanoTime() - sendStartNanos);
                    sendSpan.endSpan();
                }
            }
            if (cap) {
                batchSpansEmitted++;
            }
            batchOrdinal++;
        }
        return new DrainResult(rowsProduced, batchOrdinal, batchSpansEmitted);
    }

    /**
     * Best-effort total allocated size, in bytes, of the Arrow buffers backing a result batch — the
     * Arrow buffer footprint only, not DataFusion's internal/intermediate/spill memory. 0 if no root.
     */
    static long arrowBatchBytes(EngineResultBatch batch) {
        org.apache.arrow.vector.VectorSchemaRoot root = batch.getArrowRoot();
        if (root == null) {
            return 0L;
        }
        long total = 0L;
        for (org.apache.arrow.vector.FieldVector v : root.getFieldVectors()) {
            total += v.getBufferSize();
        }
        return total;
    }

    /**
     * Extracts a single non-negative integral metric from the DataFusion EXPLAIN-ANALYZE metrics JSON
     * (e.g. {@code "peak_mem_used":108200000}) without a JSON parser on this hot path. Matches
     * {@code "<key>":<digits>} with optional whitespace. Returns -1 when the key is absent, the value
     * isn't a plain integer, or the JSON is null — so callers distinguish "not reported" from a real
     * zero. Best-effort; never throws.
     */
    static long extractLongMetric(String metricsJson, String key) {
        if (metricsJson == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(\\d+)")
            .matcher(metricsJson);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException overflow) {
                return -1;
            }
        }
        return -1;
    }

    /** One native operator parsed from the {@code operators[]} array in the DataFusion metrics JSON. */
    static final class OperatorNode {
        String operator;
        long nodeId;
        long parentId = -1;
        long elapsedComputeNanos;
        long workNanos;        // self-work: max(elapsed_compute, scan processing) — the span bar WIDTH
        long rows;
        long spillCount;
        long spilledBytes;
        Long startUnixNanos;   // null when DataFusion didn't record a wall-clock anchor (pull lifetime)
        Long endUnixNanos;
        Long scanProcessingNanos;
        Long scanTotalNanos;
        Long scanUntilDataNanos;
    }

    /** Cap on per-operator spans emitted per fragment (reuses the per-batch cap). */
    static final int MAX_OPERATOR_SPANS = AnalyticsTracing.DEFAULT_MAX_BATCH_SPANS;

    /**
     * Synthesizes one child span per native operator under {@code fragmentSpan}, so a query can be
     * drilled down operator-by-operator in the trace UI (Q4: "where did the time go inside the shard
     * fragment"). Operators come from the {@code operators[]} array the Rust side emits in
     * {@code metricsJson} (name, node_id, parent_id, elapsed_compute_ns, rows, spill, and — for
     * operators backed by DataFusion BaselineMetrics — real {@code start_unix_nanos}/{@code end_unix_nanos}
     * wall-clock anchors).
     *
     * <p>Layout: when an operator carries real start/end epoch nanos we place the span at exactly those
     * instants ({@code timing.approx=false}) — these are accurate per-operator bars. For the rare
     * operator missing anchors we fall back to right-aligned sequential packing of
     * {@code elapsed_compute_ns} within the fragment window ({@code timing.approx=true}); those bars'
     * <em>placement</em> is approximate (real execution pipelines/overlaps) though their width is the
     * true compute time. Every span carries explicit start/end via the timestamp tracing API, parented
     * explicitly to the fragment via {@link SpanContext} so nesting is deterministic regardless of
     * which thread synthesizes them.
     *
     * <p>Capped at {@link #MAX_OPERATOR_SPANS}. Best-effort: returns the number of spans emitted, never
     * throws (a parse failure or missing array yields 0 — operator spans are decoration, not control).
     *
     * @param fragmentStartEpochNanos fragment start as epoch-nanos (NOT System.nanoTime — that's monotonic)
     * @param fragmentEndEpochNanos   fragment end as epoch-nanos
     */
    static int synthesizeOperatorSpans(
        Tracer tracer,
        Span fragmentSpan,
        String metricsJson,
        long fragmentStartEpochNanos,
        long fragmentEndEpochNanos
    ) {
        if (tracer == null || tracer == NoopTracer.INSTANCE || fragmentSpan == null || metricsJson == null) {
            return 0;
        }
        List<OperatorNode> ops;
        try {
            ops = parseOperators(metricsJson);
        } catch (Exception parseFailure) {
            return 0; // best-effort decoration
        }
        if (ops.isEmpty()) {
            return 0;
        }
        // Bar WIDTH = work_ns (real self-work: CPU for compute operators, scan-processing for the
        // scan), NOT the pull-based open->close lifetime — which would make a blocked SortExec or the
        // root merge look like the whole fragment while doing ~0 work. Each bar is ANCHORED to end at
        // the operator's real wall-clock end (end_unix_nanos when DataFusion recorded it, else the
        // fragment end) and extends left by work_ns. Bars therefore OVERLAP — which is honest: the
        // operators genuinely run concurrently in the pull pipeline. We deliberately do NOT pack
        // sequentially, because partition-summed work_ns (e.g. the scan's ~35s across partitions) can
        // exceed the fragment wall-clock window and would collapse every other bar to zero width.
        // The pull lifetime is preserved as operator.wall_* attributes.
        int emitted = 0;
        for (int i = ops.size() - 1; i >= 0; i--) {
            if (emitted >= MAX_OPERATOR_SPANS) {
                break;
            }
            OperatorNode op = ops.get(i);
            long width = Math.max(0L, op.workNanos);
            long endNs = (op.endUnixNanos != null && op.endUnixNanos > fragmentStartEpochNanos)
                ? clamp(op.endUnixNanos, fragmentStartEpochNanos, fragmentEndEpochNanos)
                : fragmentEndEpochNanos;
            long startNs = Math.max(fragmentStartEpochNanos, endNs - width);
            Attributes attrs = Attributes.create()
                .addAttribute("operator", op.operator == null ? "unknown" : op.operator)
                .addAttribute("operator.node_id", op.nodeId)
                .addAttribute("operator.parent_id", op.parentId)
                .addAttribute("operator.rows", op.rows)
                .addAttribute("operator.work_ns", op.workNanos)
                .addAttribute("operator.elapsed_compute_ns", op.elapsedComputeNanos)
                .addAttribute("operator.spill_count", op.spillCount)
                .addAttribute("operator.spilled_bytes", op.spilledBytes)
                .addAttribute("timing.bar", "work_ns")
                .addAttribute("timing.semantics", "bar_width=self_work(cpu_or_scan_processing); end-anchored@wall_end; bars overlap (concurrent); operator.wall_*=pull_lifetime");
            // Preserve the pull-based lifetime (open->close) as attributes so it isn't lost.
            if (op.startUnixNanos != null && op.endUnixNanos != null && op.endUnixNanos >= op.startUnixNanos) {
                attrs.addAttribute("operator.wall_start_unix_nanos", op.startUnixNanos)
                    .addAttribute("operator.wall_end_unix_nanos", op.endUnixNanos)
                    .addAttribute("operator.wall_duration_ns", op.endUnixNanos - op.startUnixNanos);
            }
            if (op.scanProcessingNanos != null) attrs.addAttribute("operator.scan_processing_ns", op.scanProcessingNanos);
            if (op.scanTotalNanos != null) attrs.addAttribute("operator.scan_total_ns", op.scanTotalNanos);
            if (op.scanUntilDataNanos != null) attrs.addAttribute("operator.scan_until_data_ns", op.scanUntilDataNanos);
            Span opSpan = tracer.startSpan(
                SpanCreationContext.internal()
                    .name(SPAN_OPERATOR_PREFIX + (op.operator == null ? "unknown" : op.operator))
                    .parent(new SpanContext(fragmentSpan))
                    .startTimestamp(Instant.ofEpochSecond(0, startNs))
                    .attributes(attrs)
            );
            opSpan.endSpan(Instant.ofEpochSecond(0, endNs));
            emitted++;
        }
        return emitted;
    }

    /** Span name for the Lucene filter-delegation child span (distinct from datafusion.op.*). */
    static final String SPAN_LUCENE_DELEGATION = "lucene.delegation";

    /**
     * Emits a single {@code lucene.delegation} child span (distinct from the {@code datafusion.op.*}
     * operator spans) when a predicate was executed by Lucene rather than DataFusion — so the two
     * execution engines are visually distinguishable in the trace and you can read the Lucene
     * pruning cost directly. The Lucene work happens via per-row-group FFM upcalls *inside* the
     * scan operator (it is not a DataFusion plan node), so it's surfaced as one accumulated span:
     * width = {@code delegationCpuNanos} (summed across the upcalls), tagged with the predicate
     * count and collector-call count. No-op when delegation didn't fire (cpu &lt;= 0). Bar is packed
     * to end at the fragment end (the delegation runs throughout the scan).
     */
    static void emitLuceneDelegationSpan(
        Tracer tracer,
        Span fragmentSpan,
        long delegationCpuNanos,
        long collectCalls,
        long delegatedPredicateCount,
        String treeShape,
        long fragmentStartEpochNanos,
        long fragmentEndEpochNanos
    ) {
        if (tracer == null || tracer == NoopTracer.INSTANCE || fragmentSpan == null || delegationCpuNanos <= 0) {
            return;
        }
        long endNs = fragmentEndEpochNanos;
        long startNs = Math.max(fragmentStartEpochNanos, endNs - delegationCpuNanos);
        Attributes attrs = Attributes.create()
            .addAttribute("engine", "lucene")
            .addAttribute("lucene.cpu_nanos", delegationCpuNanos)
            .addAttribute("lucene.collector_calls", collectCalls)
            .addAttribute("lucene.delegated_predicate_count", delegatedPredicateCount)
            .addAttribute("timing.bar", "lucene_cpu_nanos")
            .addAttribute("timing.semantics", "bar_width=summed_collectDocs_cpu_across_segments(concurrent); placement_packed");
        if (treeShape != null) {
            attrs.addAttribute("lucene.tree_shape", treeShape);
        }
        Span s = tracer.startSpan(
            SpanCreationContext.internal()
                .name(SPAN_LUCENE_DELEGATION)
                .parent(new SpanContext(fragmentSpan))
                .startTimestamp(Instant.ofEpochSecond(0, startNs))
                .attributes(attrs)
        );
        s.endSpan(Instant.ofEpochSecond(0, endNs));
    }

    /** Span name prefix for per-operator spans, e.g. {@code datafusion.op.SortExec}. */
    static final String SPAN_OPERATOR_PREFIX = "datafusion.op.";

    private static long clamp(long v, long lo, long hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /** Parses the {@code operators} array out of the DataFusion metrics JSON. Returns empty if absent. */
    private static List<OperatorNode> parseOperators(String metricsJson) throws Exception {
        List<OperatorNode> out = new ArrayList<>();
        try (
            XContentParser p = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                LoggingDeprecationHandler.INSTANCE,
                metricsJson
            )
        ) {
            if (p.nextToken() != XContentParser.Token.START_OBJECT) {
                return out;
            }
            while (p.nextToken() != XContentParser.Token.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                if ("operators".equals(field) && p.currentToken() == XContentParser.Token.START_ARRAY) {
                    while (p.nextToken() != XContentParser.Token.END_ARRAY) {
                        out.add(parseOperatorObject(p));
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
        return out;
    }

    private static OperatorNode parseOperatorObject(XContentParser p) throws Exception {
        OperatorNode n = new OperatorNode();
        // p is positioned at START_OBJECT for this entry
        while (p.nextToken() != XContentParser.Token.END_OBJECT) {
            String f = p.currentName();
            p.nextToken();
            switch (f) {
                case "operator" -> n.operator = p.text();
                case "node_id" -> n.nodeId = p.longValue();
                case "parent_id" -> n.parentId = p.longValue();
                case "elapsed_compute_ns" -> n.elapsedComputeNanos = p.longValue();
                case "work_ns" -> n.workNanos = p.longValue();
                case "rows" -> n.rows = p.longValue();
                case "spill_count" -> n.spillCount = p.longValue();
                case "spilled_bytes" -> n.spilledBytes = p.longValue();
                case "start_unix_nanos" -> n.startUnixNanos = p.longValue();
                case "end_unix_nanos" -> n.endUnixNanos = p.longValue();
                case "scan_processing_ns" -> n.scanProcessingNanos = p.longValue();
                case "scan_total_ns" -> n.scanTotalNanos = p.longValue();
                case "scan_until_data_ns" -> n.scanUntilDataNanos = p.longValue();
                default -> p.skipChildren();
            }
        }
        return n;
    }
}
