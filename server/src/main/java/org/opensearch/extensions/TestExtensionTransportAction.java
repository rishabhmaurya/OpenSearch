/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions;

import org.opensearch.action.ActionListener;
import org.opensearch.action.main.MainAction;
import org.opensearch.action.main.MainRequest;
import org.opensearch.action.main.MainResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import java.io.IOException;

public class TestExtensionTransportAction extends ExtensionTransportAction<MainRequest, MainResponse> {

    @Inject
    public TestExtensionTransportAction(String actionName, ActionFilters actionFilters, TransportService transportService) {
        super(MainAction.INSTANCE.name(), transportService, actionFilters, MainRequest::new, true);
    }

    @Override
    protected void doExecuteExtension(Task task, MainRequest request, ActionListener<MainResponse> listener) {

    }

    @Override
    protected MainResponse readFromStream(StreamInput in) throws IOException {
        return new MainResponse(in);
    }
}
