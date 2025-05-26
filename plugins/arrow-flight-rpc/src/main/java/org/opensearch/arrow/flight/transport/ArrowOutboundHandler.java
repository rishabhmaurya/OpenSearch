/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.opensearch.Version;
import org.opensearch.arrow.flight.stream.ArrowStreamOutput;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.transport.TransportResponse;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.ProtocolOutboundHandler;
import org.opensearch.transport.StatsTracker;
import org.opensearch.transport.TcpChannel;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportMessageListener;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestOptions;

import java.io.IOException;
import java.util.Set;

public class ArrowOutboundHandler extends ProtocolOutboundHandler {
    private BufferAllocator allocator;
    public volatile TransportMessageListener messageListener = TransportMessageListener.NOOP_LISTENER;
    public final String nodeName;
    public final Version version;
    public final String[] features;
    public final StatsTracker statsTracker;
    public final ThreadPool threadPool;

    public ArrowOutboundHandler(
        String nodeName,
        Version version,
        String[] features,
        StatsTracker statsTracker,
        ThreadPool threadPool
    ) {
        this.nodeName = nodeName;
        this.version = version;
        this.features = features;
        this.statsTracker = statsTracker;
        this.threadPool = threadPool;
    }

    public void setAllocator(BufferAllocator allocator) {
        this.allocator = allocator;
    }

    @Override
    public void sendRequest(DiscoveryNode node, TcpChannel channel, long requestId, String action, TransportRequest request, TransportRequestOptions options, Version channelVersion, boolean compressRequest, boolean isHandshake) throws IOException, TransportException {

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
        if (!(channel instanceof ArrowFlightServerChannel)) {
            throw new IllegalStateException("Expected ArrowFlightServerChannel, got " + channel.getClass().getName());
        }
        ArrowFlightServerChannel flightChannel = (ArrowFlightServerChannel) channel;
        ActionListener<Void> listener = ActionListener.wrap(() -> messageListener.onResponseSent(requestId, action, response));
        threadPool.executor(ThreadPool.Names.GENERIC).execute(() -> {
            try {
                if (response instanceof TransportResponse.Empty) {
                    flightChannel.complete(); // Signal completion
                    listener.onResponse(null);
                    return;
                }
                try (ArrowStreamOutput out = new ArrowStreamOutput(allocator)) {
                    response.writeTo(out);
                    VectorSchemaRoot root = out.getUnifiedRoot();
                    for (int i = 0; i < 2; i++) {
                        flightChannel.startBatch(root);
                        flightChannel.sendBatch(root);
                    }
                    flightChannel.complete();
                    listener.onResponse(null);
                }
            } catch (Exception e) {
                listener.onFailure(new TransportException("Failed to send response for action [" + action + "]", e));
            }
        });
    }

    @Override
    public void sendErrorResponse(
        final Version nodeVersion,
        final Set<String> features,
        final TcpChannel channel,
        final long requestId,
        final String action,
        final Exception error
    ) throws IOException {
        if (!(channel instanceof ArrowFlightServerChannel)) {
            throw new IllegalStateException("Expected ArrowFlightServerChannel, got " + channel.getClass().getName());
        }
        ArrowFlightServerChannel flightChannel = (ArrowFlightServerChannel) channel;
        ActionListener<Void> listener = ActionListener.wrap(() -> messageListener.onResponseSent(requestId, action, error));
        threadPool.executor(ThreadPool.Names.GENERIC).execute(() -> {
            try {
                flightChannel.sendError(error);
                listener.onResponse(null);
            } catch (Exception e) {
                listener.onFailure(new TransportException("Failed to send error response for action [" + action + "]", e));
            }
        });
    }

    @Override
    public void setMessageListener(TransportMessageListener listener) {
        if (messageListener == TransportMessageListener.NOOP_LISTENER) {
            messageListener = listener;
        } else {
            throw new IllegalStateException("Cannot set message listener twice");
        }
    }
}
