/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.stateupdater;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionListener;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.service.ClusterApplier;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.extensions.ExtensionService;
import org.opensearch.extensions.ExtensionTransportAction;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

public class ClusterStateUpdateTransportAction extends ExtensionTransportAction<ClusterStateUpdateRequest, ClusterStateUpdateResponse> {

    ClusterService clusterService;
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    public ClusterStateUpdateTransportAction(ActionFilters actionFilters,
                                             TransportService transportService, ExtensionService extensionService,
                                             ClusterService clusterService) {
        super(ClusterStateUpdateAction.NAME, transportService, actionFilters,
            ClusterStateUpdateRequest::new, extensionService.isEsCluster());
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecuteExtension(Task task, ClusterStateUpdateRequest request,
                                      ActionListener<ClusterStateUpdateResponse> listener) {
        clusterService.getClusterApplierService().onNewClusterState(request.toString(), () -> new ClusterState(
            request.extensionState.getVersion(), request.extensionState.stateUUID(), request.extensionState),
            new ClusterApplier.ClusterApplyListener() {

                @Override
                public void onFailure(String source, Exception e) {
                    logger.error("extension State failed. Version: " + request.extensionState.getVersion());
                }

                @Override
                public void onSuccess(String source) {
                    logger.info("extension State applied successfully. " +
                        "Version: " + request.extensionState.getVersion());
                }
            });
    }

    @Override
    protected ClusterStateUpdateResponse readFromStream(StreamInput in) {
        return new ClusterStateUpdateResponse(in);
    }
}
