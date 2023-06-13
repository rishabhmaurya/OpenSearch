/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.tracing.impl;

import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugins.TracerPlugin;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.tracing.Telemetry;
import org.opensearch.tracing.Tracer;
import org.opensearch.tracing.TracerSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A module for loading classes for tracer
 *
 * @opensearch.internal
 */
public class TracerModule {

    private static final String TRACER_TYPE_DEFAULT_KEY = "tracer.type.default";
    private static final String TRACER_TYPE_KEY = "tracer.type";

    private final Object mutex = new Object();

    private volatile Tracer defaultTracer;

    private final TracerSettings tracerSettings;

    private final ThreadPool threadPool;

    public static final Setting<String> TRACER_DEFAULT_TYPE_SETTING = Setting.simpleString(
        TRACER_TYPE_DEFAULT_KEY,
        Setting.Property.NodeScope
    );
    public static final Setting<String> TRACER_TYPE_SETTING = Setting.simpleString(TRACER_TYPE_KEY, Setting.Property.NodeScope);

    private final Settings settings;
    private final Map<String, Supplier<Telemetry>> telemetryFactories = new HashMap<>();
    private final Map<String, Supplier<Tracer>> tracerFactories = new HashMap<>();

    public TracerModule(Settings settings, List<TracerPlugin> tracerPlugins, TracerSettings tracerSettings, ThreadPool threadPool) {
        this.settings = settings;
        this.tracerSettings = tracerSettings;
        this.threadPool = threadPool;

        for (TracerPlugin tracerPlugin : tracerPlugins) {
            Map<String, Supplier<Telemetry>> tracerFactory = tracerPlugin.getTelemetries(tracerSettings);
            for (Map.Entry<String, Supplier<Telemetry>> entry : tracerFactory.entrySet()) {
                registerTelemetry(entry.getKey(), entry.getValue());
            }
        }

        for (TracerPlugin tracerPlugin : tracerPlugins) {
            Map<String, Supplier<Tracer>> tracerFactory = tracerPlugin.getTracers(tracerSettings, threadPool.getThreadContext());
            for (Map.Entry<String, Supplier<Tracer>> entry : tracerFactory.entrySet()) {
                registerTracer(entry.getKey(), entry.getValue());
            }
        }
    }

    public Supplier<Telemetry> getTelemetrySupplier() {
        final String tracerType = getTracerType();
        return telemetryFactories.get(tracerType);
    }

    public Supplier<Tracer> getDefaultTracer() {
        final String tracerType = getTracerType();
        return tracerFactories.get(tracerType);
    }

    private String getTracerType() {
        final String tracerType = TRACER_DEFAULT_TYPE_SETTING.exists(settings)
            ? TRACER_DEFAULT_TYPE_SETTING.get(settings)
            : TRACER_TYPE_SETTING.get(settings);
        return tracerType;
    }

    private void registerTelemetry(String key, Supplier<Telemetry> factory) {
        if (telemetryFactories.putIfAbsent(key, factory) != null) {
            throw new IllegalArgumentException("tracer for name: " + key + " is already registered");
        }
    }

    private void registerTracer(String key, Supplier<Tracer> factory) {
        if (tracerFactories.putIfAbsent(key, factory) != null) {
            throw new IllegalArgumentException("tracer for name: " + key + " is already registered");
        }
    }
}
