/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.example.stream;

import org.opensearch.action.ActionRequest;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.FixedExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Example plugin demonstrating streaming transport actions
 */
public class StreamTransportExamplePlugin extends Plugin implements ActionPlugin {

    /** Benchmark thread pool name */
    public static final String BENCHMARK_THREAD_POOL_NAME = "benchmark";

    /**
     * Constructor
     */
    public StreamTransportExamplePlugin() {}

    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        return Collections.singletonList(
            new FixedExecutorBuilder(
                settings,
                BENCHMARK_THREAD_POOL_NAME,
                Math.max(2000, Runtime.getRuntime().availableProcessors() * 10),
                10000,
                "thread_pool." + BENCHMARK_THREAD_POOL_NAME,
                false
            )
        );
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return Arrays.asList(
            new ActionHandler<>(StreamDataAction.INSTANCE, TransportStreamDataAction.class),
            new ActionHandler<>(BenchmarkStreamAction.INSTANCE, TransportBenchmarkStreamAction.class)
        );
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        return List.of(new RestBenchmarkStreamAction());
    }
}
