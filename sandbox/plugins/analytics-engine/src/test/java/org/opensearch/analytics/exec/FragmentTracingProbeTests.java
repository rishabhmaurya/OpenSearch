/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.telemetry.Telemetry;
import org.opensearch.telemetry.TelemetrySettings;
import org.opensearch.telemetry.metrics.MetricsTelemetry;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanCreationContext;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.telemetry.tracing.TracerFactory;
import org.opensearch.telemetry.tracing.TracingContextPropagator;
import org.opensearch.telemetry.tracing.TracingTelemetry;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.telemetry.tracing.MockSpan;
import org.opensearch.test.telemetry.tracing.MockTracingTelemetry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unit tests for {@link FragmentTracingProbe#extractLongMetric} — the best-effort scalar extractor
 * that promotes a numeric field (e.g. {@code peak_mem_used}) out of the DataFusion metrics JSON onto
 * the {@code datafusion.shard_fragment} span as a first-class, queryable attribute (M2/Q3) — and for
 * {@link FragmentTracingProbe#synthesizeOperatorSpans} — the per-operator child spans (Q4 drill-down).
 */
public class FragmentTracingProbeTests extends OpenSearchTestCase {

    public void testExtractsPeakMemUsedFromRealMetricsBlob() {
        String json = "{\"output_rows\":6851620,\"peak_mem_used\":108200000,\"spill_count\":0}";
        assertEquals(108200000L, FragmentTracingProbe.extractLongMetric(json, "peak_mem_used"));
    }

    public void testToleratesWhitespaceAroundColon() {
        assertEquals(42L, FragmentTracingProbe.extractLongMetric("{ \"peak_mem_used\" :  42 }", "peak_mem_used"));
    }

    public void testRealZeroIsDistinctFromMissing() {
        // A genuine 0 must be returned as 0 (a fragment that reserved no native memory)...
        assertEquals(0L, FragmentTracingProbe.extractLongMetric("{\"peak_mem_used\":0}", "peak_mem_used"));
        // ...whereas an absent key returns -1 so the caller can skip tagging.
        assertEquals(-1L, FragmentTracingProbe.extractLongMetric("{\"output_rows\":5}", "peak_mem_used"));
    }

    public void testNullJsonAndNonNumericValueReturnMinusOne() {
        assertEquals(-1L, FragmentTracingProbe.extractLongMetric(null, "peak_mem_used"));
        assertEquals(-1L, FragmentTracingProbe.extractLongMetric("{\"peak_mem_used\":\"NaN\"}", "peak_mem_used"));
    }

    public void testDoesNotMatchKeyAsSubstringOfAnotherKey() {
        // "max_peak_mem_used" must NOT satisfy a lookup for "peak_mem_used" (leading quote anchors the key).
        String json = "{\"max_peak_mem_used\":999}";
        assertEquals(-1L, FragmentTracingProbe.extractLongMetric(json, "peak_mem_used"));
    }

    // ─── per-operator child spans (synthesizeOperatorSpans) ──────────────

    /** A real Tracer over a capturing telemetry, mirroring AnalyticsTracingTests. */
    private Tracer newCapturingTracer(List<MockSpan> sink) {
        Settings settings = Settings.builder()
            .put(TelemetrySettings.TRACER_ENABLED_SETTING.getKey(), true)
            .put(TelemetrySettings.TRACER_FEATURE_ENABLED_SETTING.getKey(), true)
            .build();
        Set<Setting<?>> ts = new HashSet<>();
        ts.add(TelemetrySettings.TRACER_ENABLED_SETTING);
        ts.add(TelemetrySettings.TRACER_FEATURE_ENABLED_SETTING);
        ts.add(TelemetrySettings.TRACER_SAMPLER_PROBABILITY);
        TelemetrySettings telemetrySettings = new TelemetrySettings(settings, new ClusterSettings(settings, ts));
        Telemetry telemetry = new Telemetry() {
            private final MockTracingTelemetry delegate = new MockTracingTelemetry();

            @Override
            public TracingTelemetry getTracingTelemetry() {
                return new TracingTelemetry() {
                    @Override
                    public Span createSpan(SpanCreationContext ctx, Span parent) {
                        Span s = delegate.createSpan(ctx, parent);
                        if (s instanceof MockSpan ms) sink.add(ms);
                        return s;
                    }

                    @Override
                    public TracingContextPropagator getContextPropagator() {
                        return delegate.getContextPropagator();
                    }

                    @Override
                    public void close() {
                        delegate.close();
                    }
                };
            }

            @Override
            public MetricsTelemetry getMetricsTelemetry() {
                return null;
            }
        };
        return new TracerFactory(telemetrySettings, Optional.of(telemetry), new ThreadContext(Settings.EMPTY)).getTracer();
    }

    private List<MockSpan> spansNamed(List<MockSpan> all, String name) {
        List<MockSpan> hits = new ArrayList<>();
        for (MockSpan s : all) {
            if (name.equals(s.getSpanName())) hits.add(s);
        }
        return hits;
    }

    public void testSynthesizeOperatorSpansFromArray() {
        List<MockSpan> captured = new CopyOnWriteArrayList<>();
        Tracer tracer = newCapturingTracer(captured);
        Span fragment = tracer.startSpan(SpanCreationContext.server().name("datafusion.shard_fragment"));

        // A FilterExec doing real CPU work (work_ns=elapsed_compute), a scan whose work lives in
        // scan_processing_ns (elapsed_compute ~0), and a near-idle root merge.
        String json = "{\"peak_mem_used\":123,\"operators\":["
            + "{\"operator\":\"SortPreservingMergeExec\",\"node_id\":0,\"elapsed_compute_ns\":100000,"
            + "\"work_ns\":100000,\"rows\":10,\"spill_count\":0,\"spilled_bytes\":0,"
            + "\"start_unix_nanos\":1000000000,\"end_unix_nanos\":25000000000},"   // huge lifetime, tiny work
            + "{\"operator\":\"FilterExec\",\"node_id\":1,\"parent_id\":0,\"elapsed_compute_ns\":5360000000,"
            + "\"work_ns\":5360000000,\"rows\":5371678,\"spill_count\":0,\"spilled_bytes\":0},"
            + "{\"operator\":\"QueryShardExec\",\"node_id\":2,\"parent_id\":1,\"elapsed_compute_ns\":8,"
            + "\"work_ns\":2900000000,\"scan_processing_ns\":2900000000,\"scan_total_ns\":2886000000,"
            + "\"rows\":36533892,\"spill_count\":0,\"spilled_bytes\":0}"
            + "]}";

        int n = FragmentTracingProbe.synthesizeOperatorSpans(tracer, fragment, json, 0L, 25000000000L);
        assertEquals("three operator spans emitted", 3, n);

        // The root merge's bar WIDTH tracks work_ns (~0.1ms), NOT its 24s pull lifetime.
        MockSpan merge = single(spansNamed(captured, "datafusion.op.SortPreservingMergeExec"));
        assertTrue(merge.hasEnded());
        assertSame("operator span parents to the fragment span", fragment, merge.getParentSpan());
        assertEquals(100000L, ((Number) merge.getAttribute("operator.work_ns")).longValue());
        assertEquals("work_ns", merge.getAttribute("timing.bar"));
        // pull lifetime preserved as an attribute (24s) even though the bar is tiny.
        assertEquals(24000000000L, ((Number) merge.getAttribute("operator.wall_duration_ns")).longValue());

        // The scan's work bar reflects scan_processing_ns (~2.9s), NOT its 8ns elapsed_compute.
        MockSpan scan = single(spansNamed(captured, "datafusion.op.QueryShardExec"));
        assertEquals(2900000000L, ((Number) scan.getAttribute("operator.work_ns")).longValue());
        assertEquals(2900000000L, ((Number) scan.getAttribute("operator.scan_processing_ns")).longValue());
        assertEquals(8L, ((Number) scan.getAttribute("operator.elapsed_compute_ns")).longValue());
    }

    public void testLuceneDelegationSpanEmittedWhenDelegated() {
        List<MockSpan> captured = new CopyOnWriteArrayList<>();
        Tracer tracer = newCapturingTracer(captured);
        Span fragment = tracer.startSpan(SpanCreationContext.server().name("datafusion.shard_fragment"));
        FragmentTracingProbe.emitLuceneDelegationSpan(
            tracer, fragment, 14_000_000L /*cpu*/, 64 /*calls*/, 1 /*predicates*/, "CONJUNCTIVE", 0L, 20_000_000L
        );
        MockSpan luc = single(spansNamed(captured, "lucene.delegation"));
        assertTrue(luc.hasEnded());
        assertSame(fragment, luc.getParentSpan());
        assertEquals("lucene", luc.getAttribute("engine"));
        assertEquals(14_000_000L, ((Number) luc.getAttribute("lucene.cpu_nanos")).longValue());
        assertEquals(64L, ((Number) luc.getAttribute("lucene.collector_calls")).longValue());
        assertEquals("CONJUNCTIVE", luc.getAttribute("lucene.tree_shape"));
    }

    public void testLuceneDelegationSpanNoOpWhenNotDelegated() {
        List<MockSpan> captured = new CopyOnWriteArrayList<>();
        Tracer tracer = newCapturingTracer(captured);
        Span fragment = tracer.startSpan(SpanCreationContext.server().name("datafusion.shard_fragment"));
        // cpu=0 -> datafusion_only, no lucene span.
        FragmentTracingProbe.emitLuceneDelegationSpan(tracer, fragment, 0L, 0, 0, null, 0L, 100L);
        assertTrue("no lucene.delegation span when delegation didn't fire", spansNamed(captured, "lucene.delegation").isEmpty());
    }

    public void testSynthesizeIsNoOpWithoutOperatorsArray() {
        List<MockSpan> captured = new CopyOnWriteArrayList<>();
        Tracer tracer = newCapturingTracer(captured);
        Span fragment = tracer.startSpan(SpanCreationContext.server().name("datafusion.shard_fragment"));
        // Only flat keys, no operators[] — returns 0, emits no operator spans.
        int n = FragmentTracingProbe.synthesizeOperatorSpans(tracer, fragment, "{\"peak_mem_used\":5}", 0L, 100L);
        assertEquals(0, n);
        assertTrue("no datafusion.op.* spans", spansNamed(captured, "datafusion.op.SortExec").isEmpty());
    }

    public void testSynthesizeNullSafeAndCapped() {
        // null json -> 0, no throw.
        assertEquals(0, FragmentTracingProbe.synthesizeOperatorSpans(null, null, null, 0L, 1L));
        // cap: build more operators than MAX_OPERATOR_SPANS, confirm we stop at the cap.
        List<MockSpan> captured = new CopyOnWriteArrayList<>();
        Tracer tracer = newCapturingTracer(captured);
        Span fragment = tracer.startSpan(SpanCreationContext.server().name("datafusion.shard_fragment"));
        StringBuilder sb = new StringBuilder("{\"operators\":[");
        int total = FragmentTracingProbe.MAX_OPERATOR_SPANS + 20;
        for (int i = 0; i < total; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"operator\":\"Op").append(i).append("\",\"node_id\":").append(i).append(",\"elapsed_compute_ns\":1000}");
        }
        sb.append("]}");
        int n = FragmentTracingProbe.synthesizeOperatorSpans(tracer, fragment, sb.toString(), 0L, 1000000000L);
        assertEquals("emission capped", FragmentTracingProbe.MAX_OPERATOR_SPANS, n);
    }

    private static MockSpan single(List<MockSpan> hits) {
        assertEquals("expected exactly one matching span, got " + hits.size(), 1, hits.size());
        return hits.get(0);
    }
}
