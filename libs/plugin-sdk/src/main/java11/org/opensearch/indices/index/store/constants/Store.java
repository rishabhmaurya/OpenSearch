/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index.store.constants;

import org.opensearch.common.settings.Setting;
import org.opensearch.common.unit.TimeValue;

public class Store {

    /**
     * This is an escape hatch for lucenes internal optimization that checks if the IndexInput is an instance of ByteBufferIndexInput
     * and if that's the case doesn't load the term dictionary into ram but loads it off disk iff the fields is not an ID like field.
     * Since this optimization has been added very late in the release processes we add this setting to allow users to opt-out of
     * this by exploiting lucene internals and wrapping the IndexInput in a simple delegate.
     */
    public static final Setting<Boolean> FORCE_RAM_TERM_DICT = Setting.boolSetting("index.force_memory_term_dictionary", false,
        Setting.Property.IndexScope, Setting.Property.Deprecated);

    public static final Setting<TimeValue> INDEX_STORE_STATS_REFRESH_INTERVAL_SETTING =
        Setting.timeSetting("index.store.stats_refresh_interval", TimeValue.timeValueSeconds(10), Setting.Property.IndexScope);

}
