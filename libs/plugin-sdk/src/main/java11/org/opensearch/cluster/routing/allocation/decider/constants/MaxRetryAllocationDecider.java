/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.allocation.decider.constants;

import org.opensearch.common.settings.Setting;

public class MaxRetryAllocationDecider {
    public static final Setting<Integer> SETTING_ALLOCATION_MAX_RETRY = Setting.intSetting("index.allocation.max_retries", 5, 0,
        Setting.Property.Dynamic, Setting.Property.IndexScope, Setting.Property.NotCopyableOnResize);

}
