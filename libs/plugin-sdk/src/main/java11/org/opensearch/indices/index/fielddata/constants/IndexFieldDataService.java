/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index.fielddata.constants;

import org.opensearch.common.settings.Setting;

public class IndexFieldDataService {
    public static final String FIELDDATA_CACHE_VALUE_NODE = "node";
    public static final String FIELDDATA_CACHE_KEY = "index.fielddata.cache";
    public static final Setting<String> INDEX_FIELDDATA_CACHE_KEY =
        new Setting<>(FIELDDATA_CACHE_KEY, (s) -> FIELDDATA_CACHE_VALUE_NODE, (s) -> {
            switch (s) {
                case "node":
                case "none":
                    return s;
                default:
                    throw new IllegalArgumentException("failed to parse [" + s + "] must be one of [node,none]");
            }
        }, Setting.Property.IndexScope);
}
