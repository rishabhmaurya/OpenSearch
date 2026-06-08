/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.backend;

import java.util.Iterator;

/**
 * A closeable stream of record batches returned by engine execution.
 * Callers iterate batches via the returned iterator and MUST close the stream
 * when done to release native resources.
 *
 * @opensearch.internal
 */
public interface EngineResultStream extends AutoCloseable {

    /**
     * Returns an iterator over the record batches in this stream.
     * Each call returns the same iterator instance — the stream is single-pass.
     */
    Iterator<EngineResultBatch> iterator();

    /**
     * The current cumulative high-water mark of this stream's native execution memory pool, in
     * bytes, or {@code -1} when the backend cannot report it. Backend-agnostic accessor so the
     * engine layer can sample per-batch native memory growth without depending on a specific
     * backend's native bridge. Monotonic for the life of the stream; returns {@code -1}/0 once the
     * native session has been torn down, so call it while the stream is live.
     */
    default long currentNativePeakBytes() {
        return -1;
    }

    @Override
    void close();
}
