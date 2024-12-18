/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.bootstrap.server;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.OpenSearchFlightServer;
import org.apache.arrow.memory.BufferAllocator;
import org.opensearch.arrow.flight.bootstrap.tls.SslContextProvider;
import org.opensearch.arrow.flight.bootstrap.Utils;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;


/**
 * Builder class for creating and configuring OpenSearch Flight server instances.
 * This class handles the setup of Arrow Flight servers with customizable thread pools,
 * buffer allocation, producer configuration, and SSL/TLS settings.
 */
public class FlightServerBuilder {
    private final OpenSearchFlightServer.Builder builder;
    /**
     * Creates a new FlightServerBuilder instance with the specified configurations.
     *
     * @param allocator Supplier for Arrow buffer allocation
     * @param producer The Flight producer that handles incoming requests
     * @param sslContextProvider Provider for SSL/TLS context configuration
     */
    public FlightServerBuilder(
        Supplier<BufferAllocator> allocator,
        FlightProducer producer,
        SslContextProvider sslContextProvider,
        Location location,
        ExecutorService executorService
    ) {
        this.builder = OpenSearchFlightServer.builder(allocator.get(), location, producer);
        builder.executor(executorService);
        if (sslContextProvider.isSslEnabled()) {
            builder.useTls(sslContextProvider.getServerSslContext());
        }
    }

    /**
     * Builds and configures an OpenSearchFlightServer instance.
     * @return A configured OpenSearchFlightServer instance
     */
    public OpenSearchFlightServer build() throws IOException {
        return builder.build();
    }

    public FlightServerBuilder elg(EventLoopGroup bossELG, EventLoopGroup workerELG, Class<? extends Channel> channelType) {
        builder.transportHint("netty.channelType", Utils.DEFAULT_CLIENT_CHANNEL_TYPE);
        builder.transportHint("netty.bossEventLoopGroup", bossELG);
        builder.transportHint("netty.workerEventLoopGroup", workerELG);
        return this;
    }
}
