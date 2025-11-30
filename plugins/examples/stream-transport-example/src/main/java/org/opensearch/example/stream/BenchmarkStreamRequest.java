/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.example.stream;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;

/**
 * Request for benchmark stream action
 */
public class BenchmarkStreamRequest extends ActionRequest {

    private int rows = 100;
    private int columns = 10;
    private int avgColumnLength = 100;
    private String columnType = "string";
    private boolean useStreamTransport = true;
    private int parallelRequests = 1;
    private int totalRequests = 0; // 0 = use parallelRequests only
    private int targetTps = 0; // 0 = unlimited
    private String threadPool = "generic";
    private int batchSize = 100; // rows per batch for stream transport
    private String correlationId = ""; // for debugging/tracing
    private long clientStartNanos = 0;
    private long clientSendNanos = 0;
    private long serverReceiveNanos = 0;
    private String clientStartThreadPoolState = null;
    private String clientSendThreadPoolState = null;
    private String serverReceiveThreadPoolState = null;

    /** Constructor */
    public BenchmarkStreamRequest() {}

    /**
     * Constructor from stream input
     * @param in stream input
     * @throws IOException if an I/O error occurs
     */
    public BenchmarkStreamRequest(StreamInput in) throws IOException {
        super(in);
        rows = in.readVInt();
        columns = in.readVInt();
        avgColumnLength = in.readVInt();
        columnType = in.readString();
        useStreamTransport = in.readBoolean();
        parallelRequests = in.readVInt();
        totalRequests = in.readVInt();
        targetTps = in.readVInt();
        threadPool = in.readString();
        batchSize = in.readVInt();
        correlationId = in.readString();
        clientStartNanos = in.readLong();
        clientSendNanos = in.readLong();
        serverReceiveNanos = in.readLong();
        clientStartThreadPoolState = in.readOptionalString();
        clientSendThreadPoolState = in.readOptionalString();
        serverReceiveThreadPoolState = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(rows);
        out.writeVInt(columns);
        out.writeVInt(avgColumnLength);
        out.writeString(columnType);
        out.writeBoolean(useStreamTransport);
        out.writeVInt(parallelRequests);
        out.writeVInt(totalRequests);
        out.writeVInt(targetTps);
        out.writeString(threadPool);
        out.writeVInt(batchSize);
        out.writeString(correlationId);
        out.writeLong(clientStartNanos);
        out.writeLong(clientSendNanos);
        out.writeLong(serverReceiveNanos);
        out.writeOptionalString(clientStartThreadPoolState);
        out.writeOptionalString(clientSendThreadPoolState);
        out.writeOptionalString(serverReceiveThreadPoolState);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (rows <= 0) {
            validationException = addValidationError("rows must be > 0", validationException);
        }
        if (columns <= 0) {
            validationException = addValidationError("columns must be > 0", validationException);
        }
        if (parallelRequests <= 0) {
            validationException = addValidationError("parallel_requests must be > 0", validationException);
        }
        if (batchSize <= 0) {
            validationException = addValidationError("batch_size must be > 0", validationException);
        }
        return validationException;
    }

    private ActionRequestValidationException addValidationError(String error, ActionRequestValidationException exception) {
        if (exception == null) {
            exception = new ActionRequestValidationException();
        }
        exception.addValidationError(error);
        return exception;
    }

    int getRows() { return rows; }
    void setRows(int rows) { this.rows = rows; }

    int getColumns() { return columns; }
    void setColumns(int columns) { this.columns = columns; }

    int getAvgColumnLength() { return avgColumnLength; }
    void setAvgColumnLength(int avgColumnLength) { this.avgColumnLength = avgColumnLength; }

    String getColumnType() { return columnType; }
    void setColumnType(String columnType) { this.columnType = columnType; }

    boolean isUseStreamTransport() { return useStreamTransport; }
    void setUseStreamTransport(boolean useStreamTransport) { this.useStreamTransport = useStreamTransport; }

    int getParallelRequests() { return parallelRequests; }
    void setParallelRequests(int parallelRequests) { this.parallelRequests = parallelRequests; }

    int getTotalRequests() { return totalRequests; }
    void setTotalRequests(int totalRequests) { this.totalRequests = totalRequests; }

    int getTargetTps() { return targetTps; }
    void setTargetTps(int targetTps) { this.targetTps = targetTps; }

    String getThreadPool() { return threadPool; }
    void setThreadPool(String threadPool) { this.threadPool = threadPool; }

    int getBatchSize() { return batchSize; }
    void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    String getCorrelationId() { return correlationId; }
    void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    long getClientStartNanos() { return clientStartNanos; }
    void setClientStartNanos(long clientStartNanos) { this.clientStartNanos = clientStartNanos; }

    long getClientSendNanos() { return clientSendNanos; }
    void setClientSendNanos(long clientSendNanos) { this.clientSendNanos = clientSendNanos; }

    long getServerReceiveNanos() { return serverReceiveNanos; }
    void setServerReceiveNanos(long serverReceiveNanos) { this.serverReceiveNanos = serverReceiveNanos; }

    String getClientStartThreadPoolState() { return clientStartThreadPoolState; }
    void setClientStartThreadPoolState(String state) { this.clientStartThreadPoolState = state; }

    String getClientSendThreadPoolState() { return clientSendThreadPoolState; }
    void setClientSendThreadPoolState(String state) { this.clientSendThreadPoolState = state; }

    String getServerReceiveThreadPoolState() { return serverReceiveThreadPoolState; }
    void setServerReceiveThreadPoolState(String state) { this.serverReceiveThreadPoolState = state; }
}
