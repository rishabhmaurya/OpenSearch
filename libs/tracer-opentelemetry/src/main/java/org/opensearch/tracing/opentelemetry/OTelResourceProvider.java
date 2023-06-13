/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.tracing.opentelemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import org.opensearch.tracing.opentelemetry.exporter.FileSpanExporter;
import org.opensearch.tracing.TracerSettings;

import java.util.concurrent.TimeUnit;

/**
 * This class encapsulates all OpenTelemetry related resources
 */
public final class OTelResourceProvider {

    private static final ContextPropagators contextPropagators;
    private static volatile OpenTelemetry OPEN_TELEMETRY;

    public static Meter meter;

    static {
        contextPropagators = ContextPropagators.create(W3CTraceContextPropagator.getInstance());
    }

    public static OpenTelemetry getOrCreateOpenTelemetryInstance(TracerSettings tracerSettings) {
        if (OPEN_TELEMETRY == null) {
            synchronized (OTelResourceProvider.class) {
                if (OPEN_TELEMETRY == null) {
                    Resource resource = Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, "OpenSearch"));
                    SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                        .addSpanProcessor(
                            BatchSpanProcessor.builder(new FileSpanExporter())
                                .setScheduleDelay(tracerSettings.getExporterDelay().getSeconds(), TimeUnit.SECONDS)
                                .setMaxExportBatchSize(tracerSettings.getExporterBatchSize())
                                .setMaxQueueSize(tracerSettings.getExporterMaxQueueSize())
                                .build()
                        )
                        .setResource(resource)
                        .build();
                    OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
                        .setEndpoint("http://localhost:4317") // Replace with the actual endpoint
                        .build();
                    SdkMeterProvider sdkMeterProvider = SdkMeterProvider.builder()
                        .registerMetricReader(PeriodicMetricReader.builder(metricExporter).build())
                        .setResource(resource)
                        .build();

                    OPEN_TELEMETRY = OpenTelemetrySdk.builder()
                        .setTracerProvider(sdkTracerProvider)
                        .setMeterProvider(sdkMeterProvider)
                        .setPropagators(contextPropagators)
                        .buildAndRegisterGlobal();
                    meter = OPEN_TELEMETRY.meterBuilder("opensearch-task")
                        .setInstrumentationVersion("1.0.0")
                        .build();
                }
            }
        }
        return OPEN_TELEMETRY;
    }

    static ContextPropagators getContextPropagators() {
        return contextPropagators;
    }
}
