/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.metadata.constant;

import org.opensearch.cluster.block.ClusterBlock;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.common.settings.Setting;
import org.opensearch.rest.RestStatus;

public class MetadataIndexStateService {
    public static final int INDEX_CLOSED_BLOCK_ID = 4;
    public static final ClusterBlock INDEX_CLOSED_BLOCK = new ClusterBlock(4, "index closed", false,
        false, false, RestStatus.FORBIDDEN, ClusterBlockLevel.READ_WRITE);
    public static final Setting<Boolean> VERIFIED_BEFORE_CLOSE_SETTING =
        Setting.boolSetting("index.verified_before_close", false, Setting.Property.IndexScope, Setting.Property.PrivateIndex);

}
