/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.transport.TransportResponse;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.Header;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.stream.StreamErrorCode;
import org.opensearch.transport.stream.StreamException;
import org.opensearch.transport.stream.StreamTransportResponse;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.opensearch.arrow.flight.transport.ClientHeaderMiddleware.CORRELATION_ID_KEY;

/**
 * Streaming transport response implementation using Arrow Flight.
 *
 * <p>Provides non-blocking reactive handler invocation with continuous prefetching.
 * Handler is invoked only when first batch is ready. Prefetches up to 100MB of batches
 * in background to keep consumer fed.
 *
 * <p>Thread-safe for concurrent access from prefetch thread and consumer thread.
 */
class FlightTransportResponse<T extends TransportResponse> implements StreamTransportResponse<T> {
    private static final Logger logger = LogManager.getLogger(FlightTransportResponse.class);
    private static final long MAX_PREFETCH_BYTES = 100 * 1024 * 1024; // 100MB buffer
    private static final long INITIAL_POLL_TIMEOUT_MS = 1;
    private static final long MAX_POLL_TIMEOUT_MS = 100;

    private final FlightStream flightStream;
    private final NamedWriteableRegistry namedWriteableRegistry;
    private final HeaderContext headerContext;
    private final TransportResponseHandler<T> handler;
    private final ThreadPool threadPool;
    private final FlightTransportConfig config;
    private final long correlationId;

    // Prefetch state
    private final BlockingQueue<T> prefetchQueue = new LinkedBlockingQueue<>();
    private final AtomicLong currentPrefetchBytes = new AtomicLong(0);
    private final CompletableFuture<Void> firstBatchReady = new CompletableFuture<>();

    // Lifecycle flags
    private volatile boolean streamExhausted = false;
    private volatile boolean headerFetched = false;
    private volatile boolean closed = false;

    FlightTransportResponse(
        TransportResponseHandler<T> handler,
        long correlationId,
        FlightClient flightClient,
        HeaderContext headerContext,
        Ticket ticket,
        NamedWriteableRegistry namedWriteableRegistry,
        FlightTransportConfig config,
        ThreadPool threadPool
    ) {
        this.handler = handler;
        this.correlationId = correlationId;
        this.headerContext = Objects.requireNonNull(headerContext, "headerContext must not be null");
        this.namedWriteableRegistry = namedWriteableRegistry;
        this.config = config;
        this.threadPool = threadPool;
        // Initialize Flight stream with correlation ID header
        FlightCallHeaders callHeaders = new FlightCallHeaders();
        callHeaders.insert(CORRELATION_ID_KEY, String.valueOf(correlationId));
        long start = System.nanoTime();
        this.flightStream = flightClient.getStream(ticket, new HeaderCallOption(callHeaders));
        long took = (System.nanoTime() - start) / 1_000_000;
        if (took > 5) {
            logger.warn("FlightClient.getStream() took {}ms - gRPC bottleneck!", took);
        }
    }

    /**
     * Starts background prefetching. Returns immediately.
     */
    void startPrefetching() {
        Thread.ofVirtual().start(this::prefetchLoop);
    }

    /**
     * Returns future that completes when first batch is ready.
     */
    CompletableFuture<Void> getFirstBatchReadyFuture() {
        return firstBatchReady;
    }

    /**
     * Gets handler for this response.
     */
    TransportResponseHandler<T> getHandler() {
        return handler;
    }

    /**
     * Gets next batch from stream. Blocks if no batch available yet.
     * Returns null when stream is exhausted.
     *
     * <p>Uses poll-check-poll pattern with exponential backoff to avoid race condition
     * with stream exhaustion while minimizing platform thread blocking time.
     */
    @Override
    public T nextResponse() {
        if (closed) {
            throw new StreamException(StreamErrorCode.UNAVAILABLE, "Stream is closed");
        }

        try {
            long currentTimeout = INITIAL_POLL_TIMEOUT_MS;
            
            while (true) {
                // Try non-blocking poll first
                T batch = prefetchQueue.poll();
                if (batch != null) {
                    currentPrefetchBytes.addAndGet(-FlightUtils.calculateResponseSize(batch));
                    return batch;
                }

                // Check if stream exhausted after poll (avoids race)
                if (streamExhausted && prefetchQueue.isEmpty()) {
                    return null;
                }

                // Poll with exponential backoff: 1ms -> 2ms -> 4ms -> 8ms -> 16ms -> 32ms -> 64ms -> 100ms (max)
                long waitStart = System.nanoTime();
                batch = prefetchQueue.poll(currentTimeout, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    long waitTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart);
                    if (waitTimeMs > 10) {
                        logger.debug("Client blocked {}ms waiting for batch (server slow), correlationId={}", waitTimeMs, correlationId);
                    }
                    currentPrefetchBytes.addAndGet(-FlightUtils.calculateResponseSize(batch));
                    return batch;
                }
                
                // Exponential backoff: double timeout up to max
                currentTimeout = Math.min(currentTimeout * 2, MAX_POLL_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StreamException(StreamErrorCode.CANCELLED, "Interrupted while reading stream", e);
        }
    }

    /**
     * Prefetch loop - runs in virtual thread until stream exhausted.
     */
    private void prefetchLoop() {
        try {
            while (!streamExhausted) {
                // Wait if buffer full
                while (currentPrefetchBytes.get() >= MAX_PREFETCH_BYTES && !streamExhausted) {
                    Thread.sleep(MAX_POLL_TIMEOUT_MS);
                }

                if (streamExhausted) break;

                T batch = fetchNextBatch();
                if (batch != null) {
                    prefetchQueue.offer(batch);
                    currentPrefetchBytes.addAndGet(FlightUtils.calculateResponseSize(batch));

                    // Signal first batch ready
                    if (!firstBatchReady.isDone()) {
                        firstBatchReady.complete(null);
                    }
                }
            }

            // Ensure first batch ready is completed even if stream empty
            if (!firstBatchReady.isDone()) {
                firstBatchReady.complete(null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!firstBatchReady.isDone()) {
                firstBatchReady.completeExceptionally(e);
            }
        } catch (Exception e) {
            logger.warn("Prefetch failed for correlationId={}", correlationId, e);
            if (!firstBatchReady.isDone()) {
                firstBatchReady.completeExceptionally(e);
            }
        }
    }

    /**
     * Fetches next batch from Flight stream. Returns null when exhausted.
     */
    private T fetchNextBatch() {
        long startTime = System.currentTimeMillis();
        try {
            boolean hasNext = flightStream.next();

            // Fetch header on first batch
            if (!headerFetched) {
                headerFetched = true;
                Header header = headerContext.getHeader(correlationId);
                if (header != null) {
                    threadPool.getThreadContext().setHeaders(header.getHeaders());
                }
            }

            if (hasNext) {
                VectorSchemaRoot root = flightStream.getRoot();
                try (VectorStreamInput input = new VectorStreamInput(root, namedWriteableRegistry)) {
                    return handler.read(input);
                }
            }

            streamExhausted = true;
            return null;
        } catch (FlightRuntimeException e) {
            streamExhausted = true;
            throw FlightErrorMapper.fromFlightException(e);
        } catch (IOException e) {
            streamExhausted = true;
            throw new StreamException(StreamErrorCode.INTERNAL, "Failed to deserialize batch", e);
        } finally {
            long took = System.currentTimeMillis() - startTime;
            if (took > config.getSlowLogThreshold().millis()) {
                logger.warn("Flight stream next() took [{}ms], exceeding threshold [{}ms]",
                    took, config.getSlowLogThreshold().millis());
            }
        }
    }

    @Override
    public void cancel(String reason, Throwable cause) {
        if (closed) return;

        try {
            flightStream.cancel(reason, cause);
            logger.debug("Cancelled flight stream: {}", reason);
        } catch (Exception e) {
            logger.warn("Error cancelling flight stream", e);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (closed) return;

        closed = true;
        try {
            flightStream.close();
        } catch (IllegalStateException ignore) {
            // Allocator already closed
        } catch (Exception e) {
            throw new StreamException(StreamErrorCode.INTERNAL, "Error closing flight stream", e);
        }
    }
}
