/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions;

public class ExtensionService {

    boolean esCluster;

    public ExtensionService(boolean esCluster) {
        this.esCluster = esCluster;
    }

    public boolean isEsCluster() {
        return esCluster;
    }

}
