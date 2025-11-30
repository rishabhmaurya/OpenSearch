/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.example.stream;

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.transport.TransportResponse;

import java.io.IOException;

/**
 * Simple data response for node-to-node communication containing only payload
 */
class BenchmarkDataResponse extends TransportResponse {
    private final byte[] payload;
    private final long clientStartNanos;
    private final long clientSendNanos;
    private final long serverReceiveNanos;
    private final long serverGeneratedFirstBatchNanos;
    private final long serverSentToQueueNanos;
    private final long messagePickedFromQueueNanos;
    private final long messageWrittenToChannelNanos;
    private final long clientReceivedHeaderNanos;
    private final long clientReceivedMessageNanos;
    private final String clientStartThreadPoolState;
    private final String clientSendThreadPoolState;
    private final String serverReceiveThreadPoolState;
    private final String serverGeneratedThreadPoolState;

    /**
     * Constructor
     * @param payload data payload
     */
    BenchmarkDataResponse(byte[] payload) {
        this(payload, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null);
    }

    BenchmarkDataResponse(byte[] payload, long clientStartNanos, long clientSendNanos, long serverReceiveNanos,
                         long serverGeneratedFirstBatchNanos, long serverSentToQueueNanos, long messagePickedFromQueueNanos,
                         long messageWrittenToChannelNanos, long clientReceivedHeaderNanos, long clientReceivedMessageNanos,
                         String clientStartThreadPoolState, String clientSendThreadPoolState, 
                         String serverReceiveThreadPoolState, String serverGeneratedThreadPoolState) {
        this.payload = payload;
        this.clientStartNanos = clientStartNanos;
        this.clientSendNanos = clientSendNanos;
        this.serverReceiveNanos = serverReceiveNanos;
        this.serverGeneratedFirstBatchNanos = serverGeneratedFirstBatchNanos;
        this.serverSentToQueueNanos = serverSentToQueueNanos;
        this.messagePickedFromQueueNanos = messagePickedFromQueueNanos;
        this.messageWrittenToChannelNanos = messageWrittenToChannelNanos;
        this.clientReceivedHeaderNanos = clientReceivedHeaderNanos;
        this.clientReceivedMessageNanos = clientReceivedMessageNanos;
        this.clientStartThreadPoolState = clientStartThreadPoolState;
        this.clientSendThreadPoolState = clientSendThreadPoolState;
        this.serverReceiveThreadPoolState = serverReceiveThreadPoolState;
        this.serverGeneratedThreadPoolState = serverGeneratedThreadPoolState;
    }

    /**
     * Constructor from stream input
     * @param in stream input
     * @throws IOException if an I/O error occurs
     */
    BenchmarkDataResponse(StreamInput in) throws IOException {
        super(in);
        this.payload = in.readByteArray();
        this.clientStartNanos = in.readLong();
        this.clientSendNanos = in.readLong();
        this.serverReceiveNanos = in.readLong();
        this.serverGeneratedFirstBatchNanos = in.readLong();
        this.serverSentToQueueNanos = in.readLong();
        this.messagePickedFromQueueNanos = in.readLong();
        this.messageWrittenToChannelNanos = in.readLong();
        this.clientReceivedHeaderNanos = in.readLong();
        this.clientReceivedMessageNanos = in.readLong();
        this.clientStartThreadPoolState = in.readOptionalString();
        this.clientSendThreadPoolState = in.readOptionalString();
        this.serverReceiveThreadPoolState = in.readOptionalString();
        this.serverGeneratedThreadPoolState = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeByteArray(payload);
        out.writeLong(clientStartNanos);
        out.writeLong(clientSendNanos);
        out.writeLong(serverReceiveNanos);
        out.writeLong(serverGeneratedFirstBatchNanos);
        out.writeLong(serverSentToQueueNanos);
        out.writeLong(messagePickedFromQueueNanos);
        out.writeLong(messageWrittenToChannelNanos);
        out.writeLong(clientReceivedHeaderNanos);
        out.writeLong(clientReceivedMessageNanos);
        out.writeOptionalString(clientStartThreadPoolState);
        out.writeOptionalString(clientSendThreadPoolState);
        out.writeOptionalString(serverReceiveThreadPoolState);
        out.writeOptionalString(serverGeneratedThreadPoolState);
    }

    /**
     * @return payload size in bytes
     */
    int getPayloadSize() {
        return payload != null ? payload.length : 0;
    }

    long getClientStartNanos() { return clientStartNanos; }
    long getClientSendNanos() { return clientSendNanos; }
    long getServerReceiveNanos() { return serverReceiveNanos; }
    long getServerGeneratedFirstBatchNanos() { return serverGeneratedFirstBatchNanos; }
    long getServerSentToQueueNanos() { return serverSentToQueueNanos; }
    long getMessagePickedFromQueueNanos() { return messagePickedFromQueueNanos; }
    long getMessageWrittenToChannelNanos() { return messageWrittenToChannelNanos; }
    long getClientReceivedHeaderNanos() { return clientReceivedHeaderNanos; }
    long getClientReceivedMessageNanos() { return clientReceivedMessageNanos; }
    String getClientStartThreadPoolState() { return clientStartThreadPoolState; }
    String getClientSendThreadPoolState() { return clientSendThreadPoolState; }
    String getServerReceiveThreadPoolState() { return serverReceiveThreadPoolState; }
    String getServerGeneratedThreadPoolState() { return serverGeneratedThreadPoolState; }
}
