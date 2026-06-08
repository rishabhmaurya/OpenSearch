/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import org.opensearch.analytics.exec.stage.StageExecution;
import org.opensearch.analytics.exec.stage.StageStateListener;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link AnalyticsTracing} — the T1 coordinator-spine + per-stage span tree.
 *
 * <p>Drives a real {@link Tracer} (via {@link TracerFactory}) backed by {@link MockTracingTelemetry},
 * captured into a deterministic list so we can assert span names, the parent/child tree, attributes,
 * and the end-exactly-once contract, with zero cluster/Guice scaffolding.
 */
public class AnalyticsTracingTests extends OpenSearchTestCase {

    /** A real Tracer (via TracerFactory) over a capturing TracingTelemetry. */
    private Tracer tracer;
    private TracerFactory tracerFactory;
    private final List<MockSpan> captured = new CopyOnWriteArrayList<>();

    @Override
    public void setUp() throws Exception {
        super.setUp();
        captured.clear();
        Settings settings = Settings.builder()
            .put(TelemetrySettings.TRACER_ENABLED_SETTING.getKey(), true)
            .put(TelemetrySettings.TRACER_FEATURE_ENABLED_SETTING.getKey(), true)
            .build();
        Set<Setting<?>> tracerSettings = new HashSet<>();
        tracerSettings.add(TelemetrySettings.TRACER_ENABLED_SETTING);
        tracerSettings.add(TelemetrySettings.TRACER_FEATURE_ENABLED_SETTING);
        tracerSettings.add(TelemetrySettings.TRACER_SAMPLER_PROBABILITY);
        TelemetrySettings telemetrySettings = new TelemetrySettings(settings, new ClusterSettings(settings, tracerSettings));
        Telemetry telemetry = new CapturingTelemetry(captured);
        tracerFactory = new TracerFactory(telemetrySettings, Optional.of(telemetry), new ThreadContext(Settings.EMPTY));
        tracer = tracerFactory.getTracer();
    }

    @Override
    public void tearDown() throws Exception {
        tracerFactory.close();
        super.tearDown();
    }

    public void testExecutePlanAndStageSpanTree() {
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-trace-1", "ppl");

        // plan child span (synchronous bracket)
        Span planSpan = tracing.startChildSpan(AnalyticsTracing.SPAN_PLAN);
        AnalyticsTracing.endSpan(planSpan);
        tracing.recordPlanningTime(42L);

        // a SHARD_FRAGMENT stage: open on CREATED->RUNNING, end on RUNNING->SUCCEEDED
        FakeStage stage = new FakeStage(7);
        tracing.attachStageSpan(stage, "SHARD_FRAGMENT");
        stage.fire(StageExecution.State.CREATED, StageExecution.State.RUNNING);
        stage.fire(StageExecution.State.RUNNING, StageExecution.State.SUCCEEDED);

        tracing.endExecute(123L);

        MockSpan execute = captured("analytics.execute");
        MockSpan plan = captured("analytics.plan");
        // Stage span is now named by execution type so the UI differentiates without a tag drill.
        MockSpan stageSpan = captured("analytics.stage.shard_fragment");

        // all three spans exist and ended
        assertTrue("execute span must have ended", execute.hasEnded());
        assertTrue("plan span must have ended", plan.hasEnded());
        assertTrue("stage span must have ended", stageSpan.hasEnded());

        // tree: plan and stage parent to execute
        assertSame("plan must be a child of execute", execute, plan.getParentSpan());
        assertSame("stage must be a child of execute", execute, stageSpan.getParentSpan());
        assertNull("execute is the root", execute.getParentSpan());

        // attributes
        assertEquals("q-trace-1", attr(execute, AnalyticsTracing.ATTR_QUERY_ID));
        assertEquals("query text recorded on the execute span", "ppl", attr(execute, "query.text"));
        assertEquals(42L, attr(execute, AnalyticsTracing.ATTR_PLANNING_MS));
        assertEquals(123L, attr(execute, AnalyticsTracing.ATTR_ROW_COUNT));
        assertEquals(7L, ((Number) attr(stageSpan, AnalyticsTracing.ATTR_STAGE_ID)).longValue());
        assertEquals("SHARD_FRAGMENT", attr(stageSpan, AnalyticsTracing.ATTR_STAGE_TYPE));
        assertEquals("SUCCEEDED", attr(stageSpan, "terminal_state"));
    }

    public void testPlanTextFallbackWhenNoQuerySource() {
        // PPL route: front-end passes null querySource, so query.text is never set. The plan-text
        // fallback must populate query.plan_text instead so the trace stays self-identifying.
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-notext", null);
        assertTrue("tracing should be recording with a live tracer", tracing.isRecording());
        tracing.recordQueryPlanText("LogicalAggregate(group=[{0}])\n  TableScan(table=[clickbench])");
        tracing.endExecute(3L);

        MockSpan execute = captured("analytics.execute");
        assertNull("no real query.text when querySource was null", attr(execute, "query.text"));
        assertEquals(
            "LogicalAggregate(group=[{0}])\n  TableScan(table=[clickbench])",
            attr(execute, "query.plan_text")
        );
    }

    public void testRealQueryTextWinsOverPlanTextFallback() {
        // When the front-end DID supply the query text, the plan-text fallback must NOT overwrite it
        // (and must not also set query.plan_text).
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-hastext", "source=clickbench | stats count()");
        tracing.recordQueryPlanText("LogicalAggregate(...)");
        tracing.endExecute(1L);

        MockSpan execute = captured("analytics.execute");
        assertEquals("source=clickbench | stats count()", attr(execute, "query.text"));
        assertNull("plan-text fallback must not fire when real text exists", attr(execute, "query.plan_text"));
    }

    public void testRecordPlanSummaryTagsExecuteSpan() {
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-plan", "source=clickbench | stats count() by UserID");
        tracing.recordPlanSummary("clickbench", 2, "datafusion,lucene");
        tracing.endExecute(5L);

        MockSpan execute = captured("analytics.execute");
        assertEquals("source=clickbench | stats count() by UserID", attr(execute, "query.text"));
        assertEquals("clickbench", attr(execute, "query.indices"));
        assertEquals(2L, ((Number) attr(execute, "plan.stage_count")).longValue());
        assertEquals("datafusion,lucene", attr(execute, "plan.backends"));
    }

    public void testStageSpanEndsOnDirectTerminalWithoutRunning() {
        // Empty/short-circuit stage: CREATED -> SUCCEEDED in one edge (no RUNNING).
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-trace-2", "ppl");
        FakeStage stage = new FakeStage(0);
        tracing.attachStageSpan(stage, "LOCAL_PASSTHROUGH");
        stage.fire(StageExecution.State.CREATED, StageExecution.State.SUCCEEDED);
        tracing.endExecute(0L);

        MockSpan stageSpan = captured("analytics.stage.local_passthrough");
        assertTrue(stageSpan.hasEnded());
        assertSame(captured("analytics.execute"), stageSpan.getParentSpan());
    }

    public void testEndExecuteIsIdempotent() {
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-trace-3", "ppl");
        tracing.endExecute(1L);
        // a second terminal (e.g. a late failure callback) must NOT double-end.
        tracing.endExecuteWithError(new RuntimeException("late"));
        assertEquals("execute span must be created exactly once", 1, countCaptured("analytics.execute"));
        assertEquals("row_count from the first (winning) terminal is preserved", 1L,
            attr(captured("analytics.execute"), AnalyticsTracing.ATTR_ROW_COUNT));
    }

    public void testPlanChildSpanRecordsError() {
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-trace-4", "sql");
        Span planSpan = tracing.startChildSpan(AnalyticsTracing.SPAN_PLAN);
        AnalyticsTracing.endSpanWithError(planSpan, new IllegalStateException("boom"));
        tracing.endExecuteWithError(new IllegalStateException("boom"));
        assertTrue(captured("analytics.plan").hasEnded());
        assertSame(captured("analytics.execute"), captured("analytics.plan").getParentSpan());
    }

    public void testStageSpanNameDerivedFromExecutionType() {
        assertEquals("analytics.stage.shard_fragment", AnalyticsTracing.stageSpanName("SHARD_FRAGMENT"));
        assertEquals("analytics.stage.coordinator_reduce", AnalyticsTracing.stageSpanName("COORDINATOR_REDUCE"));
        // Unknown / blank type falls back to the bare stage name.
        assertEquals(AnalyticsTracing.SPAN_STAGE, AnalyticsTracing.stageSpanName(null));
        assertEquals(AnalyticsTracing.SPAN_STAGE, AnalyticsTracing.stageSpanName("  "));
    }

    public void testReduceStageSpanIsNamedByType() {
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-reduce", "ppl");
        FakeStage stage = new FakeStage(3);
        tracing.attachStageSpan(stage, "COORDINATOR_REDUCE");
        stage.fire(StageExecution.State.CREATED, StageExecution.State.RUNNING);
        stage.fire(StageExecution.State.RUNNING, StageExecution.State.SUCCEEDED);
        tracing.endExecute(1L);

        MockSpan reduce = captured("analytics.stage.coordinator_reduce");
        assertTrue(reduce.hasEnded());
        assertEquals("COORDINATOR_REDUCE", attr(reduce, AnalyticsTracing.ATTR_STAGE_TYPE));
    }

    public void testBatchSpanParentsToGivenSpanWithOrdinal() {
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-batch", "ppl");
        Span parent = tracing.executeSpan();
        Span batch = AnalyticsTracing.startBatchSpan(tracer, parent, AnalyticsTracing.SPAN_BATCH, 5L);
        AnalyticsTracing.endSpan(batch);
        tracing.endExecute(1L);

        MockSpan batchSpan = captured(AnalyticsTracing.SPAN_BATCH);
        assertTrue(batchSpan.hasEnded());
        assertSame("batch span parents to the supplied span", captured("analytics.execute"), batchSpan.getParentSpan());
        assertEquals(5L, ((Number) attr(batchSpan, "batch.ordinal")).longValue());
    }

    public void testReduceFeedSpanParentsToExecute() {
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-feed", "ppl");
        Span feed = tracing.startReduceFeedSpan(2L);
        AnalyticsTracing.endSpan(feed);
        tracing.endExecute(1L);

        MockSpan feedSpan = captured(AnalyticsTracing.SPAN_REDUCE_FEED);
        assertSame(captured("analytics.execute"), feedSpan.getParentSpan());
        assertEquals(2L, ((Number) attr(feedSpan, "batch.ordinal")).longValue());
    }

    public void testReduceProduceAndSendSpansParentToExecute() {
        // The reduce-output breakdown: produce (native FINAL-agg pull) + send (downstream push),
        // siblings of reduce_feed under the execute span.
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-reduce-out", "ppl");
        Span produce = tracing.startReduceProduceSpan(0L);
        AnalyticsTracing.endSpan(produce);
        Span send = tracing.startReduceSendSpan(0L);
        AnalyticsTracing.endSpan(send);
        tracing.recordReduceOutputBatchCount(7L);
        tracing.endExecute(1L);

        MockSpan execute = captured("analytics.execute");
        assertSame(execute, captured(AnalyticsTracing.SPAN_REDUCE_PRODUCE).getParentSpan());
        assertSame(execute, captured(AnalyticsTracing.SPAN_REDUCE_SEND).getParentSpan());
        assertEquals(0L, ((Number) attr(captured(AnalyticsTracing.SPAN_REDUCE_PRODUCE), "batch.ordinal")).longValue());
        assertEquals("reduce output batch count recorded on execute", 7L,
            ((Number) attr(execute, "reduce.output_batch_count")).longValue());
    }

    public void testBatchSpanHelpersAreNullSafe() {
        // null tracer or null parent → null span, no NPE (callers cheaply skip).
        assertNull(AnalyticsTracing.startBatchSpan(null, null, AnalyticsTracing.SPAN_BATCH, 0L));
        assertNull(AnalyticsTracing.startChildOf(null, null, AnalyticsTracing.SPAN_FRAGMENT_SETUP));
        AnalyticsTracing off = AnalyticsTracing.start(null, "q", "ppl");
        assertNull("reduce-feed span is null when tracing is off", off.startReduceFeedSpan(0L));
    }

    public void testStartChildOfParentsAndNamesCorrectly() {
        // The fragment-setup / produce / send phases use startChildOf to nest under a parent span.
        AnalyticsTracing tracing = AnalyticsTracing.start(tracer, "q-child", "ppl");
        Span parent = tracing.executeSpan();
        Span setup = AnalyticsTracing.startChildOf(tracer, parent, AnalyticsTracing.SPAN_FRAGMENT_SETUP);
        AnalyticsTracing.endSpan(setup);
        tracing.endExecute(1L);

        MockSpan setupSpan = captured(AnalyticsTracing.SPAN_FRAGMENT_SETUP);
        assertTrue(setupSpan.hasEnded());
        assertSame(captured("analytics.execute"), setupSpan.getParentSpan());
    }

    public void testNullTracerIsNoOp() {
        // The whole helper must be safe with a null tracer (tracing disabled): no spans, no NPE.
        AnalyticsTracing tracing = AnalyticsTracing.start(null, "q-none", "ppl");
        assertNull(tracing.executeSpan());
        assertFalse("a null tracer is not recording", tracing.isRecording());
        tracing.recordQueryPlanText("LogicalAggregate(...)");  // null-safe, no span
        Span planSpan = tracing.startChildSpan(AnalyticsTracing.SPAN_PLAN);
        assertNull(planSpan);
        AnalyticsTracing.endSpan(planSpan);  // null-safe
        FakeStage stage = new FakeStage(1);
        tracing.attachStageSpan(stage, "SHARD_FRAGMENT");
        stage.fire(StageExecution.State.CREATED, StageExecution.State.RUNNING);  // must not register a listener
        assertEquals("no listener should be attached when tracing is off", 0, stage.listenerCount());
        tracing.recordPlanningTime(1L);
        tracing.endExecute(0L);  // null-safe
        assertEquals(0, captured.size());
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static Object attr(Span span, String key) {
        return ((MockSpan) span).getAttribute(key);
    }

    private MockSpan captured(String name) {
        List<MockSpan> hits = new ArrayList<>();
        for (MockSpan s : captured) {
            if (name.equals(s.getSpanName())) {
                hits.add(s);
            }
        }
        assertEquals("expected exactly one span named " + name + " but got " + hits.size(), 1, hits.size());
        return hits.get(0);
    }

    private int countCaptured(String name) {
        int n = 0;
        for (MockSpan s : captured) {
            if (name.equals(s.getSpanName())) {
                n++;
            }
        }
        return n;
    }

    /** Telemetry whose tracing layer delegates to MockTracingTelemetry and records each created span. */
    private static final class CapturingTelemetry implements Telemetry {
        private final MockTracingTelemetry delegate = new MockTracingTelemetry();
        private final List<MockSpan> sink;

        CapturingTelemetry(List<MockSpan> sink) {
            this.sink = sink;
        }

        @Override
        public TracingTelemetry getTracingTelemetry() {
            return new TracingTelemetry() {
                @Override
                public Span createSpan(SpanCreationContext spanCreationContext, Span parentSpan) {
                    Span span = delegate.createSpan(spanCreationContext, parentSpan);
                    if (span instanceof MockSpan) {
                        sink.add((MockSpan) span);
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
    }

    /** A StageExecution stub that only supports id + state listeners (all this helper touches). */
    private static final class FakeStage implements StageExecution {
        private final int stageId;
        private final List<StageStateListener> listeners = new ArrayList<>();
        private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);

        FakeStage(int stageId) {
            this.stageId = stageId;
        }

        void fire(State from, State to) {
            state.set(to);
            for (StageStateListener l : listeners) {
                l.onStateChange(from, to);
            }
        }

        int listenerCount() {
            return listeners.size();
        }

        @Override
        public int getStageId() {
            return stageId;
        }

        @Override
        public void addStateListener(StageStateListener listener) {
            listeners.add(listener);
        }

        @Override
        public State getState() {
            return state.get();
        }

        // Unused by AnalyticsTracing — fail loudly if the helper ever reaches for them.
        @Override
        public org.opensearch.analytics.exec.stage.StageMetrics getMetrics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Exception getFailure() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(String reason) {
            throw new UnsupportedOperationException();
        }
    }
}
