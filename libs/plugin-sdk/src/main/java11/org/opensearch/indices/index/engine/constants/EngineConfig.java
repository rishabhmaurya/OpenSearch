/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index.engine.constants;

import org.apache.lucene.codecs.Codec;
import org.opensearch.common.settings.Setting;

public class EngineConfig {

    /**
     * Index setting to change the low level lucene codec used for writing new segments.
     * This setting is <b>not</b> realtime updateable.
     * This setting is also settable on the node and the index level, it's commonly used in hot/cold node archs where index is likely
     * allocated on both `kind` of nodes.
     */
    public static final Setting<String> INDEX_CODEC_SETTING = new Setting<>("index.codec", "default", s -> {
        switch (s) {
            case "default":
            case "best_compression":
            case "lucene_default":
                return s;
            default:
                if (Codec.availableCodecs().contains(s) == false) { // we don't error message the not officially supported ones
                    throw new IllegalArgumentException(
                        "unknown value for [index.codec] must be one of [default, best_compression] but was: " + s);
                }
                return s;
        }
    }, Setting.Property.IndexScope, Setting.Property.NodeScope);


    /**
     * Configures an index to optimize documents with auto generated ids for append only. If this setting is updated from <code>false</code>
     * to <code>true</code> might not take effect immediately. In other words, disabling the optimization will be immediately applied while
     * re-enabling it might not be applied until the engine is in a safe state to do so. Depending on the engine implementation a change to
     * this setting won't be reflected re-enabled optimization until the engine is restarted or the index is closed and reopened.
     * The default is <code>true</code>
     */
    public static final Setting<Boolean> INDEX_OPTIMIZE_AUTO_GENERATED_IDS = Setting.boolSetting("index.optimize_auto_generated_id", true,
        Setting.Property.IndexScope, Setting.Property.Dynamic);
}
