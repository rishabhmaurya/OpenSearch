/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.opensearch.ExceptionsHelper;
import org.opensearch.arrow.search.ArrowSearchPlugin;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.tasks.TaskCancelledException;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.script.MockScriptPlugin;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptType;
import org.opensearch.search.SearchService;
import org.opensearch.threadpool.ThreadPool;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;

/**
 * Runs every shard scan test with the shard's segments collected in parallel slices on virtual threads, as concurrent
 * segment search does for a streaming aggregation, and checks the slices really run in parallel.
 */
public class ArrowShardParallelScanTests extends ArrowShardScanTests {

    private static final int SLICES = 4;

    @Override
    protected int computeSlots() {
        return SLICES;
    }

    @Override
    protected Executor sliceExecutor() {
        return getInstanceFromNode(ThreadPool.class).executor(ArrowSearchPlugin.EXECUTOR);
    }

    @Override
    protected Settings concurrentSearchSettings() {
        return Settings.builder()
            .put(SearchService.CLUSTER_CONCURRENT_SEGMENT_SEARCH_MODE.getKey(), "auto")
            .put(SearchService.CONCURRENT_SEGMENT_SEARCH_MAX_SLICE_COUNT_KEY, SLICES)
            .build();
    }

    public void testSlicesSendTheirOwnBatchesFromVirtualThreads() throws Exception {
        createIndex();
        indexDocs(4000, SLICES);
        Set<Thread> senders = ConcurrentHashMap.newKeySet();
        ScanResult result = scan(
            QueryBuilders.matchAllQuery(),
            spec(16 << 10, 16 << 10, Long.MAX_VALUE),
            field -> true,
            -1,
            batches -> senders.add(Thread.currentThread())
        );
        assertRows(expected.keySet(), result.rows());
        assertTrue("batches come from several slices: " + senders, senders.size() > 1);
        for (Thread sender : senders) {
            assertTrue(sender + " is virtual", sender.isVirtual());
        }
    }

    /**
     * A consumer that goes away fails every slice: the slices computing must free their compute slots at once, and the slices
     * waiting for one must stop waiting, or the scan never ends and holds the node's slots.
     */
    public void testCancelledScanEndsAndFreesItsComputeSlots() throws Exception {
        // Fewer compute slots than slices, so slices wait for each other's slots.
        registry = new ArrowScanRegistry(4, 1);
        createIndex();
        indexDocs(4000, SLICES);
        AtomicBoolean gone = new AtomicBoolean();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread scanThread = Thread.ofVirtual().start(() -> {
            try {
                scan(
                    QueryBuilders.matchAllQuery(),
                    spec(16 << 10, 16 << 10, Long.MAX_VALUE),
                    field -> true,
                    -1,
                    batches -> gone.set(true),
                    gone::get
                );
            } catch (Exception e) {
                failure.set(e);
            }
        });
        assertTrue("the cancelled scan ended", scanThread.join(Duration.ofSeconds(60)));
        assertNotNull(failure.get());
        assertNotNull(failure.get().toString(), ExceptionsHelper.unwrap(failure.get(), TaskCancelledException.class));
        try (Releasable slot = registry.acquireBatchSlot(0)) {
            assertNotNull("the scan freed every compute slot", slot);
        }
    }

    /**
     * A query that fails while it is scored fails its slice outside of the scan's collector: the slice must still free its
     * compute slot and stop its siblings, or they wait for the slot until the whole query phase ends, which it never does.
     */
    public void testQueryFailingWhileScoredEndsTheScanAndFreesItsComputeSlots() throws Exception {
        // Fewer compute slots than slices, so slices wait for each other's slots.
        registry = new ArrowScanRegistry(4, 1);
        createIndex();
        indexDocs(4000, SLICES);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Script fail = new Script(ScriptType.INLINE, MockScriptPlugin.NAME, FailingScriptPlugin.FAIL, Map.of());
        Thread scanThread = Thread.ofVirtual().start(() -> {
            try {
                scan(QueryBuilders.scriptQuery(fail), spec(16 << 10, 16 << 10, Long.MAX_VALUE));
            } catch (Exception e) {
                failure.set(e);
            }
        });
        assertTrue("the failed scan ended", scanThread.join(Duration.ofSeconds(60)));
        assertNotNull(failure.get());
        assertThat(failure.get().toString(), ExceptionsHelper.stackTrace(failure.get()), containsString("the script failed"));
        try (Releasable slot = registry.acquireBatchSlot(0)) {
            assertNotNull("the scan freed every compute slot", slot);
        }
    }
}
