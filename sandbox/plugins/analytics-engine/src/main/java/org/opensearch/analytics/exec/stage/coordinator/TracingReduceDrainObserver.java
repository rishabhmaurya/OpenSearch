/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec.stage.coordinator;

import org.opensearch.analytics.exec.QueryContext;
import org.opensearch.analytics.spi.ReduceDrainObserver;
import org.opensearch.telemetry.tracing.Span;

/**
 * Engine-side {@link ReduceDrainObserver} that turns the datafusion reduce sink's opaque
 * produce/send callbacks into {@code datafusion.reduce.produce} / {@code datafusion.reduce.send}
 * spans (children of the execute span, mirroring the data-node per-batch split). The backend stays
 * tracing-agnostic: it only sees the {@link ReduceDrainObserver} SPI and treats the returned tokens
 * as opaque {@link Object}s.
 *
 * <p>Owns the per-stage span CAP ({@link QueryContext#MAX_BATCH_SPANS}) so a high-cardinality reduce
 * that emits many output batches can't blow up the trace; past the cap it stops opening spans but
 * keeps counting, and the total is reported via {@link #onDrainComplete}. All methods are cheap and
 * never throw (the reduce drain runs on the REDUCE pool).
 *
 * @opensearch.internal
 */
final class TracingReduceDrainObserver implements ReduceDrainObserver {

    private final QueryContext config;
    private long spansEmitted;

    TracingReduceDrainObserver(QueryContext config) {
        this.config = config;
    }

    @Override
    public Object onProduceStart(long ordinal) {
        return spansEmitted < QueryContext.MAX_BATCH_SPANS ? config.startReduceProduceSpan(ordinal) : null;
    }

    @Override
    public void onProduceEnd(Object token, long rows, long arrowBytes, long produceNanos, Throwable error) {
        if (token instanceof Span span) {
            span.addAttribute("batch.rows", rows);
            span.addAttribute("batch.arrow_bytes", arrowBytes);
            span.addAttribute("batch.produce_nanos", produceNanos);
            if (error != null) {
                span.setError(error instanceof Exception e ? e : new RuntimeException(error));
            }
            span.endSpan();
            spansEmitted++;
        }
    }

    @Override
    public Object onSendStart(long ordinal) {
        return spansEmitted < QueryContext.MAX_BATCH_SPANS ? config.startReduceSendSpan(ordinal) : null;
    }

    @Override
    public void onSendEnd(Object token, long rows, long sendNanos, Throwable error) {
        if (token instanceof Span span) {
            span.addAttribute("batch.rows", rows);
            span.addAttribute("batch.send_nanos", sendNanos);
            if (error != null) {
                span.setError(error instanceof Exception e ? e : new RuntimeException(error));
            }
            span.endSpan();
        }
    }

    @Override
    public void onDrainComplete(long totalBatches) {
        // Record on the execute span so the count survives even when most batches were capped.
        // (The coordinator_reduce stage span is opened lazily and not reachable here; the execute
        // span is the established parent for the reduce produce/send/feed spans.)
        config.recordReduceOutputBatchCount(totalBatches);
    }
}
