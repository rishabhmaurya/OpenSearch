/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.opensearch.arrow.search.Column;
import org.opensearch.arrow.search.Column.ColumnType;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MapperService;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.test.OpenSearchTestCase;
import org.junit.After;
import org.junit.Before;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the collectors of a shard's slices directly, to interleave them deterministically around the shard's row limit.
 */
public class ArrowScanCollectorTests extends OpenSearchTestCase {

    private static final Column COLUMN = new Column("k", ColumnType.UTF8, Set.of("keyword"));
    /** Rows per batch: the first chunk a slice buffers fills its first batch with every doc of the chunk. */
    private static final int BATCH_ROWS = ArrowScanCollector.SEED_CHUNK;

    private final AtomicInteger slotsHeld = new AtomicInteger();
    private BufferAllocator allocator;

    @Before
    public void createAllocator() {
        allocator = new RootAllocator();
    }

    @After
    public void closeAllocator() {
        assertEquals("compute slots are released", 0, slotsHeld.get());
        assertEquals("batches are released", 0, allocator.getAllocatedMemory());
        allocator.close();
    }

    /**
     * Slice a sends a batch of every doc it buffered while slice b takes the shard's last rows: once a resumes, it has no
     * compute slot to look for a match past the limit with, so it stops at once rather than score the rest of its segment
     * outside of the node's compute slots and its budget, and the rest of its segment may hold matches the limit cut.
     */
    public void testSliceResumingAfterTheRowLimitWasReachedStopsScoring() throws Exception {
        assertSliceResumingAfterTheRowLimitWasReached(BATCH_ROWS + randomIntBetween(1, 100), true);
    }

    /** As above, with the batch slice a sends ending its segment: no doc of the segment is left out. */
    public void testSliceResumingAfterTheRowLimitWasReachedAtTheEndOfItsSegmentLeavesNoMatchOut() throws Exception {
        assertSliceResumingAfterTheRowLimitWasReached(BATCH_ROWS, false);
    }

    private void assertSliceResumingAfterTheRowLimitWasReached(int aDocs, boolean terminatedEarly) throws Exception {
        int bDocs = randomIntBetween(1, BATCH_ROWS - 1);
        withSegments(new int[] { aDocs, bDocs }, leaves -> {
            assertEquals(aDocs, leaves.get(0).reader().maxDoc());
            CountDownLatch sending = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            ScanState state = state(BATCH_ROWS + bDocs, -1, batch -> {
                batch.close();
                sending.countDown();
                assertTrue(resume.await(30, TimeUnit.SECONDS));
            });
            try (state) {
                ArrowScanCollector a = new ArrowScanCollector(state, context());
                ArrowScanCollector b = new ArrowScanCollector(state, context());
                AtomicInteger stoppedAt = new AtomicInteger(-1);
                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread sliceA = Thread.ofVirtual().start(() -> {
                    try {
                        LeafCollector leaf = a.getLeafCollector(leaves.get(0));
                        leaf.setScorer(null);
                        int doc = 0;
                        try {
                            for (; doc < aDocs; doc++) {
                                leaf.collect(doc);
                            }
                        } catch (CollectionTerminatedException e) {
                            stoppedAt.set(doc);
                        }
                        leaf.finish();
                        a.finish();
                    } catch (Exception e) {
                        failure.set(e);
                    }
                });
                try {
                    assertTrue("slice a sends its first batch", sending.await(30, TimeUnit.SECONDS));
                    // Slice b reads every doc of its segment, which are the shard's last rows: it leaves no match out.
                    LeafCollector leaf = b.getLeafCollector(leaves.get(1));
                    leaf.setScorer(null);
                    for (int doc = 0; doc < bDocs; doc++) {
                        leaf.collect(doc);
                    }
                    leaf.finish();
                    b.finish();
                    assertTrue(state.rowBudget.exhausted());
                    assertFalse(state.terminatedEarly);
                } finally {
                    resume.countDown();
                    sliceA.join();
                }
                assertNull(failure.get());
                assertEquals("slice a stops at the doc whose batch it sent", BATCH_ROWS - 1, stoppedAt.get());
                assertEquals(terminatedEarly, state.terminatedEarly);
                assertFalse(state.timedOut);
                assertEquals(BATCH_ROWS, state.rowsSent.get());
                assertRowsUnsent(state, bDocs);
            }
        });
    }

    /**
     * A slice that reached the shard's row limit with every doc it read keeps scoring, for a match past the limit; its compute
     * budget running out meanwhile ends that wait, but does not time the scan out: it kept every row the shard may stream.
     */
    public void testComputeBudgetSpentWhileAwaitingAMatchPastTheRowLimitIsNoTimeout() throws Exception {
        int limit = randomIntBetween(1, BATCH_ROWS - 1);
        withSegments(new int[] { 100 }, leaves -> {
            ScanState state = state(limit, TimeUnit.MILLISECONDS.toNanos(100), batch -> {
                batch.close();
                fail("no batch is full");
            });
            try (state) {
                ArrowScanCollector collector = new ArrowScanCollector(state, context());
                LeafCollector leaf = collector.getLeafCollector(leaves.get(0));
                leaf.setScorer(null);
                for (int doc = 0; doc < limit; doc++) {
                    leaf.collect(doc);
                }
                assertTrue(state.rowBudget.exhausted());
                Runnable budgetCheck = ArrowScanCollector.budgetCheck(state);
                assertBusy(() -> expectThrows(CollectionTerminatedException.class, budgetCheck::run));
                assertFalse("the row limit, not the budget, ended the scan", state.timedOut);
                assertTrue("the rest of the segment may match", state.terminatedEarly);
                leaf.finish();
                collector.finish();
                assertRowsUnsent(state, limit);
            }
        });
    }

    private ScanState state(long rowLimit, long computeBudgetNanos, ScanState.BatchSender sender) {
        ArrowScanSpec spec = new ArrowScanSpec(
            List.of(COLUMN),
            1 << 20,
            1 << 20,
            BATCH_ROWS,
            3 << 20,
            rowLimit,
            TimeValue.timeValueMinutes(1)
        );
        return new ScanState(spec, allocator, field -> true, sender, waitNanos -> {
            slotsHeld.incrementAndGet();
            return slotsHeld::decrementAndGet;
        }, () -> false, computeBudgetNanos, null);
    }

    /** Checks the rows the scan left for the end of its stream, and releases them. */
    private static void assertRowsUnsent(ScanState state, int rowsUnsent) {
        int rows = 0;
        for (VectorSchemaRoot batch : state.takeRemainingRows()) {
            try (batch) {
                rows += batch.getRowCount();
            }
        }
        assertEquals(rowsUnsent, rows);
    }

    private static QueryShardContext context() {
        MapperService mapperService = mock(MapperService.class);
        when(mapperService.fieldType(COLUMN.name())).thenReturn(new KeywordFieldMapper.KeywordFieldType(COLUMN.name()));
        QueryShardContext context = mock(QueryShardContext.class);
        when(context.getMapperService()).thenReturn(mapperService);
        return context;
    }

    @FunctionalInterface
    private interface ReaderTest {
        void run(List<LeafReaderContext> leaves) throws Exception;
    }

    /** Runs {@code test} on one segment per entry of {@code docsPerSegment}, with that many docs, each with a value of the column. */
    private static void withSegments(int[] docsPerSegment, ReaderTest test) throws Exception {
        try (Directory directory = new ByteBuffersDirectory()) {
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
                for (int docs : docsPerSegment) {
                    for (int doc = 0; doc < docs; doc++) {
                        Document document = new Document();
                        document.add(new SortedSetDocValuesField(COLUMN.name(), new BytesRef("v" + doc)));
                        writer.addDocument(document);
                    }
                    writer.commit();
                }
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                assertEquals(docsPerSegment.length, reader.leaves().size());
                test.run(reader.leaves());
            }
        }
    }
}
