/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.stateupdater;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.extensions.ExtensionState;

import java.io.IOException;

public class ClusterStateUpdateRequest extends ActionRequest {
    ExtensionState extensionState;

    public ClusterStateUpdateRequest(ExtensionState indexMetadataDiff) {
        this.extensionState = indexMetadataDiff;
    }

    public ClusterStateUpdateRequest(StreamInput in) throws IOException {
        super(in);
        extensionState = ExtensionState.readFrom(in,null);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        extensionState.writeTo(out);
    }

}
