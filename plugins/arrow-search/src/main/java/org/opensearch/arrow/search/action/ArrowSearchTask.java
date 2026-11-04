/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.action;

import org.opensearch.action.search.SearchTask;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.tasks.TaskId;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The coordinating task of an Arrow search.
 * <p>
 * An export runs as long as its client keeps reading, so the task is not tracked for resource use: search backpressure
 * would otherwise cancel exports for their duration rather than their cost. Neither are the shard scans it starts, whose
 * CPU runs on virtual threads, which per-thread accounting does not measure, so search backpressure and workload management
 * do not see the search's CPU or heap; data nodes bound the scans by how many may be open and compute at once.
 */
public final class ArrowSearchTask extends SearchTask {

    private static final Runnable FIRED = () -> {};

    private final AtomicReference<Runnable> onCancel = new AtomicReference<>();

    ArrowSearchTask(
        long id,
        String type,
        String action,
        Supplier<String> descriptionSupplier,
        TaskId parentTaskId,
        Map<String, String> headers,
        TimeValue cancelAfterTimeInterval
    ) {
        super(id, type, action, descriptionSupplier, parentTaskId, headers, cancelAfterTimeInterval);
    }

    @Override
    public boolean supportsResourceTracking() {
        return false;
    }

    /**
     * @param callback run once when the task is cancelled; runs on this thread if it already was
     */
    void setOnCancel(Runnable callback) {
        final Runnable previous = onCancel.getAndUpdate(current -> current == FIRED ? FIRED : callback);
        if (previous == FIRED) {
            callback.run();
        }
    }

    @Override
    protected void onCancelled() {
        final Runnable callback = onCancel.getAndSet(FIRED);
        if (callback != null) {
            callback.run();
        }
    }
}
