/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.settingupdater;

import org.opensearch.action.ActionListener;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.extensions.ExtensionService;
import org.opensearch.extensions.ExtensionTransportAction;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

public class RemoteSettingApplier extends ExtensionTransportAction<SettingUpdateRequest, SettingUpdateResponse> {

    ClusterService clusterService;

    @Inject
    public RemoteSettingApplier(ActionFilters actionFilters, TransportService transportService,
                                ClusterService clusterService, ExtensionService extensionService) {
        super(SettingUpdateAction.NAME, transportService, actionFilters, SettingUpdateRequest::new, extensionService.isEsCluster());
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecuteExtension(Task task, SettingUpdateRequest request, ActionListener<SettingUpdateResponse> listener) {
        clusterService.getClusterSettings().applySettings(request.current);
    }

    @Override
    protected SettingUpdateResponse readFromStream(StreamInput in) {
        return new SettingUpdateResponse(in);
    }
}
