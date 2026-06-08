/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec.stage.shard;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.opensearch.arrow.transport.VectorTransfer;
import org.opensearch.analytics.backend.ExchangeSource;
import org.opensearch.analytics.exec.AnalyticsSearchTransportService;
import org.opensearch.analytics.exec.QueryContext;
import org.opensearch.analytics.exec.StreamingResponseListener;
import org.opensearch.analytics.exec.action.FragmentExecutionArrowResponse;
import org.opensearch.analytics.exec.action.FragmentExecutionRequest;
import org.opensearch.analytics.exec.stage.AbstractStageExecution;
import org.opensearch.analytics.exec.stage.DataProducer;
import org.opensearch.analytics.exec.stage.StageTask;
import org.opensearch.analytics.exec.stage.StageTaskId;
import org.opensearch.analytics.planner.dag.ExecutionTarget;
import org.opensearch.analytics.planner.dag.ShardExecutionTarget;
import org.opensearch.analytics.planner.dag.Stage;
import org.opensearch.analytics.spi.ExchangeSink;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.action.ActionListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Leaf stage: dispatches fragment work to data-node shards via Arrow streaming,
 * one {@link StageTask} per resolved target. Transport owned by {@link ShardTaskRunner};
 * data-arrival behavior by {@link #responseListenerFor}.
 *
 * @opensearch.internal
 */
public class ShardFragmentStageExecution extends AbstractStageExecution implements DataProducer {

    private final QueryContext config;
    private final ExchangeSink outputSink;
    private final ClusterService clusterService;
    // Per-stage cap counter for reduce-feed batch spans. Inbound batches arrive concurrently from
    // many shards on per-stream virtual threads, so this is atomic; once it reaches the cap we stop
    // opening feed spans (the stage span still records the totals).
    private final java.util.concurrent.atomic.AtomicLong reduceFeedSpanCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong reduceFeedOrdinal = new java.util.concurrent.atomic.AtomicLong();

    public ShardFragmentStageExecution(
        Stage stage,
        QueryContext config,
        ExchangeSink outputSink,
        ClusterService clusterService,
        Function<ShardExecutionTarget, FragmentExecutionRequest> requestBuilder,
        AnalyticsSearchTransportService dispatcher
    ) {
        super(stage, config.queryId(), config.operationListeners(), config.parentTask());
        this.config = config;
        this.outputSink = outputSink;
        this.clusterService = clusterService;
        this.runner = new ShardTaskRunner(this, config, dispatcher, requestBuilder);
    }

    @Override
    protected List<StageTask> materializeTasks() {
        List<ExecutionTarget> resolved = stage.getTargetResolver().resolve(clusterService.state(), null);
        // Empty list → base short-circuits to SUCCEEDED (nothing to dispatch).
        List<StageTask> tasks = new ArrayList<>(resolved.size());
        List<ShardExecutionTarget> shardTargets = new ArrayList<>(resolved.size());
        for (int i = 0; i < resolved.size(); i++) {
            ExecutionTarget target = resolved.get(i);
            tasks.add(new ShardStageTask(new StageTaskId(getStageId(), i), target));
            shardTargets.add((ShardExecutionTarget) target);
        }
        // Side-table for cross-stage routing (e.g. QTF Phase C maps ___ugsi → target).
        // See QueryContext.resolvedTargetsByStage Javadoc for HACK rationale.
        config.recordResolvedTargets(getStageId(), shardTargets);
        return tasks;
    }

    /**
     * Replica failover: on dispatch failure, advance to the next copy of the same shard via
     * {@link ShardExecutionTarget#nextCopy(Exception)}, which delegates to
     * {@link org.opensearch.cluster.routing.FailAwareWeightedRouting#findNext} — same iterator
     * walk + weighted-routing skip + fail-open semantics the search API uses in
     * {@code AbstractSearchAsyncAction.onShardFailure}.
     *
     * <p>Returns empty when the iterator is exhausted; the scheduler then propagates the cause
     * via {@code onTaskTerminal} and the stage fails. Cancellation short-circuit lives one
     * layer up in {@code QueryScheduler.handleFor} — it applies uniformly to every stage type.
     */
    @Override
    public Optional<StageTask> retargetForRetry(StageTask failed, Exception cause) {
        if (!(failed instanceof ShardStageTask shardTask)) {
            return Optional.empty();
        }
        if (!(shardTask.target() instanceof ShardExecutionTarget shardTarget)) {
            return Optional.empty();
        }
        ShardExecutionTarget nextCopy = shardTarget.nextCopy(cause);
        if (nextCopy == null) {
            return Optional.empty();
        }
        return Optional.of(new ShardStageTask(shardTask.id(), nextCopy));
    }

    // FOLLOW-UP: per-stage cancel granularity. Today AbstractStageExecution.cancel cancels
    // the whole parent task (via ct.cancel) to terminate in-flight data-node Flight streams.
    // That's coarse — fine for current query shapes (one failure means the query fails) but
    // it masks the real failure cause as "TaskCancelledException" in QueryExecution.terminalCause,
    // and forecloses speculative-execution / per-stage abort. Surgical alternative: track
    // per-task child-task-ids in ShardTaskRunner; cancel just those when this stage's
    // onTerminalTransition fires CANCELLED.

    @Override
    public ExchangeSource outputSource() {
        if (outputSink instanceof ExchangeSource source) {
            return source;
        }
        throw new UnsupportedOperationException("outputSink does not implement ExchangeSource");
    }

    /**
     * Runs inline on the per-stream virtual thread driving handleStreamResponse — must NOT
     * offload: reordering would let isLast race ahead and drop earlier batches via the
     * stage-terminal short-circuit. Inline also preserves end-to-end backpressure.
     */
    StreamingResponseListener<FragmentExecutionArrowResponse> responseListenerFor(int sourceOrdinal, ActionListener<Void> listener) {
        return new StreamingResponseListener<>() {
            @Override
            public boolean onStreamResponse(FragmentExecutionArrowResponse response, boolean isLast) {
                VectorSchemaRoot vsr = response.getRoot();
                if (getState().isTerminal()) {
                    if (vsr != null) vsr.close();
                    return false; // stage already settled — stop draining, let the caller cancel the stream
                }
                if (vsr == null) {
                    if (isLast) listener.onResponse(null);
                    return true;
                }
                // M1: retarget the inbound batch from the Flight transport pool onto the per-query
                // allocator so QueryContext.bufferAllocator().getPeakMemoryAllocation() accounts for
                // inbound shard bytes (otherwise it undercounts — those buffers land on POOL_FLIGHT).
                // Zero-copy: the query child and POOL_FLIGHT share the one node RootAllocator, so
                // Arrow's associate() passes and transferRoot moves the ledger without a memcpy.
                final VectorSchemaRoot queryOwned;
                try {
                    queryOwned = retargetToQueryAllocator(vsr);
                } catch (Throwable t) {
                    // Transfer failed before ownership moved: vsr buffers still live on the inbound
                    // (Flight) root, so close vsr here, then surface.
                    RuntimeException wrapped = new RuntimeException("Stage " + getStageId() + " failed retargeting batch to query allocator", t);
                    try {
                        vsr.close();
                    } catch (IllegalStateException closeFailure) {
                        wrapped.addSuppressed(closeFailure);
                    }
                    listener.onFailure(wrapped);
                    return false;
                }
                // T2 reduce-feed span (handoff timing) bracketed via recordFeedOperation — see its
                // javadoc for what feed time means vs reduce-compute. Feed control flow stays inline.
                final long feedOrdinal = reduceFeedOrdinal.getAndIncrement();
                final long feedStartNanos = System.nanoTime();
                Exception feedError = null;
                try {
                    outputSink.feed(queryOwned, sourceOrdinal);
                } catch (Exception e) {
                    feedError = e;
                }
                recordFeedOperation(feedOrdinal, queryOwned, sourceOrdinal, System.nanoTime() - feedStartNanos, feedError);
                if (feedError != null) {
                    // Sink didn't take ownership — close the VSR before surfacing.
                    RuntimeException wrapped = new RuntimeException("Stage " + getStageId() + " sink feed failed", feedError);
                    try {
                        queryOwned.close();
                    } catch (IllegalStateException closeFailure) {
                        wrapped.addSuppressed(closeFailure);
                    }
                    listener.onFailure(wrapped);
                    return false;
                }
                metrics.addRowsProcessed(queryOwned.getRowCount());
                // Downstream consumer satisfied (e.g. a LimitExec above the reduce finished and dropped
                // this input's receiver). Settle this task as success and tell the caller to cancel the
                // stream so this shard stops scanning instead of feeding batches that will be discarded.
                // Each input reacts independently on its own stream.
                if (outputSink.isConsumerDone()) {
                    listener.onResponse(null);
                    return false;
                }
                if (isLast) listener.onResponse(null);
                return true;
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(new RuntimeException("Stage " + getStageId() + " failed", e));
            }
        };
    }

    /**
     * T2 reduce-feed span for one inbound shard batch fed into the reducer. Measures the FEED
     * (handoff) time — for the streaming DataFusion reduce that is an enqueue into a bounded native
     * mpsc, so the duration is ~0 with channel room and GROWS under backpressure when the reducer
     * lags the inbound rate. It is NOT reduce-compute time (the FINAL aggregation runs async on the
     * REDUCE thread). Capped at {@link QueryContext#MAX_BATCH_SPANS} per stage; the counter (atomic,
     * concurrent feeds) advances only on a clean feed. Null/noop-safe; no-op when tracing is off.
     */
    private void recordFeedOperation(long feedOrdinal, VectorSchemaRoot batch, int sourceOrdinal, long feedNanos, Exception feedError) {
        if (reduceFeedSpanCount.get() >= QueryContext.MAX_BATCH_SPANS) {
            return;
        }
        org.opensearch.telemetry.tracing.Span feedSpan = config.startReduceFeedSpan(feedOrdinal);
        if (feedSpan == null) {
            return;
        }
        if (feedError != null) {
            feedSpan.setError(feedError);
        } else {
            feedSpan.addAttribute("batch.rows", (long) batch.getRowCount());
            feedSpan.addAttribute("source.ordinal", (long) sourceOrdinal);
            feedSpan.addAttribute("batch.feed_nanos", feedNanos);
        }
        feedSpan.endSpan();
        if (feedError == null) {
            reduceFeedSpanCount.incrementAndGet();
        }
    }

    /**
     * Zero-copy moves {@code inbound} (on the Flight transport pool) onto a root owned by the
     * per-query Arrow allocator, returning the query-owned root. After the transfer the inbound
     * root is empty and is closed here; the returned root carries the buffers and the row count.
     * Same node {@code RootAllocator} ⇒ Arrow {@code associate()} passes and {@code transferRoot}
     * forceAllocates onto (and raises the peak of) the per-query allocator with no memcpy.
     */
    private VectorSchemaRoot retargetToQueryAllocator(VectorSchemaRoot inbound) {
        BufferAllocator queryAllocator = config.bufferAllocator();
        VectorSchemaRoot queryOwned = VectorSchemaRoot.create(inbound.getSchema(), queryAllocator);
        try {
            VectorTransfer.transferRoot(inbound, queryOwned);
        } catch (Throwable t) {
            queryOwned.close();
            throw t;
        }
        inbound.close(); // now empty (buffers moved); release the Flight-pool root
        return queryOwned;
    }
}
