/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions;

import org.opensearch.action.ActionListener;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.Writeable;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;

import java.io.IOException;

public abstract class ExtensionTransportAction<Request extends ActionRequest, Response extends ActionResponse>
    extends HandledTransportAction<Request, Response> {
    private final boolean esClusterNode;
    private final TransportService transportService;

    protected ExtensionTransportAction(String actionName, TransportService transportService, ActionFilters actionFilters,
                                       Writeable.Reader<Request> requestReader,  boolean esClusterNode) {
        super(actionName, transportService, actionFilters, requestReader);
        this.esClusterNode = esClusterNode;
        this.transportService = transportService;
    }

    @Override
    protected void doExecute(Task task, Request request, ActionListener<Response> listener) {
        if (esClusterNode) {
            TransportResponseHandler<Response> handler = new TransportResponseHandler<Response>() {
                @Override
                public Response read(StreamInput in) throws IOException {
                    return readFromStream(in);
                }

                @Override
                public void handleResponse(Response response) {
                    listener.onResponse(response);
                }

                @Override
                public void handleException(TransportException exp) {
                    listener.onFailure(exp);
                }

                @Override
                public String executor() {
                    return ThreadPool.Names.GENERIC;
                }
            };
            transportService.initConnection();
            transportService.sendRequest(transportService.connection, actionName, request, TransportRequestOptions.EMPTY,
                handler);
        } else {
            doExecuteExtension(task, request, listener);
        }
    }

    protected abstract void doExecuteExtension(Task task, Request request, ActionListener<Response> listener);

    protected abstract Response readFromStream(StreamInput in);

}
