/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * How a shard stream ended, sent as metadata of its last batch.
 *
 * @param rows            the rows the shard streamed
 * @param terminatedEarly whether the shard's row limit, from {@code size} or {@code terminate_after}, left matches out
 * @param timedOut        whether a batch hit the search timeout
 */
public record ShardSummary(long rows, boolean terminatedEarly, boolean timedOut) {

    /**
     * @return the summary as batch metadata
     */
    public byte[] encode() {
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            out.writeVLong(rows);
            out.writeBoolean(terminatedEarly);
            out.writeBoolean(timedOut);
            return BytesReference.toBytes(out.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param metadata batch metadata written by {@link #encode()}
     * @return the summary
     */
    public static ShardSummary decode(byte[] metadata) {
        try (StreamInput in = new BytesArray(metadata).streamInput()) {
            return new ShardSummary(in.readVLong(), in.readBoolean(), in.readBoolean());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
