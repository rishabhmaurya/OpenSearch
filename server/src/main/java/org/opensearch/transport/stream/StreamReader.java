/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.transport.stream;

import org.opensearch.core.transport.TransportResponse;

public interface StreamReader<T extends TransportResponse> {
    boolean hasNext();
    T next();
}
