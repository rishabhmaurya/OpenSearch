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
import org.opensearch.transport.Header;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.stream.StreamErrorCode;
import org.opensearch.transport.stream.StreamException;
import org.opensearch.transport.stream.StreamTransportResponse;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.opensearch.arrow.flight.transport.ClientHeaderMiddleware.CORRELATION_ID_KEY;

/**
 * Streaming transport response implementation using Arrow Flight.
 * Manages Flight stream lifecycle with lazy initialization and prefetching support.
 */
class FlightTransportResponse<T extends TransportResponse> implements StreamTransportResponse<T> {
    private static final Logger logger = LogManager.getLogger(FlightTransportResponse.class);

    private final FlightClient flightClient;
    private final Ticket ticket;
    private final FlightCallHeaders callHeaders;
    private final NamedWriteableRegistry namedWriteableRegistry;
    private final HeaderContext headerContext;
    private final TransportResponseHandler<T> handler;
    private final FlightTransportConfig config;
    private final long correlationId;

    private volatile FlightStream flightStream;
    private volatile long currentBatchSize;
    private volatile boolean firstBatchConsumed;
    private volatile boolean closed;
    private volatile boolean prefetchStarted;
    private volatile Header initialHeader;
    private int batchNumber = 0;

    FlightTransportResponse(
        TransportResponseHandler<T> handler,
        long correlationId,
        FlightClient flightClient,
        HeaderContext headerContext,
        Ticket ticket,
        NamedWriteableRegistry namedWriteableRegistry,
        FlightTransportConfig config
    ) {
        this.handler = Objects.requireNonNull(handler);
        this.correlationId = correlationId;
        this.flightClient = Objects.requireNonNull(flightClient);
        this.headerContext = Objects.requireNonNull(headerContext);
        this.ticket = Objects.requireNonNull(ticket);
        this.namedWriteableRegistry = Objects.requireNonNull(namedWriteableRegistry);
        this.config = Objects.requireNonNull(config);
        this.callHeaders = new FlightCallHeaders();
        this.callHeaders.insert(CORRELATION_ID_KEY, String.valueOf(correlationId));
    }

    void openAndPrefetchAsync(CompletableFuture<Header> future) {
        if (prefetchStarted) return;

        synchronized (this) {
            if (prefetchStarted) return;
            if (closed) {
                future.completeExceptionally(new StreamException(StreamErrorCode.UNAVAILABLE, "Stream is closed"));
                return;
            }

            prefetchStarted = true;

            Thread.ofVirtual().start(() -> {
                try {
                    long start = System.nanoTime();
                    logger.debug("Opening stream for correlation ID: {}", correlationId);
                    flightStream = flightClient.getStream(ticket, new HeaderCallOption(callHeaders));
                    long openTime = (System.nanoTime() - start) / 1_000_000;
                    if (openTime > 10) {
                        logger.debug("FlightClient.getStream() for correlation ID: {} took {}ms", correlationId, openTime);
                    }

                    logger.debug("Prefetching first batch for correlation ID: {}", correlationId);
                    long prefetchStart = System.nanoTime();
                    flightStream.next();
                    batchNumber = 1;
                    VectorSchemaRoot root = flightStream.getRoot();
                    currentBatchSize = FlightUtils.calculateVectorSchemaRootSize(root);
                    long prefetchTime = (System.nanoTime() - prefetchStart) / 1_000_000;
                    logger.debug(
                        "First batch prefetched for correlation ID: {} in {}ms, size: {} bytes",
                        correlationId,
                        prefetchTime,
                        currentBatchSize
                    );

                    initialHeader = headerContext.getHeader(correlationId);
                    future.complete(initialHeader);
                } catch (FlightRuntimeException e) {
                    future.completeExceptionally(FlightErrorMapper.fromFlightException(e));
                } catch (Exception e) {
                    future.completeExceptionally(new StreamException(StreamErrorCode.INTERNAL, "Stream open/prefetch failed", e));
                }
            });
        }
    }

    TransportResponseHandler<T> getHandler() {
        return handler;
    }

    @Override
    public T nextResponse() {
        if (closed) throw new StreamException(StreamErrorCode.UNAVAILABLE, "Stream is closed");
        if (flightStream == null) throw new IllegalStateException("openAndPrefetch() must be called first");

        long batchRequestStart = System.currentTimeMillis();
        logger.debug("Requesting batch #{} for correlation ID: {}", batchNumber + 1, correlationId);

        try {
            boolean hasNext;
            if (!firstBatchConsumed) {
                // First batch already prefetched
                firstBatchConsumed = true;
                hasNext = true;
                logger.debug(
                    "Using prefetched batch #{} for correlation ID: {}, size: {} bytes",
                    batchNumber,
                    correlationId,
                    currentBatchSize
                );
            } else {
                // Fetch next batch
                long fetchStart = System.currentTimeMillis();
                hasNext = flightStream.next();
                if (hasNext) {
                    batchNumber++;
                    long fetchTime = System.currentTimeMillis() - fetchStart;
                    VectorSchemaRoot root = flightStream.getRoot();
                    currentBatchSize = FlightUtils.calculateVectorSchemaRootSize(root);
                    logger.debug(
                        "Batch #{} received for correlation ID: {} in {}ms, size: {} bytes",
                        batchNumber,
                        correlationId,
                        fetchTime,
                        currentBatchSize
                    );
                } else {
                    long fetchTime = System.currentTimeMillis() - fetchStart;
                    logger.debug(
                        "Stream exhausted for correlation ID: {} after {}ms, total batches: {}",
                        correlationId,
                        fetchTime,
                        batchNumber
                    );
                }
            }

            if (!hasNext) return null;

            VectorSchemaRoot root = flightStream.getRoot();
            currentBatchSize = FlightUtils.calculateVectorSchemaRootSize(root);
            try (VectorStreamInput input = new VectorStreamInput(root, namedWriteableRegistry)) {
                input.setVersion(initialHeader.getVersion());
                return handler.read(input);
            }
        } catch (FlightRuntimeException e) {
            throw FlightErrorMapper.fromFlightException(e);
        } catch (IOException e) {
            throw new StreamException(StreamErrorCode.INTERNAL, "Failed to deserialize batch", e);
        } finally {
            long took = System.currentTimeMillis() - batchRequestStart;
            if (took > config.getSlowLogThreshold().millis()) {
                logger.warn(
                    "Flight stream batch #{} for correlation ID: {} took [{}ms], exceeding threshold [{}ms]",
                    batchNumber,
                    correlationId,
                    took,
                    config.getSlowLogThreshold().millis()
                );
            }
        }
    }

    long getCurrentBatchSize() {
        return currentBatchSize;
    }

    @Override
    public void cancel(String reason, Throwable cause) {
        if (closed) return;
        try {
            if (flightStream != null) flightStream.cancel(reason, cause);
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

        if (flightStream != null) {
            try {
                flightStream.close();
            } catch (IllegalStateException ignore) {} catch (Exception e) {
                throw new StreamException(StreamErrorCode.INTERNAL, "Error closing flight stream", e);
            }
        }
    }
}
