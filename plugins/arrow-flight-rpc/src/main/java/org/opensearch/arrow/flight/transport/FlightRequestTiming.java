/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.opensearch.common.util.concurrent.ThreadContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks Flight request timing in ThreadContext headers (propagates across network)
 */
public class FlightRequestTiming {
    // Header keys - these propagate across network boundaries
    public static final String FLIGHT_CLIENT_START = "flight_client_start";
    public static final String FLIGHT_CLIENT_SEND = "flight_client_send";
    public static final String FLIGHT_CLIENT_SEND_EXECUTOR = "flight_client_send_executor";
    public static final String FLIGHT_SERVER_RECEIVE = "flight_server_receive";
    public static final String FLIGHT_SERVER_RECEIVE_EXECUTOR = "flight_server_receive_executor";
    public static final String FLIGHT_SERVER_FIRST_BATCH_GENERATED = "flight_server_first_batch_generated";
    public static final String FLIGHT_SERVER_FIRST_BATCH_QUEUED = "flight_server_first_batch_queued";
    public static final String FLIGHT_SERVER_FIRST_BATCH_SENT = "flight_server_first_batch_sent";
    public static final String FLIGHT_CLIENT_HEADER_RECEIVED = "flight_client_header_received";
    public static final String FLIGHT_CLIENT_FIRST_BATCH = "flight_client_first_batch";
    public static final String FLIGHT_SERVER_NEXT_BATCH_GENERATED = "flight_server_next_batch_generated";
    public static final String FLIGHT_SERVER_NEXT_BATCH_QUEUED = "flight_server_next_batch_queued";
    public static final String FLIGHT_SERVER_NEXT_BATCH_SENT = "flight_server_next_batch_sent";
    public static final String FLIGHT_CLIENT_NEXT_BATCH = "flight_client_next_batch";
    public static final String FLIGHT_SERVER_COMPLETE = "flight_server_complete";
    public static final String FLIGHT_CLIENT_COMPLETE = "flight_client_complete";
    public static final String FLIGHT_BATCH_COUNT = "flight_batch_count";
    public static final String FLIGHT_FIRST_BATCH_COUNT = "flight_first_batch_count";
    public static final String FLIGHT_NEXT_BATCH_COUNT = "flight_next_batch_count";
    
    public static void recordClientStart(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_CLIENT_START, String.valueOf(System.nanoTime()));
    }
    
    public static void recordClientSend(ThreadContext ctx, String executorState) {
        ctx.putHeader(FLIGHT_CLIENT_SEND, String.valueOf(System.nanoTime()));
        ctx.putHeader(FLIGHT_CLIENT_SEND_EXECUTOR, executorState);
    }
    
    public static void recordServerReceive(ThreadContext ctx, String executorState) {
        ctx.putHeader(FLIGHT_SERVER_RECEIVE, String.valueOf(System.nanoTime()));
        ctx.putHeader(FLIGHT_SERVER_RECEIVE_EXECUTOR, executorState);
    }
    
    public static void recordServerFirstBatchGenerated(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_SERVER_FIRST_BATCH_GENERATED, String.valueOf(System.nanoTime()));
    }
    
    public static void recordServerFirstBatchQueued(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_SERVER_FIRST_BATCH_QUEUED, String.valueOf(System.nanoTime()));
    }
    
    public static void recordServerFirstBatchSent(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_SERVER_FIRST_BATCH_SENT, String.valueOf(System.nanoTime()));
    }
    
    public static void recordClientHeaderReceived(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_CLIENT_HEADER_RECEIVED, String.valueOf(System.nanoTime()));
    }
    
    public static void recordClientFirstBatch(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_CLIENT_FIRST_BATCH, String.valueOf(System.nanoTime()));
        incrementFirstBatchCount(ctx);
    }
    
    public static void recordServerNextBatchGenerated(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_SERVER_NEXT_BATCH_GENERATED, String.valueOf(System.nanoTime()));
    }
    
    public static void recordServerNextBatchQueued(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_SERVER_NEXT_BATCH_QUEUED, String.valueOf(System.nanoTime()));
    }
    
    public static void recordServerNextBatchSent(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_SERVER_NEXT_BATCH_SENT, String.valueOf(System.nanoTime()));
    }
    
    public static void recordClientNextBatch(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_CLIENT_NEXT_BATCH, String.valueOf(System.nanoTime()));
        incrementNextBatchCount(ctx);
    }
    
    public static void recordServerComplete(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_SERVER_COMPLETE, String.valueOf(System.nanoTime()));
    }
    
    public static void recordClientComplete(ThreadContext ctx) {
        ctx.putHeader(FLIGHT_CLIENT_COMPLETE, String.valueOf(System.nanoTime()));
    }
    
    public static void incrementBatchCount(ThreadContext ctx) {
        String current = ctx.getHeader(FLIGHT_BATCH_COUNT);
        int count = current != null ? Integer.parseInt(current) : 0;
        ctx.putHeader(FLIGHT_BATCH_COUNT, String.valueOf(count + 1));
    }
    
    public static void incrementFirstBatchCount(ThreadContext ctx) {
        String current = ctx.getHeader(FLIGHT_FIRST_BATCH_COUNT);
        int count = current != null ? Integer.parseInt(current) : 0;
        ctx.putHeader(FLIGHT_FIRST_BATCH_COUNT, String.valueOf(count + 1));
    }
    
    public static void incrementNextBatchCount(ThreadContext ctx) {
        String current = ctx.getHeader(FLIGHT_NEXT_BATCH_COUNT);
        int count = current != null ? Integer.parseInt(current) : 0;
        ctx.putHeader(FLIGHT_NEXT_BATCH_COUNT, String.valueOf(count + 1));
    }
    
    public static Map<String, Object> extractMetrics(ThreadContext ctx) {
        Map<String, Object> metrics = new HashMap<>();
        
        Long clientStart = getLong(ctx, FLIGHT_CLIENT_START);
        Long clientSend = getLong(ctx, FLIGHT_CLIENT_SEND);
        Long serverReceive = getLong(ctx, FLIGHT_SERVER_RECEIVE);
        Long serverFirstBatchGenerated = getLong(ctx, FLIGHT_SERVER_FIRST_BATCH_GENERATED);
        Long serverFirstBatchQueued = getLong(ctx, FLIGHT_SERVER_FIRST_BATCH_QUEUED);
        Long serverFirstBatchSent = getLong(ctx, FLIGHT_SERVER_FIRST_BATCH_SENT);
        Long clientHeaderReceived = getLong(ctx, FLIGHT_CLIENT_HEADER_RECEIVED);
        Long clientFirstBatch = getLong(ctx, FLIGHT_CLIENT_FIRST_BATCH);
        Long serverNextBatchGenerated = getLong(ctx, FLIGHT_SERVER_NEXT_BATCH_GENERATED);
        Long serverNextBatchQueued = getLong(ctx, FLIGHT_SERVER_NEXT_BATCH_QUEUED);
        Long serverNextBatchSent = getLong(ctx, FLIGHT_SERVER_NEXT_BATCH_SENT);
        Long clientNextBatch = getLong(ctx, FLIGHT_CLIENT_NEXT_BATCH);
        Long serverComplete = getLong(ctx, FLIGHT_SERVER_COMPLETE);
        Long clientComplete = getLong(ctx, FLIGHT_CLIENT_COMPLETE);
        
        // Raw timestamps (nanoseconds)
        addIfPresent(metrics, "client_start_ns", clientStart);
        addIfPresent(metrics, "client_send_ns", clientSend);
        addIfPresent(metrics, "server_receive_ns", serverReceive);
        addIfPresent(metrics, "server_first_batch_generated_ns", serverFirstBatchGenerated);
        addIfPresent(metrics, "server_first_batch_queued_ns", serverFirstBatchQueued);
        addIfPresent(metrics, "server_first_batch_sent_ns", serverFirstBatchSent);
        addIfPresent(metrics, "client_header_received_ns", clientHeaderReceived);
        addIfPresent(metrics, "client_first_batch_ns", clientFirstBatch);
        addIfPresent(metrics, "server_next_batch_generated_ns", serverNextBatchGenerated);
        addIfPresent(metrics, "server_next_batch_queued_ns", serverNextBatchQueued);
        addIfPresent(metrics, "server_next_batch_sent_ns", serverNextBatchSent);
        addIfPresent(metrics, "client_next_batch_ns", clientNextBatch);
        addIfPresent(metrics, "server_complete_ns", serverComplete);
        addIfPresent(metrics, "client_complete_ns", clientComplete);
        
        // Calculated latencies (milliseconds)
        if (clientStart != null && clientComplete != null) {
            metrics.put("total_latency_ms", (clientComplete - clientStart) / 1_000_000.0);
        }
        if (clientSend != null && serverReceive != null) {
            metrics.put("network_latency_ms", (serverReceive - clientSend) / 1_000_000.0);
        }
        if (serverReceive != null && serverFirstBatchGenerated != null) {
            metrics.put("server_processing_to_first_batch_ms", (serverFirstBatchGenerated - serverReceive) / 1_000_000.0);
        }
        if (serverFirstBatchGenerated != null && serverFirstBatchQueued != null) {
            metrics.put("first_batch_generation_to_queue_ms", (serverFirstBatchQueued - serverFirstBatchGenerated) / 1_000_000.0);
        }
        if (serverFirstBatchQueued != null && serverFirstBatchSent != null) {
            metrics.put("first_batch_queue_to_network_ms", (serverFirstBatchSent - serverFirstBatchQueued) / 1_000_000.0);
        }
        if (serverFirstBatchSent != null && clientHeaderReceived != null) {
            metrics.put("first_batch_network_to_header_ms", (clientHeaderReceived - serverFirstBatchSent) / 1_000_000.0);
        }
        if (clientHeaderReceived != null && clientFirstBatch != null) {
            metrics.put("header_to_first_batch_ms", (clientFirstBatch - clientHeaderReceived) / 1_000_000.0);
        }
        
        // Next batch latencies (if available)
        if (serverNextBatchGenerated != null && serverNextBatchQueued != null) {
            metrics.put("next_batch_generation_to_queue_ms", (serverNextBatchQueued - serverNextBatchGenerated) / 1_000_000.0);
        }
        if (serverNextBatchQueued != null && serverNextBatchSent != null) {
            metrics.put("next_batch_queue_to_network_ms", (serverNextBatchSent - serverNextBatchQueued) / 1_000_000.0);
        }
        if (serverNextBatchSent != null && clientNextBatch != null) {
            metrics.put("next_batch_network_latency_ms", (clientNextBatch - serverNextBatchSent) / 1_000_000.0);
        }
        
        // Batch counts
        String batchCount = ctx.getHeader(FLIGHT_BATCH_COUNT);
        if (batchCount != null) {
            metrics.put("total_batch_count", Integer.parseInt(batchCount));
        }
        String firstBatchCount = ctx.getHeader(FLIGHT_FIRST_BATCH_COUNT);
        if (firstBatchCount != null) {
            metrics.put("first_batch_count", Integer.parseInt(firstBatchCount));
        }
        String nextBatchCount = ctx.getHeader(FLIGHT_NEXT_BATCH_COUNT);
        if (nextBatchCount != null) {
            metrics.put("next_batch_count", Integer.parseInt(nextBatchCount));
        }
        
        // Executor states
        addIfPresent(metrics, "executor_client_send", ctx.getHeader(FLIGHT_CLIENT_SEND_EXECUTOR));
        addIfPresent(metrics, "executor_server_receive", ctx.getHeader(FLIGHT_SERVER_RECEIVE_EXECUTOR));
        
        return metrics;
    }
    
    private static Long getLong(ThreadContext ctx, String key) {
        String value = ctx.getHeader(key);
        return value != null ? Long.parseLong(value) : null;
    }
    
    private static void addIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
    
    private static void addIfPresent(Map<String, Object> map, String key, Long value) {
        if (value != null) {
            map.put(key, value);
        }
    }
    
    public static String captureExecutorState(int serverActive, int serverQueue, int clientActive, int clientQueue, int[] eventLoopPending) {
        return String.format("srv_a=%d,srv_q=%d,cli_a=%d,cli_q=%d,evl=%s",
            serverActive, serverQueue, clientActive, clientQueue, formatArray(eventLoopPending));
    }
    
    public static Map<String, Object> captureThreadPoolMetrics(int flightServerActive, int flightServerQueue, 
                                                               int flightClientActive, int flightClientQueue,
                                                               int[] flightEventLoopPending,
                                                               int benchmarkActive, int benchmarkQueue) {
        Map<String, Object> threadPoolMetrics = new HashMap<>();
        threadPoolMetrics.put("flight_server_active", flightServerActive);
        threadPoolMetrics.put("flight_server_queue", flightServerQueue);
        threadPoolMetrics.put("flight_client_active", flightClientActive);
        threadPoolMetrics.put("flight_client_queue", flightClientQueue);
        threadPoolMetrics.put("flight_event_loop_pending", flightEventLoopPending);
        threadPoolMetrics.put("benchmark_active", benchmarkActive);
        threadPoolMetrics.put("benchmark_queue", benchmarkQueue);
        return threadPoolMetrics;
    }
    
    private static String formatArray(int[] arr) {
        if (arr == null || arr.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
