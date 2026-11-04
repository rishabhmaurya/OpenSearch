/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionRequest;
import org.opensearch.arrow.search.action.ArrowSearchAction;
import org.opensearch.arrow.search.action.TransportArrowSearchAction;
import org.opensearch.arrow.search.scan.ArrowScanCollectorSpecFactory;
import org.opensearch.arrow.search.scan.ArrowScanIndexListener;
import org.opensearch.arrow.search.scan.ArrowScanRegistry;
import org.opensearch.arrow.search.scan.ArrowScanSliceListener;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.index.IndexModule;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.search.query.QueryCollectorContextSpecFactory;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.threadpool.VirtualExecutorBuilder;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Streams search results as Apache Arrow record batches read from doc values.
 * <p>
 * Clients call the gRPC {@code SearchService/ServerStreamSearch} method with the {@code x-opensearch-format: arrow}
 * header. The coordinating node runs the search through the regular search coordination, and streams every shard over the
 * native Arrow stream transport; each batch is computed by a query phase on the data node and relayed to the client as an
 * Arrow IPC message, without decoding it.
 */
public class ArrowSearchPlugin extends Plugin implements ActionPlugin, SearchPlugin {

    /**
     * Virtual-thread executor of shard streams on the coordinating node, and of shard scans on data nodes, whose query
     * phases and slices both compute their batches on it, as many at once as the scans' compute slots allow, and wait on it
     * for their consumer.
     */
    public static final String EXECUTOR = "arrow_search";

    /** The node attribute of nodes that serve Arrow search shard streams. */
    public static final String NODE_ATTRIBUTE = "arrow_search";

    private static final Logger logger = LogManager.getLogger(ArrowSearchPlugin.class);

    private final ArrowScanRegistry registry;
    private final AtomicReference<Components> components = new AtomicReference<>();

    /**
     * @param settings the node settings
     */
    public ArrowSearchPlugin(Settings settings) {
        this.registry = new ArrowScanRegistry(
            ArrowSearchSettings.SHARD_MAX_OPEN_SCANS.get(settings),
            ArrowSearchSettings.SHARD_MAX_CONCURRENT_BATCHES.get(settings)
        );
    }

    /**
     * What the plugin's request handlers need once the node created its components, and the allocators they create, which
     * close with the node.
     */
    public static final class Components {
        private final Client client;
        private final ArrowSearchSettings settings;
        private final Executor executor;
        private final ArrowScanRegistry registry;
        private final List<BufferAllocator> allocators = new ArrayList<>();

        Components(Client client, ArrowSearchSettings settings, Executor executor, ArrowScanRegistry registry) {
            this.client = client;
            this.settings = settings;
            this.executor = executor;
            this.registry = registry;
        }

        public Client client() {
            return client;
        }

        public ArrowSearchSettings settings() {
            return settings;
        }

        public Executor executor() {
            return executor;
        }

        public ArrowScanRegistry registry() {
            return registry;
        }

        /**
         * Keeps an allocator to close with the node.
         */
        public void registerAllocator(BufferAllocator allocator) {
            synchronized (allocators) {
                allocators.add(allocator);
            }
        }

        void closeAllocators() {
            synchronized (allocators) {
                for (BufferAllocator allocator : allocators) {
                    try {
                        allocator.close();
                    } catch (RuntimeException e) {
                        logger.warn("failed to close arrow-search allocator [" + allocator.getName() + "]", e);
                    }
                }
                allocators.clear();
            }
        }
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        final ArrowSearchSettings settings = new ArrowSearchSettings(clusterService.getSettings(), clusterService.getClusterSettings());
        clusterService.getClusterSettings().addSettingsUpdateConsumer(ArrowSearchSettings.SHARD_MAX_OPEN_SCANS, registry::setMaxOpenScans);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(ArrowSearchSettings.SHARD_MAX_CONCURRENT_BATCHES, registry::setMaxConcurrentBatches);
        final Components created = new Components(client, settings, threadPool.executor(EXECUTOR), registry);
        components.set(created);
        return List.of(created);
    }

    /**
     * @return the components, available once the node created them
     */
    public Components components() {
        return Objects.requireNonNull(components.get(), "arrow-search plugin components are not created yet");
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return List.of(new ActionHandler<>(ArrowSearchAction.INSTANCE, TransportArrowSearchAction.class));
    }

    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        return List.of(new VirtualExecutorBuilder(EXECUTOR));
    }

    @Override
    public List<QueryCollectorContextSpecFactory> getCollectorContextSpecFactories() {
        return List.of(new ArrowScanCollectorSpecFactory());
    }

    @Override
    public void onIndexModule(IndexModule indexModule) {
        final ArrowScanIndexListener listener = new ArrowScanIndexListener(registry);
        indexModule.addIndexEventListener(listener);
        indexModule.addSearchOperationListener(new ArrowScanSliceListener());
    }

    @Override
    public List<Setting<?>> getSettings() {
        return ArrowSearchSettings.all();
    }

    @Override
    public Settings additionalSettings() {
        // Lets coordinating nodes skip copies on nodes that cannot serve shard streams, e.g. during a rolling upgrade.
        return Settings.builder().put("node.attr." + NODE_ATTRIBUTE, true).build();
    }

    @Override
    public void close() {
        final Components created = components.get();
        if (created != null) {
            created.closeAllocators();
        }
    }
}
