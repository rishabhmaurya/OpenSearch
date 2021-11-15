/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.metadata.constant;

public class Metadata {
    public enum XContentContext {
        /* Custom metadata should be returns as part of API call */
        API,

        /* Custom metadata should be stored as part of the persistent cluster state */
        GATEWAY,

        /* Custom metadata should be stored as part of a snapshot */
        SNAPSHOT
    }
    public static final String CONTEXT_MODE_PARAM = "context_mode";
    public static final String CONTEXT_MODE_GATEWAY = XContentContext.GATEWAY.toString();

    public static final String CONTEXT_MODE_API = XContentContext.API.toString();

}
