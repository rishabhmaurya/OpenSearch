/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import org.opensearch.analytics.backend.EngineResultBatch;
import org.opensearch.analytics.backend.EngineResultStream;
import org.opensearch.telemetry.tracing.Span;
import org.opensearch.telemetry.tracing.Tracer;

import java.util.Iterator;

/**
 * Self-contained data-node tracing for a single shard fragment's result stream (T2). Keeping the
 * per-batch instrumentation here — rather than inline in {@code AnalyticsSearchService} — confines
 * the tracing logic to one new file so the hot drain loop in the service stays a thin call. The
 * fragment timeline is tiled with contiguous child spans whose durations add up:
 * <ul>
 *   <li>{@code datafusion.batch.produce} — the native {@code streamNext} pull (scan/filter/aggregate)
 *       that materializes one batch; this is where the real per-batch time lives.</li>
 *   <li>{@code datafusion.batch.send} — pushing that batch to the stream transport.</li>
 * </ul>
 * Per-batch spans are capped at {@link AnalyticsTracing#DEFAULT_MAX_BATCH_SPANS} per fragment; past
 * the cap the loop keeps draining and counting but stops opening spans. All span work is gated on a
 * real tracer ({@code tracingOn}) so the noop path pays nothing.
 *
 * @opensearch.internal
 */
final class FragmentTracingProbe {

    private FragmentTracingProbe() {}

    /** Outcome of draining one fragment stream: row + batch counts the caller tags onto the fragment span. */
    record DrainResult(long rowsProduced, long batchCount, long batchSpansEmitted) {}

    /**
     * Drains {@code stream}, pushing each batch to {@code responseHandler} and bracketing the native
     * produce and the transport send with child spans of {@code fragmentSpan}. Exceptions propagate
     * to the caller unchanged (the caller owns fragment-span error tagging + resource close), so the
     * produce/send {@code finally} blocks here only end their own spans.
     */
    static DrainResult drain(
        Tracer tracer,
        Span fragmentSpan,
        EngineResultStream stream,
        AnalyticsSearchService.StreamingFragmentResponseHandler responseHandler,
        boolean tracingOn
    ) throws Exception {
        Iterator<EngineResultBatch> it = stream.iterator();
        long rowsProduced = 0;
        long batchOrdinal = 0;
        long batchSpansEmitted = 0;
        while (true) {
            boolean cap = tracingOn && batchSpansEmitted < AnalyticsTracing.DEFAULT_MAX_BATCH_SPANS;
            // PRODUCE span brackets hasNext()+next() — the native pull that materializes the batch.
            Span produceSpan = cap ? AnalyticsTracing.startBatchSpan(tracer, fragmentSpan, AnalyticsTracing.SPAN_BATCH_PRODUCE, batchOrdinal) : null;
            long produceStartNanos = System.nanoTime();
            EngineResultBatch batch = null;
            boolean hasNext = false;
            try {
                hasNext = it.hasNext();
                batch = hasNext ? it.next() : null;
            } finally {
                if (produceSpan != null) {
                    produceSpan.addAttribute("batch.produce_nanos", System.nanoTime() - produceStartNanos);
                    if (hasNext && batch != null) {
                        produceSpan.addAttribute("batch.rows", (long) batch.getRowCount());
                        produceSpan.addAttribute("batch.arrow_bytes", arrowBatchBytes(batch));
                    }
                    long peakNow = stream.currentNativePeakBytes();
                    if (peakNow >= 0) {
                        produceSpan.addAttribute("mem.shard_native.peak_bytes_running", peakNow);
                    }
                    produceSpan.endSpan();
                }
            }
            if (hasNext == false) {
                break;
            }
            int batchRows = batch.getRowCount();
            rowsProduced += batchRows;
            // SEND span brackets the transport push only.
            Span sendSpan = cap ? AnalyticsTracing.startBatchSpan(tracer, fragmentSpan, AnalyticsTracing.SPAN_BATCH_SEND, batchOrdinal) : null;
            long sendStartNanos = System.nanoTime();
            try {
                responseHandler.onBatch(batch);
            } finally {
                if (sendSpan != null) {
                    sendSpan.addAttribute("batch.rows", (long) batchRows);
                    sendSpan.addAttribute("batch.send_nanos", System.nanoTime() - sendStartNanos);
                    sendSpan.endSpan();
                }
            }
            if (cap) {
                batchSpansEmitted++;
            }
            batchOrdinal++;
        }
        return new DrainResult(rowsProduced, batchOrdinal, batchSpansEmitted);
    }

    /**
     * Best-effort total allocated size, in bytes, of the Arrow buffers backing a result batch — the
     * Arrow buffer footprint only, not DataFusion's internal/intermediate/spill memory. 0 if no root.
     */
    static long arrowBatchBytes(EngineResultBatch batch) {
        org.apache.arrow.vector.VectorSchemaRoot root = batch.getArrowRoot();
        if (root == null) {
            return 0L;
        }
        long total = 0L;
        for (org.apache.arrow.vector.FieldVector v : root.getFieldVectors()) {
            total += v.getBufferSize();
        }
        return total;
    }

    /**
     * Extracts a single non-negative integral metric from the DataFusion EXPLAIN-ANALYZE metrics JSON
     * (e.g. {@code "peak_mem_used":108200000}) without a JSON parser on this hot path. Matches
     * {@code "<key>":<digits>} with optional whitespace. Returns -1 when the key is absent, the value
     * isn't a plain integer, or the JSON is null — so callers distinguish "not reported" from a real
     * zero. Best-effort; never throws.
     */
    static long extractLongMetric(String metricsJson, String key) {
        if (metricsJson == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(\\d+)")
            .matcher(metricsJson);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException overflow) {
                return -1;
            }
        }
        return -1;
    }
}
