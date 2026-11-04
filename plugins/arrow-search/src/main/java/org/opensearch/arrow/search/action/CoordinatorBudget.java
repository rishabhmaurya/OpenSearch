/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.action;

import org.opensearch.common.Nullable;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.lease.Releasables;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The batch memory that Arrow searches coordinated by this node may buffer, reserved per search.
 */
final class CoordinatorBudget {

    private static final Runnable RELEASED = () -> {};

    private final LongSupplier limit;
    private final AtomicLong reserved = new AtomicLong();

    /**
     * @param limit the bytes all searches may reserve together
     */
    CoordinatorBudget(LongSupplier limit) {
        this.limit = limit;
    }

    /**
     * Reserves up to {@code wanted} bytes, and at least {@code minimum}.
     *
     * @return the reservation, or {@code null} if fewer than {@code minimum} bytes are free
     */
    Reservation reserve(long wanted, long minimum) {
        while (true) {
            final long current = reserved.get();
            final long free = limit.getAsLong() - current;
            if (free < minimum) {
                return null;
            }
            final long bytes = Math.min(wanted, free);
            if (reserved.compareAndSet(current, current + bytes)) {
                return new Reservation(bytes);
            }
        }
    }

    long reserved() {
        return reserved.get();
    }

    /**
     * Bytes reserved for one search. The search holds them until it ends, and every batch it relayed holds the reservation
     * until the client's transport released the batch, which may be later. Once the search ended, the reservation charges
     * the budget only for the batches that still hold it, and returns their bytes as they let go.
     */
    final class Reservation {
        private final long bytes;
        private final AtomicReference<Runnable> onReleased = new AtomicReference<>();
        // Guarded by this.
        private long charged;
        private int batches;
        private long batchBytes;
        private boolean searchEnded;

        private Reservation(long bytes) {
            this.bytes = bytes;
            this.charged = bytes;
        }

        long bytes() {
            return bytes;
        }

        /**
         * Holds the reservation for a relayed batch until the returned releasable is closed.
         *
         * @param maxBytes the most memory the batch can hold, which the budget is charged once the search ended
         * @return the hold, released once; {@code null} if the search already ended
         */
        @Nullable
        synchronized Releasable holdBatch(long maxBytes) {
            if (searchEnded) {
                return null;
            }
            batches++;
            batchBytes += maxBytes;
            return Releasables.releaseOnce(() -> releaseBatch(maxBytes));
        }

        private void releaseBatch(long maxBytes) {
            final boolean released;
            synchronized (this) {
                batches--;
                batchBytes -= maxBytes;
                assert batches >= 0 && batchBytes >= 0;
                released = searchEnded && batches == 0;
                if (searchEnded) {
                    chargeOnlyBatches();
                }
            }
            if (released) {
                notifyReleased();
            }
        }

        /**
         * Ends the search's own hold, the first time it is called: the bytes return to the budget but for the batches that
         * still hold the reservation.
         *
         * @return whether this was the first call and batches still hold the reservation
         */
        boolean endSearch() {
            final boolean held;
            synchronized (this) {
                if (searchEnded) {
                    return false;
                }
                searchEnded = true;
                chargeOnlyBatches();
                held = batches > 0;
            }
            if (held == false) {
                notifyReleased();
            }
            return held;
        }

        /**
         * @return whether the search ended and no batch holds the reservation anymore, so that its bytes returned
         */
        synchronized boolean isReleased() {
            return searchEnded && batches == 0;
        }

        /**
         * @param callback run once the bytes returned to the budget, at once if they already did; replaces an earlier one
         */
        void whenReleased(Runnable callback) {
            final Runnable previous = onReleased.getAndUpdate(current -> current == RELEASED ? RELEASED : callback);
            if (previous == RELEASED) {
                callback.run();
            }
        }

        private void chargeOnlyBatches() {
            assert Thread.holdsLock(this);
            final long target = Math.min(charged, batchBytes);
            reserved.addAndGet(target - charged);
            charged = target;
        }

        private void notifyReleased() {
            final Runnable callback = onReleased.getAndSet(RELEASED);
            if (callback != null) {
                callback.run();
            }
        }
    }
}
