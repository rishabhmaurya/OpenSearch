/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.allocation.decider.constants;

import org.opensearch.common.settings.Setting;

public class ShardsLimitAllocationDecider {
    public static final Setting<Integer> INDEX_TOTAL_SHARDS_PER_NODE_SETTING =
        Setting.intSetting("index.routing.allocation.total_shards_per_node", -1, -1,
            Setting.Property.Dynamic, Setting.Property.IndexScope);
}
