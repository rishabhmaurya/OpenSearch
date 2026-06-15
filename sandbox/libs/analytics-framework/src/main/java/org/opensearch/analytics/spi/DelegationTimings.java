/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.spi;

import java.util.concurrent.atomic.LongAdder;

/**
 * Accumulates per-query Lucene filter-delegation timing across the many {@code collectDocs} FFM
 * upcalls that run during a native shard scan (~once per row-group per segment — far too frequent
 * for a span each). The data-node fragment path reads the totals at fragment finalize and promotes
 * them to attributes on the {@code datafusion.shard_fragment} span.
 *
 * <p>Writes happen on multiple Tokio worker threads concurrently (the native scan is parallel), so
 * the counters are {@link LongAdder}s and the summed nanos are <em>CPU-nanos</em> — they can exceed
 * the fragment's wall-clock produce time when delegation runs on several threads at once. Lives in
 * the framework SPI so both the engine (constructs + reads) and the datafusion backend (writes from
 * the upcall) can reference it without an engine↔backend type dependency.
 *
 * @opensearch.internal
 */
public final class DelegationTimings {

    private final LongAdder collectNanos = new LongAdder();
    private final LongAdder collectCalls = new LongAdder();

    /** Records one {@code collectDocs} upcall: its wall-clock duration on the calling thread. */
    public void addCollect(long nanos) {
        collectNanos.add(nanos);
        collectCalls.increment();
    }

    /** Total CPU-nanos spent in {@code collectDocs} across all threads (may exceed wall-clock). */
    public long collectNanos() {
        return collectNanos.sum();
    }

    /** Number of {@code collectDocs} upcalls (≈ row-groups/segments pruned via Lucene). */
    public long collectCalls() {
        return collectCalls.sum();
    }
}
