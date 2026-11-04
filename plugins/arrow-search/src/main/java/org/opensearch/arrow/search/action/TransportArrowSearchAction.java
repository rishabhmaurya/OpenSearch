/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.action;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.rounding.RoundingPolicy;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.OriginalIndices;
import org.opensearch.action.admin.cluster.node.tasks.cancel.CancelTasksRequest;
import org.opensearch.action.admin.cluster.node.tasks.get.GetTaskAction;
import org.opensearch.action.fieldcaps.FieldCapabilitiesRequest;
import org.opensearch.action.fieldcaps.FieldCapabilitiesResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.TransportSearchAction;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.TransportAction;
import org.opensearch.arrow.allocator.ArrowNativeAllocator;
import org.opensearch.arrow.search.ArrowSearchPlugin;
import org.opensearch.arrow.search.ArrowSearchSettings;
import org.opensearch.arrow.search.Column;
import org.opensearch.arrow.search.ColumnResolver;
import org.opensearch.arrow.search.scan.ArrowScanSpec;
import org.opensearch.arrow.search.scan.ArrowShardStreamHandler;
import org.opensearch.arrow.search.scan.ArrowShardStreamRequest;
import org.opensearch.arrow.spi.NativeAllocatorPoolConfig;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.routing.GroupShardsIterator;
import org.opensearch.cluster.routing.OperationRouting;
import org.opensearch.cluster.routing.ShardIterator;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Nullable;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.core.index.Index;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.tasks.TaskCancelledException;
import org.opensearch.core.tasks.TaskId;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.indices.IndicesService;
import org.opensearch.ratelimitting.admissioncontrol.enums.AdmissionControlActionType;
import org.opensearch.search.SearchService;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.fetch.subphase.FieldAndFormat;
import org.opensearch.search.internal.SearchContext;
import org.opensearch.search.pipeline.SearchPipelineService;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.Scheduler;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.RemoteClusterAware;
import org.opensearch.transport.StreamTransportService;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.OriginSettingClient;
import org.opensearch.transport.client.node.NodeClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Coordinates Arrow searches, and serves the shard streams of this node.
 * <p>
 * A search resolves its columns from field capabilities, sends their schema, and then runs through
 * {@link TransportSearchAction}'s single-phase path, which provides the index resolution, routing, can-match, throttling,
 * failover, stats and slow logs of a regular search, while {@link ArrowSearchExecution} streams each shard.
 */
public class TransportArrowSearchAction extends TransportAction<ArrowSearchRequest, SearchResponse> {

    /**
     * Batches a shard stream may hold in flight on the coordinating node: the stream transport prefetches up to five, and the
     * relay holds one while it waits to send it. Besides, gRPC holds the batch last sent while the client reads it, one per
     * search, as the call is not ready for the next once it queued a batch.
     */
    static final int BATCHES_IN_FLIGHT_PER_STREAM = 6;
    /** How often a stopped search cancels its tasks again while some of its shard streams are still opening. */
    static final TimeValue CANCEL_RETRY_INTERVAL = TimeValue.timeValueSeconds(1);
    private static final String PHASE_NAME = "arrow_scan";
    private static final Logger logger = LogManager.getLogger(TransportArrowSearchAction.class);

    private final ClusterService clusterService;
    private final IndexNameExpressionResolver resolver;
    private final TransportService transportService;
    private final StreamTransportService streamTransportService;
    private final TransportSearchAction transportSearchAction;
    private final SearchService searchService;
    private final ThreadPool threadPool;
    private final NodeClient client;
    private final ArrowSearchSettings settings;
    private final Executor executor;
    private final CoordinatorBudget budget;
    private final BufferAllocator relayAllocator;

    @Inject
    public TransportArrowSearchAction(
        ActionFilters actionFilters,
        TransportService transportService,
        @Nullable StreamTransportService streamTransportService,
        ClusterService clusterService,
        IndexNameExpressionResolver resolver,
        TransportSearchAction transportSearchAction,
        SearchService searchService,
        IndicesService indicesService,
        ThreadPool threadPool,
        NodeClient client,
        ArrowNativeAllocator nativeAllocator,
        ArrowSearchPlugin.Components components
    ) {
        super(ArrowSearchAction.NAME, actionFilters, transportService.getTaskManager());
        this.clusterService = clusterService;
        this.resolver = resolver;
        this.transportService = transportService;
        this.streamTransportService = streamTransportService;
        this.transportSearchAction = transportSearchAction;
        this.searchService = searchService;
        this.threadPool = threadPool;
        this.client = client;
        this.settings = components.settings();
        this.executor = components.executor();
        this.budget = new CoordinatorBudget(settings::coordinatorMaxBufferedBytes);

        final BufferAllocator flightPool = nativeAllocator.getPoolAllocator(NativeAllocatorPoolConfig.POOL_FLIGHT);
        this.relayAllocator = flightPool.newChildAllocator("arrow-search-relay", 0, settings.coordinatorMaxBufferedBytes());
        components.registerAllocator(relayAllocator);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(
                ArrowSearchSettings.COORDINATOR_MAX_BUFFERED_BYTES,
                value -> relayAllocator.setLimit(value.getBytes())
            );

        if (streamTransportService != null) {
            final BufferAllocator batchAllocator = flightPool.newChildAllocator(
                "arrow-search-batches",
                0,
                ArrowSearchSettings.SHARD_MAX_BUFFERED_BYTES.get(clusterService.getSettings()).getBytes()
            );
            components.registerAllocator(batchAllocator);
            streamTransportService.registerRequestHandler(
                ArrowShardStreamHandler.ACTION,
                ArrowSearchPlugin.EXECUTOR,
                false,
                true,
                AdmissionControlActionType.SEARCH,
                ArrowShardStreamRequest::new,
                new ArrowShardStreamHandler(
                    searchService,
                    indicesService,
                    transportService.getTaskManager(),
                    clusterService,
                    components.registry(),
                    settings,
                    batchAllocator,
                    threadPool.executor(ArrowSearchPlugin.EXECUTOR)
                )
            );
        }
    }

    @Override
    protected void doExecute(Task task, ArrowSearchRequest request, ActionListener<SearchResponse> listener) {
        try {
            start((ArrowSearchTask) task, request, listener);
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private void start(ArrowSearchTask task, ArrowSearchRequest request, ActionListener<SearchResponse> listener) {
        if (streamTransportService == null) {
            throw new OpenSearchStatusException(
                "Arrow search needs the stream transport: install the arrow-flight-rpc plugin and enable ["
                    + FeatureFlags.STREAM_TRANSPORT
                    + "]",
                RestStatus.SERVICE_UNAVAILABLE
            );
        }
        rejectRemoteIndices(request);
        rejectSearchPipelines(request);

        final BatchSizes sizes = BatchSizes.of(settings, relayAllocator.getRoundingPolicy());
        final long minimum = sizes.perSearch() + sizes.perStream();
        final ShardTargets targets = ShardTargets.of(clusterService.state(), resolver, clusterService.operationRouting(), request);
        // Request listeners, such as workload management, may still set max_concurrent_shard_requests once the search
        // started, so it reserves for one stream per shard, and trims the reservation when its first shard starts.
        final CoordinatorBudget.Reservation reservation = budget.reserve(
            reservationBytes(sizes.perStream(), sizes.perSearch(), settings.maxBufferedBytesPerRequest(), targets.shards()),
            minimum
        );
        if (reservation == null) {
            if (minimum > settings.coordinatorMaxBufferedBytes()) {
                throw new IllegalStateException(
                    "an arrow search needs ["
                        + new ByteSizeValue(minimum)
                        + "] for one shard stream, more than [search.arrow.coordinator.max_buffered_bytes] allows: raise it, or lower"
                        + " [search.arrow.max_row_size] and [search.arrow.batch_size]"
                );
            }
            throw new OpenSearchRejectedExecutionException(
                "rejected arrow search: the searches on this node already buffer the maximum of ["
                    + settings.coordinatorMaxBufferedBytes()
                    + "] bytes"
            );
        }
        final ArrowBatchSink sink = request.sink();
        final ActionListener<SearchResponse> released = ActionListener.runBefore(
            listener,
            () -> endSearch(reservation, sink, threadPool, settings.clientIdleTimeout())
        );
        try {
            final List<ColumnResolver.RequestedField> requestedFields = request.requestedFields();
            final long rowLimit = request.rowLimit();
            final long shardRowLimit = request.shardRowLimit();
            if (request.allowPartialSearchResults() == null) {
                request.allowPartialSearchResults(searchService.defaultAllowPartialSearchResults());
            }
            prepareSource(request);

            final FieldCapabilitiesRequest fieldCaps = new FieldCapabilitiesRequest().indices(request.indices())
                .indicesOptions(request.indicesOptions())
                .fields(requestedFields.stream().map(ColumnResolver.RequestedField::pattern).toArray(String[]::new))
                .includeUnmapped(true);
            fieldCaps.setParentTask(clusterService.localNode().getId(), task.getId());
            client.fieldCaps(fieldCaps, ActionListener.wrap(response -> executor.execute(() -> {
                try {
                    run(task, request, response, requestedFields, rowLimit, shardRowLimit, sizes, targets, reservation, released);
                } catch (Exception e) {
                    released.onFailure(e);
                }
            }), released::onFailure));
        } catch (Exception e) {
            released.onFailure(e);
        }
    }

    private void run(
        ArrowSearchTask task,
        ArrowSearchRequest request,
        FieldCapabilitiesResponse fieldCaps,
        List<ColumnResolver.RequestedField> requestedFields,
        long rowLimit,
        long shardRowLimit,
        BatchSizes sizes,
        ShardTargets targets,
        CoordinatorBudget.Reservation reservation,
        ActionListener<SearchResponse> listener
    ) throws Exception {
        final List<Column> columns = ColumnResolver.resolve(requestedFields, fieldCaps, maxColumns(request));
        final Schema schema = Column.toSchema(columns);
        final int streams = (int) Math.max(1, Math.min(Integer.MAX_VALUE, (reservation.bytes() - sizes.perSearch()) / sizes.perStream()));
        // Each relay waits for its turn at the client, so a data node may wait for one client timeout per open stream.
        final TimeValue producerReadyTimeout = TimeValue.timeValueMillis(
            Math.multiplyExact(settings.clientIdleTimeout().millis(), (long) streams + 1)
        );
        final ArrowScanSpec spec = new ArrowScanSpec(
            columns,
            sizes.batchBytes(),
            sizes.firstBatchBytes(),
            sizes.batchRows(),
            sizes.maxBatchBytes(),
            shardRowLimit,
            producerReadyTimeout
        );
        final ArrowBatchSink sink = request.sink();
        final ArrowSearchExecution execution = new ArrowSearchExecution(
            task,
            sink,
            spec,
            new FlightShardStreamClient(streamTransportService),
            ArrowSearchPlugin.EXECUTOR,
            executor,
            relayAllocator,
            reservation,
            sizes.batchMemory(),
            streams,
            () -> limitStreams(reservation, sizes, streams, targets.maxOpenStreams(request.getMaxConcurrentShardRequests())),
            rowLimit,
            request.allowPartialSearchResults(),
            (reason, opening) -> cancelChildren(task, reason, opening)
        );
        sink.setOnCancel(() -> execution.stop(ArrowSearchExecution.Stop.CANCELLED, null));
        task.setOnCancel(() -> executor.execute(() -> execution.stop(ArrowSearchExecution.Stop.CANCELLED, null)));
        if (task.isCancelled()) {
            throw new TaskCancelledException("the arrow search was cancelled");
        }
        sink.sendSchema(schema);
        transportSearchAction.executeRequest(
            task,
            request,
            PHASE_NAME,
            false,
            execution,
            ActionListener.wrap(listener::onResponse, e -> listener.onFailure(execution.failureFor(e)))
        );
    }

    /**
     * Ends the search's own hold on its reservation; only the first call has an effect, as a listener may be notified twice.
     * Batches that gRPC has not yet written to the client keep holding it, and only they still count against the budget; a
     * client that has not read them within the idle timeout has its call reset, which releases them.
     */
    static void endSearch(CoordinatorBudget.Reservation reservation, ArrowBatchSink sink, Scheduler scheduler, TimeValue idleTimeout) {
        if (reservation.endSearch() == false) {
            return;
        }
        final Scheduler.ScheduledCancellable reset;
        try {
            reset = scheduler.schedule(() -> {
                if (reservation.isReleased() == false) {
                    sink.reset();
                }
            }, idleTimeout, ThreadPool.Names.SAME);
        } catch (OpenSearchRejectedExecutionException e) {
            // The node is closing, and with it the client's connection.
            logger.debug("failed to schedule the reset of an arrow search the client did not read: {}", e.getMessage());
            return;
        }
        reservation.whenReleased(reset::cancel);
    }

    /**
     * The batch sizes of one search, read from the settings once so that what its coordinator reserves fits what its scans
     * send.
     *
     * @param batchBytes      the size batches grow to
     * @param firstBatchBytes the size of a shard's first batch
     * @param batchRows       the maximum rows in a batch
     * @param maxBatchBytes   the largest batch a scan sends, and so the largest row: a row larger than the batch size goes
     *                        out on its own, and a row the scan must reject always exceeds the batch budget, so it is
     *                        rejected in whichever batch it falls
     * @param batchMemory     the memory the coordinator holds for a received batch of {@code maxBatchBytes}: the stream
     *                        transport reads its body into one buffer, which Arrow rounds up as it does every allocation, to
     *                        the next power of two below its 16MB chunk size
     */
    record BatchSizes(long batchBytes, long firstBatchBytes, int batchRows, long maxBatchBytes, long batchMemory) {

        static BatchSizes of(ArrowSearchSettings settings, RoundingPolicy rounding) {
            final long batchBytes = settings.batchBytes();
            final long maxBatchBytes = Math.max(settings.maxRowBytes(), batchBytes);
            return new BatchSizes(
                batchBytes,
                Math.min(settings.firstBatchBytes(), batchBytes),
                settings.batchRows(),
                maxBatchBytes,
                rounding.getRoundedSize(maxBatchBytes)
            );
        }

        /**
         * @return the bytes one open shard stream may buffer on the coordinating node, every batch it holds in flight being
         *         as large as a batch can be
         */
        long perStream() {
            return BATCHES_IN_FLIGHT_PER_STREAM * batchMemory;
        }

        /**
         * @return the bytes a search buffers on the coordinating node besides its streams: the batch gRPC holds while the
         *         client reads it
         */
        long perSearch() {
            return batchMemory;
        }
    }

    /**
     * Moves the options a regular search would apply to hits into the scan: {@code post_filter} filters like the query,
     * and the row limits are enforced by the scan, so no shard collects or fetches hits.
     */
    private static void prepareSource(ArrowSearchRequest request) {
        final SearchSourceBuilder source = request.source();
        QueryBuilder query = source.query() == null ? new MatchAllQueryBuilder() : source.query();
        if (source.postFilter() != null) {
            // Without aggregations or scores, a post filter selects exactly what a filter clause does.
            query = new BoolQueryBuilder().must(query).filter(source.postFilter());
            source.postFilter(null);
        }
        source.query(query).size(0).from(0).trackTotalHits(true).terminateAfter(SearchContext.DEFAULT_TERMINATE_AFTER);
        // Shards still check the fields against index.max_docvalue_fields_search; the list format is the scan's alone.
        source.docValueFields().replaceAll(field -> new FieldAndFormat(field.field, null));
        request.requestCache(false);
    }

    private void rejectRemoteIndices(ArrowSearchRequest request) {
        final ClusterState state = clusterService.state();
        final Map<String, OriginalIndices> groups = transportService.getRemoteClusterService()
            .groupIndices(request.indicesOptions(), request.indices(), index -> resolver.hasIndexAbstraction(index, state));
        if (groups.keySet().stream().anyMatch(alias -> RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY.equals(alias) == false)) {
            throw new IllegalArgumentException("Arrow search does not support remote cluster indices");
        }
    }

    /**
     * @return the bytes a search reserves: its own, and one stream's worth for each of the {@code streams} it may open at
     *         once, within the per-request limit, and at least one stream's worth whatever the limit
     */
    static long reservationBytes(long perStream, long perSearch, long perRequest, long streams) {
        final long reserved = Math.max(1, Math.min(streams, Math.max(0, perRequest - perSearch) / perStream));
        return perSearch + reserved * perStream;
    }

    /**
     * Trims a search's reservation, made for {@code streams} streams, to the streams it may open at once.
     *
     * @return how many streams the search may open at once
     */
    static int limitStreams(CoordinatorBudget.Reservation reservation, BatchSizes sizes, int streams, long maxOpenStreams) {
        final int limit = (int) Math.max(1, Math.min(streams, maxOpenStreams));
        reservation.trim(sizes.perSearch() + limit * sizes.perStream());
        return limit;
    }

    /**
     * The shards a search's routing and preference target, and the nodes holding a copy of them, which bound the shard
     * streams it opens at once.
     */
    record ShardTargets(int shards, int nodes) {

        /**
         * @return the targets of the search, or a single shard if they do not resolve, as the search then fails the same way
         */
        static ShardTargets of(
            ClusterState state,
            IndexNameExpressionResolver resolver,
            OperationRouting operationRouting,
            SearchRequest request
        ) {
            final GroupShardsIterator<ShardIterator> shards;
            try {
                final Map<String, Set<String>> routing = resolver.resolveSearchRouting(state, request.routing(), request.indices());
                shards = operationRouting.searchShards(state, resolver.concreteIndexNames(state, request), routing, request.preference());
            } catch (RuntimeException e) {
                logger.debug("failed to resolve the shards of an arrow search: {}", e.getMessage());
                return new ShardTargets(1, 1);
            }
            final Set<String> nodes = new HashSet<>();
            for (ShardIterator shard : shards) {
                for (ShardRouting copy : shard.getShardRoutings()) {
                    nodes.add(copy.currentNodeId());
                }
            }
            return new ShardTargets(shards.size(), nodes.size());
        }

        /**
         * @return the most shard streams the search opens at once, at least one: one per shard, and no more than
         *         {@code maxConcurrentShardRequests} per node, which is how many shard requests a search runs at once on a node
         */
        long maxOpenStreams(int maxConcurrentShardRequests) {
            return Math.max(1, Math.min(shards, (long) maxConcurrentShardRequests * nodes));
        }
    }

    /**
     * Every shard reads every column, as nulls where its index does not map the field, so a search across indices may read
     * as many columns as the largest {@code index.max_docvalue_fields_search} of its target indices lets a search of one.
     */
    private int maxColumns(ArrowSearchRequest request) {
        final ClusterState state = clusterService.state();
        int max = -1;
        try {
            for (Index index : resolver.concreteIndices(state, request)) {
                final IndexMetadata metadata = state.metadata().index(index);
                if (metadata != null) {
                    max = Math.max(max, IndexSettings.MAX_DOCVALUE_FIELDS_SEARCH_SETTING.get(metadata.getSettings()));
                }
            }
        } catch (IndexNotFoundException e) {
            // The search itself reports the missing index.
        }
        return max >= 0 ? max : IndexSettings.MAX_DOCVALUE_FIELDS_SEARCH_SETTING.get(Settings.EMPTY);
    }

    /**
     * A search pipeline would transform the hits and response of a regular search, which an Arrow search does not produce,
     * and its request processors may restrict what a search sees, so searches of indices with a default pipeline are
     * rejected rather than run without it.
     */
    private void rejectSearchPipelines(ArrowSearchRequest request) {
        final ClusterState state = clusterService.state();
        final Index[] indices;
        try {
            indices = resolver.concreteIndices(state, request);
        } catch (IndexNotFoundException e) {
            // The search itself reports the missing index.
            return;
        }
        for (Index index : indices) {
            final IndexMetadata metadata = state.metadata().index(index);
            if (metadata != null && IndexSettings.DEFAULT_SEARCH_PIPELINE.exists(metadata.getSettings())) {
                final String pipeline = IndexSettings.DEFAULT_SEARCH_PIPELINE.get(metadata.getSettings());
                if (SearchPipelineService.NOOP_PIPELINE_ID.equals(pipeline) == false) {
                    throw new IllegalArgumentException(
                        "Arrow search does not support search pipelines, but index ["
                            + index.getName()
                            + "] has the default search pipeline ["
                            + pipeline
                            + "]"
                    );
                }
            }
        }
    }

    /**
     * @return the bytes the Arrow searches coordinated by this node reserve
     */
    public long reservedBytes() {
        return budget.reserved();
    }

    /**
     * Cancels the search's tasks on every node, such as shard streams still opening, but not the search task itself, whose
     * response still has to be sent.
     */
    private void cancelChildren(ArrowSearchTask task, String reason, Supplier<Set<String>> opening) {
        final TaskId parent = new TaskId(clusterService.localNode().getId(), task.getId());
        cancelChildren(CancelTasksRequest.ALL_NODES, (nodes, done) -> {
            final CancelTasksRequest request = new CancelTasksRequest();
            request.setParentTaskId(parent);
            request.setNodes(nodes);
            request.setReason(reason);
            request.setWaitForCompletion(false);
            // As the tasks origin, like the cancel-after timeout, since the caller may not hold the privilege to cancel tasks.
            new OriginSettingClient(client, GetTaskAction.TASKS_ORIGIN).admin()
                .cluster()
                .cancelTasks(request, ActionListener.runAfter(ActionListener.wrap(response -> {}, e -> {
                    logger.debug("failed to cancel the shard tasks of arrow search [{}]: {}", task.getId(), e.getMessage());
                }), done));
        }, opening, threadPool, CANCEL_RETRY_INTERVAL);
    }

    /**
     * Cancels a stopped search's tasks on {@code nodes}, every node if none is given, and then again on the nodes of the shard
     * streams still opening, until none is. A cancel only reaches the tasks already registered, and a stream's request may
     * reach its node after the cancel did, while the stream itself can only be cancelled once its first batch arrived.
     *
     * @param cancel   sends one cancel to the given nodes, and runs the given callback once it completed
     * @param opening  the nodes of the shard streams still opening
     * @param interval how long to wait before cancelling again
     */
    static void cancelChildren(
        String[] nodes,
        BiConsumer<String[], Runnable> cancel,
        Supplier<Set<String>> opening,
        Scheduler scheduler,
        TimeValue interval
    ) {
        cancel.accept(nodes, () -> {
            if (opening.get().isEmpty()) {
                return;
            }
            try {
                scheduler.schedule(() -> {
                    final Set<String> stillOpening = opening.get();
                    if (stillOpening.isEmpty() == false) {
                        cancelChildren(stillOpening.toArray(String[]::new), cancel, opening, scheduler, interval);
                    }
                }, interval, ThreadPool.Names.GENERIC);
            } catch (OpenSearchRejectedExecutionException e) {
                // The node is closing, and with it the shard streams.
                logger.debug("failed to schedule cancelling the shard tasks of an arrow search again: {}", e.getMessage());
            }
        });
    }
}
