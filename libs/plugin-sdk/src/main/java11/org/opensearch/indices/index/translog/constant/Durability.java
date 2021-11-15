/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index.translog.constant;

public enum Durability {

    /**
     * Async durability - translogs are synced based on a time interval.
     */
    ASYNC,
    /**
     * Request durability - translogs are synced for each high level request (bulk, index, delete)
     */
    REQUEST

}
