/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.action;

import org.opensearch.action.ActionType;
import org.opensearch.action.search.SearchResponse;

/**
 * Searches and streams the doc values of the matches as Arrow record batches into the {@link ArrowBatchSink} of the
 * request; the response is the search summary. It runs on the local node only, because results flow into a local sink.
 * The name sits under {@code indices:data/read/search}, so search privileges apply.
 */
public final class ArrowSearchAction extends ActionType<SearchResponse> {

    /** The action name. */
    public static final String NAME = "indices:data/read/search/arrow";

    /** The singleton instance. */
    public static final ArrowSearchAction INSTANCE = new ArrowSearchAction();

    private ArrowSearchAction() {
        super(NAME, SearchResponse::new);
    }
}
