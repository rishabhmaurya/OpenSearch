/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.Version;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.ActionType;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.TransportAction;
import org.opensearch.arrow.allocator.ArrowBasePlugin;
import org.opensearch.arrow.allocator.ArrowNativeAllocator;
import org.opensearch.arrow.flight.stats.FlightStatsCollector;
import org.opensearch.arrow.spi.NativeAllocatorPoolConfig;
import org.opensearch.arrow.transport.ArrowBatchResponse;
import org.opensearch.arrow.transport.ArrowBatchResponseHandler;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.tasks.Task;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.StreamTransportService;
import org.opensearch.transport.TransportChannel;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.stream.StreamTransportResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.opensearch.common.util.FeatureFlags.STREAM_TRANSPORT;

/**
 * Verifies the in-process (same-JVM) streaming path end-to-end on a real {@link FlightTransport}.
 *
 * <p>When a streaming request targets the coordinator's own node, {@link FlightTransport#initiateChannel}
 * builds the Flight client over gRPC's in-process transport ({@code FlightGrpcUtils} + an
 * {@code InProcessChannelBuilder} channel) instead of the loopback socket. This test drives a real
 * Arrow-batch stream to the local node and asserts:
 * <ul>
 *   <li>every batch arrives intact and in order (correctness of the in-process path), and</li>
 *   <li>the in-process channel actually engaged (the {@code FlightTransport} counter incremented),
 *       proving the optimization fired rather than silently falling back to the wire.</li>
 * </ul>
 * The base-class allocator/thread-leak checks at teardown guard the Arrow buffer lifecycle across the
 * by-reference in-process handoff (no use-after-free, no leaked off-heap buffers).
 */
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 1, supportsDedicatedMasters = false)
public class LocalInProcessStreamIT extends OpenSearchIntegTestCase {

    private static final Schema SCHEMA = new Schema(List.of(new Field("value", FieldType.nullable(new ArrowType.Int(32, true)), null)));

    private static final int BATCH_COUNT = 5;
    private static final int ROWS_PER_BATCH = 1024;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(LocalStreamTestPlugin.class, ArrowBasePlugin.class);
    }

    @Override
    protected Collection<PluginInfo> additionalNodePlugins() {
        return List.of(
            new PluginInfo(
                FlightStreamPlugin.class.getName(),
                "classpath plugin",
                "NA",
                Version.CURRENT,
                "1.8",
                FlightStreamPlugin.class.getName(),
                null,
                List.of(ArrowBasePlugin.class.getName()),
                false
            )
        );
    }

    @LockFeatureFlag(STREAM_TRANSPORT)
    public void testLocalStreamUsesInProcessChannel() throws Exception {
        StreamTransportService sts = internalCluster().getInstance(StreamTransportService.class);
        FlightStatsCollector stats = internalCluster().getInstance(FlightStatsCollector.class);

        int before = stats.getInProcessChannelsOpened();

        DiscoveryNode localNode = sts.getLocalNode();
        // The stream service has no implicit local send path; establish a real self-connection, which
        // FlightTransport serves over its in-process endpoint.
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<Exception> connectFailure = new AtomicReference<>();
        sts.connectToNode(localNode, null, ActionListener.wrap(v -> connected.countDown(), e -> {
            connectFailure.set(e);
            connected.countDown();
        }));
        assertTrue("self-connection should establish within 30s", connected.await(30, TimeUnit.SECONDS));
        assertNull("self-connection must not fail: " + connectFailure.get(), connectFailure.get());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Integer> firstValues = new ArrayList<>();
        AtomicInteger batchesReceived = new AtomicInteger(0);

        sts.sendRequest(
            localNode,
            LocalStreamAction.NAME,
            new LocalStreamRequest(BATCH_COUNT, ROWS_PER_BATCH),
            TransportRequestOptions.builder().withType(TransportRequestOptions.Type.STREAM).build(),
            new ArrowBatchResponseHandler<LocalStreamResponse>() {
                @Override
                public void handleStreamResponse(StreamTransportResponse<LocalStreamResponse> stream) {
                    try {
                        LocalStreamResponse response;
                        while ((response = stream.nextResponse()) != null) {
                            try (VectorSchemaRoot root = response.getRoot()) {
                                IntVector v = (IntVector) root.getVector("value");
                                firstValues.add(v.get(0));
                                batchesReceived.incrementAndGet();
                            }
                        }
                        stream.close();
                    } catch (Exception e) {
                        failure.compareAndSet(null, e);
                        stream.cancel("consumer error", e);
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void handleException(TransportException exp) {
                    failure.compareAndSet(null, exp);
                    latch.countDown();
                }

                @Override
                public String executor() {
                    return ThreadPool.Names.GENERIC;
                }

                @Override
                public LocalStreamResponse read(StreamInput in) throws IOException {
                    return new LocalStreamResponse(in);
                }
            }
        );

        assertTrue("local stream should complete within 30s", latch.await(30, TimeUnit.SECONDS));
        assertNull("no failure expected on the in-process path: " + failure.get(), failure.get());
        assertEquals("all batches must arrive", BATCH_COUNT, batchesReceived.get());
        // Producer writes batch index b as value 0 of batch b, so the first-value sequence is 0..N-1.
        assertEquals(List.of(0, 1, 2, 3, 4), firstValues);

        int after = stats.getInProcessChannelsOpened();
        assertTrue("same-node stream must open an in-process channel (before=" + before + ", after=" + after + ")", after > before);
    }

    // ── action / request / response / plugin ──

    public static class LocalStreamResponse extends ArrowBatchResponse {
        public LocalStreamResponse(VectorSchemaRoot root) {
            super(root);
        }

        public LocalStreamResponse(StreamInput in) throws IOException {
            super(in);
        }
    }

    public static class LocalStreamRequest extends ActionRequest {
        private final int batchCount;
        private final int rowsPerBatch;

        public LocalStreamRequest(int batchCount, int rowsPerBatch) {
            this.batchCount = batchCount;
            this.rowsPerBatch = rowsPerBatch;
        }

        public LocalStreamRequest(StreamInput in) throws IOException {
            super(in);
            this.batchCount = in.readInt();
            this.rowsPerBatch = in.readInt();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeInt(batchCount);
            out.writeInt(rowsPerBatch);
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }
    }

    public static class LocalStreamAction extends ActionType<LocalStreamResponse> {
        public static final LocalStreamAction INSTANCE = new LocalStreamAction();
        public static final String NAME = "cluster:internal/test/local_inprocess_stream";

        private LocalStreamAction() {
            super(NAME, LocalStreamResponse::new);
        }
    }

    public static class TransportLocalStreamAction extends TransportAction<LocalStreamRequest, LocalStreamResponse> {
        private final BufferAllocator allocator;

        @Inject
        public TransportLocalStreamAction(
            StreamTransportService streamTransportService,
            ActionFilters actionFilters,
            ArrowNativeAllocator nativeAllocator
        ) {
            super(LocalStreamAction.NAME, actionFilters, streamTransportService.getTaskManager());
            this.allocator = nativeAllocator.getPoolAllocator(NativeAllocatorPoolConfig.POOL_FLIGHT)
                .newChildAllocator("local-inprocess-it", 0, Long.MAX_VALUE);
            streamTransportService.registerRequestHandler(
                LocalStreamAction.NAME,
                ThreadPool.Names.GENERIC,
                LocalStreamRequest::new,
                this::handleStreamRequest
            );
        }

        @Override
        protected void doExecute(Task task, LocalStreamRequest request, ActionListener<LocalStreamResponse> listener) {
            listener.onFailure(new UnsupportedOperationException("Use StreamTransportService"));
        }

        private void handleStreamRequest(LocalStreamRequest request, TransportChannel channel, Task task) throws IOException {
            try {
                for (int b = 0; b < request.batchCount; b++) {
                    VectorSchemaRoot root = VectorSchemaRoot.create(SCHEMA, allocator);
                    boolean transferred = false;
                    try {
                        IntVector v = (IntVector) root.getVector("value");
                        v.allocateNew(request.rowsPerBatch);
                        // value 0 carries the batch index so the consumer can assert ordering.
                        v.setSafe(0, b);
                        for (int i = 1; i < request.rowsPerBatch; i++) {
                            v.setSafe(i, i);
                        }
                        root.setRowCount(request.rowsPerBatch);
                        channel.sendResponseBatch(new LocalStreamResponse(root));
                        transferred = true;
                    } finally {
                        if (!transferred) {
                            root.close();
                        }
                    }
                }
                channel.completeStream();
            } catch (Exception e) {
                channel.sendResponse(e);
            }
        }
    }

    public static class LocalStreamTestPlugin extends Plugin implements ActionPlugin {
        public LocalStreamTestPlugin() {}

        @Override
        public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
            return List.of(new ActionHandler<>(LocalStreamAction.INSTANCE, TransportLocalStreamAction.class));
        }
    }
}
