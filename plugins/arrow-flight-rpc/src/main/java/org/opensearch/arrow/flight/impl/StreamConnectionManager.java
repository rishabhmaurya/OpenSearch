/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.impl;

import org.apache.arrow.flight.CallOptions;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.opensearch.arrow.flight.bootstrap.FlightClientManager;
import org.opensearch.arrow.spi.StreamReader;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.transport.ConnectTransportException;
import org.opensearch.transport.ConnectionManager;
import org.opensearch.transport.ConnectionProfile;
import org.opensearch.transport.NodeNotConnectedException;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportConnectionListener;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportResponseHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class StreamConnectionManager implements ConnectionManager {
    private final FlightClientManager flightClientManager;
    private final ConcurrentHashMap<DiscoveryNode, FlightConnection> connections = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<TransportConnectionListener> listeners = new CopyOnWriteArrayList<>();
    private final Transport.ResponseHandlers responseHandlers;

    private volatile boolean closed = false;

    public StreamConnectionManager(FlightClientManager flightClientManager, Transport.ResponseHandlers responseHandlers) {
        this.flightClientManager = flightClientManager;
        this.responseHandlers = responseHandlers;
    }

    @Override
    public void addListener(TransportConnectionListener listener) {
        listeners.addIfAbsent(listener);
    }

    @Override
    public void removeListener(TransportConnectionListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void openConnection(DiscoveryNode node, ConnectionProfile connectionProfile, ActionListener<Transport.Connection> listener) {
        if (closed) {
            listener.onFailure(new IllegalStateException("StreamConnectionManager is closed"));
            return;
        }
        // Ensure the connection profile is for STREAM
        if (connectionProfile.getNumConnectionsPerType(TransportRequestOptions.Type.STREAM) == 0) {
            listener.onFailure(new IllegalArgumentException("Connection profile must support STREAM type"));
            return;
        }
        CompletableFuture<FlightClient> completableFuture = new CompletableFuture<>();
        completableFuture.thenAccept(client -> {
            FlightConnection connection = connections.computeIfAbsent(node, n -> new FlightConnection(node, client, responseHandlers));
            listeners.forEach(l -> l.onConnectionOpened(connection));
            listeners.forEach(l -> l.onNodeConnected(node, connection));
            listener.onResponse(connection);
        })
        .exceptionally(throwable -> {
            listener.onFailure(new ConnectTransportException(node, "Failed to build Flight client", throwable));
            return null;
        });
        flightClientManager.getFlightClient(node.getId()).ifPresentOrElse(
            client -> {
                FlightConnection connection = connections.computeIfAbsent(node, n -> new FlightConnection(node, client, responseHandlers));
                listeners.forEach(l -> l.onConnectionOpened(connection));
                listeners.forEach(l -> l.onNodeConnected(node, connection));
                listener.onResponse(connection);
            },
            () -> flightClientManager.buildClientFuture(node.getId(), completableFuture)
        );
    }

    @Override
    public void connectToNode(
        DiscoveryNode node,
        ConnectionProfile connectionProfile,
        ConnectionValidator connectionValidator,
        ActionListener<Void> listener
    ) throws ConnectTransportException {
        openConnection(node, connectionProfile, ActionListener.wrap(connection -> {
            connectionValidator.validate(connection, connectionProfile, listener);
        }, listener::onFailure));
    }

    @Override
    public Transport.Connection getConnection(DiscoveryNode node) {
        FlightConnection connection = connections.get(node);
        if (connection == null) {
            throw new NodeNotConnectedException(node, "No stream connection to node");
        }
        return connection;
    }

    @Override
    public boolean nodeConnected(DiscoveryNode node) {
        return connections.containsKey(node);
    }

    @Override
    public void disconnectFromNode(DiscoveryNode node) {
        FlightConnection connection = connections.remove(node);
        if (connection != null) {
            connection.close();
            listeners.forEach(l -> l.onNodeDisconnected(node, connection));
            listeners.forEach(l -> l.onConnectionClosed(connection));
        }
    }

    @Override
    public void setPendingDisconnection(DiscoveryNode node) {
        // Optionally implement if needed
    }

    @Override
    public void clearPendingDisconnections() {
        // Optionally implement if needed
    }

    @Override
    public Set<DiscoveryNode> getAllConnectedNodes() {
        return Set.copyOf(connections.keySet());
    }

    @Override
    public int size() {
        return connections.size();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            connections.values().forEach(FlightConnection::close);
            connections.clear();
            try {
                flightClientManager.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed to close FlightClientManager", e);
            }
        }
    }

    @Override
    public void closeNoBlock() {
        close();
    }

    @Override
    public ConnectionProfile getConnectionProfile() {
        // Return a profile dedicated to STREAM
        return ConnectionProfile.buildSingleChannelProfile(TransportRequestOptions.Type.STREAM);
    }

    private static class FlightConnection implements Transport.Connection {
        private final DiscoveryNode node;
        private final FlightClient flightClient;
        private volatile boolean isClosed = false;
        private final CopyOnWriteArrayList<ActionListener<Void>> closeListeners = new CopyOnWriteArrayList<>();
        private final Transport.ResponseHandlers responseHandlers;

        FlightConnection(DiscoveryNode node, FlightClient flightClient, Transport.ResponseHandlers responseHandlers) {
            this.node = node;
            this.flightClient = flightClient;
            this.responseHandlers = responseHandlers;
        }

        @Override
        public DiscoveryNode getNode() {
            return node;
        }

        @Override
        public void sendRequest(
            long requestId,
            String action,
            TransportRequest request,
            TransportRequestOptions options
        ) throws IOException, TransportException {
            if (isClosed) {
                throw new NodeNotConnectedException(node, "Flight connection is closed");
            }
            // Serialize the TransportRequest
            byte[] requestBytes;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 StreamOutput out = new BytesStreamOutput()) {
                request.writeTo(out);
                requestBytes = baos.toByteArray();
            }
            FlightStream flightStream;
            try {
                flightStream = flightClient.getStream(new Ticket(requestBytes), null);
            } catch (Exception e) {
                throw new TransportException("Failed to initiate Flight stream", e);
            }
            // Create FlightStreamReader
            StreamReader<VectorSchemaRoot> streamReader = new FlightStreamReader(flightStream);
            // Notify response handler
            Transport.ResponseContext<?> context = responseHandlers.remove(requestId);
            if (context != null) {
                @SuppressWarnings("unchecked")
                TransportResponseHandler<StreamReader<VectorSchemaRoot>> handler =
                    (TransportResponseHandler<StreamReader<VectorSchemaRoot>>) context.handler();
                handler.handleResponse(streamReader);
            } else {
                // If no handler is found, close the stream to avoid leaks
                streamReader.close();
                throw new TransportException("No response handler found for request ID: " + requestId);
            }
        }

        @Override
        public void addCloseListener(ActionListener<Void> listener) {
            closeListeners.add(listener);
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }

        @Override
        public void close() {
            if (!isClosed) {
                isClosed = true;
                try {
                    flightClient.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                closeListeners.forEach(listener -> listener.onResponse(null));
            }
        }
    }
}
