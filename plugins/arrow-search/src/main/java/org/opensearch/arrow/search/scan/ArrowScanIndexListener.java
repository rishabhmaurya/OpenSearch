/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.opensearch.common.Nullable;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.shard.IndexEventListener;
import org.opensearch.index.shard.IndexShard;

/**
 * Aborts the scans of a shard that closes, e.g. when it relocates or its index is deleted, so they release the shard's
 * reader: a scan that computes stops at once, and a scan that waits for its consumer stops when it resumes or its wait
 * times out.
 */
public final class ArrowScanIndexListener implements IndexEventListener {

    private final ArrowScanRegistry registry;

    /**
     * @param registry the scans of this node
     */
    public ArrowScanIndexListener(ArrowScanRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void beforeIndexShardClosed(ShardId shardId, @Nullable IndexShard indexShard, Settings indexSettings) {
        registry.abortShard(shardId, "shard " + shardId + " is closing");
    }
}
