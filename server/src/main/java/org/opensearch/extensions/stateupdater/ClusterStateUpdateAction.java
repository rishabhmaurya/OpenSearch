/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.stateupdater;

import org.opensearch.action.ActionType;

public class ClusterStateUpdateAction extends ActionType<ClusterStateUpdateResponse> {

    public static final String NAME = "extension:state/update";
    public static final ClusterStateUpdateAction INSTANCE = new ClusterStateUpdateAction();

    public ClusterStateUpdateAction() {
        super(NAME, ClusterStateUpdateResponse::new);
    }
}

