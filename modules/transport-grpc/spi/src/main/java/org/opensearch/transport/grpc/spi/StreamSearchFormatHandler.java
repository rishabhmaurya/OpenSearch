/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.transport.grpc.spi;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.common.annotation.ExperimentalApi;

/**
 * Serves {@code SearchService/ServerStreamSearch} calls whose {@value #FORMAT_HEADER} request header
 * matches {@link #format()}.
 * <p>
 * Implementations are discovered through {@code META-INF/services} by plugins that declare
 * {@code transport-grpc} in their {@code extended.plugins}.
 */
@ExperimentalApi
public interface StreamSearchFormatHandler {

    /** gRPC request metadata key selecting the response format of a streaming search. */
    String FORMAT_HEADER = "x-opensearch-format";

    /**
     * @return the format this handler serves, compared case-sensitively with the {@value #FORMAT_HEADER} header
     */
    String format();

    /**
     * Executes {@code request} and streams results into {@code channel}. Must not block the calling gRPC thread and must
     * terminate the channel exactly once with {@link StreamSearchResponseChannel#complete} or
     * {@link StreamSearchResponseChannel#fail}.
     *
     * @param request the search request converted from protobuf
     * @param channel the response channel of the call
     */
    void execute(SearchRequest request, StreamSearchResponseChannel channel);
}
