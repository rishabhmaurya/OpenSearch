/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import org.opensearch.analytics.exec.stage.StageExecution;
import org.opensearch.analytics.exec.stage.StageMetrics;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanContext;
import org.opensearch.telemetry.tracing.SpanCreationContext;
import org.opensearch.telemetry.tracing.SpanScope;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.telemetry.tracing.attributes.Attributes;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Span lifecycle helper for an analytics query (the T1 coordinator spine + per-stage spans).
 *
 * <p>Encapsulates the async-lifecycle subtleties so the call sites in
 * {@link DefaultPlanExecutor} / {@link ExecutionGraph} stay simple:
 * <ul>
 *   <li>The {@code analytics.execute} root span opens during synchronous setup and closes at
 *       the asynchronous query terminal (success/failure), exactly once.</li>
 *   <li>Per-stage spans are parented <em>explicitly</em> to the execute span (via
 *       {@link SpanContext}), not via ambient thread scope — stage state transitions fire on
 *       SEARCH / SCHEDULER / REDUCE threads, so we cannot rely on the execute span still being
 *       in scope on those threads. They open on the first transition out of CREATED and end on
 *       the terminal transition, exactly once.</li>
 * </ul>
 *
 * <p>All methods are null-safe against a {@code null} {@link Tracer}; with the noop tracer the
 * spans are noop and add negligible overhead, so callers never need to branch on whether
 * tracing is enabled.
 *
 * @opensearch.internal
 */
final class AnalyticsTracing {

    static final String SPAN_EXECUTE = "analytics.execute";
    static final String SPAN_PLAN = "analytics.plan";
    static final String SPAN_SCHEDULE = "analytics.schedule";
    static final String SPAN_STAGE = "analytics.stage";
    /** Per result-batch span on the data node — one segment-result flush to the stream transport. */
    static final String SPAN_BATCH = "datafusion.batch";
    /** Data-node fragment SETUP: reader pin + native session build + Substrait decode + plan compile (time-to-first-batch). */
    static final String SPAN_FRAGMENT_SETUP = "datafusion.shard_fragment.setup";
    /** Per-batch PRODUCE: the native streamNext pull that runs scan/filter/aggregate to materialize one batch. */
    static final String SPAN_BATCH_PRODUCE = "datafusion.batch.produce";
    /** Per-batch SEND: pushing the produced batch to the stream transport (Flight). */
    static final String SPAN_BATCH_SEND = "datafusion.batch.send";
    /** Per inbound-batch span on the coordinator — one shard batch fed into the reduce. */
    static final String SPAN_REDUCE_FEED = "analytics.reduce_feed";
    /** Per reduced-output-batch PRODUCE: the native FINAL-aggregation pull on the coordinator. */
    static final String SPAN_REDUCE_PRODUCE = "datafusion.reduce.produce";
    /** Per reduced-output-batch SEND: pushing the reduced batch downstream from the coordinator. */
    static final String SPAN_REDUCE_SEND = "datafusion.reduce.send";

    /**
     * Default hard cap on per-batch child spans emitted per fragment (data node) and per reduce
     * stage (coordinator). A scan-heavy query flushes thousands of batches; without a cap the trace
     * blows up. Past the cap we stop opening batch spans but keep counting, and record the totals on
     * the parent span so the elision is visible.
     */
    static final int DEFAULT_MAX_BATCH_SPANS = 64;

    static final String ATTR_QUERY_ID = "query_id";
    static final String ATTR_STAGE_ID = "stage_id";
    static final String ATTR_STAGE_TYPE = "execution_type";
    static final String ATTR_ROW_COUNT = "row_count";
    static final String ATTR_PLANNING_MS = "planning_time_ms";

    private final Tracer tracer;
    private final Span executeSpan;
    private final AtomicBoolean executeEnded = new AtomicBoolean(false);
    // True when a real query TEXT was supplied by the front-end (query.text set at open). Gates the
    // plan-text fallback so we never overwrite a genuine source string with the derived plan.
    private boolean queryTextProvided;

    private AnalyticsTracing(Tracer tracer, Span executeSpan) {
        this.tracer = tracer;
        this.executeSpan = executeSpan;
    }

    /**
     * Opens the {@code analytics.execute} root span for a query. {@code querySource} is the actual
     * query TEXT (PPL/SQL) when the front-end supplies it — tagged as {@code query.text} so a trace
     * is self-identifying (Q1). The returned helper owns the span's lifecycle; the caller MUST
     * eventually call {@link #endExecute} / {@link #endExecuteWithError} on the query terminal.
     */
    static AnalyticsTracing start(Tracer tracer, String queryId, String querySource) {
        if (tracer == null) {
            return new AnalyticsTracing(null, null);
        }
        Attributes attrs = Attributes.create().addAttribute(ATTR_QUERY_ID, queryId);
        if (querySource != null && querySource.isBlank() == false) {
            // The query text itself — the single most useful field for "which query is this trace?".
            // Truncate defensively so a pathological query can't bloat the span.
            String text = querySource.length() > 2048 ? querySource.substring(0, 2048) + "…[truncated]" : querySource;
            attrs.addAttribute("query.text", text);
        }
        Span span = tracer.startSpan(SpanCreationContext.server().name(SPAN_EXECUTE).attributes(attrs));
        AnalyticsTracing tracing = new AnalyticsTracing(tracer, span);
        tracing.queryTextProvided = querySource != null && querySource.isBlank() == false;
        return tracing;
    }

    /** True when this helper has a live (non-noop) execute span — i.e. tracing is enabled for this query. */
    boolean isRecording() {
        return executeSpan != null;
    }

    /**
     * Fallback for the query-identity attribute (Q1) when the front-end did NOT supply the original
     * query text (e.g. the external opensearch-sql {@code /_plugins/_ppl} route passes a null
     * querySource). Tags the execute span with the Calcite plan string as {@code query.plan_text} so
     * the trace is still self-identifying. No-op if a real {@code query.text} was already recorded at
     * open, if tracing is off, or if {@code planText} is blank. The value is truncated to match the
     * defensive cap used for {@code query.text}.
     */
    void recordQueryPlanText(String planText) {
        if (executeSpan == null || queryTextProvided || planText == null || planText.isBlank()) {
            return;
        }
        String text = planText.length() > 2048 ? planText.substring(0, 2048) + "…[truncated]" : planText;
        executeSpan.addAttribute("query.plan_text", text);
    }

    /** The execute span, for putting in scope at the dispatch site so transport spans nest under it. */
    Span executeSpan() {
        return executeSpan;
    }

    /**
     * Puts the execute span in scope on the current thread, returning a {@link SpanScope} to close
     * (try-with-resources). Null-safe: returns a no-op {@code SpanScope} when tracing is off. Does
     * NOT end the span — that happens asynchronously on the query terminal via {@link #endExecute}.
     */
    SpanScope withExecuteInScope(Tracer t) {
        if (t == null || executeSpan == null) {
            return SpanScope.NO_OP;
        }
        return t.withSpanInScope(executeSpan);
    }

    /**
     * Opens a child span of the execute span (e.g. {@code analytics.plan}). Returns {@code null}
     * when tracing is off. The caller owns the lifecycle via {@link #endSpan} /
     * {@link #endSpanWithError} — used for the synchronous planning phase whose locals must stay
     * in the enclosing method scope (so a try/finally bracket reads cleaner than a Supplier).
     */
    Span startChildSpan(String name) {
        if (tracer == null) {
            return null;
        }
        return tracer.startSpan(SpanCreationContext.internal().name(name).parent(new SpanContext(executeSpan)));
    }

    /**
     * Opens a short-lived child span of {@code parent} for a single result batch, named
     * {@code spanName} (e.g. {@link #SPAN_BATCH} / {@link #SPAN_REDUCE_FEED}), tagged with its
     * ordinal. Parented explicitly via {@link SpanContext} so it nests deterministically regardless
     * of the thread it runs on (batch flushes / reduce feeds run on work / per-stream threads). The
     * caller owns the lifecycle (tag more, then {@link #endSpan}). Returns {@code null} when
     * {@code tracer} or {@code parent} is null (tracing off) so callers can cheaply skip the work.
     */
    static Span startBatchSpan(Tracer tracer, Span parent, String spanName, long ordinal) {
        if (tracer == null || parent == null) {
            return null;
        }
        Attributes attrs = Attributes.create().addAttribute("batch.ordinal", ordinal);
        return tracer.startSpan(SpanCreationContext.internal().name(spanName).parent(new SpanContext(parent)).attributes(attrs));
    }

    /**
     * Opens a child span of {@code parent} with no preset attributes (e.g. the fragment SETUP span,
     * or per-batch produce/send phases when the ordinal is carried elsewhere). Explicit parent so it
     * nests deterministically on the work thread. Returns {@code null} when tracer/parent is null.
     */
    static Span startChildOf(Tracer tracer, Span parent, String spanName) {
        if (tracer == null || parent == null) {
            return null;
        }
        return tracer.startSpan(SpanCreationContext.internal().name(spanName).parent(new SpanContext(parent)));
    }

    /**
     * Opens a {@link #SPAN_REDUCE_FEED} child span for one inbound shard batch fed into the
     * coordinator reduce, parented to the execute span (so it nests under the query trace alongside
     * {@code analytics.stage}). Returns {@code null} when tracing is off. The caller owns the
     * lifecycle and runs on a per-stream thread, so the span must be opened and ended within the
     * same feed callback.
     */
    Span startReduceFeedSpan(long ordinal) {
        return startBatchSpan(tracer, executeSpan, SPAN_REDUCE_FEED, ordinal);
    }

    /** Opens a {@link #SPAN_REDUCE_PRODUCE} child of the execute span for one reduced-output batch. */
    Span startReduceProduceSpan(long ordinal) {
        return startBatchSpan(tracer, executeSpan, SPAN_REDUCE_PRODUCE, ordinal);
    }

    /** Opens a {@link #SPAN_REDUCE_SEND} child of the execute span for one reduced-output batch. */
    Span startReduceSendSpan(long ordinal) {
        return startBatchSpan(tracer, executeSpan, SPAN_REDUCE_SEND, ordinal);
    }

    /** Tags the execute span with the total reduced-output batch count (how many the reduce produced). */
    void recordReduceOutputBatchCount(long totalBatches) {
        if (executeSpan != null) {
            executeSpan.addAttribute("reduce.output_batch_count", totalBatches);
        }
    }

    /** Ends a span returned by {@link #startChildSpan}. Null-safe. */
    static void endSpan(Span span) {
        if (span != null) {
            span.endSpan();
        }
    }

    /** Ends a span returned by {@link #startChildSpan} with an error. Null-safe. */
    static void endSpanWithError(Span span, Exception e) {
        if (span != null) {
            span.setError(e);
            span.endSpan();
        }
    }

    /**
     * Overwrites the {@code query_id} attribute on the execute span with the canonical DAG query
     * id once the DAG is built. The span is opened before planning, when only the task id
     * ("unassigned") is known; the real UUID is minted in DAGBuilder. Using the DAG id keeps the
     * coordinator execute span and the data-node shard-fragment spans on the SAME query_id, so a
     * trace can be filtered by it.
     */
    void setQueryId(String queryId) {
        if (executeSpan != null && queryId != null) {
            executeSpan.addAttribute(ATTR_QUERY_ID, queryId);
        }
    }

    /** Tags the execute span with planning time once planning completes. */
    void recordPlanningTime(long planningTimeMs) {
        if (executeSpan != null) {
            executeSpan.addAttribute(ATTR_PLANNING_MS, planningTimeMs);
        }
    }

    /**
     * Tags the execute span with plan-shape summary once the DAG is built (Q1/Q4 context): the
     * target indices, the number of stages, and the chosen backend(s). Lets you see at a glance
     * what the query touched and how it was decomposed, without opening child spans.
     */
    void recordPlanSummary(String indices, int stageCount, String backends) {
        if (executeSpan == null) {
            return;
        }
        if (indices != null) {
            executeSpan.addAttribute("query.indices", indices);
        }
        executeSpan.addAttribute("plan.stage_count", (long) stageCount);
        if (backends != null) {
            executeSpan.addAttribute("plan.backends", backends);
        }
    }

    /**
     * Convenience overload that derives the stage count and backend set from the {@code dag} so the
     * caller doesn't carry the DAG-walk helpers. No-op when the execute span is absent.
     */
    void recordPlanSummary(org.opensearch.analytics.planner.dag.QueryDAG dag, String indices) {
        if (executeSpan == null) {
            return;
        }
        recordPlanSummary(indices, countStages(dag), backendsOf(dag));
    }

    /** Total stage count in the DAG (root + all descendants). */
    private static int countStages(org.opensearch.analytics.planner.dag.QueryDAG dag) {
        return countStagesRec(dag.rootStage());
    }

    private static int countStagesRec(org.opensearch.analytics.planner.dag.Stage stage) {
        int n = 1;
        for (org.opensearch.analytics.planner.dag.Stage c : stage.getChildStages()) {
            n += countStagesRec(c);
        }
        return n;
    }

    /** Distinct chosen backend ids across the DAG's stages (e.g. "datafusion" or "datafusion,lucene"). */
    private static String backendsOf(org.opensearch.analytics.planner.dag.QueryDAG dag) {
        java.util.LinkedHashSet<String> backends = new java.util.LinkedHashSet<>();
        collectBackends(dag.rootStage(), backends);
        return backends.isEmpty() ? null : String.join(",", backends);
    }

    private static void collectBackends(org.opensearch.analytics.planner.dag.Stage stage, java.util.Set<String> out) {
        if (stage.getPlanAlternatives() != null) {
            stage.getPlanAlternatives().forEach(p -> { if (p.backendId() != null) out.add(p.backendId()); });
        }
        stage.getChildStages().forEach(c -> collectBackends(c, out));
    }

    /**
     * Tags the execute span with per-query peak memory (M2). The two systems are disjoint (JVM
     * off-heap Arrow vs native DataFusion pool) — reported as separate attributes, never summed.
     * Call before {@link #endExecute} at the query terminal.
     */
    void recordMemory(long peakArrowBytes, long peakNativeBytes) {
        if (executeSpan != null) {
            executeSpan.addAttribute("mem.query_arrow.peak_bytes", peakArrowBytes);
            executeSpan.addAttribute("mem.query_native.peak_bytes", peakNativeBytes);
        }
    }

    /**
     * Attaches a per-stage span to {@code stage}'s state machine. The span opens on the first
     * transition out of CREATED and ends on the terminal transition (exactly once each, guarded
     * by the AtomicReference compareAndSet). Parented explicitly to the execute span so it
     * attaches regardless of which thread the transition fires on.
     */
    void attachStageSpan(StageExecution stage, String stageType) {
        if (tracer == null) {
            return;
        }
        final int stageId = stage.getStageId();
        final java.util.concurrent.atomic.AtomicReference<Span> stageSpanRef = new java.util.concurrent.atomic.AtomicReference<>();
        final AtomicBoolean ended = new AtomicBoolean(false);

        stage.addStateListener((from, to) -> {
            // Open lazily on the first transition out of CREATED (RUNNING, or straight to a
            // terminal for empty/short-circuit stages).
            if (from == StageExecution.State.CREATED) {
                Attributes attrs = Attributes.create()
                    .addAttribute(ATTR_STAGE_ID, stageId)
                    .addAttribute(ATTR_STAGE_TYPE, stageType);
                Span span = tracer.startSpan(
                    SpanCreationContext.internal().name(stageSpanName(stageType)).parent(new SpanContext(executeSpan)).attributes(attrs)
                );
                stageSpanRef.compareAndSet(null, span);
            }
            if (to.isTerminal() && ended.compareAndSet(false, true)) {
                Span span = stageSpanRef.get();
                if (span != null) {
                    span.addAttribute("terminal_state", to.name());
                    // Per-stage volume (Q2: which stage moved how much data). StageMetrics is the
                    // same bracket that times the stage; null-guarded for stubbed/test stages.
                    try {
                        StageMetrics m = stage.getMetrics();
                        if (m != null) {
                            span.addAttribute("rows_processed", m.getRowsProcessed());
                            span.addAttribute("bytes_read", m.getBytesRead());
                        }
                    } catch (RuntimeException ignored) {
                        // metrics are best-effort decoration; never fail span close
                    }
                    span.endSpan();
                }
            }
        });
    }

    /**
     * Builds the stage span's operation name from its execution type so the trace UI differentiates
     * stages by NAME (e.g. {@code analytics.stage.shard_fragment} vs
     * {@code analytics.stage.coordinator_reduce}) rather than forcing a drill into the
     * {@code execution_type} attribute. Lowercased per the OTel low-cardinality span-name guideline
     * (execution type is a bounded enum, so it is safe in the name). Falls back to the bare
     * {@link #SPAN_STAGE} when the type is unknown.
     */
    static String stageSpanName(String stageType) {
        if (stageType == null || stageType.isBlank()) {
            return SPAN_STAGE;
        }
        return SPAN_STAGE + "." + stageType.toLowerCase(java.util.Locale.ROOT);
    }

    /** Ends the execute span successfully (idempotent). */
    void endExecute(long rowCount) {
        if (executeSpan != null && executeEnded.compareAndSet(false, true)) {
            executeSpan.addAttribute(ATTR_ROW_COUNT, rowCount);
            executeSpan.endSpan();
        }
    }

    /** Ends the execute span with an error (idempotent). */
    void endExecuteWithError(Exception e) {
        if (executeSpan != null && executeEnded.compareAndSet(false, true)) {
            executeSpan.setError(e);
            executeSpan.endSpan();
        }
    }
}
