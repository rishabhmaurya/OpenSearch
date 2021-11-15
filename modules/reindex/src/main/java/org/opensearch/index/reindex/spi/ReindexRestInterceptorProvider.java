/*
 * Copyright OpenSearch Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.index.reindex.spi;

import java.util.Optional;
import org.apache.http.HttpRequestInterceptor;
import org.opensearch.mod.common.util.concurrent.ThreadContext;
import org.opensearch.index.reindex.ReindexRequest;

public interface ReindexRestInterceptorProvider {
    /**
     * @param request Reindex request.
     * @param threadContext Current thread context.
     * @return HttpRequestInterceptor object.
     */
    Optional<HttpRequestInterceptor> getRestInterceptor(ReindexRequest request, ThreadContext threadContext);
}
