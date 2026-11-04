/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.opensearch.action.search.SearchShardTask;
import org.opensearch.core.tasks.TaskId;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The task of a shard scan, which runs as one query phase that pauses while its consumer is not ready.
 * <p>
 * Its lifetime follows the client's pace, and its CPU runs on virtual threads, which per-thread CPU and allocation
 * accounting does not see, so it is not tracked for resource use: search backpressure would otherwise cancel exports for
 * their duration, and neither it nor workload management sees the scan's CPU or heap. The node bounds scans instead, by
 * how many may be open and how many may compute at once.
 */
public final class ArrowShardScanTask extends SearchShardTask {

    ArrowShardScanTask(
        long id,
        String type,
        String action,
        String description,
        TaskId parentTaskId,
        Map<String, String> headers,
        Supplier<String> metadataSupplier
    ) {
        super(id, type, action, description, parentTaskId, headers, metadataSupplier);
    }

    @Override
    public boolean supportsResourceTracking() {
        return false;
    }
}
