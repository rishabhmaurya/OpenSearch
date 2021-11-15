/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index.cache.bitset.constants;

import org.opensearch.common.settings.Setting;

public class BitsetFilterCache {
    public static final Setting<Boolean> INDEX_LOAD_RANDOM_ACCESS_FILTERS_EAGERLY_SETTING =
        Setting.boolSetting("index.load_fixed_bitset_filters_eagerly", true, Setting.Property.IndexScope);

}
