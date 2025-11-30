/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.vector.VectorSchemaRoot;


class FlightUtils {

    private FlightUtils() {}

    static long calculateVectorSchemaRootSize(VectorSchemaRoot root) {
        if (root == null) {
            return 0;
        }
        long totalSize = 0;
        for (int i = 0; i < root.getFieldVectors().size(); i++) {
            var vector = root.getVector(i);
            if (vector != null) {
                totalSize += vector.getBufferSize();
            }
        }
        return totalSize;
    }
    
    /**
     * Estimates the size of a response object.
     * Uses a simple heuristic - actual size may vary.
     */
    static long calculateResponseSize(Object response) {
        if (response == null) {
            return 0;
        }
        // Rough estimate: 1KB per response object
        // This is a heuristic - actual size depends on response content
        return 1024;
    }
}
