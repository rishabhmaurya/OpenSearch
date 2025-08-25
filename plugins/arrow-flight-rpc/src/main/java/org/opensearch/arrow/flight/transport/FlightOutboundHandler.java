/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.flight.FlightRuntimeException;
import org.opensearch.Version;
import org.opensearch.arrow.flight.bootstrap.ServerConfig;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.transport.TransportResponse;
import org.opensearch.search.aggregations.bucket.terms.AggregatorProfiler;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.ProtocolOutboundHandler;
import org.opensearch.transport.StatsTracker;
import org.opensearch.transport.TcpChannel;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportMessageListener;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.nativeprotocol.NativeOutboundMessage;
import org.opensearch.transport.stream.StreamException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Outbound handler for Arrow Flight streaming responses.
 * It must invoke messageListener and relay any exception back to the caller and not supress them
 * @opensearch.internal
 */
class FlightOutboundHandler extends ProtocolOutboundHandler {
    private volatile TransportMessageListener messageListener = TransportMessageListener.NOOP_LISTENER;
    private final String nodeName;
    private final Version version;
    private final String[] features;
    private final StatsTracker statsTracker;
    private final ThreadPool threadPool;
    private final Executor serverExecutor;
    private final ConcurrentHashMap<Long, LinkedBlockingQueue<BatchTask>> requestQueues = new ConcurrentHashMap<>();

    public FlightOutboundHandler(String nodeName, Version version, String[] features, StatsTracker statsTracker, ThreadPool threadPool) {
        this.nodeName = nodeName;
        this.version = version;
        this.features = features;
        this.statsTracker = statsTracker;
        this.threadPool = threadPool;
        this.serverExecutor = threadPool.executor(ServerConfig.FLIGHT_SERVER_THREAD_POOL_NAME);
    }

    @Override
    public void sendRequest(
        DiscoveryNode node,
        TcpChannel channel,
        long requestId,
        String action,
        TransportRequest request,
        TransportRequestOptions options,
        Version channelVersion,
        boolean compressRequest,
        boolean isHandshake
    ) throws IOException, TransportException {
        throw new UnsupportedOperationException("sendRequest not implemented for FlightOutboundHandler");
    }

    @Override
    public void sendResponse(
        final Version nodeVersion,
        final Set<String> features,
        final TcpChannel channel,
        final long requestId,
        final String action,
        final TransportResponse response,
        final boolean compress,
        final boolean isHandshake
    ) throws IOException {
        throw new UnsupportedOperationException(
            "sendResponse() is not supported for streaming requests in FlightOutboundHandler; use sendResponseBatch()"
        );
    }

    public void sendResponseBatch(
        final Version nodeVersion,
        final Set<String> features,
        final TcpChannel channel,
        final long requestId,
        final String action,
        final TransportResponse response,
        final boolean compress,
        final boolean isHandshake
    ) throws IOException {
        ThreadContext.StoredContext storedContext = threadPool.getThreadContext().stashContext();
        BatchTask task = new BatchTask(
            nodeVersion,
            features,
            channel,
            requestId,
            action,
            response,
            compress,
            isHandshake,
            false,
            storedContext
        );

        LinkedBlockingQueue<BatchTask> queue = requestQueues.computeIfAbsent(requestId, k -> {
            LinkedBlockingQueue<BatchTask> newQueue = new LinkedBlockingQueue<>();
            serverExecutor.execute(() -> processRequestQueue(requestId, newQueue));
            return newQueue;
        });

        if (!queue.offer(task)) {
            storedContext.close();
            throw new IOException("Failed to queue batch task");
        }
    }

    private void processRequestQueue(long requestId, LinkedBlockingQueue<BatchTask> queue) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                BatchTask task = queue.poll(180, TimeUnit.SECONDS);
                if (task == null) {
                    break; // Timeout - assume request abandoned
                }
                if (task.isComplete) {
                    processCompleteTask(task);
                    break;
                } else if (task.isError) {
                    processErrorTask(task);
                    break;
                } else {
                    processBatchTask(task);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            requestQueues.remove(requestId);
        }
    }

    private void processBatchTask(BatchTask task) {
        try (BatchTask ignored = task) {
            task.storedContext.restore();
            if (!(task.channel instanceof FlightServerChannel flightChannel)) {
                Exception error = new IllegalStateException("Expected FlightServerChannel, got " + task.channel.getClass().getName());
                messageListener.onResponseSent(task.requestId, task.action, error);
                return;
            }

            try {
                try (VectorStreamOutput out = new VectorStreamOutput(flightChannel.getAllocator(), flightChannel.getRoot())) {
                    long startTime = System.nanoTime();
                    task.response.writeTo(out);
                    long serializationTime = System.nanoTime() - startTime;
                    AggregatorProfiler.getInstance().recordTime(AggregatorProfiler.Operation.SERIALIZATION, serializationTime);

                    long sendChannelStart = System.nanoTime();
                    flightChannel.sendBatch(getHeaderBuffer(task.requestId, task.nodeVersion, task.features), out);
                    long sendChannelTime = System.nanoTime() - sendChannelStart;
                    AggregatorProfiler.getInstance().recordTime(AggregatorProfiler.Operation.SEND_CHANNEL, sendChannelTime);
                    messageListener.onResponseSent(task.requestId, task.action, task.response);
                }
            } catch (FlightRuntimeException e) {
                messageListener.onResponseSent(task.requestId, task.action, FlightErrorMapper.fromFlightException(e));
            } catch (Exception e) {
                messageListener.onResponseSent(task.requestId, task.action, e);
            }
        }
    }

    public void completeStream(
        final Version nodeVersion,
        final Set<String> features,
        final TcpChannel channel,
        final long requestId,
        final String action
    ) {
        ThreadContext.StoredContext storedContext = threadPool.getThreadContext().stashContext();
        BatchTask completeTask = new BatchTask(
            nodeVersion,
            features,
            channel,
            requestId,
            action,
            TransportResponse.Empty.INSTANCE,
            false,
            false,
            true,
            storedContext
        );

        LinkedBlockingQueue<BatchTask> queue = requestQueues.computeIfAbsent(requestId, k -> {
            LinkedBlockingQueue<BatchTask> newQueue = new LinkedBlockingQueue<>();
            serverExecutor.execute(() -> processRequestQueue(requestId, newQueue));
            return newQueue;
        });
        queue.offer(completeTask);
    }

    private void processCompleteTask(BatchTask task) {
        try (BatchTask ignored = task) {
            task.storedContext.restore();
            if (!(task.channel instanceof FlightServerChannel flightChannel)) {
                Exception error = new IllegalStateException("Expected FlightServerChannel, got " + task.channel.getClass().getName());
                messageListener.onResponseSent(task.requestId, task.action, error);
                return;
            }

            try {
                flightChannel.completeStream();
                messageListener.onResponseSent(task.requestId, task.action, TransportResponse.Empty.INSTANCE);
            } catch (Exception e) {
                messageListener.onResponseSent(task.requestId, task.action, e);
            }
        }
    }

    @Override
    public void sendErrorResponse(
        final Version nodeVersion,
        final Set<String> features,
        final TcpChannel channel,
        final long requestId,
        final String action,
        final Exception error
    ) {
        ThreadContext.StoredContext storedContext = threadPool.getThreadContext().stashContext();
        BatchTask errorTask = new BatchTask(nodeVersion, features, channel, requestId, action, false, false, error, storedContext);

        LinkedBlockingQueue<BatchTask> queue = requestQueues.computeIfAbsent(requestId, k -> {
            LinkedBlockingQueue<BatchTask> newQueue = new LinkedBlockingQueue<>();
            serverExecutor.execute(() -> processRequestQueue(requestId, newQueue));
            return newQueue;
        });
        queue.offer(errorTask);
    }

    private void processErrorTask(BatchTask task) {
        try (BatchTask ignored = task) {
            task.storedContext.restore();
            if (!(task.channel instanceof FlightServerChannel flightServerChannel)) {
                Exception error = new IllegalStateException("Expected FlightServerChannel, got " + task.channel.getClass().getName());
                messageListener.onResponseSent(task.requestId, task.action, error);
                return;
            }

            try {
                Exception flightError = task.error;
                if (task.error instanceof StreamException) {
                    flightError = FlightErrorMapper.toFlightException((StreamException) task.error);
                }
                flightServerChannel.sendError(getHeaderBuffer(task.requestId, task.nodeVersion, task.features), flightError);
                messageListener.onResponseSent(task.requestId, task.action, task.error);
            } catch (Exception e) {
                messageListener.onResponseSent(task.requestId, task.action, e);
            }
        }
    }

    @Override
    public void setMessageListener(TransportMessageListener listener) {
        if (messageListener == TransportMessageListener.NOOP_LISTENER) {
            messageListener = listener;
        } else {
            throw new IllegalStateException("Cannot set message listener twice");
        }
    }

    private ByteBuffer getHeaderBuffer(long requestId, Version nodeVersion, Set<String> features) throws IOException {
        // Just a way( probably inefficient) to serialize header to reuse existing logic present in
        // NativeOutboundMessage.Response#writeVariableHeader()
        NativeOutboundMessage.Response headerMessage = new NativeOutboundMessage.Response(
            threadPool.getThreadContext(),
            features,
            out -> {},
            Version.min(version, nodeVersion),
            requestId,
            false,
            false
        );
        try (BytesStreamOutput bytesStream = new BytesStreamOutput()) {
            BytesReference headerBytes = headerMessage.serialize(bytesStream);
            return ByteBuffer.wrap(headerBytes.toBytesRef().bytes);
        }
    }
}
