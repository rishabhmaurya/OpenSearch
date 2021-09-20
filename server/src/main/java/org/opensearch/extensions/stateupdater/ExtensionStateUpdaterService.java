/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.stateupdater;

import org.opensearch.client.Client;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.regex.Regex;
import org.opensearch.extensions.ExtensionState;

import java.util.Set;

public class ExtensionStateUpdaterService {

    Set<String> subscribedIndices;
    Client client;

    public ExtensionStateUpdaterService(Client client, Set<String> subscribedIndices) {
        this.client = client;
        this.subscribedIndices = subscribedIndices;
    }

    public void subscribeNewIndex(String index) {
        subscribedIndices.add(index);
    }

    public boolean hasExtensionStateChanged(ClusterChangedEvent event) {
        boolean hasChanged = false;
        if (event.metadataChanged()) {
            for (IndexMetadata indexMetadata: event.state().metadata()) {
                hasChanged |= hasIndexMetadataChanged(event, indexMetadata);
            }
            for (IndexMetadata indexMetadata: event.previousState().metadata()) {
                hasChanged |= hasIndexMetadataChanged(event, indexMetadata);
            }
            if (event.previousState().getExtensionStateVersion() == 0) {
                hasChanged = true;
            }
        }
        return hasChanged;
    }

    private boolean hasIndexMetadataChanged(ClusterChangedEvent event, IndexMetadata indexMetadata) {
        boolean hasChanged = false;
        for (String subscribedIndex : subscribedIndices) {
            if (Regex.simpleMatch(subscribedIndex, indexMetadata.getIndex().getName())) {
                IndexMetadata prevIndexMetadata = event.previousState().metadata().index(
                    indexMetadata.getIndex().getName());
                IndexMetadata curIndexMetadata = event.state().metadata().index(
                    indexMetadata.getIndex().getName());
                if (prevIndexMetadata == null && curIndexMetadata != null) {
                    hasChanged = true;
                    break;
                } else if (curIndexMetadata == null && prevIndexMetadata != null) {
                    hasChanged = true;
                    break;
                } else if (prevIndexMetadata == null) {

                } else if ((prevIndexMetadata.getVersion() != curIndexMetadata.getVersion())) {
                    hasChanged = true;
                    break;
                }
            }
        }
        return hasChanged;
    }

    public void publishExtensionState(ClusterChangedEvent event) {
        ExtensionState extensionState = new ExtensionState(event.state().getExtensionStateVersion(),
            event.state().stateUUID(), event.state(), subscribedIndices);
        client.execute(ClusterStateUpdateAction.INSTANCE, new ClusterStateUpdateRequest(extensionState));
    }
}
