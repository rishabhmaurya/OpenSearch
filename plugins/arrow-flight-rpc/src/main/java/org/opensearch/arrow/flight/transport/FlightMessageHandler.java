/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.memory.BufferAllocator;
import org.opensearch.Version;
import org.opensearch.common.util.BigArrays;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.NativeMessageHandler;
import org.opensearch.transport.OutboundHandler;
import org.opensearch.transport.ProtocolOutboundHandler;
import org.opensearch.transport.StatsTracker;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportHandshaker;
import org.opensearch.transport.TransportKeepAlive;

import java.util.concurrent.atomic.AtomicReference;

public class FlightMessageHandler extends NativeMessageHandler {

    public FlightMessageHandler(String nodeName, Version version, String[] features, StatsTracker statsTracker, ThreadPool threadPool, BigArrays bigArrays, OutboundHandler outboundHandler, NamedWriteableRegistry namedWriteableRegistry, TransportHandshaker handshaker, Transport.RequestHandlers requestHandlers, Transport.ResponseHandlers responseHandlers, Tracer tracer, TransportKeepAlive keepAlive) {
        super(nodeName, version, features, statsTracker, threadPool, bigArrays, outboundHandler, namedWriteableRegistry, handshaker, requestHandlers, responseHandlers, tracer, keepAlive);
    }

    @Override
    protected ProtocolOutboundHandler createNativeOutboundHandler(
        String nodeName,
        Version version,
        String[] features,
        StatsTracker statsTracker,
        ThreadPool threadPool,
        BigArrays bigArrays,
        OutboundHandler outboundHandler
    ) {
        return new FlightOutboundHandler(nodeName, version, features, statsTracker, threadPool);
    }
}
