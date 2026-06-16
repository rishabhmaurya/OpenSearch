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
 * Manual-index, decent-data validation for the value-free ("doc-ids only") BKD numeric range
 * delegation. The {@code bkdnum} dataset has 5000 docs with a {@code long event_value} (0..4999,
 * opted in via {@code "bkd_secondary_prune": true}) and a {@code long plain_value} (no opt-in).
 *
 * <p>The load-bearing property: flipping
 * {@code analytics.query.value_free_bkd_range_delegation_enabled} must NOT change the answer.
 * Flag ON delegates {@code event_value > N} to the value-free BKD (sub-page prune + parquet
 * residual re-check); flag OFF runs it natively on parquet. Both must return identical, correct
 * rows — across broad, selective, boundary, and empty ranges where pruning impact varies.
 */
public class ValueFreeBKDPruningIT extends AnalyticsRestTestCase {

    private static final Dataset DATASET = new Dataset("bkdnum", "bkdnum");
    private static final String FLAG = "analytics.query.value_free_bkd_range_delegation_enabled";
    private static boolean provisioned = false;

    @Override
    protected void onBeforeQuery() throws IOException {
        if (provisioned == false) {
            DatasetProvisioner.provision(client(), DATASET);
            provisioned = true;
        }
    }

    private void setFlag(boolean enabled) throws IOException {
        Request req = new Request("PUT", "/_cluster/settings");
        req.setJsonEntity("{\"persistent\":{\"" + FLAG + "\": " + enabled + "}}");
        client().performRequest(req);
    }

    private long countWhere(String predicate) throws IOException {
        // Count via row fetch (the delegated scan/filter path) rather than stats count() so we
        // exercise the BKD-pruned scan directly. The id field is keyword (1 per doc).
        Map<String, Object> r = executePpl(
            "source=" + DATASET.indexName + " | where " + predicate + " | fields id"
        );
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) r.get("datarows");
        assertNotNull("no datarows for: " + predicate, rows);
        return rows.size();
    }

    private List<Long> valuesWhere(String predicate, String field) throws IOException {
        Map<String, Object> r = executePpl(
            "source=" + DATASET.indexName + " | where " + predicate + " | fields " + field + " | sort " + field
        );
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) r.get("datarows");
        assertNotNull("no datarows for: " + predicate, rows);
        List<Long> out = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            out.add(((Number) row.get(0)).longValue());
        }
        return out;
    }

    /** Flag ON vs OFF must agree on the count for a range, across selectivities. */
    public void testCountParityAcrossSelectivities() throws IOException {
        String[] predicates = {
            "event_value > 4900",   // very selective (99 rows) — pruning should bite hard
            "event_value > 4999",   // empty
            "event_value < 100",    // selective low end (100 rows)
            "event_value >= 1000 and event_value <= 1009", // tight band (10 rows)
            "event_value > 4000 and event_value <= 4500"   // mid band (500 rows)
        };
        for (String p : predicates) {
            setFlag(false);
            long off = countWhere(p);
            setFlag(true);
            long on = countWhere(p);
            assertEquals("count parity (ON==OFF) for: " + p, off, on);
        }
    }

    /** The selective range returns exactly the expected rows with the flag ON (delegated path). */
    public void testSelectiveRangeExactRowsDelegated() throws IOException {
        setFlag(true);
        List<Long> vals = valuesWhere("event_value > 4990", "event_value");
        // event_value is 0..4999 monotone, so > 4990 => 4991..4999 = 9 rows.
        List<Long> expected = new ArrayList<>();
        for (long v = 4991; v <= 4999; v++) {
            expected.add(v);
        }
        assertEquals("delegated selective range must be exact", expected, vals);
    }

    /** A non-opted-in field (plain_value) must also return correct results with the flag ON. */
    public void testNonOptedInFieldParity() throws IOException {
        String p = "plain_value > 4990"; // selective; non-opted-in field must stay native + correct
        setFlag(false);
        long off = countWhere(p);
        setFlag(true);
        long on = countWhere(p);
        assertEquals("non-opted-in field must be correct regardless of flag", off, on);
    }

    @Override
    protected boolean preserveClusterUponCompletion() {
        return true;
    }
}
