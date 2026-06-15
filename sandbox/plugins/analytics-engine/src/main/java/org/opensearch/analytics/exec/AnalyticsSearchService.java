/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.analytics.backend.AnalyticsOperationListener;
import org.opensearch.analytics.backend.EngineResultBatch;
import org.opensearch.analytics.backend.EngineResultStream;  // result stream SPI (per-batch native peak accessor)
import org.opensearch.analytics.backend.FragmentExecutionStats;
import org.opensearch.analytics.backend.SearchExecEngine;
import org.opensearch.analytics.backend.ShardScanExecutionContext;
import org.opensearch.analytics.exec.action.FetchByRowIdsRequest;
import org.opensearch.analytics.exec.action.FragmentExecutionRequest;
import org.opensearch.analytics.exec.task.AnalyticsShardTask;
import org.opensearch.analytics.spi.AnalyticsSearchBackendPlugin;
import org.opensearch.analytics.spi.BackendExecutionContext;
import org.opensearch.analytics.spi.DelegationDescriptor;
import org.opensearch.analytics.spi.DelegationThreadTracker;
import org.opensearch.analytics.spi.FilterDelegationHandle;
import org.opensearch.analytics.spi.FragmentInstructionHandler;
import org.opensearch.analytics.spi.FragmentInstructionHandlerFactory;
import org.opensearch.analytics.spi.InstructionNode;
import org.opensearch.arrow.allocator.ArrowNativeAllocator;
import org.opensearch.arrow.spi.NativeAllocatorPoolConfig;
import org.opensearch.common.concurrent.GatedCloseable;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.tasks.TaskCancelledException;
import org.opensearch.index.engine.dataformat.DocumentInput;
import org.opensearch.index.engine.exec.IndexReaderProvider;
import org.opensearch.index.engine.exec.IndexReaderProvider.Reader;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.tasks.Task;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanCreationContext;
import org.opensearch.telemetry.tracing.SpanScope;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.telemetry.tracing.attributes.Attributes;
import org.opensearch.telemetry.tracing.noop.NoopTracer;
import org.opensearch.tasks.TaskResourceTrackingService;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Data-node service that executes plan fragments against local shards.
 * Acquires a reader from the shard's composite engine, builds an
 * {@link ShardScanExecutionContext}, and invokes the backend's {@link SearchExecEngine}
 * to produce results.
 *
 * <p>Does NOT hold {@code IndicesService} — receives an already-resolved
 * {@link IndexShard} from the transport action.
 *
 * <p>Owns a service-lifetime {@link BufferAllocator} shared by every fragment, obtained as a child of
 * the framework's QUERY pool via {@link ArrowNativeAllocator#getPoolAllocator(String)}. One allocator
 * per service means memory accounting is reported at the service level. For the streaming path, Arrow
 * Flight's outbound handler co-locates its transfer target on the same root (see
 * {@code FlightOutboundHandler#processBatchTask}), keeping transfers same-root and avoiding the known
 * cross-allocator bug with foreign-backed buffers from the C Data Interface.
 *
 * @opensearch.internal
 */
public class AnalyticsSearchService implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(AnalyticsSearchService.class);

    private final Map<String, AnalyticsSearchBackendPlugin> backends;
    private final AnalyticsOperationListener listener;
    private final NamedWriteableRegistry namedWriteableRegistry;
    /** Cross-phase reader cache for QTF — query phase stores, fetch phase acquires. */
    private final ReaderContextStore readerContextStore;
    private TaskResourceTrackingService taskResourceTrackingService;
    private final BufferAllocator allocator;
    private final ArrowNativeAllocator nativeAllocator;
    /** Data-node tracer (T2). Set by {@code AnalyticsSearchTransportService} (Guice-injected). NoopTracer until then. */
    private volatile Tracer tracer = NoopTracer.INSTANCE;

    public AnalyticsSearchService(Map<String, AnalyticsSearchBackendPlugin> backends, ArrowNativeAllocator nativeAllocator) {
        this(backends, List.of(), nativeAllocator, null, null);
    }

    public AnalyticsSearchService(
        Map<String, AnalyticsSearchBackendPlugin> backends,
        ArrowNativeAllocator nativeAllocator,
        NamedWriteableRegistry namedWriteableRegistry,
        ReaderContextStore readerContextStore
    ) {
        this(backends, List.of(), nativeAllocator, namedWriteableRegistry, readerContextStore);
    }

    public AnalyticsSearchService(
        Map<String, AnalyticsSearchBackendPlugin> backends,
        List<AnalyticsOperationListener> listeners,
        ArrowNativeAllocator nativeAllocator,
        NamedWriteableRegistry namedWriteableRegistry,
        ReaderContextStore readerContextStore
    ) {
        this.backends = backends;
        this.listener = new AnalyticsOperationListener.CompositeListener(listeners);
        this.nativeAllocator = nativeAllocator;
        // Source the service-level allocator from the unified framework's query pool so all
        // analytics-engine allocations are tracked and capped by the framework. Hard-fail if
        // the framework is missing — silently falling back to a separate root would break
        // Arrow's same-root invariant for cross-plugin handoff.
        //
        // Child uses Long.MAX_VALUE so dynamic resizes of parquet.native.pool.query.max take
        // effect immediately via Arrow's parent-cap check at allocateBytes — no listener needed.
        BufferAllocator queryPool = nativeAllocator.getPoolAllocator(NativeAllocatorPoolConfig.POOL_QUERY);
        this.allocator = queryPool.newChildAllocator("analytics-search-service", 0, Long.MAX_VALUE);
        this.namedWriteableRegistry = namedWriteableRegistry;
        this.readerContextStore = readerContextStore;
    }

    @Override
    public void close() {
        allocator.close();
    }

    public void setTaskResourceTrackingService(TaskResourceTrackingService service) {
        this.taskResourceTrackingService = service;
    }

    /** Sets the data-node tracer (T2). Wired by {@code AnalyticsSearchTransportService}; defaults to NoopTracer. */
    public void setTracer(Tracer tracer) {
        this.tracer = tracer != null ? tracer : NoopTracer.INSTANCE;
    }

    public FragmentResources executeFragmentStreaming(FragmentExecutionRequest request, IndexShard shard, AnalyticsShardTask task) {
        return executeFragmentStreamingResolved(request, shard, task).resources;
    }

    private ResolvedExecution executeFragmentStreamingResolved(
        FragmentExecutionRequest request,
        IndexShard shard,
        AnalyticsShardTask task
    ) {
        ResolvedFragment resolved = resolveFragment(request, shard);
        try {
            FragmentResources resources = startFragment(request, resolved, shard, task);
            return new ResolvedExecution(resources, resolved);
        } catch (TaskCancelledException | IllegalStateException | IllegalArgumentException e) {
            listener.onFragmentFailure(resolved.queryId, resolved.stageId, resolved.shardIdStr, e);
            throw e;
        } catch (Exception e) {
            listener.onFragmentFailure(resolved.queryId, resolved.stageId, resolved.shardIdStr, e);
            throw new RuntimeException("Failed to start streaming fragment on " + shard.shardId(), e);
        }
    }

    private record ResolvedExecution(FragmentResources resources, ResolvedFragment resolved) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            resources.close();
        }
    }

    /**
     * Async variant that forks fragment execution onto the given executor and streams
     * batches back through the channel. The transport thread returns immediately.
     */
    public void executeFragmentStreamingAsync(
        FragmentExecutionRequest request,
        IndexShard shard,
        AnalyticsShardTask task,
        StreamingFragmentResponseHandler responseHandler,
        Executor executor
    ) {
        try {
            executor.execute(() -> {
                LOGGER.debug("[FragmentExecution] shard={} task={}", shard.shardId(), task.getId());
                final long startNanos = System.nanoTime();
                // Paired EPOCH anchor for the fragment window. startNanos is monotonic (good for
                // durations, unusable as an OTel timestamp); this gives per-operator child spans a
                // real wall-clock origin. End epoch is derived as start-epoch + monotonic-elapsed so
                // the operator-span window width tracks the precise fragment duration.
                final long fragmentStartEpochNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
                long rowsProduced = 0;
                // T2: data-node span. The inbound Flight transport span is in this thread's
                // ThreadContext (the SEARCH executor is context-preserving), so startSpan
                // auto-parents to the coordinator's shard-dispatch span -> the fragment shows
                // up as a child of the query trace on the right node. NoopTracer => no-op.
                final Span fragmentSpan = tracer.startSpan(
                    SpanCreationContext.server()
                        .name("datafusion.shard_fragment")
                        .attributes(
                            Attributes.create()
                                .addAttribute("query_id", request.getQueryId())
                                .addAttribute("stage_id", request.getStageId())
                                .addAttribute("shard_id", shard.shardId().toString())
                        )
                );
                boolean fragmentSpanEnded = false;
                // T2 per-batch instrumentation. The fragment span's interior used to be a blindspot:
                // the old single batch span wrapped only responseHandler.onBatch (the SEND, ~microseconds)
                // while the actual batch PRODUCTION (the native streamNext pull running scan/filter/agg,
                // evaluated inside it.hasNext()/it.next()) fell into the untimed gaps between spans, and
                // the entire reader-pin + native-session-build + plan-compile setup before the first
                // batch was invisible. We now tile the fragment timeline with contiguous child spans so
                // the durations add up:
                //   datafusion.shard_fragment.setup  — reader pin + session build + plan compile (TTFB)
                //   datafusion.batch.produce         — the native pull that materializes batch N
                //   datafusion.batch.send            — pushing batch N to the stream transport
                // All null/noop-safe; gated on a real tracer so the noop path pays nothing.
                final boolean tracingOn = tracer != NoopTracer.INSTANCE;
                long batchOrdinal = 0;
                long batchSpansEmitted = 0;
                // SETUP span brackets the resolve (reader pin, native session, Substrait decode, plan
                // compile). Opened before executeFragmentStreamingResolved, closed once it returns —
                // BEFORE the drain loop — so it captures only time-to-resolved, not the streaming.
                final Span setupSpan = tracingOn ? AnalyticsTracing.startChildOf(tracer, fragmentSpan, AnalyticsTracing.SPAN_FRAGMENT_SETUP) : null;
                final long setupStartNanos = System.nanoTime();
                ResolvedExecution exec = null;
                try (SpanScope ignored = tracer.withSpanInScope(fragmentSpan)) {
                    try {
                        exec = executeFragmentStreamingResolved(request, shard, task);
                    } finally {
                        if (setupSpan != null) {
                            setupSpan.addAttribute("setup_nanos", System.nanoTime() - setupStartNanos);
                            setupSpan.endSpan();
                        }
                    }
                    // T2 per-batch drain (produce/send child spans) lives in FragmentTracingProbe so
                    // this hot loop stays a thin call. Exceptions propagate; this method's catch/finally
                    // owns fragment-span error tagging and exec close.
                    EngineResultStream stream = exec.resources().stream();
                    FragmentTracingProbe.DrainResult drained = FragmentTracingProbe.drain(tracer, fragmentSpan, stream, responseHandler, tracingOn);
                    rowsProduced = drained.rowsProduced();
                    batchOrdinal = drained.batchCount();
                    batchSpansEmitted = drained.batchSpansEmitted();
                    long fragmentTookNanos = System.nanoTime() - startNanos;
                    // DataFusion native execution metrics (per-operator EXPLAIN-ANALYZE-style JSON:
                    // scan rows, row-groups pruned, partial-agg time, etc.) — the answer to
                    // "what happened at shard level / where's the bottleneck" (Q2/Q4).
                    byte[] metricsJson = exec.resources().getExecutionMetrics();
                    String nativeMetrics = metricsJson != null
                        ? new String(metricsJson, java.nio.charset.StandardCharsets.UTF_8)
                        : null;
                    if (LOGGER.isDebugEnabled() && nativeMetrics != null) {
                        LOGGER.debug("[FragmentMetrics] shard={} metrics={}", shard.shardId(), nativeMetrics);
                    }
                    responseHandler.onComplete();
                    ResolvedFragment resolved = exec.resolved();
                    DelegationDescriptor delegation = resolved.plan().getDelegationDescriptor();
                    boolean usedSecondaryIndex = delegation != null;
                    int delegatedPredicateCount = delegation != null ? delegation.delegatedPredicateCount() : 0;
                    String filterTreeShape = delegation != null ? delegation.treeShape().name() : null;
                    boolean hasPartialAggregate = resolved.plan()
                        .getInstructions()
                        .stream()
                        .anyMatch(n -> n.type() == org.opensearch.analytics.spi.InstructionType.SETUP_PARTIAL_AGGREGATE);
                    FragmentExecutionStats stats = new FragmentExecutionStats(
                        rowsProduced,
                        usedSecondaryIndex,
                        delegatedPredicateCount,
                        filterTreeShape,
                        hasPartialAggregate,
                        task.getId(),
                        task.getHeader(Task.X_OPAQUE_ID)
                    );
                    // T2: tag the fragment span with execution facts, then end it before the
                    // success listener fires (so the span closes on the work thread).
                    fragmentSpan.addAttribute("rows_produced", rowsProduced);
                    fragmentSpan.addAttribute("took_nanos", fragmentTookNanos);
                    fragmentSpan.addAttribute("took_ms", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(fragmentTookNanos));
                    fragmentSpan.addAttribute("has_partial_aggregate", hasPartialAggregate);
                    // How many batches this fragment flushed vs how many got their own span (the rest
                    // were elided by the per-fragment cap) — keeps the count signal even when capped.
                    fragmentSpan.addAttribute("batch.count_total", batchOrdinal);
                    fragmentSpan.addAttribute("batch.span_count", batchSpansEmitted);
                    // Q4: was Lucene used as a delegated filter, and how. Human-readable summary
                    // plus the raw fields. "lucene_pruned" = the inverted index narrowed the scan;
                    // "datafusion_only" = no delegation, full columnar scan + native eval.
                    fragmentSpan.addAttribute("filter.delegation", usedSecondaryIndex ? "lucene_pruned" : "datafusion_only");
                    fragmentSpan.addAttribute("filter.delegated_predicate_count", (long) delegatedPredicateCount);
                    if (filterTreeShape != null) {
                        fragmentSpan.addAttribute("filter.tree_shape", filterTreeShape);
                    }
                    // T2: how much of the produce time was Lucene delegation. Read BEFORE exec.close()
                    // drops the FFM binding. CPU-nanos (summed across concurrent scan threads, so it
                    // can exceed produce wall-clock); collect_calls ≈ row-groups/segments Lucene-pruned.
                    org.opensearch.analytics.spi.DelegationTimings dt = exec.resources().getDelegationTimings();
                    long delegationCpuNanos = 0;
                    long delegationCollectCalls = 0;
                    if (dt != null) {
                        delegationCpuNanos = dt.collectNanos();
                        delegationCollectCalls = dt.collectCalls();
                        fragmentSpan.addAttribute("filter.delegation_cpu_nanos", delegationCpuNanos);
                        fragmentSpan.addAttribute("filter.delegation_collect_calls", delegationCollectCalls);
                    }
                    // Q2/Q4: per-operator native execution breakdown (scan/prune/agg timings & row counts).
                    if (nativeMetrics != null) {
                        String m = nativeMetrics.length() > 8192 ? nativeMetrics.substring(0, 8192) + "…[truncated]" : nativeMetrics;
                        fragmentSpan.addAttribute("datafusion.metrics", m);
                        // M2/Q3: promote the native pool high-water mark to a first-class, queryable
                        // numeric attribute instead of leaving it buried in the metrics JSON blob.
                        // This is the per-SHARD native peak (this node's DataFusion pool for this
                        // fragment) — intentionally NOT summed with other shards or the coordinator
                        // reduce (those pools live on separate nodes; see tracing SPEC "not a cluster
                        // sum"). -1 when the field is absent so a missing value is distinguishable.
                        long shardNativePeak = FragmentTracingProbe.extractLongMetric(nativeMetrics, "peak_mem_used");
                        if (shardNativePeak >= 0) {
                            fragmentSpan.addAttribute("mem.shard_native.peak_bytes", shardNativePeak);
                        }
                        // Q4 drill-down: synthesize one child span per native operator (SortExec,
                        // AggregateExec, QueryShardExec, …) from the operators[] array, so the
                        // fragment can be opened up operator-by-operator in the trace UI. Uses the
                        // FULL (untruncated) metrics JSON — the operators array is past the 8192-char
                        // span-attribute cap above. Best-effort; never throws.
                        long fragmentEndEpochNanos = fragmentStartEpochNanos + (System.nanoTime() - startNanos);
                        int operatorSpans = FragmentTracingProbe.synthesizeOperatorSpans(
                            tracer,
                            fragmentSpan,
                            nativeMetrics,
                            fragmentStartEpochNanos,
                            fragmentEndEpochNanos
                        );
                        if (operatorSpans > 0) {
                            fragmentSpan.addAttribute("datafusion.operator_span_count", (long) operatorSpans);
                        }
                        // Distinct Lucene span: when a predicate executed via Lucene (delegation), emit
                        // one lucene.delegation child span (engine=lucene) alongside the datafusion.op.*
                        // spans, so the two engines are visually separable and the Lucene pruning cost
                        // is readable on its own bar. No-op when delegation didn't fire.
                        FragmentTracingProbe.emitLuceneDelegationSpan(
                            tracer,
                            fragmentSpan,
                            delegationCpuNanos,
                            delegationCollectCalls,
                            (long) delegatedPredicateCount,
                            filterTreeShape,
                            fragmentStartEpochNanos,
                            fragmentEndEpochNanos
                        );
                    }
                    fragmentSpan.endSpan();
                    fragmentSpanEnded = true;
                    listener.onFragmentSuccess(
                        request.getQueryId(),
                        request.getStageId(),
                        shard.shardId().toString(),
                        fragmentTookNanos,
                        shard.indexSettings(),
                        stats
                    );
                } catch (Exception e) {
                    fragmentSpan.setError(e);
                    responseHandler.onFailure(e);
                } finally {
                    // exec is no longer managed by try-with-resources (the loop was restructured to
                    // bracket produce vs send with spans), so close it here in reverse order. Closing
                    // releases the reader context + native stream/session; null if resolve threw.
                    if (exec != null) {
                        try {
                            exec.close();
                        } catch (Exception closeEx) {
                            LOGGER.warn("[FragmentExecution] error closing resolved execution for shard={}", shard.shardId(), closeEx);
                        }
                    }
                    if (fragmentSpanEnded == false) {
                        fragmentSpan.endSpan();
                    }
                }
            });
        } catch (Exception e) {
            responseHandler.onFailure(e);
        }
    }

    /**
     * QTF fetch phase: retrieves specific rows by global row ID via the backend SPI and
     * streams batches via {@link StreamingFragmentResponseHandler}. Forks onto
     * {@code executor} so the iterator drain doesn't pin the transport thread — mirrors
     * {@link #executeFragmentStreamingAsync}.
     *
     * <p>Reuses the {@link ReaderContext} opened during the query phase. If the context
     * is missing (expired before fetch arrived, or query-phase reader-store invariant
     * broken), the call fails — there is no cold-start fallback because shard-global
     * {@code __row_id__} values produced by one reader cannot be reinterpreted by
     * another (segment topology may differ across reopens).
     */
    public void executeFetchByRowIdsAsync(
        FetchByRowIdsRequest request,
        IndexShard shard,
        AnalyticsShardTask task,
        StreamingFragmentResponseHandler responseHandler,
        Executor executor
    ) {
        try {
            executor.execute(() -> drainFetchByRowIds(request, shard, task, responseHandler));
        } catch (Exception e) {
            responseHandler.onFailure(e);
        }
    }

    /**
     * Acquires the per-shard {@link ReaderContext}, materialises the rowId vector, invokes
     * the backend, drains the stream into {@code responseHandler}, and releases all resources
     * in a single try-with-resources scope. Runs on the caller's executor — exhausting the
     * iterator here lets the native engine apply backpressure when the channel is slow.
     */
    private void drainFetchByRowIds(
        FetchByRowIdsRequest request,
        IndexShard shard,
        AnalyticsShardTask task,
        StreamingFragmentResponseHandler responseHandler
    ) {
        if (task != null && task.isCancelled()) {
            responseHandler.onFailure(new TaskCancelledException("Fetch task cancelled before execution: " + task.getReasonCancelled()));
            return;
        }
        long[] rowIds = request.getRowIds();
        String[] columns = request.getColumns();
        if (rowIds == null || rowIds.length == 0 || columns == null || columns.length == 0) {
            responseHandler.onFailure(
                new IllegalArgumentException(
                    "fetch on "
                        + shard.shardId()
                        + " requires non-empty rowIds and columns; got rowIds="
                        + (rowIds == null ? "null" : rowIds.length)
                        + ", columns="
                        + (columns == null ? "null" : columns.length)
                )
            );
            return;
        }
        ReaderContext readerContext = readerContextStore.acquireContext(request.getQueryId(), shard.shardId());
        if (readerContext == null) {
            responseHandler.onFailure(
                new IllegalStateException(
                    "No ReaderContext for queryId="
                        + request.getQueryId()
                        + " on "
                        + shard.shardId()
                        + " — query phase missing or context expired"
                )
            );
            return;
        }
        assert assertFetchInvariants(readerContext, request.getQueryId());
        AnalyticsSearchBackendPlugin backend = backends.get(request.getBackendId());
        if (backend == null) {
            readerContextStore.releaseContext(request.getQueryId(), shard.shardId());
            responseHandler.onFailure(
                new IllegalStateException(
                    "No backend registered for backendId="
                        + request.getBackendId()
                        + " on "
                        + shard.shardId()
                        + "; available: "
                        + backends.keySet()
                )
            );
            return;
        }
        // Caller contract: rowIds must already be sorted ascending (RowSelection invariant on
        // native side). Asserted here so violations are caught in dev builds before the FFM call.
        assert assertAscending(rowIds);
        BigIntVector rowIdVector = null;
        FragmentResources resources = null;
        try {
            rowIdVector = new BigIntVector(DocumentInput.ROW_ID_FIELD, allocator);
            rowIdVector.allocateNew(rowIds.length);
            for (int i = 0; i < rowIds.length; i++) {
                rowIdVector.set(i, rowIds[i]);
            }
            rowIdVector.setValueCount(rowIds.length);
            EngineResultStream stream = backend.fetchByRowIds(readerContext.getReader(), rowIdVector, columns, allocator, task.getId());
            // FragmentResources keeps the rowIdVector alive until the stream drains — closing
            // it earlier would pull off-heap memory out from under the native FFM call.
            resources = new FragmentResources(readerContextStore, readerContext, null, stream, null, rowIdVector);
        } catch (Exception e) {
            if (rowIdVector != null) rowIdVector.close();
            readerContextStore.releaseContext(request.getQueryId(), shard.shardId());
            responseHandler.onFailure(new RuntimeException("Failed to execute fetch-by-row-ids on " + shard.shardId(), e));
            return;
        }
        try (FragmentResources ctx = resources) {
            Iterator<EngineResultBatch> it = ctx.stream().iterator();
            while (it.hasNext()) {
                responseHandler.onBatch(it.next());
            }
            responseHandler.onComplete();
        } catch (Exception e) {
            responseHandler.onFailure(e);
        }
    }

    /**
     * Callback interface for async fragment streaming results.
     */
    public interface StreamingFragmentResponseHandler {
        void onBatch(EngineResultBatch batch) throws Exception;

        void onComplete();

        void onFailure(Exception e);
    }

    private FragmentResources startFragment(FragmentExecutionRequest request, ResolvedFragment resolved, IndexShard shard, Task task)
        throws IOException {
        // === SETUP_PHASE_J timing — instrument each major step inside startFragment to localize delegation-on slowdown ===
        long sf_t0 = System.nanoTime();
        long sf_qid = task != null ? task.getId() : 0L;
        LOGGER.info("SETUP_PHASE_J qid={} t0=startFragment_begin", sf_qid);
        GatedCloseable<Reader> gatedReader = resolved.readerProvider.acquireReader();
        LOGGER.info("SETUP_PHASE_J qid={} after_acquireReader elapsed_ms={}", sf_qid, (System.nanoTime() - sf_t0) / 1_000_000L);
        // QTF: hand the reader to the store so the fetch phase can reuse it without re-opening.
        // FragmentResources holds a reference to the ReaderContext; close() releases it back
        // to the store, the reaper closes after keepAlive.
        ReaderContext readerContext = readerContextStore.createContext(request.getQueryId(), shard.shardId(), gatedReader);
        assert assertReaderInvariants(gatedReader, readerContext, request.getQueryId(), shard);
        SearchExecEngine<ShardScanExecutionContext, EngineResultStream> engine = null;
        EngineResultStream stream = null;
        BackendExecutionContext backendContext = null;
        Runnable trackerCleanup = null;
        org.opensearch.analytics.spi.DelegationTimings delegationTimings = null;
        try {
            ShardScanExecutionContext ctx = buildContext(request, readerContext.getReader(), resolved.plan, shard, task);
            AnalyticsSearchBackendPlugin backend = backends.get(resolved.plan.getBackendId());
            LOGGER.info("SETUP_PHASE_J qid={} after_buildContext elapsed_ms={}", sf_qid, (System.nanoTime() - sf_t0) / 1_000_000L);

            long sf_t_handlers = System.nanoTime();
            backendContext = applyInstructionHandlers(backend, resolved.plan.getInstructions(), ctx);
            LOGGER.info("SETUP_PHASE_J qid={} after_applyInstructionHandlers elapsed_ms={} step_ms={}", sf_qid, (System.nanoTime() - sf_t0) / 1_000_000L, (System.nanoTime() - sf_t_handlers) / 1_000_000L);

            // Handle exchange — if plan has delegation, ask accepting backend for handle and pass to driving
            // TODO: currently assumes single accepting backend. When multiple accepting backends exist
            // (e.g., Lucene + Tantivy), group expressions by acceptingBackendId and create one handle per group.
            DelegationDescriptor delegation = resolved.plan.getDelegationDescriptor();
            if (delegation != null) {
                // Filter delegation routes per-query state via taskId; without a task we cannot
                // isolate concurrent queries from each other. Validate before allocating any
                // delegation resources to avoid leaks.
                if (task == null) {
                    throw new IllegalStateException("Filter delegation requires a tracked task for per-query isolation");
                }
                long contextId = task.getId();

                String acceptingBackendId = delegation.delegatedExpressions().getFirst().getAcceptingBackendId();
                AnalyticsSearchBackendPlugin acceptingBackend = backends.get(acceptingBackendId);
                long sf_t_handle = System.nanoTime();
                FilterDelegationHandle handle = acceptingBackend.getFilterDelegationHandle(delegation.delegatedExpressions(), ctx);
                LOGGER.info("SETUP_PHASE_J qid={} after_getFilterDelegationHandle elapsed_ms={} step_ms={}", sf_qid, (System.nanoTime() - sf_t0) / 1_000_000L, (System.nanoTime() - sf_t_handle) / 1_000_000L);

                // Build a thread tracker when task resource tracking is available.
                DelegationThreadTracker tracker = null;
                if (taskResourceTrackingService != null) {
                    long taskId = task.getId();
                    TaskResourceTrackingService service = taskResourceTrackingService;
                    tracker = new DelegationThreadTracker() {
                        @Override
                        public long trackStart() {
                            long threadId = Thread.currentThread().threadId();
                            service.taskExecutionStartedOnThread(taskId, threadId);
                            return threadId;
                        }

                        @Override
                        public void trackEnd(long threadId) {
                            service.taskExecutionFinishedOnThread(taskId, threadId);
                        }
                    };
                }

                // T2: accumulate Lucene delegation CPU-time across the per-segment collectDocs
                // upcalls so the fragment span can report filter.delegation_cpu_nanos / _collect_calls.
                // Only when tracing is on (the upcall path is hot — thousands of calls — so the sink
                // is null otherwise and the upcall pays nothing).
                if (tracer != NoopTracer.INSTANCE) {
                    delegationTimings = new org.opensearch.analytics.spi.DelegationTimings();
                }

                // Register handle and tracker together under the query's contextId so concurrent
                // queries have isolated FFM callback bindings. The returned cleanup removes the
                // binding after query execution completes.
                trackerCleanup = backend.configureFilterDelegation(contextId, handle, tracker, backendContext, delegationTimings);
            }

            long sf_t_engine = System.nanoTime();
            engine = backend.getSearchExecEngineProvider().createSearchExecEngine(ctx, backendContext);
            LOGGER.info("SETUP_PHASE_J qid={} after_createSearchExecEngine elapsed_ms={} step_ms={}", sf_qid, (System.nanoTime() - sf_t0) / 1_000_000L, (System.nanoTime() - sf_t_engine) / 1_000_000L);
            long sf_t_exec = System.nanoTime();
            stream = engine.execute(ctx);
            LOGGER.info("SETUP_PHASE_J qid={} after_engine_execute elapsed_ms={} step_ms={}", sf_qid, (System.nanoTime() - sf_t0) / 1_000_000L, (System.nanoTime() - sf_t_exec) / 1_000_000L);
            FragmentResources fr = new FragmentResources(readerContextStore, readerContext, engine, stream, trackerCleanup);
            fr.setDelegationTimings(delegationTimings);
            return fr;
        } catch (Exception e) {
            LOGGER.error(
                () -> new org.apache.logging.log4j.message.ParameterizedMessage(
                    "startFragment failed [queryId={}, stageId={}, shardId={}]",
                    resolved.queryId,
                    resolved.stageId,
                    resolved.shardIdStr
                ),
                e
            );
            try {
                new FragmentResources(readerContextStore, readerContext, engine, stream, trackerCleanup).close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            // Close the backend execution context as a safety net for failure paths that
            // never reached / never finished the engine construction — if the handle was
            // already transferred, close() is a no-op (implementations must be idempotent).
            if (backendContext != null) {
                try {
                    backendContext.close();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }

    /**
     * Applies each instruction handler in order. Each handler reads the previous handler's
     * {@link BackendExecutionContext} and returns the next one. Returns {@code null} when the
     * instruction list is empty.
     */
    private static BackendExecutionContext applyInstructionHandlers(
        AnalyticsSearchBackendPlugin backend,
        List<InstructionNode> instructions,
        ShardScanExecutionContext ctx
    ) {
        if (instructions.isEmpty()) return null;
        FragmentInstructionHandlerFactory factory = backend.getInstructionHandlerFactory();
        BackendExecutionContext backendContext = null;
        for (InstructionNode node : instructions) {
            FragmentInstructionHandler handler = factory.createHandler(node);
            backendContext = handler.apply(node, ctx, backendContext);
        }
        return backendContext;
    }

    private record ResolvedFragment(IndexReaderProvider readerProvider, FragmentExecutionRequest.PlanAlternative plan, String queryId,
        int stageId, String shardIdStr) {
    }

    private ResolvedFragment resolveFragment(FragmentExecutionRequest request, IndexShard shard) {
        IndexReaderProvider readerProvider = shard.getReaderProvider();
        if (readerProvider == null) {
            throw new IllegalStateException("No ReaderProvider on " + shard.shardId());
        }

        // Backend selection happens on the coordinator (PlanAlternativeSelector), so the
        // request typically carries a single alternative. We still iterate to handle the
        // case where a stage genuinely has multiple value-producing alternatives — pick the
        // first one whose backend is registered locally.
        FragmentExecutionRequest.PlanAlternative selectedPlan = null;
        for (FragmentExecutionRequest.PlanAlternative alt : request.getPlanAlternatives()) {
            if (backends.containsKey(alt.getBackendId())) {
                selectedPlan = alt;
                break;
            }
        }
        if (selectedPlan == null) {
            throw new IllegalArgumentException(
                "No plan alternative matches available backends. Alternatives: "
                    + request.getPlanAlternatives().stream().map(FragmentExecutionRequest.PlanAlternative::getBackendId).toList()
                    + ". Available: "
                    + backends.keySet()
            );
        }

        String shardIdStr = shard.shardId().toString();
        listener.onPreFragmentExecution(request.getQueryId(), request.getStageId(), shardIdStr);
        return new ResolvedFragment(readerProvider, selectedPlan, request.getQueryId(), request.getStageId(), shardIdStr);
    }

    private ShardScanExecutionContext buildContext(
        FragmentExecutionRequest request,
        Reader reader,
        FragmentExecutionRequest.PlanAlternative plan,
        IndexShard shard,
        Task task
    ) {
        // Fallback table name only — the backend derives the actual registration name from the
        // Substrait fragment's NamedTable (which carries the planner's logical alias/pattern name),
        // so this concrete shard index name is used only when no plan is supplied.
        String tableName = request.getShardId().getIndexName();
        ShardScanExecutionContext ctx = new ShardScanExecutionContext(tableName, task, reader);
        ctx.setFragmentBytes(plan.getFragmentBytes());
        ctx.setAllocator(allocator);
        ctx.setMapperService(shard.mapperService());
        ctx.setIndexSettings(shard.indexSettings());
        ctx.setNamedWriteableRegistry(namedWriteableRegistry);
        ctx.setQueryCache(shard.getQueryCache());
        ctx.setQueryCachingPolicy(shard.getQueryCachingPolicy());
        ctx.setShardId(shard.shardId());
        return ctx;
    }

    // ── Assertion helpers (invoked only when -ea is enabled; bodies are dead in production) ──

    private static boolean assertReaderInvariants(
        GatedCloseable<Reader> gatedReader,
        ReaderContext readerContext,
        String queryId,
        IndexShard shard
    ) {
        if (gatedReader == null) {
            throw new AssertionError("acquireReader returned null for shard " + shard.shardId());
        }
        if (readerContext == null) {
            throw new AssertionError("createContext returned null for queryId=" + queryId);
        }
        if (readerContext.getReader() == null) {
            throw new AssertionError("ReaderContext returned null reader for queryId=" + queryId);
        }
        return true;
    }

    private boolean assertFetchInvariants(ReaderContext readerContext, String queryId) {
        if (readerContext.getReader() == null) {
            throw new AssertionError("acquired ReaderContext has null reader for queryId=" + queryId);
        }
        if (backends.isEmpty()) {
            throw new AssertionError("no backends registered — service constructor invariant violated");
        }
        return true;
    }

    private static boolean assertAscending(long[] values) {
        for (int i = 1; i < values.length; i++) {
            if (values[i] < values[i - 1]) {
                throw new AssertionError("rowIds not ascending at index " + i + ": " + values[i - 1] + " > " + values[i]);
            }
        }
        return true;
    }

}
