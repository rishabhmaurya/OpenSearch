/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.impl;

import org.apache.arrow.flight.CallStatus;

import org.apache.arrow.flight.FlightProducer.ServerStreamListener;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.opensearch.common.annotation.PublicApi;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.transport.TcpChannel;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * TcpChannel implementation for Arrow Flight, using ServerStreamListener for response streaming.
 *
 * @opensearch.api
 */
@PublicApi(since = "1.0.0")
public class ArrowFlightServerChannel implements TcpChannel {
    private final ServerStreamListener listener;
    private final VectorSchemaRoot root;
    private final ChannelStats stats = new ChannelStats();
    private volatile boolean isOpen = true;

    public ArrowFlightServerChannel(ServerStreamListener listener, VectorSchemaRoot root) {
        this.listener = listener;
        this.root = root;
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
        return new InetSocketAddress("localhost", 0); // Placeholder; use FlightService’s bound address
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return null; // Flight doesn’t expose remote address
    }

    @Override
    public void sendMessage(BytesReference reference, ActionListener<Void> listener) {
        try {
            // Assume VectorStreamOutput handles serialization to VectorSchemaRoot
            // Call putNext() to stream the current root
            this.listener.putNext();
            listener.onResponse(null);
        } catch (Exception e) {
            this.listener.error(CallStatus.INTERNAL.withCause(e).toRuntimeException());
            listener.onFailure(e);
        }
    }

    @Override
    public void addConnectListener(ActionListener<Void> listener) {
        listener.onResponse(null); // Flight connections are managed externally
    }

    @Override
    public void addCloseListener(ActionListener<Void> listener) {
        listener.onResponse(null); // Simplified; add listener to Flight cancellation if needed
    }

    @Override
    public ChannelStats getChannelStats() {
        return stats;
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public void close() {
        isOpen = false;
        listener.completed();
        root.close();
    }

    @Override
    public <T> Optional<T> get(String name, Class<T> clazz) {
        return Optional.empty();
    }
}
