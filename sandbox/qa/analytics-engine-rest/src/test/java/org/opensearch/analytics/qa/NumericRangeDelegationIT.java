/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.qa;

import org.opensearch.client.Request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * End-to-end REST coverage for numeric range delegation through the value-free ("doc-ids only")
 * BKD on the Lucene secondary, gated by {@code analytics.query.value_free_bkd_range_delegation_enabled}.
 *
 * <p>The {@code calcs} dataset has an integer column {@code int0}; the composite index builds a
 * value-free BKD for it on the Lucene secondary (indexing side is always on). These tests assert
 * the load-bearing property: <b>flipping the query-side flag must not change the answer</b> — with
 * the flag ON the range predicate delegates to the BKD (pruning + parquet residual re-check), with
 * it OFF the predicate runs natively on parquet. Both must return identical, correct rows.
 *
 * <p>Run on a cluster with the Rust scan engine (the per-shard funnel that consumes the BKD bitmap):
 * <pre>
 *   ./gradlew :sandbox:qa:analytics-engine-rest:integTest \
 *       --tests "org.opensearch.analytics.qa.NumericRangeDelegationIT" -Dsandbox.enabled=true
 * </pre>
 *
 * <p><b>Known limitation (segment merge).</b> The value-free BKD stores no point values, so it
 * cannot be reconstructed by Mustang's {@code addIndexes}+IndexSort merge (which re-reads per-doc
 * values). The provisioner here flushes but does not force-merge, so the test runs against a
 * single segment. A production index that triggers a background merge of a value-free numeric
 * field would hit the fail-loud guard in {@code Lucene90PointsWriter#merge}; rebuilding the BKD
 * from the parquet primary on merge is the outstanding follow-up.
 */
public class NumericRangeDelegationIT extends AnalyticsRestTestCase {

    private static final Dataset DATASET = new Dataset("calcs", "calcs");
    private static final String FLAG = "analytics.query.value_free_bkd_range_delegation_enabled";

    private static boolean dataProvisioned = false;

    @Override
    protected void onBeforeQuery() throws IOException {
        if (dataProvisioned == false) {
            DatasetProvisioner.provision(client(), DATASET);
            dataProvisioned = true;
        }
    }

    private void setFlag(boolean enabled) throws IOException {
        Request req = new Request("PUT", "/_cluster/settings");
        req.setJsonEntity("{\"persistent\":{\"" + FLAG + "\": " + enabled + "}}");
        client().performRequest(req);
    }

    /** Collect the int0 column of a PPL result as a sorted long list (order-independent compare). */
    private List<Long> int0Values(String ppl) throws IOException {
        Map<String, Object> response = executePpl(ppl);
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) response.get("datarows");
        assertNotNull("Response missing 'datarows' for query: " + ppl, rows);
        List<Long> out = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            out.add(((Number) row.get(0)).longValue());
        }
        out.sort(null);
        return out;
    }

    /**
     * The core property: {@code where int0 > 4} returns the SAME rows whether the value-free BKD
     * delegation flag is ON or OFF. ON exercises the BKD pruning + residual path; OFF the native
     * parquet path. If they ever diverge, the BKD pruning dropped or duplicated a row.
     */
    public void testRangeDelegationFlagParity_greaterThan() throws IOException {
        String ppl = "source=" + DATASET.indexName + " | where int0 > 4 | fields int0";

        setFlag(false);
        List<Long> off = int0Values(ppl);

        setFlag(true);
        List<Long> on = int0Values(ppl);

        assertFalse("query should return some rows for int0 > 4", on.isEmpty());
        assertEquals("BKD-delegated result must equal the native result for: " + ppl, off, on);
    }

    /** Same parity check for a bounded range ({@code >=} and {@code <=}), a two-sided point range. */
    public void testRangeDelegationFlagParity_betweenBounds() throws IOException {
        String ppl = "source=" + DATASET.indexName + " | where int0 >= 4 and int0 <= 8 | fields int0";

        setFlag(false);
        List<Long> off = int0Values(ppl);

        setFlag(true);
        List<Long> on = int0Values(ppl);

        assertEquals("BKD-delegated bounded range must equal native result for: " + ppl, off, on);
    }

    /** A selective high-cutoff range (where the BKD prunes the most) must still be exact. */
    public void testRangeDelegationFlagParity_selectiveHighCutoff() throws IOException {
        String ppl = "source=" + DATASET.indexName + " | where int0 > 10 | fields int0";

        setFlag(false);
        List<Long> off = int0Values(ppl);

        setFlag(true);
        List<Long> on = int0Values(ppl);

        assertEquals("selective high-cutoff range must equal native result for: " + ppl, off, on);
    }

    @Override
    protected boolean preserveClusterUponCompletion() {
        return true;
    }
}
