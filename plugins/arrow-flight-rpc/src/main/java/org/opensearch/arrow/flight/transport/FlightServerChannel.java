/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.flight.CallStatus;

import org.apache.arrow.flight.FlightProducer.ServerStreamListener;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.opensearch.common.annotation.PublicApi;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.transport.TcpChannel;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * TcpChannel implementation for Arrow Flight, using ServerStreamListener for response streaming.
 *
 * @opensearch.api
 */
@PublicApi(since = "1.0.0")
public class FlightServerChannel implements TcpChannel {
    private final ServerStreamListener listener;
    private final BufferAllocator allocator;
    private VectorSchemaRoot currentRoot;
    private boolean isClosed = false;

    public FlightServerChannel(ServerStreamListener listener, BufferAllocator allocator) {
        this.listener = listener;
        this.allocator = allocator;
    }

    public BufferAllocator getAllocator() {
        return allocator;
    }

    @Override
    public boolean isServerChannel() {
        return true;
    }

    @Override
    public String getProfile() {
        return "arrow_flight";
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress("localhost", 0); // gRPC-based
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress("localhost", 0); // gRPC-based
    }

    public void startBatch(VectorSchemaRoot root) throws IOException {
        if (isClosed) {
            throw new IOException("Channel is closed");
        }
        try {
            if (currentRoot != null) {
                currentRoot.close();
            }
            currentRoot = root;
            listener.start(root);
        } catch (Exception e) {
            throw new IOException("Failed to start batch", e);
        }
    }

    public void sendBatch(VectorSchemaRoot root) throws IOException {
        if (isClosed) {
            throw new IOException("Channel is closed");
        }
        try {
            listener.putNext();
        } catch (Exception e) {
            throw new IOException("Failed to send batch", e);
        }
    }

    public void complete() throws IOException {
        if (isClosed) {
            return;
        }
        isClosed = true;
        try {
            if (currentRoot != null) {
                currentRoot.close();
                currentRoot = null;
            }
            listener.completed();
        } catch (Exception e) {
            throw new IOException("Failed to complete stream", e);
        }
    }

    public void sendError(Exception exception) throws IOException {
        if (isClosed) {
            return;
        }
        isClosed = true;
        try {
            if (currentRoot != null) {
                currentRoot.close();
                currentRoot = null;
            }
            listener.error(CallStatus.INTERNAL.withCause(exception).withDescription("Stream error").toRuntimeException());
        } catch (Exception e) {
            throw new IOException("Failed to send error", e);
        }
    }

    @Override
    public void sendMessage(BytesReference reference, ActionListener<Void> listener) {
        throw new UnsupportedOperationException("ArrowFlightServerChannel does not support BytesReference");
    }

    @Override
    public void addConnectListener(ActionListener<Void> listener) {
        // gRPC manages connections
    }

    @Override
    public ChannelStats getChannelStats() {
        return new ChannelStats();
    }

    @Override
    public void close() {
        try {
            complete();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void addCloseListener(ActionListener<Void> listener) {

    }

    @Override
    public boolean isOpen() {
        return false;
    }
}
