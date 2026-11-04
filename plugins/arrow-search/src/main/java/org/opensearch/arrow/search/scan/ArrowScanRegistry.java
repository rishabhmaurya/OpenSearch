/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.opensearch.common.Nullable;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.core.index.shard.ShardId;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The shard scans open on this node, and the limits on how many may be open, and compute a batch, at once.
 */
public final class ArrowScanRegistry {

    private final Set<RunningScan> scans = ConcurrentHashMap.newKeySet();
    private final Slots scanSlots;
    private final Slots batchSlots;

    /**
     * @param maxOpenScans         how many scans may be open at once
     * @param maxConcurrentBatches how many scans may compute a batch at once
     */
    public ArrowScanRegistry(int maxOpenScans, int maxConcurrentBatches) {
        this.scanSlots = new Slots(maxOpenScans);
        this.batchSlots = new Slots(maxConcurrentBatches);
    }

    /**
     * @param maxOpenScans how many scans may be open at once; scans already open are not affected by a lower limit
     */
    public void setMaxOpenScans(int maxOpenScans) {
        scanSlots.setLimit(maxOpenScans);
    }

    /**
     * @param maxConcurrentBatches how many scans may compute a batch at once
     */
    public void setMaxConcurrentBatches(int maxConcurrentBatches) {
        batchSlots.setLimit(maxConcurrentBatches);
    }

    /**
     * Takes a slot for a new scan, waiting up to {@code wait} for one to free up.
     *
     * @return the slot, released when the scan ends
     * @throws OpenSearchRejectedExecutionException if no slot freed up in time, which the coordinator retries on another copy
     */
    Releasable acquireScanSlot(TimeValue wait) throws InterruptedException {
        final Releasable slot = scanSlots.acquire(wait.nanos());
        if (slot == null) {
            throw new OpenSearchRejectedExecutionException(
                "rejected arrow scan: this node already runs the maximum of [" + scanSlots.limit() + "] open scans"
            );
        }
        return slot;
    }

    /**
     * Takes a slot to compute, waiting up to {@code waitNanos} for one to free up. A scan slice holds it while it collects,
     * not while it waits for its consumer.
     *
     * @return the slot, or {@code null} if none freed up in time
     */
    @Nullable
    Releasable acquireBatchSlot(long waitNanos) throws InterruptedException {
        return batchSlots.acquire(waitNanos);
    }

    /**
     * Registers a running scan of a shard.
     *
     * @param shardId the shard
     * @param abort   stops the scan, given why
     * @return removes the scan once it ended
     */
    Releasable register(ShardId shardId, Consumer<String> abort) {
        final RunningScan scan = new RunningScan(shardId, abort);
        scans.add(scan);
        return () -> scans.remove(scan);
    }

    /**
     * @return the number of scans running on this node
     */
    public int runningScans() {
        return scans.size();
    }

    /**
     * Aborts the scans of a shard, e.g. because it is closing.
     */
    void abortShard(ShardId shardId, String reason) {
        for (RunningScan scan : scans) {
            if (scan.shardId().equals(shardId)) {
                scan.abort().accept(reason);
            }
        }
    }

    private record RunningScan(ShardId shardId, Consumer<String> abort) {
    }

    /** A counting semaphore whose limit can change. */
    private static final class Slots {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition freed = lock.newCondition();
        private int limit;
        private int taken;

        Slots(int limit) {
            this.limit = limit;
        }

        int limit() {
            lock.lock();
            try {
                return limit;
            } finally {
                lock.unlock();
            }
        }

        void setLimit(int limit) {
            lock.lock();
            try {
                this.limit = limit;
                freed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /**
         * @return the slot, or {@code null} if none freed up within {@code waitNanos}
         */
        Releasable acquire(long waitNanos) throws InterruptedException {
            lock.lock();
            try {
                long remaining = waitNanos;
                while (taken >= limit) {
                    if (remaining <= 0) {
                        return null;
                    }
                    remaining = freed.awaitNanos(remaining);
                }
                taken++;
            } finally {
                lock.unlock();
            }
            final AtomicBoolean released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) {
                    lock.lock();
                    try {
                        taken--;
                        freed.signal();
                    } finally {
                        lock.unlock();
                    }
                }
            };
        }
    }
}
