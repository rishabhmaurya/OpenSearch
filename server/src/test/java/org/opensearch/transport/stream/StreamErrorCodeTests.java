/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.transport.stream;

import org.opensearch.OpenSearchStatusException;
import org.opensearch.ResourceNotFoundException;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.tasks.TaskCancelledException;
import org.opensearch.test.OpenSearchTestCase;

public class StreamErrorCodeTests extends OpenSearchTestCase {

    public void testStreamExceptionStatusFollowsItsCode() {
        assertEquals(RestStatus.BAD_REQUEST, new StreamException(StreamErrorCode.INVALID_ARGUMENT, "x").status());
        assertEquals(RestStatus.TOO_MANY_REQUESTS, new StreamException(StreamErrorCode.RESOURCE_EXHAUSTED, "x").status());
        assertEquals(RestStatus.SERVICE_UNAVAILABLE, new StreamException(StreamErrorCode.UNAVAILABLE, "x").status());
        assertEquals(RestStatus.GATEWAY_TIMEOUT, new StreamException(StreamErrorCode.TIMED_OUT, "x").status());
        assertEquals(RestStatus.INTERNAL_SERVER_ERROR, new StreamException(StreamErrorCode.INTERNAL, "x").status());
    }

    /** A cancellation, e.g. of a closing shard's scan, keeps the status of a TaskCancelledException, not a client error. */
    public void testCancellationIsNotAClientError() {
        assertEquals(new TaskCancelledException("x").status(), new StreamException(StreamErrorCode.CANCELLED, "x").status());
        assertEquals(RestStatus.INTERNAL_SERVER_ERROR, StreamErrorCode.forFailure(new TaskCancelledException("x")).restStatus());
    }

    public void testFailureCodes() {
        assertEquals(StreamErrorCode.INVALID_ARGUMENT, StreamErrorCode.forFailure(new IllegalArgumentException("x")));
        assertEquals(StreamErrorCode.RESOURCE_EXHAUSTED, StreamErrorCode.forFailure(new OpenSearchRejectedExecutionException("x")));
        assertEquals(StreamErrorCode.CANCELLED, StreamErrorCode.forFailure(new TaskCancelledException("x")));
        assertEquals(StreamErrorCode.CANCELLED, StreamErrorCode.forFailure(new RuntimeException(new TaskCancelledException("x"))));
        assertEquals(StreamErrorCode.NOT_FOUND, StreamErrorCode.forFailure(new ResourceNotFoundException("x")));
        assertEquals(StreamErrorCode.UNAUTHORIZED, StreamErrorCode.forFailure(new OpenSearchStatusException("x", RestStatus.FORBIDDEN)));
        assertEquals(
            StreamErrorCode.UNAVAILABLE,
            StreamErrorCode.forFailure(new OpenSearchStatusException("x", RestStatus.SERVICE_UNAVAILABLE))
        );
        assertEquals(StreamErrorCode.INTERNAL, StreamErrorCode.forFailure(new IllegalStateException("x")));
    }

    public void testEveryCodeRoundTripsThroughAStreamException() {
        for (StreamErrorCode code : StreamErrorCode.values()) {
            assertEquals(code, StreamErrorCode.forFailure(new StreamException(code, "x")));
        }
    }
}
