/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.stateupdater;

import org.opensearch.action.ActionResponse;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;

import java.io.IOException;

public class ClusterStateUpdateResponse extends ActionResponse {


    public ClusterStateUpdateResponse(StreamInput streamInput) {

    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {

    }
}
