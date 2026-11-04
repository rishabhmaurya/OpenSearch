/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

/**
 * Data on a shard that a scan cannot represent, such as a multi-valued field or a mapping that does not fit the column.
 * Every copy of the shard holds the same data, so the failure is final: it is a bad request, never retried.
 */
public final class ArrowScanDataException extends IllegalArgumentException {

    /**
     * @param message what cannot be represented
     */
    public ArrowScanDataException(String message) {
        super(message);
    }
}
