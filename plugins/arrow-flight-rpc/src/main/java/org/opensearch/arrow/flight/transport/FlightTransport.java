/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightGrpcUtils;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.OSFlightClient;
import org.apache.arrow.flight.OSFlightServer;
import org.apache.arrow.flight.auth.ServerAuthHandler;
import org.apache.arrow.flight.grpc.ClientInterceptorAdapter;
import org.apache.arrow.flight.grpc.ServerBackpressureThresholdInterceptor;
import org.apache.arrow.flight.grpc.ServerInterceptorAdapter;
import org.apache.arrow.flight.grpc.ServerInterceptorAdapter.KeyFactory;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.Version;
import org.opensearch.arrow.allocator.ArrowNativeAllocator;
import org.opensearch.arrow.flight.bootstrap.ServerConfig;
import org.opensearch.arrow.flight.bootstrap.tls.SslContextProvider;
import org.opensearch.arrow.flight.stats.FlightStatsCollector;
import org.opensearch.arrow.spi.NativeAllocatorPoolConfig;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.network.NetworkAddress;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.PortsRange;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.BigArrays;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.common.transport.BoundTransportAddress;
import org.opensearch.core.common.transport.TransportAddress;
import org.opensearch.core.indices.breaker.CircuitBreakerService;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.BindTransportException;
import org.opensearch.transport.ConnectTransportException;
import org.opensearch.transport.ConnectionProfile;
import org.opensearch.transport.InboundHandler;
import org.opensearch.transport.OutboundHandler;
import org.opensearch.transport.StatsTracker;
import org.opensearch.transport.TcpChannel;
import org.opensearch.transport.TcpServerChannel;
import org.opensearch.transport.TcpTransport;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportHandshaker;
import org.opensearch.transport.TransportKeepAlive;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import static org.opensearch.arrow.flight.bootstrap.ServerConfig.SETTING_FLIGHT_BIND_HOST;
import static org.opensearch.arrow.flight.bootstrap.ServerConfig.SETTING_FLIGHT_PORTS;
import static org.opensearch.arrow.flight.bootstrap.ServerConfig.SETTING_FLIGHT_PUBLISH_HOST;
import static org.opensearch.arrow.flight.bootstrap.ServerConfig.SETTING_FLIGHT_PUBLISH_PORT;

@SuppressWarnings("removal")
class FlightTransport extends TcpTransport {
    private static final Logger logger = LogManager.getLogger(FlightTransport.class);
    private static final String DEFAULT_PROFILE = "stream_profile";

    private final PortsRange portRange;
    private final String[] bindHosts;
    private final String[] publishHosts;
    private volatile BoundTransportAddress boundAddress;
    private volatile FlightServer flightServer;
    // Same-JVM gRPC endpoint used for local-node streams: the request never leaves the process, so it
    // skips the loopback socket, TLS, and HTTP/2 framing while keeping the exact Flight code path
    // (producer, middleware, backpressure, cancellation). Null until doStart binds it.
    private volatile Server inProcessServer;
    private volatile String inProcessServerName;
    // Scheduler handed to both in-process gRPC builders so the transport owns the deadline timer and
    // shuts it down deterministically on stop. Without it, gRPC falls back to its JVM-shared
    // grpc-timer (via SharedResourceHolder), whose ref-counted shutdown lingers past node close.
    private volatile ScheduledExecutorService inProcessScheduler;
    private final SslContextProvider sslContextProvider;
    private FlightProducer flightProducer;
    private final EventLoopGroup bossEventLoopGroup;
    private final EventLoopGroup workerEventLoopGroup;
    private final ExecutorService serverExecutor;
    private final ExecutorService clientExecutor;
    private final ExecutorService[] flightEventLoopGroup;
    private final AtomicInteger nextExecutorIndex = new AtomicInteger(0);

    private final ThreadPool threadPool;
    private BufferAllocator serverAllocator;
    private BufferAllocator clientAllocator;

    private final NamedWriteableRegistry namedWriteableRegistry;
    private final FlightStatsCollector statsCollector;
    private final ArrowNativeAllocator nativeAllocator;
    private final FlightTransportConfig config = new FlightTransportConfig();

    final FlightServerMiddleware.Key<ServerHeaderMiddleware> SERVER_HEADER_KEY = FlightServerMiddleware.Key.of(
        "flight-server-header-middleware"
    );

    public FlightTransport(
        Settings settings,
        Version version,
        ThreadPool threadPool,
        PageCacheRecycler pageCacheRecycler,
        CircuitBreakerService circuitBreakerService,
        NamedWriteableRegistry namedWriteableRegistry,
        NetworkService networkService,
        Tracer tracer,
        SslContextProvider sslContextProvider,
        FlightStatsCollector statsCollector,
        ArrowNativeAllocator nativeAllocator
    ) {
        super(settings, version, threadPool, pageCacheRecycler, circuitBreakerService, namedWriteableRegistry, networkService, tracer);
        this.portRange = SETTING_FLIGHT_PORTS.get(settings);
        this.bindHosts = SETTING_FLIGHT_BIND_HOST.get(settings).toArray(new String[0]);
        this.publishHosts = SETTING_FLIGHT_PUBLISH_HOST.get(settings).toArray(new String[0]);
        this.sslContextProvider = sslContextProvider;
        this.statsCollector = statsCollector;
        this.nativeAllocator = nativeAllocator;
        this.bossEventLoopGroup = createEventLoopGroup("os-grpc-boss-ELG", 1);
        this.workerEventLoopGroup = createEventLoopGroup("os-grpc-worker-ELG", Runtime.getRuntime().availableProcessors());
        this.serverExecutor = threadPool.executor(ServerConfig.GRPC_EXECUTOR_THREAD_POOL_NAME);
        this.clientExecutor = threadPool.executor(ServerConfig.FLIGHT_CLIENT_THREAD_POOL_NAME);
        this.threadPool = threadPool;
        this.namedWriteableRegistry = namedWriteableRegistry;

        int eventLoopCount = ServerConfig.getEventLoopThreads();
        this.flightEventLoopGroup = new ExecutorService[eventLoopCount];
        for (int i = 0; i < eventLoopCount; i++) {
            int finalI = i;
            flightEventLoopGroup[i] = Executors.newSingleThreadExecutor(r -> new Thread(r, "flight-eventloop-" + finalI));
        }
    }

    @Override
    protected void doStart() {
        boolean success = false;
        try {
            // Use the unified native allocator's flight pool directly as the parent for
            // server/client child allocators. Allocations are tracked and capped by the
            // framework alongside ingest, query, and datafusion. Hard-fail if the framework
            // plugin is missing — silently falling back to a separate root would break the
            // same-root invariant for cross-plugin Arrow handoff.
            //
            // Server/client are children of the pool with Long.MAX_VALUE limits so dynamic
            // resizes of parquet.native.pool.flight.max take effect immediately via Arrow's
            // parent-cap check at allocateBytes — no listener needed.
            BufferAllocator flightPool = nativeAllocator.getPoolAllocator(NativeAllocatorPoolConfig.POOL_FLIGHT);
            serverAllocator = flightPool.newChildAllocator("server", 0, Long.MAX_VALUE);
            clientAllocator = flightPool.newChildAllocator("client", 0, Long.MAX_VALUE);
            if (statsCollector != null) {
                statsCollector.setBufferAllocator(flightPool);
                statsCollector.setThreadPool(threadPool);
            }
            flightProducer = new ArrowFlightProducer(
                this,
                flightPool,
                SERVER_HEADER_KEY,
                statsCollector,
                ServerConfig.FLIGHT_READY_TIMEOUT.get(settings).millis()
            );
            bindServer();
            success = true;
            if (statsCollector != null) {
                statsCollector.incrementServerChannelsActive();
            }
        } finally {
            if (!success) {
                doStop();
            }
        }
    }

    private void bindServer() {
        InetAddress[] hostAddresses;
        try {
            hostAddresses = networkService.resolveBindHostAddresses(bindHosts);
        } catch (IOException e) {
            throw new BindTransportException("Failed to resolve host [" + Arrays.toString(bindHosts) + "]", e);
        }

        List<InetSocketAddress> boundAddresses = bindToPort(hostAddresses);
        List<TransportAddress> transportAddresses = boundAddresses.stream().map(TransportAddress::new).collect(Collectors.toList());

        InetAddress publishInetAddress;
        try {
            publishInetAddress = networkService.resolvePublishHostAddresses(publishHosts);
        } catch (IOException e) {
            throw new BindTransportException("Failed to resolve publish address", e);
        }

        int publishPort = Transport.resolveTransportPublishPort(
            SETTING_FLIGHT_PUBLISH_PORT.get(settings),
            transportAddresses,
            publishInetAddress
        );
        if (publishPort < 0) {
            throw new BindTransportException(
                "Failed to auto-resolve flight publish port, multiple bound addresses "
                    + transportAddresses
                    + " with distinct ports and none matched the publish address ("
                    + publishInetAddress
                    + ")."
            );
        }

        TransportAddress publishAddress = new TransportAddress(new InetSocketAddress(publishInetAddress, publishPort));
        this.boundAddress = new BoundTransportAddress(transportAddresses.toArray(new TransportAddress[0]), publishAddress);

        startInProcessServer();
    }

    /**
     * Starts the in-process gRPC endpoint that serves same-node streams. It registers the identical
     * {@link #flightProducer} behind the same middleware stack the Netty server uses (header,
     * backpressure, auth), so a local stream exercises the same {@code getStream} -&gt;
     * {@link ArrowFlightProducer} -&gt; {@link FlightServerChannel} path as a remote one — only the
     * transport underneath differs. Best-effort: if it fails to start we log and leave
     * {@link #inProcessServerName} null so {@link #initiateChannel} falls back to the loopback wire.
     */
    private void startInProcessServer() {
        // A unique registry name per transport instance keeps parallel test nodes in one JVM isolated.
        final String name = InProcessServerBuilder.generateName();
        try {
            // Owned deadline scheduler so gRPC does not acquire its JVM-shared grpc-timer; closed in
            // stopInternal for a deterministic, linger-free shutdown.
            final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "flight-inprocess-timer")
            );
            this.inProcessScheduler = scheduler;
            ServerHeaderMiddleware.Factory headerFactory = new ServerHeaderMiddleware.Factory();
            Server server = InProcessServerBuilder.forName(name)
                // Run handlers on the shared gRPC executor, never on the caller's transport thread.
                .executor(serverExecutor)
                .scheduledExecutorService(scheduler)
                .maxInboundMessageSize(Integer.MAX_VALUE)
                .maxInboundMetadataSize(Integer.MAX_VALUE)
                // Same FlightBindingService + gRPC interceptors OSFlightServer installs on the Netty path.
                .addService(
                    ServerInterceptors.intercept(
                        FlightGrpcUtils.createFlightService(serverAllocator, flightProducer, ServerAuthHandler.NO_OP, serverExecutor),
                        new ServerBackpressureThresholdInterceptor(
                            (int) ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.get(settings).getBytes()
                        )
                    )
                )
                // Same Flight middleware adapter the Netty server uses (server header middleware),
                // so ArrowFlightProducer can look SERVER_HEADER_KEY up on the local path too.
                .intercept(new ServerInterceptorAdapter(List.of(new KeyFactory<>(SERVER_HEADER_KEY, headerFactory))))
                .build()
                .start();
            this.inProcessServer = server;
            this.inProcessServerName = name;
            logger.info("Arrow Flight in-process endpoint started for local-node streams [{}]", name);
        } catch (Exception e) {
            // Leave inProcessServerName null so initiateChannel falls back to the loopback wire, and
            // release the scheduler we created so it does not dangle.
            if (inProcessScheduler != null) {
                inProcessScheduler.shutdownNow();
                inProcessScheduler = null;
            }
            logger.warn("Failed to start Arrow Flight in-process endpoint; local streams will use the loopback wire", e);
        }
    }

    private List<InetSocketAddress> bindToPort(InetAddress[] hostAddresses) {
        final AtomicReference<Exception> lastException = new AtomicReference<>();
        final List<InetSocketAddress> boundAddresses = new ArrayList<>();
        final List<Location> locations = new ArrayList<>();

        boolean success = portRange.iterate(portNumber -> {
            try {
                boundAddresses.clear();
                locations.clear();

                for (InetAddress hostAddress : hostAddresses) {
                    InetSocketAddress socketAddress = new InetSocketAddress(hostAddress, portNumber);
                    boundAddresses.add(socketAddress);

                    Location location = sslContextProvider != null
                        ? Location.forGrpcTls(NetworkAddress.format(hostAddress), portNumber)
                        : Location.forGrpcInsecure(NetworkAddress.format(hostAddress), portNumber);
                    locations.add(location);
                }

                ServerHeaderMiddleware.Factory factory = new ServerHeaderMiddleware.Factory();
                OSFlightServer.Builder builder = OSFlightServer.builder()
                    .allocator(serverAllocator)
                    .producer(flightProducer)
                    .sslContext(sslContextProvider != null ? sslContextProvider.getServerSslContext() : null)
                    .channelType(ServerConfig.serverChannelType())
                    .bossEventLoopGroup(bossEventLoopGroup)
                    .workerEventLoopGroup(workerEventLoopGroup)
                    .executor(serverExecutor)
                    .backpressureThreshold((int) ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.get(settings).getBytes())
                    .middleware(SERVER_HEADER_KEY, factory);

                // Server-side gRPC keepalive (see ServerConfig.FLIGHT_KEEPALIVE_TIME). NOTE: only the
                // server pings today; adding a client keepalive also requires
                // permitKeepAliveTime/permitKeepAliveWithoutCalls here, else the server GOAWAYs the
                // client with "too_many_pings".
                final long keepAliveTimeMs = ServerConfig.getGrpcKeepAliveTime().millis();
                final long keepAliveTimeoutMs = ServerConfig.getGrpcKeepAliveTimeout().millis();
                builder.transportHint("grpc.builderConsumer", (Consumer<NettyServerBuilder>) b -> {
                    b.keepAliveTime(keepAliveTimeMs, TimeUnit.MILLISECONDS);
                    b.keepAliveTimeout(keepAliveTimeoutMs, TimeUnit.MILLISECONDS);
                });

                builder.location(locations.get(0));
                for (int i = 1; i < locations.size(); i++) {
                    builder.addListenAddress(locations.get(i));
                }

                FlightServer server = builder.build();
                server.start();
                this.flightServer = server;
                logger.info("Arrow Flight server started. Listening at {}", locations);
                return true;
            } catch (Exception e) {
                lastException.set(e);
                return false;
            }
        });

        if (!success) {
            throw new BindTransportException("Failed to bind to " + Arrays.toString(hostAddresses) + ":" + portRange, lastException.get());
        }

        return new ArrayList<>(boundAddresses);
    }

    @Override
    protected void stopInternal() {
        try {

            if (flightServer != null) {
                flightServer.shutdown();
                flightServer.awaitTermination();
                flightServer.close();
                flightServer = null;
            }
            if (inProcessServer != null) {
                inProcessServer.shutdown();
                if (!inProcessServer.awaitTermination(5, TimeUnit.SECONDS)) {
                    inProcessServer.shutdownNow();
                }
                inProcessServer = null;
                inProcessServerName = null;
            }
            if (inProcessScheduler != null) {
                inProcessScheduler.shutdownNow();
                inProcessScheduler = null;
            }
            serverAllocator.close();
            clientAllocator.close();
            gracefullyShutdownELG(bossEventLoopGroup, "os-grpc-boss-ELG");
            gracefullyShutdownELG(workerEventLoopGroup, "os-grpc-worker-ELG");

            for (ExecutorService executor : flightEventLoopGroup) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            if (statsCollector != null) {
                statsCollector.decrementServerChannelsActive();
            }
        } catch (Exception e) {
            logger.error("Error stopping FlightTransport", e);
        }
    }

    @Override
    public BoundTransportAddress boundAddress() {
        return boundAddress;
    }

    @Override
    protected TcpServerChannel bind(String name, InetSocketAddress address) {
        return null;
    }

    @Override
    protected TcpChannel initiateChannel(DiscoveryNode node) throws IOException {
        TransportAddress publishAddress = node.getStreamAddress();
        String address = publishAddress.getAddress();
        int flightPort = publishAddress.address().getPort();
        Location location = sslContextProvider != null
            ? Location.forGrpcTls(address, flightPort)
            : Location.forGrpcInsecure(address, flightPort);

        HeaderContext context = new HeaderContext();
        ClientHeaderMiddleware.Factory factory = new ClientHeaderMiddleware.Factory(context, getVersion());
        // Same-node target: build the client over the in-process channel so the stream never hits the
        // loopback socket. Everything after the channel — FlightClientChannel, header middleware,
        // buffer-release interceptor, stream/cancel handling — is identical to the wire path.
        FlightClient client = isLocalNode(publishAddress)
            ? buildInProcessClient(factory)
            : OSFlightClient.builder()
                .allocator(clientAllocator)
                .location(location)
                .channelType(ServerConfig.clientChannelType())
                .eventLoopGroup(workerEventLoopGroup)
                .sslContext(sslContextProvider != null ? sslContextProvider.getClientSslContext() : null)
                .executor(clientExecutor)
                .intercept(factory)
                .grpcIntercept(new BufferReleasingClientInterceptor())
                .build();

        try {
            return new FlightClientChannel(
                boundAddress,
                client,
                node,
                location,
                context,
                DEFAULT_PROFILE,
                getResponseHandlers(),
                threadPool,
                this.inboundHandler.getMessageListener(),
                namedWriteableRegistry,
                statsCollector,
                config
            );
        } catch (Exception e) {
            try {
                client.close();
            } catch (Exception ce) {
                e.addSuppressed(ce);
            }
            throw e;
        }
    }

    /**
     * Whether {@code streamAddress} is this node's own Flight endpoint. Compared against our published
     * stream address (not {@code isLocalNode()} on a {@link DiscoveryNode}) so the decision is made
     * purely on the transport address the caller is dialing — the same value the wire path would open a
     * loopback socket to. Only true when the in-process endpoint actually started.
     */
    private boolean isLocalNode(TransportAddress streamAddress) {
        if (inProcessServerName == null || boundAddress == null) {
            return false;
        }
        TransportAddress publish = boundAddress.publishAddress();
        if (publish != null && publish.address().equals(streamAddress.address())) {
            return true;
        }
        for (TransportAddress bound : boundAddress.boundAddresses()) {
            if (bound.address().equals(streamAddress.address())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a {@link FlightClient} bound to the in-process endpoint via {@link FlightGrpcUtils}. The
     * channel carries the same client-side header middleware and buffer-release interceptor as the wire
     * client; only the transport underneath is in-memory rather than a socket.
     */
    private FlightClient buildInProcessClient(ClientHeaderMiddleware.Factory factory) {
        // flight-core 18.1.0's createFlightClient has no middleware overload, so install the Flight
        // client middleware as a gRPC interceptor via Arrow's ClientInterceptorAdapter (the same bridge
        // OSFlightClient relies on internally). This carries the header-handshake middleware, and the
        // buffer-release interceptor runs alongside it.
        ManagedChannel channel = InProcessChannelBuilder.forName(inProcessServerName)
            .executor(clientExecutor)
            .scheduledExecutorService(inProcessScheduler)
            .maxInboundMessageSize(Integer.MAX_VALUE)
            .maxInboundMetadataSize(Integer.MAX_VALUE)
            .usePlaintext()
            .intercept(new ClientInterceptorAdapter(List.of(factory)), new BufferReleasingClientInterceptor())
            .build();
        if (statsCollector != null) {
            statsCollector.incrementInProcessChannelsOpened();
        }
        return FlightGrpcUtils.createFlightClient(clientAllocator, channel);
    }

    @Override
    public void setSlowLogThreshold(TimeValue slowLogThreshold) {
        super.setSlowLogThreshold(slowLogThreshold);
        config.setSlowLogThreshold(slowLogThreshold);
    }

    @Override
    public void openConnection(DiscoveryNode node, ConnectionProfile profile, ActionListener<Transport.Connection> listener) {
        try {
            ensureOpen();
            TcpChannel channel = initiateChannel(node);
            List<TcpChannel> channels = Collections.singletonList(channel);
            NodeChannels nodeChannels = new NodeChannels(node, channels, profile, getVersion());
            listener.onResponse(nodeChannels);
        } catch (Exception e) {
            listener.onFailure(new ConnectTransportException(node, "Failed to open Flight connection", e));
        }
    }

    @Override
    protected InboundHandler createInboundHandler(
        String nodeName,
        Version version,
        String[] features,
        StatsTracker statsTracker,
        ThreadPool threadPool,
        BigArrays bigArrays,
        OutboundHandler outboundHandler,
        NamedWriteableRegistry namedWriteableRegistry,
        TransportHandshaker handshaker,
        TransportKeepAlive keepAlive,
        RequestHandlers requestHandlers,
        ResponseHandlers responseHandlers,
        Tracer tracer
    ) {
        return new FlightInboundHandler(
            nodeName,
            version,
            features,
            statsTracker,
            threadPool,
            bigArrays,
            outboundHandler,
            namedWriteableRegistry,
            handshaker,
            keepAlive,
            requestHandlers,
            responseHandlers,
            tracer
        );
    }

    private EventLoopGroup createEventLoopGroup(String name, int threads) {
        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadFactory threadFactory = r -> new Thread(r, name + "-" + threadCounter.incrementAndGet());
        return new MultiThreadIoEventLoopGroup(threads, threadFactory, NioIoHandler.newFactory());
    }

    private void gracefullyShutdownELG(EventLoopGroup group, String name) {
        if (group != null) {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }

    /** Returns the next executor from the pool using round-robin assignment. */
    public ExecutorService getNextFlightExecutor() {
        return flightEventLoopGroup[nextExecutorIndex.getAndIncrement() % flightEventLoopGroup.length];
    }
}
