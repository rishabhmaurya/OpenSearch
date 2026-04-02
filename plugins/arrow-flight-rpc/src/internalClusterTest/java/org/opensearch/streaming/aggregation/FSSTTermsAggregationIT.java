/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.streaming.aggregation;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.opensearch.action.admin.indices.flush.FlushRequest;
import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.arrow.flight.transport.FlightStreamPlugin;
import org.opensearch.common.action.ActionFuture;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.plugins.Plugin;
import org.opensearch.search.SearchService;
import org.opensearch.search.aggregations.bucket.terms.GlobalOrdinalsStringTermsAggregator;
import org.opensearch.search.aggregations.bucket.terms.StreamStringTermsAggregator;
import org.opensearch.search.aggregations.bucket.terms.StringTerms;
import org.opensearch.search.profile.ProfileResult;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.ParameterizedDynamicSettingsOpenSearchIntegTestCase;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.opensearch.common.util.FeatureFlags.STREAM_TRANSPORT;
import static org.opensearch.index.query.QueryBuilders.existsQuery;
import static org.opensearch.search.SearchService.CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING;
import static org.opensearch.search.aggregations.AggregationBuilders.terms;

/**
 * Integration test for FSST compressed access in terms aggregations.
 * Tests both StreamStringTermsAggregator and GlobalOrdinalsStringTermsAggregator paths.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 2)
public class FSSTTermsAggregationIT extends ParameterizedDynamicSettingsOpenSearchIntegTestCase {

    public FSSTTermsAggregationIT(Settings dynamicSettings) {
        super(dynamicSettings);
    }

    @ParametersFactory
    public static Collection<Object[]> parameters() {
        return Arrays.asList(
            new Object[] { Settings.builder().put(CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING.getKey(), false).build() },
            new Object[] { Settings.builder().put(CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING.getKey(), true).build() }
        );
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(FlightStreamPlugin.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        internalCluster().ensureAtLeastNumDataNodes(2);

        // Note: we do NOT set any FSST system properties — data is written with standard LZ4 codec.
        // This test validates the dynamic setting toggle and debug info reporting.

        // Enable streaming aggregation
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder()
                    .put("search.aggregations.streaming.max_estimated_bucket_count", 100000)
                    .put("search.aggregations.streaming.min_cardinality_ratio", 0.001)
                    .put("search.aggregations.streaming.min_estimated_bucket_count", 1)
                    .build()
            )
            .get();

        // Create index with URL-like data across multiple segments
        Settings indexSettings = Settings.builder()
            .put("index.number_of_shards", 2)
            .put("index.number_of_replicas", 0)
            .put("index.merge.policy.max_merged_segment", "1b") // prevent merges
            .build();
        client().admin().indices().prepareCreate("fsst_test")
            .setSettings(indexSettings)
            .setMapping("{\"properties\":{\"url\":{\"type\":\"keyword\"}}}")
            .get();
        ensureGreen("fsst_test");

        String[] urls = {
            "http://example.com/page/1",
            "http://example.com/page/2",
            "http://example.com/page/3",
            "http://other.ru/catalog/item/100",
            "http://other.ru/catalog/item/200",
            "http://other.ru/catalog/item/300",
            "http://shop.example.com/product/shoes",
            "http://shop.example.com/product/shirt",
            "http://shop.example.com/product/pants",
        };

        // Segment 1
        BulkRequest bulk1 = new BulkRequest();
        for (int i = 0; i < 3; i++) {
            bulk1.add(new IndexRequest("fsst_test").source("{\"url\":\"" + urls[i] + "\"}", XContentType.JSON));
        }
        client().bulk(bulk1).actionGet();
        client().admin().indices().flush(new FlushRequest("fsst_test")).actionGet();

        // Segment 2
        BulkRequest bulk2 = new BulkRequest();
        for (int i = 3; i < 6; i++) {
            bulk2.add(new IndexRequest("fsst_test").source("{\"url\":\"" + urls[i] + "\"}", XContentType.JSON));
        }
        client().bulk(bulk2).actionGet();
        client().admin().indices().flush(new FlushRequest("fsst_test")).actionGet();

        // Segment 3
        BulkRequest bulk3 = new BulkRequest();
        for (int i = 6; i < 9; i++) {
            bulk3.add(new IndexRequest("fsst_test").source("{\"url\":\"" + urls[i] + "\"}", XContentType.JSON));
        }
        client().bulk(bulk3).actionGet();
        client().admin().indices().refresh(new RefreshRequest("fsst_test")).actionGet();
    }

    @Override
    public void tearDown() throws Exception {
        // Clean up transient settings
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder()
                    .putNull("search.aggregations.streaming.max_estimated_bucket_count")
                    .putNull("search.aggregations.streaming.min_cardinality_ratio")
                    .putNull("search.aggregations.streaming.min_estimated_bucket_count")
                    .putNull(SearchService.FSST_COMPRESSED_AGGREGATION_ENABLED.getKey())
                    .build()
            )
            .get();
        super.tearDown();
    }

    @LockFeatureFlag(STREAM_TRANSPORT)
    public void testStreamAggregationWithFSSTDisabled() throws Exception {
        // Disable FSST
        setFSSTEnabled(false);

        SearchResponse resp = runTermsAggregation();
        assertNotNull(resp);

        StringTerms agg = resp.getAggregations().get("urls");
        assertNotNull(agg);
        assertEquals(9, agg.getBuckets().size());

        // All URLs should be readable
        for (StringTerms.Bucket bucket : agg.getBuckets()) {
            assertTrue("Key should start with http://: " + bucket.getKeyAsString(),
                bucket.getKeyAsString().startsWith("http://"));
            assertEquals(1, bucket.getDocCount());
        }

        // FSST should NOT be used
        assertFSSTUsageInProfile(resp, false);
        assertAggregatorUsed(resp, StreamStringTermsAggregator.class);
    }

    @LockFeatureFlag(STREAM_TRANSPORT)
    public void testStreamAggregationWithFSSTEnabled() throws Exception {
        // Enable FSST setting — codec is LZ4, so falls back to lookupOrd gracefully
        setFSSTEnabled(true);

        SearchResponse resp = runTermsAggregation();
        assertNotNull(resp);

        StringTerms agg = resp.getAggregations().get("urls");
        assertNotNull(agg);
        assertEquals(9, agg.getBuckets().size());

        for (StringTerms.Bucket bucket : agg.getBuckets()) {
            assertTrue("Key should start with http://: " + bucket.getKeyAsString(),
                bucket.getKeyAsString().startsWith("http://"));
            assertEquals(1, bucket.getDocCount());
        }

        // FSST setting enabled but LZ4 codec — graceful fallback, FSST not used
        assertFSSTUsageInProfile(resp, false);
        assertAggregatorUsed(resp, StreamStringTermsAggregator.class);
    }

    @LockFeatureFlag(STREAM_TRANSPORT)
    public void testGlobalOrdinalsWithFSSTDisabled() throws Exception {
        // Use restrictive streaming settings to force GlobalOrdinalsStringTermsAggregator
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder()
                    .put("search.aggregations.streaming.max_estimated_bucket_count", 1)
                    .put("search.aggregations.streaming.min_cardinality_ratio", 0.9)
                    .put("search.aggregations.streaming.min_estimated_bucket_count", 1000)
                    .build()
            )
            .get();

        setFSSTEnabled(false);

        SearchResponse resp = runTermsAggregation();
        assertNotNull(resp);

        StringTerms agg = resp.getAggregations().get("urls");
        assertNotNull(agg);
        assertEquals(9, agg.getBuckets().size());

        for (StringTerms.Bucket bucket : agg.getBuckets()) {
            assertTrue(bucket.getKeyAsString().startsWith("http://"));
        }

        assertFSSTUsageInProfile(resp, false);
        assertAggregatorUsed(resp, GlobalOrdinalsStringTermsAggregator.class);
    }

    @LockFeatureFlag(STREAM_TRANSPORT)
    public void testFSSTSettingDynamicToggle() throws Exception {
        // Start disabled
        setFSSTEnabled(false);
        SearchResponse resp1 = runTermsAggregation();
        assertFSSTUsageInProfile(resp1, false);

        // Enable dynamically
        setFSSTEnabled(true);
        SearchResponse resp2 = runTermsAggregation();
        // Setting is enabled — debug info should reflect the check was made
        // (actual FSST usage depends on codec support)
        StringTerms agg = resp2.getAggregations().get("urls");
        assertEquals(9, agg.getBuckets().size());
    }

    private void setFSSTEnabled(boolean enabled) {
        client().admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(
                Settings.builder()
                    .put(SearchService.FSST_COMPRESSED_AGGREGATION_ENABLED.getKey(), enabled)
                    .build()
            )
            .get();
    }

    private SearchResponse runTermsAggregation() {
        ActionFuture<SearchResponse> future = client().prepareStreamSearch("fsst_test")
            .setQuery(existsQuery("url"))
            .addAggregation(terms("urls").field("url").size(20))
            .setSize(0)
            .setRequestCache(false)
            .setProfile(true)
            .execute();
        return future.actionGet();
    }

    private void assertAggregatorUsed(SearchResponse resp, Class<?> expectedAggregatorClass) {
        assertNotNull("Profile response should be present", resp.getProfileResults());
        boolean found = false;
        for (var shardProfile : resp.getProfileResults().values()) {
            List<ProfileResult> aggProfileResults = shardProfile.getAggregationProfileResults().getProfileResults();
            for (var profileResult : aggProfileResults) {
                if (expectedAggregatorClass.getSimpleName().equals(profileResult.getQueryName())) {
                    found = true;
                    break;
                }
            }
            if (found) break;
        }
        assertTrue("Expected to find " + expectedAggregatorClass.getSimpleName() + " in profile", found);
    }

    private void assertFSSTUsageInProfile(SearchResponse resp, boolean expectedUsed) {
        boolean found = false;
        for (var shardProfile : resp.getProfileResults().values()) {
            List<ProfileResult> aggResults = shardProfile.getAggregationProfileResults().getProfileResults();
            for (ProfileResult result : aggResults) {
                Map<String, Object> debug = result.getDebugInfo();
                if (debug != null && debug.containsKey("fsst_compressed_access_used")) {
                    found = true;
                    assertEquals(
                        "FSST usage mismatch in " + result.getQueryName(),
                        expectedUsed,
                        debug.get("fsst_compressed_access_used")
                    );
                }
            }
        }
        assertTrue("Should find fsst_compressed_access_used in profile debug info", found);
    }
}
