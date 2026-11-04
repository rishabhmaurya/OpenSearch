/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.action;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.action.search.SearchResponse;

/**
 * Receives the results of an Arrow search on the coordinating node: the schema, then batches, then a summary or a failure.
 * Implementations are thread-safe, because batches of different shards arrive concurrently.
 */
public interface ArrowBatchSink {

    /**
     * Sends the schema of every batch. Called once, before any batch, and may wait for the consumer like
     * {@link #sendBatch}.
     */
    void sendSchema(Schema schema) throws Exception;

    /**
     * Sends a batch, waiting while the consumer is not ready for more data; the caller is expected to be a virtual thread.
     * Takes ownership of {@code batch} in every case, including when it throws, and closes it once the consumer has it.
     *
     * @throws Exception if the consumer went away or stopped reading for too long
     */
    void sendBatch(VectorSchemaRoot batch) throws Exception;

    /**
     * Ends the stream with the search summary. Idempotent with {@link #fail}: only the first terminal call has an effect.
     */
    void complete(SearchResponse summary);

    /**
     * Ends the stream with a failure, and wakes up callers waiting in {@link #sendBatch}.
     */
    void fail(Exception failure);

    /**
     * Discards the batches the transport still holds for the consumer, which closes them, for a consumer that stopped
     * reading after the stream ended. Ends the stream like {@link #fail} if it has not ended yet.
     */
    void reset();

    /**
     * @param onCancel run once, never on the caller's thread, when the consumer goes away
     */
    void setOnCancel(Runnable onCancel);
}
