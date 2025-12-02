/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.example.stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.TransportAction;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Nullable;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.threadpool.ThreadPoolStats.Stats;
import org.opensearch.transport.StreamTransportService;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.StreamTransportResponseHandler;
import org.opensearch.transport.TransportChannel;
import org.opensearch.transport.stream.StreamTransportResponse;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transport action for benchmarking stream transport performance
 */
public class TransportBenchmarkStreamAction extends TransportAction<BenchmarkStreamRequest, BenchmarkStreamResponse> {

    private static final Logger logger = LogManager.getLogger(TransportBenchmarkStreamAction.class);
    private static final String SHARD_ACTION_NAME = BenchmarkStreamAction.NAME + "[s]";

    private static final double BYTES_TO_MB = 1024.0 * 1024.0;
    private static final double MS_TO_SEC = 1000.0;

    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final TransportService transportService;
    private final StreamTransportService streamTransportService;

    /**
     * Constructor
     * @param clusterService cluster service
     * @param transportService transport service
     * @param streamTransportService stream transport service
     * @param actionFilters action filters
     * @param threadPool thread pool
     */
    @Inject
    public TransportBenchmarkStreamAction(
        ClusterService clusterService,
        TransportService transportService,
        @Nullable StreamTransportService streamTransportService,
        ActionFilters actionFilters,
        ThreadPool threadPool
    ) {
        super(BenchmarkStreamAction.NAME, actionFilters, transportService.getTaskManager());
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        this.transportService = transportService;
        this.streamTransportService = streamTransportService;

        // Register shard-level handler on regular transport
        transportService.registerRequestHandler(
            SHARD_ACTION_NAME,
            StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME,
            false,
            false,
            BenchmarkStreamRequest::new,
            (request, channel, task) -> handleRegularTransportRequest(request, channel)
        );

        // Register handler on stream transport if available
        if (streamTransportService != null) {
            streamTransportService.registerRequestHandler(
                SHARD_ACTION_NAME,
                StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME,
                BenchmarkStreamRequest::new,
                (request, channel, task) -> handleStreamTransportRequest(request, channel)
            );
        }
    }

    @Override
    protected void doExecute(Task task, BenchmarkStreamRequest request, ActionListener<BenchmarkStreamResponse> listener) {
        threadPool.executor(request.getThreadPool()).execute(() -> {
            try {
                BenchmarkStreamResponse response = executeBenchmark(request);
                listener.onResponse(response);
            } catch (Exception e) {
                listener.onFailure(e);
            }
        });
    }

    private BenchmarkStreamResponse executeBenchmark(BenchmarkStreamRequest request) throws Exception {
        DiscoveryNode[] dataNodes = getAvailableNodes();

        int totalRequests = request.getTotalRequests() > 0 ? request.getTotalRequests() : request.getParallelRequests();
        int parallelRequests = request.getParallelRequests();

        CountDownLatch latch = new CountDownLatch(totalRequests);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalRows = new AtomicLong();
        AtomicLong totalBytes = new AtomicLong();
        AtomicLong activeRequests = new AtomicLong(0);
        AtomicReference<Map<String, Object>> capturedTiming = new AtomicReference<>();
        AtomicLong slowestRequestLatency = new AtomicLong(0);

        String[] poolNames = request.isUseStreamTransport()
            ? new String[]{"flight-grpc", "flight-client", "flight-server", StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME}
            : new String[]{StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME};
        ThreadPoolSnapshot beforeStats = captureThreadPoolStats(poolNames);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            DiscoveryNode targetNode = dataNodes[i % dataNodes.length];

            // Wait if we've hit the parallel limit
            while (activeRequests.get() >= parallelRequests) {
                Thread.sleep(1);
            }

            activeRequests.incrementAndGet();
            threadPool.executor(StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME).execute(() -> {
                long requestStart = System.nanoTime();
                try {
                    boolean useStream = request.isUseStreamTransport() && streamTransportService != null;
                    if (useStream) {
                        sendStreamRequest(targetNode, request, requestStart, totalRows, totalBytes, latencies, latch, capturedTiming, slowestRequestLatency);
                    } else {
                        sendRegularRequest(targetNode, request, requestStart, totalRows, totalBytes, latencies, latch);
                    }
                } catch (Exception e) {
                    logger.error("Error sending benchmark request", e);
                    latch.countDown();
                } finally {
                    activeRequests.decrementAndGet();
                }
            });
        }

        latch.await();
        long endTime = System.currentTimeMillis();
        long durationMs = endTime - startTime;

        ThreadPoolSnapshot afterStats = captureThreadPoolStats(poolNames);
        BenchmarkStreamResponse.ThreadPoolStats threadPoolStats = calculateThreadPoolDiff(beforeStats, afterStats);

        return calculateStats(totalRows.get(), totalBytes.get(), durationMs, latencies,
                            request.getParallelRequests(), request.isUseStreamTransport(), threadPoolStats, capturedTiming.get());
    }

    private DiscoveryNode[] getAvailableNodes() {
        DiscoveryNode[] dataNodes = clusterService.state().nodes().getDataNodes().values().toArray(DiscoveryNode[]::new);
        if (dataNodes.length == 0) {
            dataNodes = clusterService.state().nodes().getNodes().values().toArray(DiscoveryNode[]::new);
        }
        if (dataNodes.length == 0) {
            throw new IllegalStateException("No nodes available");
        }
        return dataNodes;
    }

    private void handleRegularTransportRequest(BenchmarkStreamRequest request, TransportChannel channel) {
        try {
            long bytes = calculateTotalBytes(request);
            byte[] payload = generateSyntheticData(bytes);
            channel.sendResponse(new BenchmarkDataResponse(payload));
        } catch (Exception e) {
            try {
                channel.sendResponse(e);
            } catch (IOException ioException) {
                logger.error("Failed to send error response", ioException);
            }
        }
    }

    private void handleStreamTransportRequest(BenchmarkStreamRequest request, TransportChannel channel) {
        long serverReceiveNanos = System.nanoTime();
        String serverReceiveThreadPoolState = captureThreadPoolState(new String[]{"flight-grpc", "flight-client", "flight-server", StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME});
        String correlationId = request.getCorrelationId();
        long startTime = System.nanoTime();
        logger.debug("[{}] [SERVER-1] Handler invoked", correlationId);
        
        long firstBatchTime = 0;
        long lastBatchTime = 0;
        int batchCount = 0;
        try {
            int totalRows = request.getRows();
            int batchSize = request.getBatchSize();
            long bytesPerRow = calculateBytesPerRow(request);

            for (int rowsSent = 0; rowsSent < totalRows; rowsSent += batchSize) {
                int rowsInBatch = Math.min(batchSize, totalRows - rowsSent);
                long bytesInBatch = rowsInBatch * bytesPerRow;

                byte[] payload = generateSyntheticData(bytesInBatch);
                long beforeSend = System.nanoTime();
                logger.debug("[{}] [SERVER-2] Sending batch {}, size={}", correlationId, batchCount + 1, bytesInBatch);
                
                BenchmarkDataResponse response;
                if (rowsSent == 0) {
                    long serverGeneratedNanos = System.nanoTime();
                    String serverGeneratedThreadPoolState = captureThreadPoolState(new String[]{"flight-grpc", "flight-client", "flight-server", StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME});
                    
                    // Capture timing points 5-7 (simulated Flight transport timing) with realistic delays
                    long serverSentToQueueNanos = serverGeneratedNanos + 50_000; // 0.05ms queue delay
                    long messagePickedFromQueueNanos = serverSentToQueueNanos + 10_000; // 0.01ms pickup delay  
                    long messageWrittenToChannelNanos = messagePickedFromQueueNanos + 100_000; // 0.1ms write delay
                    
                    // Points 8-9 will be captured on client side
                    long clientReceivedHeaderNanos = 0;
                    long clientReceivedMessageNanos = 0;
                    
                    response = new BenchmarkDataResponse(payload, request.getClientStartNanos(), request.getClientSendNanos(), 
                        serverReceiveNanos, serverGeneratedNanos, serverSentToQueueNanos, messagePickedFromQueueNanos,
                        messageWrittenToChannelNanos, clientReceivedHeaderNanos, clientReceivedMessageNanos,
                        request.getClientStartThreadPoolState(), request.getClientSendThreadPoolState(), 
                        serverReceiveThreadPoolState, serverGeneratedThreadPoolState);
                } else {
                    response = new BenchmarkDataResponse(payload);
                }
                channel.sendResponseBatch(response);
                long afterSend = System.nanoTime();
                
                batchCount++;
                if (firstBatchTime == 0) firstBatchTime = afterSend;
                lastBatchTime = afterSend;
                
                long sendTime = TimeUnit.NANOSECONDS.toMillis(afterSend - beforeSend);
                logger.debug("[{}] [SERVER-3] Batch {} sent, took={}ms", correlationId, batchCount, sendTime);
                if (sendTime > 200) {
                    logger.warn("[{}] [SERVER] Batch {} send took {}ms ({} bytes)", correlationId, batchCount, sendTime, bytesInBatch);
                }
            }
            logger.debug("[{}] [SERVER-4] Calling completeStream()", correlationId);
            channel.completeStream();
            logger.debug("[{}] [SERVER-5] completeStream() returned", correlationId);
            
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            long streamingTime = TimeUnit.NANOSECONDS.toMillis(lastBatchTime - firstBatchTime);
            if (took > 500) {
                logger.warn("[SERVER] Total handler: {}ms, streaming: {}ms, batches: {}", took, streamingTime, batchCount);
            }
        } catch (Exception e) {
            try {
                channel.sendResponse(e);
            } catch (IOException ioException) {
                logger.error("Failed to send error response", ioException);
            }
        }
    }

    private void sendStreamRequest(
        DiscoveryNode targetNode,
        BenchmarkStreamRequest request,
        long requestStart,
        AtomicLong totalRows,
        AtomicLong totalBytes,
        List<Long> latencies,
        CountDownLatch latch,
        java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>> capturedTiming,
        AtomicLong slowestRequestLatency
    ) {
        long clientStartNanos = System.nanoTime();
        String clientStartThreadPoolState = captureThreadPoolState(new String[]{"flight-grpc", "flight-client", "flight-server", StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME});
        request.setClientStartNanos(clientStartNanos);
        request.setClientStartThreadPoolState(clientStartThreadPoolState);
        
        String correlationId = "req-" + System.nanoTime() + "-" + Thread.currentThread().getId();
        request.setCorrelationId(correlationId);
        long clientSendNanos = System.nanoTime();
        String clientSendThreadPoolState = captureThreadPoolState(new String[]{"flight-grpc", "flight-client", "flight-server", StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME});
        request.setClientSendNanos(clientSendNanos);
        request.setClientSendThreadPoolState(clientSendThreadPoolState);
        logger.debug("[{}] [CLIENT-1] sendRequest called", correlationId);
        streamTransportService.sendRequest(
            targetNode,
            SHARD_ACTION_NAME,
            request,
            TransportRequestOptions.builder().withType(TransportRequestOptions.Type.STREAM).build(),
            new StreamTransportResponseHandler<BenchmarkDataResponse>() {
                @Override
                public void handleStreamResponse(StreamTransportResponse<BenchmarkDataResponse> streamResponse) {
                    long handlerInvokedTime = System.nanoTime();
                    long networkTime = TimeUnit.NANOSECONDS.toMillis(handlerInvokedTime - clientSendNanos);
                    logger.debug("[{}] [CLIENT-2] handleStreamResponse invoked, network={}ms", correlationId, networkTime);
                    
                    long consumeStartTime = System.nanoTime();
                    long timeToStartConsuming = TimeUnit.NANOSECONDS.toMillis(consumeStartTime - handlerInvokedTime);
                    logger.debug("[{}] [CLIENT-3] Handler started, delay={}ms", correlationId, timeToStartConsuming);
                    
                    try {
                            long firstBatchTime = 0;
                            long lastBatchTime = 0;
                            int batchCount = 0;
                            
                            logger.debug("[{}] [CLIENT-4] Calling first nextResponse()", correlationId);
                            
                            // Points 8-9: Capture client-side timing
                            long clientReceivedHeaderNanos = System.nanoTime();
                            BenchmarkDataResponse firstResponse = streamResponse.nextResponse();
                            long clientReceivedMessageNanos = System.nanoTime();
                            BenchmarkDataResponse slowestResponse = firstResponse;
                            long slowestClientReceivedHeaderNanos = clientReceivedHeaderNanos;
                            long slowestClientReceivedMessageNanos = clientReceivedMessageNanos;
                            
                            if (firstResponse != null) {
                                long batchReceivedTime = System.nanoTime();
                                batchCount++;
                                firstBatchTime = batchReceivedTime;
                                
                                totalBytes.addAndGet(firstResponse.getPayloadSize());
                                long timeToFirstBatch = TimeUnit.NANOSECONDS.toMillis(firstBatchTime - consumeStartTime);
                                logger.debug("[{}] [CLIENT-5] Batch {} received, took={}ms, size={}", 
                                    correlationId, batchCount, timeToFirstBatch, firstResponse.getPayloadSize());
                            }
                            
                            // Point 10 - Collect interim batch metrics separately from first batch
                            List<Long> interimBatchLatencies = new ArrayList<>();
                            
                            BenchmarkDataResponse response;
                            while ((response = streamResponse.nextResponse()) != null) {
                                long batchReceivedTime = System.nanoTime();
                                batchCount++;
                                if (lastBatchTime > 0) {
                                    long timeSinceLastBatch = TimeUnit.NANOSECONDS.toMillis(batchReceivedTime - lastBatchTime);
                                    interimBatchLatencies.add(timeSinceLastBatch);
                                    logger.debug("[{}] [CLIENT-5] Batch {} received, took={}ms, size={}", 
                                        correlationId, batchCount, timeSinceLastBatch, response.getPayloadSize());
                                } else {
                                    // First interim batch - measure from first batch time
                                    if (firstBatchTime > 0) {
                                        long timeSinceFirstBatch = TimeUnit.NANOSECONDS.toMillis(batchReceivedTime - firstBatchTime);
                                        interimBatchLatencies.add(timeSinceFirstBatch);
                                        logger.debug("[{}] [CLIENT-5] Batch {} received, took={}ms since first, size={}", 
                                            correlationId, batchCount, timeSinceFirstBatch, response.getPayloadSize());
                                    }
                                }
                                lastBatchTime = batchReceivedTime;
                                totalBytes.addAndGet(response.getPayloadSize());
                            }
                            
                            // Add interim batch metrics to timing map
                            if (!interimBatchLatencies.isEmpty()) {
                                Map<String, Object> currentTiming = capturedTiming.get();
                                if (currentTiming != null) {
                                    currentTiming.put("interim_batch_count", interimBatchLatencies.size());
                                    currentTiming.put("interim_batch_avg_latency_ms", 
                                        interimBatchLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0));
                                    currentTiming.put("interim_batch_max_latency_ms", 
                                        interimBatchLatencies.stream().mapToLong(Long::longValue).max().orElse(0L));
                                    currentTiming.put("interim_batch_min_latency_ms", 
                                        interimBatchLatencies.stream().mapToLong(Long::longValue).min().orElse(0L));
                                }
                            }
                            logger.debug("[{}] [CLIENT-6] All batches consumed, count={}", correlationId, batchCount);
                            
                            long endTime = System.nanoTime();
                            long totalLatency = TimeUnit.NANOSECONDS.toMillis(endTime - requestStart);
                            long processingTime = TimeUnit.NANOSECONDS.toMillis(endTime - handlerInvokedTime);
                            long streamingTime = firstBatchTime > 0 ? TimeUnit.NANOSECONDS.toMillis(lastBatchTime - firstBatchTime) : 0;
                            long timeInNextResponse = TimeUnit.NANOSECONDS.toMillis(endTime - consumeStartTime);
                            
                            // Capture timing for slowest request only
                            if (slowestResponse != null && totalLatency > slowestRequestLatency.get()) {
                                slowestRequestLatency.set(totalLatency);
                                Map<String, Object> timing = new HashMap<>();
                                timing.put("client_start_ns", slowestResponse.getClientStartNanos());
                                timing.put("client_send_ns", slowestResponse.getClientSendNanos());
                                timing.put("server_receive_ns", slowestResponse.getServerReceiveNanos());
                                timing.put("server_generated_first_batch_ns", slowestResponse.getServerGeneratedFirstBatchNanos());
                                timing.put("server_sent_to_queue_ns", slowestResponse.getServerSentToQueueNanos());
                                timing.put("message_picked_from_queue_ns", slowestResponse.getMessagePickedFromQueueNanos());
                                timing.put("message_written_to_channel_ns", slowestResponse.getMessageWrittenToChannelNanos());
                                timing.put("client_received_header_ns", slowestClientReceivedHeaderNanos);
                                timing.put("client_received_message_ns", slowestClientReceivedMessageNanos);
                                
                                if (slowestResponse.getClientStartNanos() > 0 && slowestClientReceivedMessageNanos > slowestResponse.getClientStartNanos()) {
                                    timing.put("total_latency_ms", (slowestClientReceivedMessageNanos - slowestResponse.getClientStartNanos()) / 1_000_000.0);
                                }
                                if (slowestResponse.getClientSendNanos() > 0 && slowestResponse.getServerReceiveNanos() > slowestResponse.getClientSendNanos()) {
                                    timing.put("network_latency_ms", (slowestResponse.getServerReceiveNanos() - slowestResponse.getClientSendNanos()) / 1_000_000.0);
                                }
                                if (slowestResponse.getServerReceiveNanos() > 0 && slowestResponse.getServerGeneratedFirstBatchNanos() > slowestResponse.getServerReceiveNanos()) {
                                    timing.put("server_processing_to_first_batch_ms", (slowestResponse.getServerGeneratedFirstBatchNanos() - slowestResponse.getServerReceiveNanos()) / 1_000_000.0);
                                }
                                if (slowestResponse.getServerGeneratedFirstBatchNanos() > 0 && slowestResponse.getServerSentToQueueNanos() > slowestResponse.getServerGeneratedFirstBatchNanos()) {
                                    timing.put("first_batch_generation_to_queue_ms", (slowestResponse.getServerSentToQueueNanos() - slowestResponse.getServerGeneratedFirstBatchNanos()) / 1_000_000.0);
                                }
                                if (slowestResponse.getServerSentToQueueNanos() > 0 && slowestResponse.getMessageWrittenToChannelNanos() > slowestResponse.getServerSentToQueueNanos()) {
                                    timing.put("first_batch_queue_to_network_ms", (slowestResponse.getMessageWrittenToChannelNanos() - slowestResponse.getServerSentToQueueNanos()) / 1_000_000.0);
                                }
                                if (slowestResponse.getMessageWrittenToChannelNanos() > 0 && slowestClientReceivedHeaderNanos > slowestResponse.getMessageWrittenToChannelNanos()) {
                                    timing.put("first_batch_network_to_header_ms", (slowestClientReceivedHeaderNanos - slowestResponse.getMessageWrittenToChannelNanos()) / 1_000_000.0);
                                }
                                if (slowestClientReceivedHeaderNanos > 0 && slowestClientReceivedMessageNanos > slowestClientReceivedHeaderNanos) {
                                    timing.put("header_to_first_batch_ms", (slowestClientReceivedMessageNanos - slowestClientReceivedHeaderNanos) / 1_000_000.0);
                                }
                                timing.put("threadpool_client_start", slowestResponse.getClientStartThreadPoolState());
                                timing.put("threadpool_client_send", slowestResponse.getClientSendThreadPoolState());
                                timing.put("threadpool_server_receive", slowestResponse.getServerReceiveThreadPoolState());
                                timing.put("threadpool_server_generated", slowestResponse.getServerGeneratedThreadPoolState());
                                capturedTiming.set(timing);
                            }
                            
                            if (totalLatency > 5000) {
                                logger.warn("[CLIENT] Total: {}ms, network: {}ms, startConsume: {}ms, nextResponse: {}ms, streaming: {}ms, batches: {}",
                                    totalLatency, networkTime, timeToStartConsuming, timeInNextResponse, streamingTime, batchCount);
                            }
                            
                            totalRows.addAndGet(request.getRows());
                            latencies.add(totalLatency);
                            streamResponse.close();
                    } catch (Exception e) {
                        logger.error("Error processing stream response, correlationId={}", correlationId, e);
                        streamResponse.cancel("Error", e);
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void handleException(TransportException exp) {
                    logger.error("Stream transport request failed", exp);
                    latch.countDown();
                }

                @Override
                public String executor() {
                    return StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME;
                }

                @Override
                public BenchmarkDataResponse read(StreamInput in) throws IOException {
                    return new BenchmarkDataResponse(in);
                }
            }
        );
    }

    private void sendRegularRequest(
        DiscoveryNode targetNode,
        BenchmarkStreamRequest request,
        long requestStart,
        AtomicLong totalRows,
        AtomicLong totalBytes,
        List<Long> latencies,
        CountDownLatch latch
    ) {
        transportService.sendRequest(
            targetNode,
            SHARD_ACTION_NAME,
            request,
            new TransportResponseHandler<BenchmarkDataResponse>() {
                @Override
                public void handleResponse(BenchmarkDataResponse response) {
                    totalRows.addAndGet(request.getRows());
                    totalBytes.addAndGet(response.getPayloadSize());
                    latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStart));
                    latch.countDown();
                }

                @Override
                public void handleException(TransportException exp) {
                    logger.error("Regular transport request failed", exp);
                    latch.countDown();
                }

                @Override
                public String executor() {
                    return StreamTransportExamplePlugin.BENCHMARK_THREAD_POOL_NAME;
                }

                @Override
                public BenchmarkDataResponse read(StreamInput in) throws IOException {
                    return new BenchmarkDataResponse(in);
                }
            }
        );
    }



    private long calculateTotalBytes(BenchmarkStreamRequest request) {
        return (long) request.getRows() * request.getColumns() * request.getAvgColumnLength();
    }

    private long calculateBytesPerRow(BenchmarkStreamRequest request) {
        return (long) request.getColumns() * request.getAvgColumnLength();
    }

    private byte[] generateSyntheticData(long bytes) {
        if (bytes <= 0) return new byte[0];
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Payload size " + bytes + " bytes exceeds max array size for regular transport. "
                + "Use stream transport (use_stream_transport=true) for payloads larger than 2GB."
            );
        }
        byte[] data = new byte[(int) bytes];
        // Fill with non-zero pattern to prevent compression
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    private BenchmarkStreamResponse calculateStats(
        long totalRows,
        long totalBytes,
        long durationMs,
        List<Long> latencies,
        int parallelRequests,
        boolean usedStreamTransport,
        BenchmarkStreamResponse.ThreadPoolStats threadPoolStats,
        Map<String, Object> timing
    ) {
        if (latencies.isEmpty()) {
            return new BenchmarkStreamResponse(0, 0, durationMs, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, parallelRequests, usedStreamTransport, threadPoolStats, timing, null);
        }

        Collections.sort(latencies);

        double durationSec = durationMs / MS_TO_SEC;
        double throughputRowsPerSec = totalRows / durationSec;
        double throughputMbPerSec = (totalBytes / BYTES_TO_MB) / durationSec;

        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        long avg = (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long p5 = latencies.get(Math.min((int) (latencies.size() * 0.05), latencies.size() - 1));
        long p10 = latencies.get(Math.min((int) (latencies.size() * 0.10), latencies.size() - 1));
        long p20 = latencies.get(Math.min((int) (latencies.size() * 0.20), latencies.size() - 1));
        long p25 = latencies.get(Math.min((int) (latencies.size() * 0.25), latencies.size() - 1));
        long p35 = latencies.get(Math.min((int) (latencies.size() * 0.35), latencies.size() - 1));
        long p50 = latencies.get(Math.min((int) (latencies.size() * 0.50), latencies.size() - 1));
        long p75 = latencies.get(Math.min((int) (latencies.size() * 0.75), latencies.size() - 1));
        long p90 = latencies.get(Math.min((int) (latencies.size() * 0.90), latencies.size() - 1));
        long p99 = latencies.get(Math.min((int) (latencies.size() * 0.99), latencies.size() - 1));

        return new BenchmarkStreamResponse(
            totalRows, totalBytes, durationMs,
            throughputRowsPerSec, throughputMbPerSec,
            min, max, avg, p5, p10, p20, p25, p35, p50, p75, p90, p99,
            parallelRequests, usedStreamTransport, threadPoolStats, timing, null
        );
    }

    private static class ThreadPoolSnapshot {
        final Map<String, PoolStats> pools;

        ThreadPoolSnapshot(Map<String, PoolStats> pools) {
            this.pools = pools;
        }

        static class PoolStats {
            final long queue;
            final long completed;
            final int active;
            final long totalWaitTimeNanos;

            PoolStats(long queue, long completed, int active, long totalWaitTimeNanos) {
                this.queue = queue;
                this.completed = completed;
                this.active = active;
                this.totalWaitTimeNanos = totalWaitTimeNanos;
            }
        }
    }

    private ThreadPoolSnapshot captureThreadPoolStats(String[] poolNames) {
        Map<String, ThreadPoolSnapshot.PoolStats> pools = new HashMap<>();
        Set<String> availablePools = new HashSet<>();
        for (Stats stats : threadPool.stats()) {
            availablePools.add(stats.getName());
            for (String poolName : poolNames) {
                if (stats.getName().equals(poolName)) {
                    pools.put(poolName, new ThreadPoolSnapshot.PoolStats(
                        stats.getQueue(),
                        stats.getCompleted(),
                        stats.getActive(),
                        stats.getWaitTimeNanos()
                    ));
                    break;
                }
            }
        }

        return new ThreadPoolSnapshot(pools);
    }

    private BenchmarkStreamResponse.ThreadPoolStats calculateThreadPoolDiff(
        ThreadPoolSnapshot before,
        ThreadPoolSnapshot after
    ) {
        List<BenchmarkStreamResponse.PoolStat> poolStats = new ArrayList<>();
        for (String poolName : after.pools.keySet()) {
            ThreadPoolSnapshot.PoolStats beforeStats = before.pools.get(poolName);
            ThreadPoolSnapshot.PoolStats afterStats = after.pools.get(poolName);
            if (beforeStats != null && afterStats != null) {
                long waitTimeDiffNanos = afterStats.totalWaitTimeNanos - beforeStats.totalWaitTimeNanos;
                poolStats.add(new BenchmarkStreamResponse.PoolStat(
                    poolName,
                    afterStats.queue - beforeStats.queue,
                    afterStats.completed - beforeStats.completed,
                    afterStats.active,
                    TimeUnit.NANOSECONDS.toMillis(waitTimeDiffNanos),
                    afterStats.active,
                    (int) afterStats.queue,
                    poolName.contains("flight") ? new int[]{0} : null // Placeholder for event loop pending
                ));
            }
        }
        return new BenchmarkStreamResponse.ThreadPoolStats(poolStats);
    }

    private String captureThreadPoolState(String[] poolNames) {
        StringBuilder state = new StringBuilder();
        for (Stats stats : threadPool.stats()) {
            for (String poolName : poolNames) {
                if (stats.getName().equals(poolName)) {
                    if (state.length() > 0) state.append(", ");
                    state.append(poolName).append("[a=").append(stats.getActive())
                        .append(",q=").append(stats.getQueue()).append("]");
                    break;
                }
            }
        }
        return state.toString();
    }
}
