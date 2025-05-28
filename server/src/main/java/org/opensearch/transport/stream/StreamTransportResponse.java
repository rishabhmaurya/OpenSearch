/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.transport.stream;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.common.annotation.PublicApi;
import org.opensearch.core.transport.TransportResponse;

/**
 * A stream of TransportResponse objects for streaming response handling.
 *
 * @opensearch.api
 */
@ExperimentalApi
public class StreamTransportResponse<T extends TransportResponse> {
    private final StreamReader<T> streamReader;

    public StreamTransportResponse(StreamReader<T> streamReader) {
        this.streamReader = streamReader;
    }

    public boolean hasNext() {
        return streamReader.hasNext();
    }

    public T next() {
        return streamReader.next();
    }
}
