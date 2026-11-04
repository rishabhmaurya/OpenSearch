/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.opensearch.arrow.transport.ArrowBatchResponse;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/**
 * One record batch of a shard stream, carried by the native Arrow stream transport without serialization. The last batch
 * of a stream carries its {@link ShardSummary} as metadata.
 */
public final class ArrowScanBatchResponse extends ArrowBatchResponse {

    /**
     * @param batch   the batch; ownership transfers to the transport when sent
     * @param summary the encoded summary on the last batch, otherwise {@code null}
     */
    public ArrowScanBatchResponse(VectorSchemaRoot batch, byte[] summary) {
        super(batch, summary);
    }

    /**
     * @param in the Arrow stream input of the transport
     * @throws IOException if reading fails
     */
    public ArrowScanBatchResponse(StreamInput in) throws IOException {
        super(in);
    }
}
