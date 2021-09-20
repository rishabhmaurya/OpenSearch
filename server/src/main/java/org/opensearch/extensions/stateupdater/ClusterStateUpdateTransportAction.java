/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.stateupdater;

import org.opensearch.action.ActionListener;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.extensions.ExtensionService;
import org.opensearch.extensions.ExtensionTransportAction;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import java.io.IOException;

public class ClusterStateUpdateTransportAction extends ExtensionTransportAction<ClusterStateUpdateRequest, ClusterStateUpdateResponse> {


    @Inject
    public ClusterStateUpdateTransportAction(ActionFilters actionFilters,
                                             TransportService transportService, ExtensionService extensionService) {
        super(ClusterStateUpdateAction.NAME, transportService, actionFilters,
            ClusterStateUpdateRequest::new, extensionService.isEsCluster());
    }

    @Override
    protected void doExecuteExtension(Task task, ClusterStateUpdateRequest request, ActionListener<ClusterStateUpdateResponse> listener) {

    }

    @Override
    protected ClusterStateUpdateResponse readFromStream(StreamInput in) throws IOException {
        return new ClusterStateUpdateResponse(in);
    }
}
