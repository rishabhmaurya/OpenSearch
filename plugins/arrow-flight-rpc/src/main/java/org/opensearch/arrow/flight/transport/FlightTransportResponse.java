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
    private static final long MAX_POLL_TIMEOUT_MS = 10; // Reduced from 100ms to minimize parking overhead

    private final FlightClient flightClient;
    private final Ticket ticket;
    private final FlightCallHeaders callHeaders;
    private volatile FlightStream flightStream;
    private final NamedWriteableRegistry namedWriteableRegistry;
    private final HeaderContext headerContext;
    private final TransportResponseHandler<T> handler;
    private final ThreadPool threadPool;
    private final FlightTransportConfig config;
    private final long correlationId;

    // Lifecycle flags
    private volatile boolean streamExhausted = false;
    private volatile boolean headerFetched = false;
    private volatile boolean firstBatchConsumed = false;
    private volatile boolean closed = false;
    private final Object streamLock = new Object();

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
        this.flightClient = flightClient;
        this.ticket = ticket;
        // Prepare call headers for later use
        this.callHeaders = new FlightCallHeaders();
        this.callHeaders.insert(CORRELATION_ID_KEY, String.valueOf(correlationId));
    }

    /**
     * Initializes the FlightStream. FlightStream itself handles async I/O via gRPC.
     */
    void startPrefetching() {
        // Initialize stream - gRPC handles async I/O internally
        long start = System.nanoTime();
        this.flightStream = flightClient.getStream(ticket, new HeaderCallOption(callHeaders));
        long took = (System.nanoTime() - start) / 1_000_000;
        if (took > 5) {
            logger.warn("FlightClient.getStream() took {}ms", took);
        }
    }
    
    /**
     * Prefetches the first batch to ensure data is ready before handler invocation.
     * Returns true if first batch is available, false if stream is empty.
     */
    boolean prefetchFirstBatch() {
        try {
            boolean hasNext = flightStream.next();
            
            // Fetch header on first batch
            if (hasNext && !headerFetched) {
                headerFetched = true;
                Header header = headerContext.getHeader(correlationId);
                if (header != null) {
                    threadPool.getThreadContext().setHeaders(header.getHeaders());
                }
            }
            
            return hasNext;
        } catch (FlightRuntimeException e) {
            streamExhausted = true;
            throw FlightErrorMapper.fromFlightException(e);
        }
    }

    /**
     * Returns future that completes when first batch is ready.
     */
    CompletableFuture<Void> getFirstBatchReadyFuture() {
        return null;
    }

    /**
     * Gets handler for this response.
     */
    TransportResponseHandler<T> getHandler() {
        return handler;
    }

    /**
     * Gets next batch from stream. FlightStream.next() blocks efficiently using gRPC's async I/O.
     * Returns null when stream is exhausted.
     */
    @Override
    public T nextResponse() {
        if (closed) {
            throw new StreamException(StreamErrorCode.UNAVAILABLE, "Stream is closed");
        }

        //synchronized (streamLock) {
            return fetchNextBatch();
        //}
    }



    /**
     * Fetches next batch from Flight stream. Returns null when exhausted.
     * If first batch was prefetched, returns it on first call without fetching again.
     */
    private T fetchNextBatch() {
        long startTime = System.currentTimeMillis();
        try {
            boolean hasNext;
            
            // First call after prefetch - just deserialize the prefetched batch
            if (headerFetched && !firstBatchConsumed) {
                firstBatchConsumed = true;
                hasNext = true; // We already know first batch exists from prefetch
            } else {
                // Fetch next batch
                hasNext = flightStream.next();
                
                // Handle header if this is first batch and wasn't prefetched
                if (hasNext && !headerFetched) {
                    headerFetched = true;
                    Header header = headerContext.getHeader(correlationId);
                    if (header != null) {
                        threadPool.getThreadContext().setHeaders(header.getHeaders());
                    }
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
