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
import org.opensearch.common.settings.Settings;
import org.opensearch.telemetry.OTelTelemetrySettings;

import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

public class OTelMetricExporterFactory {
    public enum MetricExporterType {
        OTLP_GRPC {
            @Override
            public MetricExporter createExporter(Settings settings) throws PrivilegedActionException {
                String endpoint = "http://localhost:4317";
                return AccessController.doPrivileged((PrivilegedExceptionAction<OtlpGrpcMetricExporter>) () ->
                    OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build()
                );
            }

            @Override
            public String getName() {
                return "otlp_grpc";
            }
        },
        OTLP_HTTP {
            @Override
            public MetricExporter createExporter(Settings settings) throws PrivilegedActionException {
                String endpoint = "http://localhost:4318/v1/metrics";
                return AccessController.doPrivileged((PrivilegedExceptionAction<OtlpHttpMetricExporter>) () ->
                    OtlpHttpMetricExporter.builder().setEndpoint(endpoint).build()
                );
            }

            @Override
            public String getName() {
                return "otlp_http";
            }
        },
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
        public static boolean containsKey(String key) {
            for (MetricExporterType value : MetricExporterType.values()) {
                if (value.getName().equals(key)) {
                    return true;
                }
            }
            return false;
        }

        public static MetricExporter createMetricExporter(String key, Settings settings) throws PrivilegedActionException {
            for (MetricExporterType value : MetricExporterType.values()) {
                if (value.getName().equals(key)) {
                    return value.createExporter(settings);
                }
            }
            return LOGGING.createExporter(settings);
        }
        public abstract MetricExporter createExporter(Settings settings) throws PrivilegedActionException;
        public abstract String getName();
    }

    public static MetricExporter create(Settings settings) {
        String metricExporterName = OTelTelemetrySettings.OTEL_TRACER_METRIC_EXPORTER_NAME_SETTING.get(settings);
        try {
            return MetricExporterType.createMetricExporter(metricExporterName, settings);
        } catch (PrivilegedActionException ex) {
            throw new IllegalStateException("MetricExporter creation failed", ex.getCause());
        }
    }
}

