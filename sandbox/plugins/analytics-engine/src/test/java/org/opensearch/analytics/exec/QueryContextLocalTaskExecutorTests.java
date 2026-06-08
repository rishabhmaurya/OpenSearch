/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.opensearch.analytics.exec.task.AnalyticsQueryTask;
import org.opensearch.analytics.planner.dag.QueryDAG;
import org.opensearch.analytics.planner.dag.Stage;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.tasks.TaskId;
import org.opensearch.tasks.Task;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

/**
 * Verifies the GAP-C fix in {@link QueryContext#localTaskExecutor()}: the per-query
 * virtual-thread executor must propagate the submitting thread's {@link ThreadContext}
 * (which carries the in-scope tracing span and {@code X-Opaque-Id}) onto the worker
 * thread. A raw {@code newThreadPerTaskExecutor} does not, which would detach
 * LOCAL/late-materialization spans from the query trace (tracing GAP-C).
 */
public class QueryContextLocalTaskExecutorTests extends OpenSearchTestCase {

    private static final RootAllocator TEST_ROOT = new RootAllocator(Long.MAX_VALUE);

    private ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getTestName());
    }

    @Override
    public void tearDown() throws Exception {
        ThreadPool.terminate(threadPool, 5, TimeUnit.SECONDS);
        super.tearDown();
    }

    public void testLocalTaskExecutorPropagatesThreadContext() throws Exception {
        QueryContext ctx = newQueryContext("q-ctx-prop");
        try {
            ThreadContext threadContext = threadPool.getThreadContext();
            AtomicReference<String> seenOnWorker = new AtomicReference<>("<not-run>");
            AtomicReference<String> workerThreadName = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            // Set a header on the SUBMITTING thread's context. Tracing rides the same
            // ThreadContext (CURRENT_SPAN transient + X-Opaque-Id header), so a header
            // surviving the hop proves the span context would too.
            try (ThreadContext.StoredContext ignored = threadContext.stashContext()) {
                threadContext.putHeader(Task.X_OPAQUE_ID, "trace-abc-123");

                Executor exec = ctx.localTaskExecutor();
                exec.execute(() -> {
                    workerThreadName.set(Thread.currentThread().getName());
                    seenOnWorker.set(threadContext.getHeader(Task.X_OPAQUE_ID));
                    done.countDown();
                });
            }

            assertTrue("local task did not run within timeout", done.await(5, TimeUnit.SECONDS));
            assertEquals("X-Opaque-Id must propagate to the local-task worker thread", "trace-abc-123", seenOnWorker.get());
            // Sanity: the task really ran on the per-query virtual thread, not inline.
            assertTrue(
                "expected an analytics-local-task virtual thread, got: " + workerThreadName.get(),
                workerThreadName.get() != null && workerThreadName.get().startsWith("analytics-local-task-")
            );
        } finally {
            ctx.close();
        }
    }

    public void testLocalTaskWorkerHasNoLeakedContextWithoutSubmitter() throws Exception {
        QueryContext ctx = newQueryContext("q-ctx-clean");
        try {
            ThreadContext threadContext = threadPool.getThreadContext();
            AtomicReference<String> seenOnWorker = new AtomicReference<>("<not-run>");
            CountDownLatch done = new CountDownLatch(1);

            // No header set on the submitting thread -> worker must observe no header
            // (preserveContext restores the submitter's empty context, it doesn't leak
            // whatever happened to be on the virtual thread from a prior task).
            Executor exec = ctx.localTaskExecutor();
            exec.execute(() -> {
                seenOnWorker.set(threadContext.getHeader(Task.X_OPAQUE_ID));
                done.countDown();
            });

            assertTrue("local task did not run within timeout", done.await(5, TimeUnit.SECONDS));
            assertNull("no X-Opaque-Id should be visible when the submitter had none", seenOnWorker.get());
        } finally {
            ctx.close();
        }
    }

    private QueryContext newQueryContext(String queryId) {
        BufferAllocator allocator = TEST_ROOT.newChildAllocator(queryId, 0, Long.MAX_VALUE);
        AnalyticsQueryTask task = new AnalyticsQueryTask(
            1L,
            "transport",
            "analytics_query",
            queryId,
            TaskId.EMPTY_TASK_ID,
            Map.of(),
            null
        );
        QueryDAG dag = new QueryDAG(queryId, mock(Stage.class));
        // Real ThreadPool -> the context-preserving wrap is active (production path).
        return new QueryContext(dag, threadPool, task, allocator, true, 1, 1024);
    }
}
