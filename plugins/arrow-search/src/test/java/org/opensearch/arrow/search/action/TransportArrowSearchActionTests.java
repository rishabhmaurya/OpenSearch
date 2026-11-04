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
import org.opensearch.arrow.search.ArrowSearchSettings;
import org.opensearch.cluster.coordination.DeterministicTaskQueue;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.Scheduler;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.opensearch.node.Node.NODE_NAME_SETTING;

public class TransportArrowSearchActionTests extends OpenSearchTestCase {

    private static final TimeValue IDLE_TIMEOUT = TimeValue.timeValueSeconds(60);

    private final CoordinatorBudget budget = new CoordinatorBudget(() -> 100);
    private final CoordinatorBudget.Reservation reservation = budget.reserve(64, 10);
    private final DeterministicTaskQueue scheduler = new DeterministicTaskQueue(
        Settings.builder().put(NODE_NAME_SETTING.getKey(), "node").build(),
        random()
    );
    private final AtomicInteger resets = new AtomicInteger();
    /** The batch gRPC still holds for the client, which a reset discards. */
    private Releasable unreadBatch;
    private final ArrowBatchSink sink = new ArrowBatchSink() {
        @Override
        public void sendSchema(Schema schema) {}

        @Override
        public void sendBatch(VectorSchemaRoot batch) {
            batch.close();
        }

        @Override
        public void complete(SearchResponse summary) {}

        @Override
        public void fail(Exception failure) {}

        @Override
        public void reset() {
            resets.incrementAndGet();
            if (unreadBatch != null) {
                unreadBatch.close();
            }
        }

        @Override
        public void setOnCancel(Runnable onCancel) {}
    };

    private static ArrowSearchSettings settings(Settings settings) {
        return new ArrowSearchSettings(settings, new ClusterSettings(settings, Set.copyOf(ArrowSearchSettings.all())));
    }

    private void runScheduledTasks() {
        while (scheduler.hasDeferredTasks()) {
            scheduler.advanceTime();
            scheduler.runAllRunnableTasks();
        }
    }

    public void testStreamsAreSizedForRowsLargerThanBatches() {
        TransportArrowSearchAction.BatchSizes sizes = TransportArrowSearchAction.BatchSizes.of(
            settings(Settings.builder().put(ArrowSearchSettings.MAX_ROW_SIZE.getKey(), "3mb").build())
        );
        assertEquals(new ByteSizeValue(1, ByteSizeUnit.MB).getBytes(), sizes.batchBytes());
        // A row larger than the batch size goes out as a batch of its own, so a stream may hold only such batches.
        assertEquals(new ByteSizeValue(3, ByteSizeUnit.MB).getBytes(), sizes.maxBatchBytes());
        assertEquals(TransportArrowSearchAction.BATCHES_IN_FLIGHT_PER_STREAM * sizes.maxBatchBytes(), sizes.perStream());
    }

    public void testRowsSmallerThanBatchesDoNotShrinkThem() {
        TransportArrowSearchAction.BatchSizes sizes = TransportArrowSearchAction.BatchSizes.of(
            settings(
                Settings.builder()
                    .put(ArrowSearchSettings.BATCH_SIZE.getKey(), "256kb")
                    .put(ArrowSearchSettings.FIRST_BATCH_SIZE.getKey(), "1mb")
                    .put(ArrowSearchSettings.MAX_ROW_SIZE.getKey(), "16kb")
                    .build()
            )
        );
        long batchBytes = new ByteSizeValue(256, ByteSizeUnit.KB).getBytes();
        assertEquals(batchBytes, sizes.maxBatchBytes());
        assertEquals(batchBytes, sizes.firstBatchBytes());
        assertEquals(TransportArrowSearchAction.BATCHES_IN_FLIGHT_PER_STREAM * batchBytes, sizes.perStream());
    }

    public void testSearchWhoseBatchesWereAllReadReleasesItsReservation() {
        TransportArrowSearchAction.endSearch(reservation, sink, scheduler.getThreadPool(), IDLE_TIMEOUT);
        assertEquals(0, budget.reserved());
        assertFalse(scheduler.hasDeferredTasks());
    }

    public void testClientThatDoesNotReadItsLastBatchesIsReset() {
        unreadBatch = reservation.holdBatch(10);
        TransportArrowSearchAction.endSearch(reservation, sink, scheduler.getThreadPool(), IDLE_TIMEOUT);
        assertEquals("only the batch the client has not read still counts against the coordinator", 10, budget.reserved());

        runScheduledTasks();
        assertEquals(IDLE_TIMEOUT.millis(), scheduler.getCurrentTimeMillis());
        assertEquals(1, resets.get());
        assertEquals(0, budget.reserved());
    }

    public void testClientThatReadsItsLastBatchesInTimeIsNotReset() {
        Releasable batch = reservation.holdBatch(10);
        TransportArrowSearchAction.endSearch(reservation, sink, scheduler.getThreadPool(), IDLE_TIMEOUT);
        batch.close();
        assertEquals(0, budget.reserved());

        runScheduledTasks();
        assertEquals(0, resets.get());
    }

    public void testSearchEndedTwiceKeepsItsUnreadBatchesCharged() {
        unreadBatch = reservation.holdBatch(10);
        AtomicInteger scheduled = new AtomicInteger();
        Scheduler counting = (command, delay, executor) -> {
            scheduled.incrementAndGet();
            return scheduler.getThreadPool().schedule(command, delay, executor);
        };
        // As when the listener's response handling throws and the listener is then notified of the failure.
        TransportArrowSearchAction.endSearch(reservation, sink, counting, IDLE_TIMEOUT);
        TransportArrowSearchAction.endSearch(reservation, sink, counting, IDLE_TIMEOUT);
        assertEquals("the second end does not release the batch's hold", 10, budget.reserved());
        assertEquals("the second end does not schedule another reset", 1, scheduled.get());

        runScheduledTasks();
        assertEquals("the call is reset once", 1, resets.get());
        assertEquals(0, budget.reserved());
    }

    public void testSearchReservesOnlyWhatItsShardsNeed() {
        assertEquals("one stream per shard", 42, TransportArrowSearchAction.reservationBytes(21, 64, 2));
        assertEquals(21, TransportArrowSearchAction.reservationBytes(21, 64, 1));
        assertEquals("within the per-request limit", 64, TransportArrowSearchAction.reservationBytes(21, 64, 100));
        assertEquals(64, TransportArrowSearchAction.reservationBytes(21, 64, Long.MAX_VALUE));
        assertEquals("at least one stream", 21, TransportArrowSearchAction.reservationBytes(21, 10, 5));
    }
}
