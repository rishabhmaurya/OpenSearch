/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch;

import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.mod.Version;
import org.opensearch.mod.common.settings.Settings;

import java.util.Locale;

// #RF - moved indexCreated from Version.java to here to avoid dependency of server on common
public class VersionUtil {
    /**
     * Return the {@link Version} of OpenSearch that has been used to create an index given its settings.
     *
     * @throws IllegalStateException if the given index settings doesn't contain a value for the key
     *         {@value IndexMetadata#SETTING_VERSION_CREATED}
     */
    public static Version indexCreated(Settings indexSettings) {
        final Version indexVersion = IndexMetadata.SETTING_INDEX_VERSION_CREATED.get(indexSettings);
        if (indexVersion.equals(Version.V_EMPTY)) {
            final String message = String.format(
                Locale.ROOT,
                "[%s] is not present in the index settings for index with UUID [%s]",
                IndexMetadata.SETTING_INDEX_VERSION_CREATED.getKey(),
                indexSettings.get(IndexMetadata.SETTING_INDEX_UUID));
            throw new IllegalStateException(message);
        }
        return indexVersion;
    }
}
