/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import org.opensearch.test.OpenSearchTestCase;

/**
 * Unit tests for {@link FragmentTracingProbe#extractLongMetric} — the best-effort scalar extractor
 * that promotes a numeric field (e.g. {@code peak_mem_used}) out of the DataFusion metrics JSON onto
 * the {@code datafusion.shard_fragment} span as a first-class, queryable attribute (M2/Q3).
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
}
