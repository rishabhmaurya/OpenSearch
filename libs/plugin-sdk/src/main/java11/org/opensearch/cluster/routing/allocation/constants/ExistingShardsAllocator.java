/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.allocation.constants;

import org.opensearch.common.settings.Setting;
import org.opensearch.gateway.constants.GatewayAllocator;

public interface ExistingShardsAllocator {
    Setting<String> EXISTING_SHARDS_ALLOCATOR_SETTING = Setting.simpleString(
        "index.allocation.existing_shards_allocator", GatewayAllocator.ALLOCATOR_NAME,
        Setting.Property.IndexScope, Setting.Property.PrivateIndex);
}
