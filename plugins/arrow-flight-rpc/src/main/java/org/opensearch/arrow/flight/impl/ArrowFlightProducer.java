/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.impl;

import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.NoOpFlightProducer;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.common.bytes.ReleasableBytesReference;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.InboundPipeline;
import org.opensearch.transport.TcpTransport;
import org.opensearch.transport.Transport;

import java.util.Collections;

/**
 * FlightProducer implementation for handling Arrow Flight requests.
 */
public class ArrowFlightProducer extends NoOpFlightProducer {
    private final BufferAllocator allocator;
    private final InboundPipeline pipeline;

    public ArrowFlightProducer(TcpTransport nativeTransport, BufferAllocator allocator) {
        final ThreadPool threadPool = nativeTransport.getThreadPool();
        final Transport.RequestHandlers requestHandlers = nativeTransport.getRequestHandlers();
        this.pipeline = new InboundPipeline(
            nativeTransport.getVersion(),
            nativeTransport.getStatsTracker(),
            nativeTransport.getPageCacheRecycler(),
            threadPool::relativeTimeInMillis,
            nativeTransport.getInflightBreaker(),
            requestHandlers::getHandler,
            nativeTransport::inboundMessage
        );
        this.allocator = allocator;
    }

    @Override
    public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
        try {
            VectorSchemaRoot root = VectorSchemaRoot.create(new Schema(Collections.emptyList()), allocator);
            listener.start(root);
            ArrowFlightServerChannel channel = new ArrowFlightServerChannel(listener, root);
            ReleasableBytesReference content = ReleasableBytesReference.wrap(new BytesArray(ticket.getBytes()));
            pipeline.handleBytes(channel, content);
        } catch (Exception e) {
            listener.error(CallStatus.INTERNAL.withCause(e).withDescription("Failed to process stream").toRuntimeException());
        }
    }
}
