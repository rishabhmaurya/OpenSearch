package org.opensearch.transport;

import org.opensearch.Version;
import org.opensearch.common.lease.Releasable;
import org.opensearch.core.transport.TransportResponse;

import java.io.IOException;
import java.util.Set;

public class ArrowFlightTransportChannel implements TransportChannel {
    private final ProtocolOutboundHandler outboundHandler;
    private final TcpChannel channel;
    private final String action;
    private final long requestId;
    private final Version version;
    private final Set<String> features;
    private final boolean isHandshake;
    private final Releasable breakerRelease;
    private boolean isCompleted = false;

    public ArrowFlightTransportChannel(
        ProtocolOutboundHandler outboundHandler,
        TcpChannel channel,
        String action,
        long requestId,
        Version version,
        Set<String> features,
        boolean isHandshake,
        Releasable breakerRelease
    ) {
        this.outboundHandler = outboundHandler;
        this.channel = channel;
        this.action = action;
        this.requestId = requestId;
        this.version = version;
        this.features = features;
        this.isHandshake = isHandshake;
        this.breakerRelease = breakerRelease;
    }

    @Override
    public String getProfileName() {
        return channel.getProfile();
    }

    @Override
    public String getChannelType() {
        return "arrow_flight";
    }

    @Override
    public void sendResponse(TransportResponse response) throws IOException {
        sendResponseBatch(response);
        complete();
    }

    @Override
    public void sendResponseBatch(TransportResponse response) throws IOException {
        if (isCompleted) {
            throw new IllegalStateException("Cannot send batch after completion");
        }
        outboundHandler.sendResponse(version, features, channel, requestId, action, response, false, isHandshake);
    }

    @Override
    public void complete() throws IOException {
        if (isCompleted) {
            throw new IllegalStateException("Channel already completed");
        }
        isCompleted = true;
        try {
            outboundHandler.sendResponse(version, features, channel, requestId, action, new TransportResponse.Empty(), false, isHandshake);
        } finally {
            breakerRelease.close();
        }
    }

    @Override
    public void sendResponse(Exception exception) throws IOException {
        if (isCompleted) {
            return;
        }
        isCompleted = true;
        try {
            outboundHandler.sendErrorResponse(version, features, channel, requestId, action, exception);
        } finally {
            breakerRelease.close();
        }
    }

    @Override
    public Version getVersion() {
        return version;
    }
}
