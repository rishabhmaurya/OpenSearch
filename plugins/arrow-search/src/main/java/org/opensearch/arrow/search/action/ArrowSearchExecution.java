/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.action;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.TotalHits;
import org.opensearch.ExceptionsHelper;
import org.opensearch.OpenSearchException;
import org.opensearch.action.search.SearchTask;
import org.opensearch.action.search.TransportSearchAction;
import org.opensearch.arrow.search.ArrowBatches;
import org.opensearch.arrow.search.ArrowSearchPlugin;
import org.opensearch.arrow.search.scan.ArrowScanBatchResponse;
import org.opensearch.arrow.search.scan.ArrowScanSpec;
import org.opensearch.arrow.search.scan.ArrowShardStreamRequest;
import org.opensearch.arrow.search.scan.ShardSummary;
import org.opensearch.arrow.transport.ArrowBatchResponseHandler;
import org.opensearch.arrow.transport.VectorTransfer;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.Nullable;
import org.opensearch.common.lease.Releasable;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.tasks.TaskCancelledException;
import org.opensearch.search.SearchHits;
import org.opensearch.search.SearchPhaseResult;
import org.opensearch.search.SearchShardTarget;
import org.opensearch.search.internal.InternalSearchResponse;
import org.opensearch.search.internal.ShardSearchRequest;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.stream.StreamErrorCode;
import org.opensearch.transport.stream.StreamException;
import org.opensearch.transport.stream.StreamTransportResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Runs the shard phase of an Arrow search inside the regular search coordination: for each shard copy the search picks, it
 * opens a stream on the copy's node and relays every batch to the sink.
 * <p>
 * The search's routing, throttling and failover decide which copies are tried, with one rule added: a shard that relayed
 * rows is never retried, because its rows cannot be taken back. Its failure, and any failure the request cannot recover
 * from, stops the whole search at once. Reaching the row limit also stops every stream, but the search succeeds.
 */
final class ArrowSearchExecution implements TransportSearchAction.SinglePhaseSearchAction {

    private static final Logger logger = LogManager.getLogger(ArrowSearchExecution.class);

    /** Cancels the search's tasks on other nodes. */
    @FunctionalInterface
    interface ChildTaskCanceller {
        /**
         * Cancels the search's tasks on every node, but not the search task itself.
         *
         * @param reason  why the search stopped
         * @param opening the nodes of the shard streams still opening, as of each call: their requests may reach their nodes
         *                after the cancel did, so these nodes need cancelling again until none is left
         */
        void cancel(String reason, Supplier<Set<String>> opening);
    }

    /** Why the search stopped early. The first reason wins. */
    enum Stop {
        NONE,
        ROW_LIMIT,
        FAILED,
        CANCELLED
    }

    private final SearchTask task;
    private final ArrowBatchSink sink;
    private final ArrowScanSpec spec;
    private final ShardStreamClient client;
    private final String executorName;
    private final Executor executor;
    private final BufferAllocator relayAllocator;
    private final CoordinatorBudget.Reservation reservation;
    private final long batchMemory;
    private final ShardStreamScheduler scheduler;
    private final IntSupplier streamLimit;
    private final Object streamLimitLock = new Object();
    private volatile boolean streamsLimited;
    private final boolean allowPartialResults;
    private final ChildTaskCanceller cancelChildren;

    private final AtomicLong rowsRemaining;
    private final AtomicReference<Stop> stop = new AtomicReference<>(Stop.NONE);
    private final AtomicReference<Exception> fatal = new AtomicReference<>();
    private final Set<ShardId> relayedShards = ConcurrentHashMap.newKeySet();
    private final Set<ShardStream> streams = ConcurrentHashMap.newKeySet();

    /**
     * @param task                the search task, parent of the shard streams
     * @param sink                receives the batches
     * @param spec                the scan every shard runs
     * @param client              opens shard streams
     * @param executorName        the virtual-thread executor relays run on
     * @param executor            that executor
     * @param relayAllocator      owns relayed batches until the sink has sent them
     * @param reservation         the search's coordinator memory, which every relayed batch holds until the sink closes it
     * @param batchMemory         the memory a relayed batch may take, which it counts as once the search ended
     * @param maxOpenStreams      how many shard streams may be open at once
     * @param streamLimit         trims the reservation to the streams the search may open at once, and returns how many, at
     *                            most {@code maxOpenStreams}; called once, before the first stream opens, when the search's
     *                            request listeners, such as workload management, have set its
     *                            {@code max_concurrent_shard_requests}
     * @param rowLimit            the most rows to relay
     * @param allowPartialResults whether shards may fail without failing the search
     * @param cancelChildren      cancels the search's child tasks on every node, but not the search task
     */
    ArrowSearchExecution(
        SearchTask task,
        ArrowBatchSink sink,
        ArrowScanSpec spec,
        ShardStreamClient client,
        String executorName,
        Executor executor,
        BufferAllocator relayAllocator,
        CoordinatorBudget.Reservation reservation,
        long batchMemory,
        int maxOpenStreams,
        IntSupplier streamLimit,
        long rowLimit,
        boolean allowPartialResults,
        ChildTaskCanceller cancelChildren
    ) {
        this.task = task;
        this.sink = sink;
        this.spec = spec;
        this.client = client;
        this.executorName = executorName;
        this.executor = executor;
        this.relayAllocator = relayAllocator;
        this.reservation = reservation;
        this.batchMemory = batchMemory;
        this.scheduler = new ShardStreamScheduler(maxOpenStreams, executor);
        this.streamLimit = streamLimit;
        this.rowsRemaining = new AtomicLong(rowLimit);
        this.allowPartialResults = allowPartialResults;
        this.cancelChildren = cancelChildren;
    }

    @Override
    public void executeOnShardTarget(
        SearchTask searchTask,
        SearchShardTarget target,
        Transport.Connection connection,
        ShardSearchRequest request,
        ActionListener<SearchPhaseResult> listener
    ) {
        final DiscoveryNode node = connection.getNode();
        final ShardStream stream = new ShardStream(target, node, request, listener);
        if (node.getStreamAddress() == null || node.getAttributes().containsKey(ArrowSearchPlugin.NODE_ATTRIBUTE) == false) {
            // Before anything is relayed, so the search tries the next copy.
            executor.execute(
                () -> stream.complete(
                    // Only the node's name and id: its description holds addresses and attributes the caller must not see.
                    new StreamException(
                        StreamErrorCode.UNAVAILABLE,
                        "node [" + node.getName() + "][" + node.getId() + "] does not serve Arrow search shard streams"
                    )
                )
            );
            return;
        }
        limitStreams();
        scheduler.submit(node.getId(), stream);
    }

    /**
     * Lowers the open stream limit to what the search's final request allows, once, before the first stream opens.
     */
    private void limitStreams() {
        if (streamsLimited) {
            return;
        }
        synchronized (streamLimitLock) {
            if (streamsLimited == false) {
                scheduler.limit(streamLimit.getAsInt());
                streamsLimited = true;
            }
        }
    }

    @Override
    public boolean shouldTryNextCopy(SearchShardTarget target, Exception failure) {
        if (relayedShards.contains(target.getShardId())) {
            return false;
        }
        final Stop reason = stop.get();
        if (reason == Stop.ROW_LIMIT) {
            // The next copy is never opened: it ends at once as a shard the row limit stopped, which succeeds.
            return true;
        }
        return reason == Stop.NONE && isDeterministic(failure) == false;
    }

    @Override
    public void onShardGroupFailure(int shardIndex, @Nullable SearchShardTarget target, Exception failure) {
        if (stop.get() != Stop.NONE) {
            return;
        }
        final boolean relayed = target != null && relayedShards.contains(target.getShardId());
        if (relayed || isDeterministic(failure) || allowPartialResults == false) {
            stop(Stop.FAILED, failure);
        }
    }

    /**
     * A bad request fails on every copy alike, and silently skipping a shard of an export because its data cannot be read
     * would lose rows, so such failures end the search.
     */
    private static boolean isDeterministic(Exception failure) {
        if (ExceptionsHelper.status(failure) != RestStatus.BAD_REQUEST) {
            return false;
        }
        // A cancellation on the data node, e.g. by search backpressure, says nothing about the other copies.
        final StreamException streamFailure = (StreamException) ExceptionsHelper.unwrap(failure, StreamException.class);
        final boolean cancelled = ExceptionsHelper.unwrap(failure, TaskCancelledException.class) != null
            || (streamFailure != null && streamFailure.getErrorCode() == StreamErrorCode.CANCELLED);
        return cancelled == false;
    }

    @Override
    public InternalSearchResponse buildResponse(List<SearchPhaseResult> results) {
        final Exception failure = fatal.get();
        if (failure != null) {
            throw failure instanceof RuntimeException runtime ? runtime : new OpenSearchException(failure);
        }
        if (stop.get() == Stop.CANCELLED) {
            throw cancelled();
        }
        long rows = 0;
        boolean truncated = false;
        boolean timedOut = false;
        boolean terminatedEarly = false;
        for (SearchPhaseResult result : results) {
            if (result instanceof ArrowShardResult shard) {
                rows += shard.rows();
                truncated |= shard.truncated();
                timedOut |= shard.timedOut();
                terminatedEarly |= shard.terminatedEarly();
            }
        }
        final TotalHits.Relation relation = truncated || timedOut || terminatedEarly
            ? TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO
            : TotalHits.Relation.EQUAL_TO;
        return new InternalSearchResponse(
            new SearchHits(SearchHits.EMPTY, new TotalHits(rows, relation), Float.NaN),
            null,
            null,
            null,
            timedOut,
            truncated || terminatedEarly,
            1
        );
    }

    @Override
    public boolean allowShardSkipping() {
        return true;
    }

    @Override
    public boolean applySearchRequestWrappers() {
        return true;
    }

    /**
     * @return the failure the search ended with: the one that stopped it, if any, otherwise {@code failure}
     */
    Exception failureFor(Exception failure) {
        final Exception stopped = fatal.get();
        if (stopped != null) {
            return stopped;
        }
        return stop.get() == Stop.CANCELLED ? cancelled() : failure;
    }

    Stop stopReason() {
        return stop.get();
    }

    /**
     * Stops the search: open streams are cancelled, queued ones abandoned, and on failure or cancellation the sink fails
     * and the search's tasks on other nodes are cancelled. Safe to call from any thread; only the first call has effect,
     * except that a failure overrides reaching the row limit.
     *
     * @return whether this call stopped the search
     */
    boolean stop(Stop reason, @Nullable Exception cause) {
        assert reason != Stop.NONE;
        // A failure after the row limit, such as the client going away before it read the last rows, still fails the search.
        if (stop.compareAndSet(Stop.NONE, reason) == false
            && (reason != Stop.FAILED || stop.compareAndSet(Stop.ROW_LIMIT, reason) == false)) {
            return false;
        }
        if (reason == Stop.FAILED) {
            fatal.compareAndSet(null, cause);
        }
        logger.debug("arrow search [{}] stopped: {}", task.getId(), reason);
        scheduler.close();
        for (ShardStream stream : streams) {
            stream.cancel(reason.name());
        }
        if (reason != Stop.ROW_LIMIT) {
            sink.fail(failureFor(cause));
        }
        // Reaches the shard streams that are still opening, which the cancel above cannot, until none is left.
        cancelChildren.cancel("arrow search " + reason.name().toLowerCase(Locale.ROOT), this::openingStreamNodes);
        return true;
    }

    /**
     * @return the nodes of the shard streams whose request was sent but whose first batch has not arrived, which only a cancel
     *         of their tasks reaches
     */
    private Set<String> openingStreamNodes() {
        final Set<String> nodes = new HashSet<>();
        for (ShardStream stream : streams) {
            if (stream.response == null) {
                nodes.add(stream.node.getId());
            }
        }
        return nodes;
    }

    private TaskCancelledException cancelled() {
        final String reason = task.getReasonCancelled();
        return new TaskCancelledException("the arrow search was cancelled" + (reason == null ? "" : ": " + reason));
    }

    /** @return how many rows of {@code rows} may still be relayed, claiming them */
    private long claimRows(long rows) {
        while (true) {
            final long remaining = rowsRemaining.get();
            if (remaining == 0) {
                return 0;
            }
            final long granted = Math.min(remaining, rows);
            if (rowsRemaining.compareAndSet(remaining, remaining - granted)) {
                return granted;
            }
        }
    }

    /**
     * A relayed batch, which holds the search's reservation until it is closed. The sink closes it once the client's transport
     * released its memory, which can be after the search ended, so that memory counts against the coordinator until then.
     */
    private static final class RelayedBatch extends VectorSchemaRoot {
        private final Releasable hold;

        private RelayedBatch(Schema schema, List<FieldVector> vectors, Releasable hold) {
            super(schema, vectors, 0);
            this.hold = hold;
        }

        static RelayedBatch create(Schema schema, BufferAllocator allocator, CoordinatorBudget.Reservation reservation, long maxBytes) {
            final Releasable hold = reservation.holdBatch(maxBytes);
            if (hold == null) {
                throw new IllegalStateException("the arrow search already ended");
            }
            boolean success = false;
            try {
                final List<FieldVector> vectors = new ArrayList<>(schema.getFields().size());
                for (Field field : schema.getFields()) {
                    vectors.add(field.createVector(allocator));
                }
                final RelayedBatch batch = new RelayedBatch(schema, vectors, hold);
                success = true;
                return batch;
            } finally {
                if (success == false) {
                    hold.close();
                }
            }
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                hold.close();
            }
        }
    }

    /**
     * The stream of one shard copy: opened on its node, relayed until exhausted, then reported to the search exactly once.
     */
    private final class ShardStream extends ArrowBatchResponseHandler<ArrowScanBatchResponse> implements ShardStreamScheduler.Launch {
        private final SearchShardTarget target;
        private final DiscoveryNode node;
        private final ShardSearchRequest request;
        private final ActionListener<SearchPhaseResult> listener;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile StreamTransportResponse<ArrowScanBatchResponse> response;
        private long rows;
        private boolean cutShort;
        private ShardSummary summary;

        ShardStream(SearchShardTarget target, DiscoveryNode node, ShardSearchRequest request, ActionListener<SearchPhaseResult> listener) {
            this.target = target;
            this.node = node;
            this.request = request;
            this.listener = listener;
        }

        @Override
        public void run() {
            streams.add(this);
            if (stop.get() != Stop.NONE) {
                end(null);
                return;
            }
            try {
                client.open(node, new ArrowShardStreamRequest(request, spec), task, this);
            } catch (Exception e) {
                end(e);
            }
        }

        @Override
        public void abandon() {
            complete(null);
        }

        @Override
        public ArrowScanBatchResponse read(StreamInput in) throws IOException {
            return new ArrowScanBatchResponse(in);
        }

        @Override
        public String executor() {
            return executorName;
        }

        @Override
        public void handleStreamResponse(StreamTransportResponse<ArrowScanBatchResponse> stream) {
            response = stream;
            Exception failure = null;
            boolean drained = false;
            try {
                // A stop that missed this stream in its cancel sweep is seen here: the stream is not read, but cancelled below.
                ArrowScanBatchResponse batch;
                while (stop.get() == Stop.NONE && (batch = stream.nextResponse()) != null) {
                    relay(batch);
                }
                drained = stop.get() == Stop.NONE;
            } catch (Exception e) {
                failure = e;
            } finally {
                try {
                    if (drained == false) {
                        stream.cancel("arrow search stopped reading the shard", null);
                    }
                    stream.close();
                } catch (Exception e) {
                    logger.debug("failed to close the stream of shard {}: {}", target.getShardId(), e.getMessage());
                }
                end(failure);
            }
        }

        @Override
        public void handleException(TransportException exp) {
            end(exp);
        }

        private void relay(ArrowScanBatchResponse batch) throws Exception {
            final VectorSchemaRoot received = batch.getRoot();
            try {
                if (batch.getMetadata() != null) {
                    summary = ShardSummary.decode(batch.getMetadata());
                }
                final int batchRows = received.getRowCount();
                if (batchRows == 0) {
                    return;
                }
                final long granted = claimRows(batchRows);
                if (granted < batchRows) {
                    cutShort = true;
                }
                if (granted == 0) {
                    stop(Stop.ROW_LIMIT, null);
                    return;
                }
                // The relay's own allocator owns the batch while the client reads it, so it outlives the stream, and so may
                // the search's reservation, which the batch holds until then.
                final VectorSchemaRoot relayed = RelayedBatch.create(received.getSchema(), relayAllocator, reservation, batchMemory);
                try {
                    VectorTransfer.transferRoot(received, relayed);
                    if (granted < batchRows) {
                        ArrowBatches.setRowCount(relayed, (int) granted);
                    }
                } catch (RuntimeException e) {
                    relayed.close();
                    throw e;
                }
                // Marked before sending: once any row may have reached the client the shard must not be retried.
                relayedShards.add(target.getShardId());
                rows += granted;
                try {
                    sink.sendBatch(relayed);
                } catch (Exception e) {
                    stop(Stop.FAILED, e);
                    throw e;
                }
                if (rowsRemaining.get() == 0) {
                    stop(Stop.ROW_LIMIT, null);
                }
            } finally {
                received.close();
            }
        }

        void cancel(String reason) {
            final StreamTransportResponse<ArrowScanBatchResponse> open = response;
            if (open != null) {
                open.cancel("arrow search stopped: " + reason, null);
            }
        }

        /** Ends a stream that held a scheduler slot. */
        private void end(@Nullable Exception failure) {
            if (ended.compareAndSet(false, true) == false) {
                return;
            }
            streams.remove(this);
            scheduler.release(node.getId());
            complete(failure);
        }

        /** Reports the shard to the search, exactly once. */
        void complete(@Nullable Exception failure) {
            if (completed.compareAndSet(false, true) == false) {
                return;
            }
            final Stop reason = stop.get();
            try {
                if (reason == Stop.ROW_LIMIT) {
                    // The rows the search needs have been relayed, so however this stream ended, the shard succeeded. It
                    // is complete only if it sent its summary before the limit cut it short.
                    listener.onResponse(shardResult(cutShort || summary == null));
                } else if (failure != null) {
                    // Decided here, where the stop reason was read, so a row limit reached meanwhile cannot turn a shard that
                    // must fail the search into one that is retried.
                    if (reason == Stop.NONE && (relayedShards.contains(target.getShardId()) || isDeterministic(failure))) {
                        stop(Stop.FAILED, failure);
                    }
                    listener.onFailure(failure);
                } else if (reason != Stop.NONE) {
                    listener.onFailure(failureFor(new IllegalStateException("arrow search stopped")));
                } else if (summary == null) {
                    listener.onFailure(
                        new StreamException(
                            StreamErrorCode.INTERNAL,
                            "the stream of shard " + target.getShardId() + " ended without a summary"
                        )
                    );
                } else {
                    listener.onResponse(shardResult(false));
                }
            } catch (Exception e) {
                logger.warn(() -> "failed to complete shard " + target.getShardId() + " of arrow search [" + task.getId() + "]", e);
            }
        }

        private ArrowShardResult shardResult(boolean truncated) {
            final boolean shardTimedOut = summary != null && summary.timedOut();
            final boolean shardTerminatedEarly = summary != null && summary.terminatedEarly();
            return new ArrowShardResult(rows, truncated, shardTimedOut, shardTerminatedEarly);
        }
    }
}
