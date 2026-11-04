/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.opensearch.index.shard.SearchOperationListener;
import org.opensearch.search.internal.SearchContext;

/**
 * Stops an arrow scan when one of its slices fails inside the searcher, e.g. a query that throws while it is scored, where
 * the scan's collector does not see the failure: the slice frees its compute slot and the other slices stop, rather than
 * waiting for slots the failed slice would only give back once the whole query phase ended.
 */
public final class ArrowScanSliceListener implements SearchOperationListener {

    /** Creates the listener. */
    public ArrowScanSliceListener() {}

    @Override
    public void onFailedSliceExecution(SearchContext searchContext) {
        // Runs on the thread of the failed slice.
        if (searchContext.getSearchExt(ArrowScanExt.NAME) instanceof ArrowScanExt ext) {
            ArrowScanCollector.sliceFailed(ext.state());
        }
    }
}
