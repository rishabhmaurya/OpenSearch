/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.bootstrap;

import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.OpenSearchFlightServer;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.util.VisibleForTesting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.arrow.flight.bootstrap.client.FlightClientManager;
import org.opensearch.arrow.flight.bootstrap.server.FlightServerBuilder;
import org.opensearch.arrow.flight.bootstrap.server.ServerConfig;
import org.opensearch.arrow.flight.bootstrap.tls.DefaultSslContextProvider;
import org.opensearch.arrow.flight.bootstrap.tls.DisabledSslContextProvider;
import org.opensearch.arrow.flight.bootstrap.tls.SslContextProvider;
import org.opensearch.arrow.flight.core.BaseFlightProducer;
import org.opensearch.arrow.flight.core.FlightStreamManager;
import org.opensearch.arrow.spi.StreamManager;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.SetOnce;
import org.opensearch.common.lifecycle.AbstractLifecycleComponent;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugins.SecureTransportSettingsProvider;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * FlightService manages the Arrow Flight server and client for OpenSearch.
 * It handles the initialization, startup, and shutdown of the Flight server and client,
 * as well as managing the stream operations through a FlightStreamManager.
 */
public class FlightService extends AbstractLifecycleComponent {

    private static final Logger logger = LogManager.getLogger(FlightService.class);

    private static OpenSearchFlightServer server;
    private static BufferAllocator allocator;
    private static Supplier<StreamManager> streamManager;
    private static FlightClientManager clientManager;
    private final SetOnce<ThreadPool> threadPool = new SetOnce<>();
    private final SetOnce<ClusterService> clusterService = new SetOnce<>();

    private final SetOnce<SecureTransportSettingsProvider> secureTransportSettingsProvider = new SetOnce<>();
    private SslContextProvider sslContextProvider;

    /**
     * Constructor for FlightService.
     * @param settings The settings for the FlightService.
     */
    public FlightService(Settings settings) {
        try {
            AccessController.doPrivileged((PrivilegedExceptionAction<Void>) () -> {
                ServerConfig.init(settings);
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Arrow Flight server", e);
        }
    }

    /**
     * Initializes the FlightService with the provided ClusterService and ThreadPool.
     * It sets up the SSL context provider, client manager, and stream manager.
     * @param clusterService The ClusterService instance.
     * @param threadPool The ThreadPool instance.
     */
    public void initialize(ClusterService clusterService, ThreadPool threadPool) {
        this.clusterService.trySet(clusterService);
        this.threadPool.trySet(Objects.requireNonNull(threadPool));
    }

    /**
     * Sets the SecureTransportSettingsProvider for the FlightService.
     * @param secureTransportSettingsProvider The SecureTransportSettingsProvider instance.
     */
    public void setSecureTransportSettingsProvider(SecureTransportSettingsProvider secureTransportSettingsProvider) {
        this.secureTransportSettingsProvider.trySet(secureTransportSettingsProvider);
    }


    @Override
    protected void doStart() {
        // everything is lazily started in onNodeStart() after TransportService is started
    }

    /**
     * Stops the FlightService by closing the Arrow Flight server, client manager, and stream manager.
     */
    @Override
    protected void doStop() {
        try {
            if (server != null) {
                server.shutdown();
            }
            if (streamManager != null && streamManager.get() != null) {
                streamManager.get().close();
            }
            if (clientManager != null) {
                clientManager.close();
            }
            if (server != null) {
                server.close();
            }
            logger.info("Arrow Flight service closed successfully");
        } catch (Exception e) {
            logger.error("Error while closing Arrow Flight service", e);
        }
    }

    /**
     * Closes the BufferAllocator used by the FlightService.
     */
    @Override
    protected void doClose() {
        if (allocator != null) {
            allocator.close();
        }
    }

    /**
     * Retrieves the FlightClientManager used by the FlightService.
     * @return The FlightClientManager instance.
     */
    public FlightClientManager getFlightClientManager() {
        return clientManager;
    }

    public void onNodeStart(DiscoveryNode localNode) {
        if (isDedicatedClusterManagerNode(localNode)) {
            return;
        }
        try {
            allocator = AccessController.doPrivileged(
                (PrivilegedExceptionAction<BufferAllocator>) () -> new RootAllocator(Integer.MAX_VALUE)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Arrow Flight server", e);
        }

        if (ServerConfig.isSslEnabled()) {
            sslContextProvider = new DefaultSslContextProvider(secureTransportSettingsProvider::get);
        } else {
            sslContextProvider = new DisabledSslContextProvider();
        }
        clientManager = new FlightClientManager(() -> allocator, Objects.requireNonNull(clusterService.get()), sslContextProvider);
        FlightStreamManager flightStreamManager = new FlightStreamManager(() -> allocator, clientManager);
        streamManager = () -> flightStreamManager;

        try {
            FlightProducer producer = new BaseFlightProducer(clientManager, flightStreamManager, allocator);
            Location serverLocation = ServerConfig.getLocation(localNode.getAddress().getAddress(), Integer.parseInt(localNode.getAttributes().get("transport.stream.port")));
            FlightServerBuilder builder = new FlightServerBuilder(threadPool.get(), () -> allocator, producer, sslContextProvider, serverLocation);
            server = builder.build();
            server.start();
            logger.info("Arrow Flight server started successfully:{}", serverLocation);
        } catch (IOException e) {
            logger.error("Failed to start Arrow Flight server", e);
            throw new RuntimeException("Failed to start Arrow Flight server", e);
        }
    }

    private boolean isDedicatedClusterManagerNode(DiscoveryNode node) {
        Set<DiscoveryNodeRole> nodeRoles = node.getRoles();
        return nodeRoles.size() == 1 &&
            (nodeRoles.contains(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE) ||
                nodeRoles.contains(DiscoveryNodeRole.MASTER_ROLE));
    }

    /**
     * Retrieves the StreamManager used by the FlightService.
     * @return The StreamManager instance.
     */
    public Supplier<StreamManager> getStreamManager() {
        return streamManager;
    }

    @VisibleForTesting
    SslContextProvider getSslContextProvider() {
        return sslContextProvider;
    }

    @VisibleForTesting
    BufferAllocator getAllocator() {
        return allocator;
    }
}
