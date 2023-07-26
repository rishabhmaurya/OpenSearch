/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.telemetry.metrics;

import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.telemetry.OTelTelemetrySettings;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A factory class for creating different types of OpenTelemetry Metric Exporters.
 */
public class OTelMetricExporterFactory {

    /**
     * Default constructor
     */
    private OTelMetricExporterFactory() {

    }

    private static final String METRIC_READER_THREAD_NAME = "otlp_metric_reader";

    /**
     * An enum representing different types of Metric Exporters.
     */
    public enum MetricExporterType {
        /**
         * OpenTelemetry gRPC Metric Exporter.
         */
        OTLP_GRPC {
            @Override
            public MetricExporter createExporter(Settings settings) throws PrivilegedActionException {
                String endpoint = "http://localhost:4317";
                return AccessController.doPrivileged(
                    (PrivilegedExceptionAction<OtlpGrpcMetricExporter>) () -> OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build()
                );
            }

            @Override
            public String getName() {
                return "otlp_grpc";
            }
        },
        /**
         * OpenTelemetry HTTP Metric Exporter.
         */
        OTLP_HTTP {
            @Override
            public MetricExporter createExporter(Settings settings) throws PrivilegedActionException {
                String endpoint = "http://localhost:4318/v1/metrics";
                return AccessController.doPrivileged(
                    (PrivilegedExceptionAction<OtlpHttpMetricExporter>) () -> OtlpHttpMetricExporter.builder().setEndpoint(endpoint).build()
                );
            }

            @Override
            public String getName() {
                return "otlp_http";
            }
        },
        /**
         * Logging Metric Exporter.
         */
        LOGGING {
            @Override
            public MetricExporter createExporter(Settings settings) {
                return LoggingMetricExporter.create();
            }

            @Override
            public String getName() {
                return "logging";
            }
        };

        /**
         * Checks if the enum contains the given metric exporter type key.
         *
         * @param key The key to check.
         * @return true if the key is found, false otherwise.
         */
        public static boolean containsKey(String key) {
            for (MetricExporterType value : MetricExporterType.values()) {
                if (value.getName().equals(key)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Creates a MetricExporter instance based on the given key and settings.
         *
         * @param key      The key representing the type of MetricExporter.
         * @param settings The settings to use for MetricExporter creation.
         * @return A MetricExporter instance.
         * @throws PrivilegedActionException if the creation of the MetricExporter fails.
         */
        public static MetricExporter createMetricExporter(String key, Settings settings) throws PrivilegedActionException {
            for (MetricExporterType value : MetricExporterType.values()) {
                if (value.getName().equals(key)) {
                    return value.createExporter(settings);
                }
            }
            return LOGGING.createExporter(settings);
        }

        /**
         * Abstract method to create a MetricExporter instance.
         *
         * @param settings The settings to use for MetricExporter creation.
         * @return A MetricExporter instance.
         * @throws PrivilegedActionException if the creation of the MetricExporter fails.
         */
        public abstract MetricExporter createExporter(Settings settings) throws PrivilegedActionException;

        /**
         * Abstract method to get the name of the MetricExporter type.
         *
         * @return The name of the MetricExporter type.
         */
        public abstract String getName();
    }

    /**
     * Creates a MetricExporter instance based on the provided settings.
     *
     * @param settings The settings to use for MetricExporter creation.
     * @return A MetricExporter instance.
     */
    private static MetricExporter createMetricExporter(Settings settings) {
        String metricExporterName = OTelTelemetrySettings.OTEL_TRACER_METRIC_EXPORTER_NAME_SETTING.get(settings);
        try {
            return MetricExporterType.createMetricExporter(metricExporterName, settings);
        } catch (PrivilegedActionException ex) {
            throw new IllegalStateException("MetricExporter creation failed", ex.getCause());
        }
    }

    /**
     * Creates a PeriodicMetricReader using the provided settings.
     *
     * @param settings The settings to use for PeriodicMetricReader creation.
     * @return A PeriodicMetricReader instance.
     */
    public static MetricReader createPeriodicMetricReader(Settings settings) {
        long interval = OTelTelemetrySettings.OTEL_TRACER_METRIC_READER_INTERVAL_SETTING.get(settings).getSeconds();
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(
            1,
            new PrivilegedThreadFactory(OpenSearchExecutors.daemonThreadFactory(METRIC_READER_THREAD_NAME))
        );
        return PeriodicMetricReader.builder(createMetricExporter(settings))
            .setExecutor(pool)
            .setInterval(interval, TimeUnit.SECONDS)
            .build();
    }

    /**
     * A custom ThreadFactory that executes the thread with a privileged action.
     */
    private static class PrivilegedThreadFactory implements ThreadFactory {
        private final ThreadFactory delegate;

        public PrivilegedThreadFactory(ThreadFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Thread newThread(Runnable r) {
            return delegate.newThread(() -> AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                r.run();
                return null;
            }));
        }
    }
}
