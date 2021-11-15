/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index.constants;

import org.opensearch.common.settings.Setting;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class IndexModule {
    public static final Setting<String> INDEX_STORE_TYPE_SETTING =
        new Setting<>("index.store.type", "", Function.identity(), Setting.Property.IndexScope, Setting.Property.NodeScope);

    public static final Setting<String> INDEX_RECOVERY_TYPE_SETTING =
        new Setting<>("index.recovery.type", "", Function.identity(), Setting.Property.IndexScope, Setting.Property.NodeScope);

    /** On which extensions to load data into the file-system cache upon opening of files.
     *  This only works with the mmap directory, and even in that case is still
     *  best-effort only. */
    public static final Setting<List<String>> INDEX_STORE_PRE_LOAD_SETTING =
        Setting.listSetting("index.store.preload", Collections.emptyList(), Function.identity(),
            Setting.Property.IndexScope, Setting.Property.NodeScope);

    // whether to use the query cache
    public static final Setting<Boolean> INDEX_QUERY_CACHE_ENABLED_SETTING =
        Setting.boolSetting("index.queries.cache.enabled", true, Setting.Property.IndexScope);

}
