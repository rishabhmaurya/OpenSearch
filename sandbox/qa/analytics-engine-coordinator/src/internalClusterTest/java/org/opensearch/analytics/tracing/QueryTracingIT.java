/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.tracing;

import org.opensearch.Version;
import org.opensearch.analytics.AnalyticsPlugin;
import org.opensearch.analytics.exec.DefaultPlanExecutor;
import org.opensearch.analytics.sql.SqlPlanRunner;
import org.opensearch.arrow.allocator.ArrowBasePlugin;
import org.opensearch.arrow.flight.transport.FlightStreamPlugin;
import org.opensearch.be.datafusion.DataFusionPlugin;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.composite.CompositeDataFormatPlugin;
import org.opensearch.index.engine.dataformat.stub.MockCommitterEnginePlugin;
import org.opensearch.parquet.ParquetOnlyDataFormatPlugin;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.plugins.TelemetryPlugin;
import org.opensearch.telemetry.Telemetry;
import org.opensearch.telemetry.TelemetrySettings;
import org.opensearch.telemetry.metrics.MetricsTelemetry;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.SpanCreationContext;
import org.opensearch.telemetry.tracing.TracingContextPropagator;
import org.opensearch.telemetry.tracing.TracingTelemetry;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.telemetry.tracing.MockSpan;
import org.opensearch.test.telemetry.tracing.MockTracingTelemetry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * End-to-end tracing IT: runs a real {@code stats ... by <field>} query through the full
 * coordinator → shard-fragment → reduce path and asserts the {@code analytics.execute} →
 * {@code analytics.plan} / {@code analytics.stage} span tree is actually emitted (not just at
 * the unit-helper level).
 *
 * <p>Uses a single data node with a 2-shard index so the span capture list stays process-local
 * and the distributed aggregate split produces a SHARD_FRAGMENT + COORDINATOR_REDUCE DAG (i.e.
 * multiple {@code analytics.stage} spans). The default {@link org.opensearch.test.telemetry.MockTelemetryPlugin}
 * is disabled in favour of a local capturing telemetry plugin whose spans we can read back by name.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 1, numClientNodes = 0, supportsDedicatedMasters = false)
public class QueryTracingIT extends OpenSearchIntegTestCase {

    private static final String INDEX = "tracing_idx";

    /** Process-local capture of every span the test cluster's tracer creates. */
    public static final List<MockSpan> CAPTURED = new CopyOnWriteArrayList<>();

    @Override
    protected boolean addMockTelemetryPlugin() {
        return false; // we register our own capturing telemetry plugin instead
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(
            ArrowBasePlugin.class,
            CompositeDataFormatPlugin.class,
            MockCommitterEnginePlugin.class,
            CapturingTelemetryPlugin.class
        );
    }

    @Override
    protected Collection<PluginInfo> additionalNodePlugins() {
        return List.of(
            classpathPlugin(FlightStreamPlugin.class, List.of(ArrowBasePlugin.class.getName())),
            classpathPlugin(AnalyticsPlugin.class, Collections.emptyList()),
            classpathPlugin(ParquetOnlyDataFormatPlugin.class, Collections.emptyList()),
            classpathPlugin(DataFusionPlugin.class, List.of(AnalyticsPlugin.class.getName()))
        );
    }

    private static PluginInfo classpathPlugin(Class<? extends Plugin> pluginClass, List<String> extendedPlugins) {
        return new PluginInfo(
            pluginClass.getName(),
            "classpath plugin",
            "NA",
            Version.CURRENT,
            "1.8",
            pluginClass.getName(),
            null,
            extendedPlugins,
            false
        );
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put(FeatureFlags.PLUGGABLE_DATAFORMAT_EXPERIMENTAL_FLAG, true)
            .put(FeatureFlags.STREAM_TRANSPORT, true)
            .put(TelemetrySettings.TRACER_FEATURE_ENABLED_SETTING.getKey(), true)
            .put(TelemetrySettings.TRACER_ENABLED_SETTING.getKey(), true)
            .put(TelemetrySettings.TRACER_SAMPLER_PROBABILITY.getKey(), 1.0d)
            .build();
    }

    public void testStatsByGroupEmitsExecutePlanAndStageSpans() throws Exception {
        CAPTURED.clear();
        createAndSeedIndex(2);

        // `SELECT g, count(*) FROM idx GROUP BY g` — distributed aggregate split => SHARD_FRAGMENT
        // partials on 2 shards + a COORDINATOR_REDUCE final, so >= 2 analytics.stage spans.
        List<Object[]> rows = sqlPlanRunner().executeSql("SELECT g, count(*) FROM " + INDEX + " GROUP BY g");
        assertFalse("query should return grouped rows", rows.isEmpty());

        // Spans flush synchronously (MockSpan.endSpan -> processor.onEnd inline). Assert the tree.
        Span execute = single("analytics.execute");
        assertTrue("execute span must have ended", ((MockSpan) execute).hasEnded());
        assertEquals("ppl/sql language tag present", true, ((MockSpan) execute).getAttributes().containsKey("query_id"));

        // M1/M2: per-query memory attributes are present on the execute span and non-negative.
        // We assert the PLUMBING (attribute present, well-formed, >= 0), not a positive value:
        // a tiny query (12 rows / 3 groups) legitimately reserves ~0 bytes in the coordinator
        // reduce pool, so native peak can be 0 here. The end-to-end value is exercised by the
        // larger ClickBench-scale runs; here we prove the wiring reaches the span.
        Object nativePeak = ((MockSpan) execute).getAttribute("mem.query_native.peak_bytes");
        Object arrowPeak = ((MockSpan) execute).getAttribute("mem.query_arrow.peak_bytes");
        assertNotNull("execute span carries mem.query_native.peak_bytes", nativePeak);
        assertNotNull("execute span carries mem.query_arrow.peak_bytes", arrowPeak);
        assertTrue("native peak must be >= 0 (plumbing present)", ((Number) nativePeak).longValue() >= 0L);
        assertTrue("arrow peak must be >= 0", ((Number) arrowPeak).longValue() >= 0L);

        Span plan = single("analytics.plan");
        assertSame("plan must be a child of execute", execute, plan.getParentSpan());
        assertTrue(((MockSpan) plan).hasEnded());

        // Stage spans are now NAMED by execution type (analytics.stage.shard_fragment /
        // analytics.stage.coordinator_reduce) so the UI differentiates by name, not a tag drill.
        List<MockSpan> stages = byNamePrefix("analytics.stage");
        assertTrue("expected at least 2 stage spans (shard fragment + coordinator reduce), got " + stages.size(), stages.size() >= 2);
        for (MockSpan stage : stages) {
            assertSame("every stage span parents to execute", execute, stage.getParentSpan());
            assertTrue("stage span must have ended", stage.hasEnded());
            assertNotNull("stage span carries stage_id", stage.getAttribute("stage_id"));
            assertNotNull("stage span carries execution_type", stage.getAttribute("execution_type"));
        }
        // The execution type must be reflected in the span NAME (lowercased), not just the attribute.
        assertTrue(
            "expected a span named analytics.stage.shard_fragment",
            stages.stream().anyMatch(s -> "analytics.stage.shard_fragment".equals(s.getSpanName()))
        );
        assertTrue(
            "expected a span named analytics.stage.coordinator_reduce",
            stages.stream().anyMatch(s -> "analytics.stage.coordinator_reduce".equals(s.getSpanName()))
        );
        // T2 reduce-side: per inbound-batch reduce-feed spans, children of execute, tagged with the
        // source shard ordinal. The 2-shard grouped query feeds >=1 batch into the reduce.
        List<MockSpan> reduceFeeds = byName("analytics.reduce_feed");
        assertFalse("expected at least one analytics.reduce_feed span", reduceFeeds.isEmpty());
        for (MockSpan feed : reduceFeeds) {
            assertTrue("reduce_feed span must have ended", feed.hasEnded());
            assertSame("reduce_feed parents to execute", execute, feed.getParentSpan());
            assertNotNull("reduce_feed carries source.ordinal", feed.getAttribute("source.ordinal"));
            assertNotNull("reduce_feed carries batch.rows", feed.getAttribute("batch.rows"));
            assertNotNull("reduce_feed carries batch.feed_nanos (handoff time, not reduce-compute)", feed.getAttribute("batch.feed_nanos"));
        }

        // T2 reduce OUTPUT breakdown: the native FINAL-aggregation produce + downstream send,
        // children of execute (siblings of reduce_feed). The grouped query produces >=1 reduced batch.
        List<MockSpan> reduceProduces = byName("datafusion.reduce.produce");
        assertFalse("expected at least one datafusion.reduce.produce span", reduceProduces.isEmpty());
        for (MockSpan rp : reduceProduces) {
            assertTrue("reduce.produce span must have ended", rp.hasEnded());
            assertSame("reduce.produce parents to execute", execute, rp.getParentSpan());
            assertNotNull("reduce.produce carries batch.produce_nanos", rp.getAttribute("batch.produce_nanos"));
        }
        List<MockSpan> reduceSends = byName("datafusion.reduce.send");
        assertFalse("expected at least one datafusion.reduce.send span", reduceSends.isEmpty());
        for (MockSpan rs : reduceSends) {
            assertTrue("reduce.send span must have ended", rs.hasEnded());
            assertSame("reduce.send parents to execute", execute, rs.getParentSpan());
            assertNotNull("reduce.send carries batch.send_nanos", rs.getAttribute("batch.send_nanos"));
        }
        // The reduce records how many output batches it produced (on the execute span).
        assertNotNull("execute carries reduce.output_batch_count", ((MockSpan) execute).getAttribute("reduce.output_batch_count"));

        // T2: data-node fragment spans — one per shard, carrying execution facts.
        List<MockSpan> fragments = byName("datafusion.shard_fragment");
        assertTrue("expected at least 2 datafusion.shard_fragment spans (one per shard), got " + fragments.size(), fragments.size() >= 2);
        for (MockSpan frag : fragments) {
            assertTrue("fragment span must have ended", frag.hasEnded());
            assertNotNull("fragment carries shard_id", frag.getAttribute("shard_id"));
            assertNotNull("fragment carries rows_produced", frag.getAttribute("rows_produced"));
            assertNotNull("fragment carries took_nanos", frag.getAttribute("took_nanos"));
            assertEquals("fragment query_id matches", ((MockSpan) execute).getAttribute("query_id"), frag.getAttribute("query_id"));
            // The fragment records how many batches it flushed and how many got their own span.
            assertNotNull("fragment carries batch.count_total", frag.getAttribute("batch.count_total"));
            assertNotNull("fragment carries batch.span_count", frag.getAttribute("batch.span_count"));
        }

        // T2 fragment SETUP: reader pin + native session build + plan compile (time-to-first-batch),
        // child of a shard fragment, carrying setup_nanos. One per shard.
        List<MockSpan> setups = byName("datafusion.shard_fragment.setup");
        assertFalse("expected at least one datafusion.shard_fragment.setup span", setups.isEmpty());
        for (MockSpan s : setups) {
            assertTrue("setup span must have ended", s.hasEnded());
            assertNotNull("setup carries setup_nanos", s.getAttribute("setup_nanos"));
            assertTrue("setup parents to a shard fragment", fragments.contains((MockSpan) s.getParentSpan()));
        }

        // T2 per-batch PRODUCE: brackets the native pull (scan/filter/agg) materializing each batch,
        // carrying batch.produce_nanos. >=1 produce span per shard (the terminal one signals end-of-stream).
        List<MockSpan> produces = byName("datafusion.batch.produce");
        assertFalse("expected at least one datafusion.batch.produce span", produces.isEmpty());
        for (MockSpan p : produces) {
            assertTrue("produce span must have ended", p.hasEnded());
            assertNotNull("produce carries batch.ordinal", p.getAttribute("batch.ordinal"));
            assertNotNull("produce carries batch.produce_nanos", p.getAttribute("batch.produce_nanos"));
            assertTrue("produce parents to a shard fragment", fragments.contains((MockSpan) p.getParentSpan()));
        }

        // T2 per-batch SEND: brackets the transport push, carrying batch.send_nanos + rows.
        List<MockSpan> sends = byName("datafusion.batch.send");
        assertFalse("expected at least one datafusion.batch.send span", sends.isEmpty());
        for (MockSpan b : sends) {
            assertTrue("send span must have ended", b.hasEnded());
            assertNotNull("send carries batch.ordinal", b.getAttribute("batch.ordinal"));
            assertNotNull("send carries batch.rows", b.getAttribute("batch.rows"));
            assertNotNull("send carries batch.send_nanos", b.getAttribute("batch.send_nanos"));
            assertTrue("send parents to a shard fragment", fragments.contains((MockSpan) b.getParentSpan()));
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private SqlPlanRunner sqlPlanRunner() {
        String node = internalCluster().getNodeNames()[0];
        ClusterService clusterService = internalCluster().getInstance(ClusterService.class, node);
        DefaultPlanExecutor executor = internalCluster().getInstance(DefaultPlanExecutor.class, node);
        return new SqlPlanRunner(clusterService, executor);
    }

    private void createAndSeedIndex(int shardCount) {
        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, shardCount)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put("index.pluggable.dataformat.enabled", true)
            .put("index.pluggable.dataformat", "composite")
            .put("index.composite.primary_data_format", "parquet")
            .putList("index.composite.secondary_data_formats")
            .build();
        assertTrue(
            "index creation must be acknowledged",
            client().admin().indices().prepareCreate(INDEX).setSettings(indexSettings).setMapping("g", "type=integer").get().isAcknowledged()
        );
        ensureGreen(INDEX);
        for (int i = 0; i < 12; i++) {
            client().prepareIndex(INDEX).setSource("g", i % 3).get();
        }
        client().admin().indices().prepareRefresh(INDEX).get();
        client().admin().indices().prepareFlush(INDEX).get();
    }

    private Span single(String name) {
        List<MockSpan> hits = byName(name);
        assertEquals("expected exactly one span named " + name + ", got " + hits.size(), 1, hits.size());
        return hits.get(0);
    }

    private List<MockSpan> byName(String name) {
        List<MockSpan> hits = new ArrayList<>();
        for (MockSpan s : CAPTURED) {
            if (name.equals(s.getSpanName())) {
                hits.add(s);
            }
        }
        return hits;
    }

    private List<MockSpan> byNamePrefix(String prefix) {
        List<MockSpan> hits = new ArrayList<>();
        for (MockSpan s : CAPTURED) {
            if (s.getSpanName() != null && s.getSpanName().startsWith(prefix)) {
                hits.add(s);
            }
        }
        return hits;
    }

    /** Telemetry plugin whose tracing layer delegates to MockTracingTelemetry and records spans into {@link #CAPTURED}. */
    public static final class CapturingTelemetryPlugin extends Plugin implements TelemetryPlugin {
        @Override
        public Optional<Telemetry> getTelemetry(TelemetrySettings settings) {
            return Optional.of(new Telemetry() {
                private final MockTracingTelemetry delegate = new MockTracingTelemetry();

                @Override
                public TracingTelemetry getTracingTelemetry() {
                    return new TracingTelemetry() {
                        @Override
                        public Span createSpan(SpanCreationContext spanCreationContext, Span parentSpan) {
                            Span span = delegate.createSpan(spanCreationContext, parentSpan);
                            if (span instanceof MockSpan) {
                                CAPTURED.add((MockSpan) span);
                            }
                            return span;
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
            });
        }

        @Override
        public String getName() {
            return "capturing-mock";
        }
    }
}
