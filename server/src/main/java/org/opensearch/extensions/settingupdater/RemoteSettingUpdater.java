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
import org.opensearch.common.inject.Inject;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.extensions.ExtensionService;
import org.opensearch.extensions.ExtensionTransportAction;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import java.io.IOException;


public class RemoteSettingUpdater extends ExtensionTransportAction<SettingUpdateRequest, SettingUpdateResponse> {


    @Inject
    public RemoteSettingUpdater(ActionFilters actionFilters,
                                TransportService transportService, ExtensionService extensionService) {
        super(SettingUpdateAction.NAME,transportService, actionFilters, SettingUpdateRequest::new, extensionService.isEsCluster());
    }

    @Override
    protected void doExecuteExtension(Task task, SettingUpdateRequest request, ActionListener<SettingUpdateResponse> listener) {

    }

    @Override
    protected SettingUpdateResponse readFromStream(StreamInput in) throws IOException {
        return new SettingUpdateResponse(in);
    }

}
