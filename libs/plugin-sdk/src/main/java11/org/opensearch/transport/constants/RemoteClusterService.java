/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.transport.constants;

import org.opensearch.common.settings.Setting;

public class RemoteClusterService {
    public static final Setting<Boolean> SEARCH_ENABLE_REMOTE_CLUSTERS =
        Setting.boolSetting("search.remote.connect", true, Setting.Property.NodeScope, Setting.Property.Deprecated);

}
