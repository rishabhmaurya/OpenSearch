/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.constants;

import org.opensearch.common.settings.Setting;

public class IndicesRequestCache {

    public static final Setting<Boolean> INDEX_CACHE_REQUEST_ENABLED_SETTING =
        Setting.boolSetting("index.requests.cache.enable", true, Setting.Property.Dynamic, Setting.Property.IndexScope);

}
