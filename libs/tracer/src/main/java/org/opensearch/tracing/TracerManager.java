/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.tracing;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.tracing.noop.NoopTracer;

import java.io.IOException;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * TracerManager represents a single global class that is used to access tracers.
 *
 * The Tracer singleton object can be retrieved using TracerManager.getTracer(). The TracerManager object
 * is created during class initialization and cannot subsequently be changed.
 */
public class TracerManager {

    private static final Logger logger = LogManager.getLogger(TracerManager.class);
    private static volatile TracerManager INSTANCE;

    private final Supplier<Tracer> defaultTracer;

    private final TracerSettings tracerSettings;
    private final Supplier<Telemetry> telemetrySupplier;


    /**
     * Initializes the TracerFactory singleton instance
     *
     * @param tracerSettings       tracer settings instance
     */
    public static synchronized void initTracerManager(
        TracerSettings tracerSettings,
        Supplier<Telemetry> tracerSupplier,
        Supplier<Tracer> defaultTracerSupplier
    ) {
        if (INSTANCE == null) {
            INSTANCE = new TracerManager(tracerSettings, tracerSupplier, defaultTracerSupplier);
        } else {
            logger.warn("Trying to double initialize TracerFactory, skipping");
        }
    }

    /**
     * Returns the {@link Tracer} singleton instance
     * @return Tracer instance
     */
    public static Tracer getTracer() {
        return INSTANCE == null ? NoopTracer.INSTANCE : INSTANCE.tracer();
    }

    public static BiConsumer<Map<String, String>, Map<String, Object>> getTracerHeaderInjector() {
        return INSTANCE == null ? (x, y) -> {} : INSTANCE.tracerHeaderInjector();
    }

    /**
     * Closes the {@link Tracer}
     */
    public static void closeTracer() {
        if (INSTANCE != null && INSTANCE.defaultTracer != null) {
            try {
                INSTANCE.defaultTracer.get().close();
            } catch (IOException e) {
                logger.warn("Error closing tracer", e);
            }
        }
    }

    public TracerManager(TracerSettings tracerSettings, Supplier<Telemetry> telemetrySupplier, Supplier<Tracer> defaultTracer) {
        this.tracerSettings = tracerSettings;
        this.telemetrySupplier = telemetrySupplier;
        this.defaultTracer = defaultTracer;
    }

    private Tracer tracer() {
        return isTracingDisabled() ? NoopTracer.INSTANCE : INSTANCE.defaultTracer.get();
    }

    private BiConsumer<Map<String, String>, Map<String, Object>> tracerHeaderInjector() {
        return isTracingDisabled() ? (x, y) -> {} : telemetrySupplier.get().injectSpanInHeader();
    }

    public boolean isTracingDisabled() {
        return Level.DISABLED == tracerSettings.getTracerLevel();
    }

    // for testing
    static void clear() {
        INSTANCE = null;
    }

}
