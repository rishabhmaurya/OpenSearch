/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.constants;

import org.opensearch.common.settings.Setting;
import org.opensearch.common.unit.TimeValue;

public class UnassignedInfo {
    public static final Setting<TimeValue> INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING =
        Setting.positiveTimeSetting("index.unassigned.node_left.delayed_timeout", TimeValue.timeValueMinutes(1), Setting.Property.Dynamic,
            Setting.Property.IndexScope);
}
