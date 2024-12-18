/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.bootstrap.server;

import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.opensearch.arrow.flight.bootstrap.tls.SslContextProvider;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FlightServerBuilderTests extends OpenSearchTestCase {

    private ThreadPool threadPool;
    private BufferAllocator allocator;
    private FlightProducer producer;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        ServerConfig.init(Settings.EMPTY);
        threadPool = mock(ThreadPool.class);
        allocator = new RootAllocator();
        producer = mock(FlightProducer.class);
    }

    @Override
    public void tearDown() throws Exception {
        allocator.close();
        super.tearDown();
    }

    public void testBuilderConstructorWithValidInputs() throws IOException {
        ExecutorService executorService = Objects.requireNonNull(threadPool).executor(ServerConfig.FLIGHT_THREAD_POOL_NAME);
        FlightServerBuilder newBuilder = new FlightServerBuilder(() -> allocator, producer, mock(SslContextProvider.class), null, executorService);
        assertNotNull(newBuilder);
        assertNotNull(newBuilder.build());
    }

    public void testBuilderConstructorWithNullThreadPool() {
        expectThrows(
            NullPointerException.class,
            () -> (new FlightServerBuilder(() -> allocator, producer, mock(SslContextProvider.class), null, null)).build()
        );
    }

    public void testBuilderConstructorWithNullAllocator() {
        ExecutorService executorService = Objects.requireNonNull(threadPool).executor(ServerConfig.FLIGHT_THREAD_POOL_NAME);
        expectThrows(
            NullPointerException.class,
            () -> (new FlightServerBuilder(null, producer, mock(SslContextProvider.class), null, executorService)).build()
        );
    }

    public void testBuilderConstructorWithSslNull() {
        SslContextProvider sslContextProvider = mock(SslContextProvider.class);
        when(sslContextProvider.isSslEnabled()).thenReturn(true);
        when(sslContextProvider.getServerSslContext()).thenReturn(null);
        ExecutorService executorService = Objects.requireNonNull(threadPool).executor(ServerConfig.FLIGHT_THREAD_POOL_NAME);
        FlightServerBuilder newBuilder = new FlightServerBuilder(() -> allocator, producer, sslContextProvider, null, executorService);
        assertNotNull(newBuilder);
        expectThrows(NullPointerException.class, newBuilder::build);
    }
}
