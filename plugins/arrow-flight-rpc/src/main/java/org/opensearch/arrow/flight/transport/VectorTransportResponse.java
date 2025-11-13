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
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.transport.TransportResponse;

import java.io.IOException;

/**
 * Special transport response that contains a VectorSchemaRoot directly,
 * bypassing serialization/deserialization in Flight transport.
 * 
 * This response type is optimized for Arrow Flight transport where the
 * VectorSchemaRoot can be transmitted directly without conversion to bytes.
 */
public class VectorTransportResponse extends TransportResponse {
    
    private final VectorSchemaRoot vectorSchemaRoot;
    
    /**
     * Creates a new VectorTransportResponse with the given VectorSchemaRoot.
     * 
     * @param vectorSchemaRoot the vector schema root containing the data
     */
    public VectorTransportResponse(VectorSchemaRoot vectorSchemaRoot) {
        this.vectorSchemaRoot = vectorSchemaRoot;
    }
    
    /**
     * Constructor for deserialization - should not be used for VectorTransportResponse
     * as it bypasses normal serialization.
     */
    public VectorTransportResponse(StreamInput in) throws IOException {
        super(in);
        throw new UnsupportedOperationException("VectorTransportResponse should not be deserialized from StreamInput");
    }
    
    /**
     * Gets the VectorSchemaRoot containing the response data.
     * 
     * @return the vector schema root
     */
    public VectorSchemaRoot getVectorSchemaRoot() {
        return vectorSchemaRoot;
    }
    
    /**
     * This method should not be called for VectorTransportResponse as it bypasses
     * normal serialization in Flight transport.
     */
    @Override
    public void writeTo(StreamOutput out) throws IOException {
        throw new UnsupportedOperationException("VectorTransportResponse should not be serialized to StreamOutput");
    }
}