/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportResponseHandler;

import java.io.IOException;

/**
 * Example implementation of a TransportResponseHandler that expects VectorTransportResponse.
 * This handler will receive VectorSchemaRoot directly without deserialization overhead.
 */
public class VectorTransportResponseHandlerExample implements TransportResponseHandler<VectorTransportResponse>, VectorTransportResponseHandler {
    
    private final String executor;
    
    public VectorTransportResponseHandlerExample(String executor) {
        this.executor = executor;
    }
    
    public VectorTransportResponseHandlerExample() {
        this(ThreadPool.Names.GENERIC);
    }
    
    @Override
    public VectorTransportResponse read(StreamInput in) throws IOException {
        // This method should not be called for VectorTransportResponse handlers
        // as they bypass normal deserialization
        throw new UnsupportedOperationException("VectorTransportResponse handlers should not use StreamInput deserialization");
    }
    
    @Override
    public void handleResponse(VectorTransportResponse response) {
        // Handle the VectorTransportResponse directly
        VectorSchemaRoot root = response.getVectorSchemaRoot();
        
        // Process the VectorSchemaRoot as needed
        System.out.println("Received VectorSchemaRoot with " + root.getRowCount() + " rows");
        
        // Example: iterate through the data
        for (int i = 0; i < root.getRowCount(); i++) {
            // Process each row as needed
            // Access vectors: root.getVector(fieldName) or root.getVector(index)
        }
    }
    
    @Override
    public void handleException(org.opensearch.transport.TransportException exp) {
        // Handle transport exceptions
        System.err.println("Transport exception: " + exp.getMessage());
    }
    
    @Override
    public String executor() {
        return executor;
    }
}