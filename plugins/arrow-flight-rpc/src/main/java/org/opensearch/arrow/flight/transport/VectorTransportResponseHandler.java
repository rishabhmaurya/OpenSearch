/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

/**
 * Marker interface for transport response handlers that expect VectorTransportResponse.
 * Handlers implementing this interface will receive VectorTransportResponse directly
 * without deserialization in Flight transport.
 */
public interface VectorTransportResponseHandler {
    // Marker interface - no methods needed
}