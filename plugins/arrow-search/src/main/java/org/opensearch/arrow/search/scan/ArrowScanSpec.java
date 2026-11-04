/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.opensearch.arrow.search.Column;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;

import java.io.IOException;
import java.util.List;

/**
 * What a shard scan reads and how it sizes its batches.
 *
 * @param columns              the columns, in schema order
 * @param batchBytes           the size a batch grows to
 * @param firstBatchBytes      the size of the first batch, which doubles until {@code batchBytes}, so the first rows arrive
 *                             early
 * @param maxBatchRows         the maximum rows in a batch
 * @param maxRowBytes          the largest row a batch may hold; a larger row fails the scan
 * @param shardRowLimit        the maximum rows the shard streams
 * @param producerReadyTimeout how long the shard waits for the coordinator to accept a batch
 */
public record ArrowScanSpec(List<Column> columns, long batchBytes, long firstBatchBytes, int maxBatchRows, long maxRowBytes,
    long shardRowLimit, TimeValue producerReadyTimeout) implements Writeable {

    public ArrowScanSpec {
        columns = List.copyOf(columns);
    }

    /**
     * @param in the stream to read from
     * @throws IOException if reading fails
     */
    public ArrowScanSpec(StreamInput in) throws IOException {
        this(in.readList(Column::new), in.readVLong(), in.readVLong(), in.readVInt(), in.readVLong(), in.readVLong(), in.readTimeValue());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeList(columns);
        out.writeVLong(batchBytes);
        out.writeVLong(firstBatchBytes);
        out.writeVInt(maxBatchRows);
        out.writeVLong(maxRowBytes);
        out.writeVLong(shardRowLimit);
        out.writeTimeValue(producerReadyTimeout);
    }

    /**
     * @param batchIndex the zero-based index of a batch
     * @return the byte budget of that batch
     */
    public long batchBudget(int batchIndex) {
        final long ramped = batchIndex >= Long.numberOfLeadingZeros(firstBatchBytes) - 1 ? batchBytes : firstBatchBytes << batchIndex;
        return Math.max(1, Math.min(batchBytes, ramped));
    }
}
