/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index.mapper.constants;

import org.opensearch.common.settings.Setting;

public class FieldMapper {
    public static final Setting<Boolean> IGNORE_MALFORMED_SETTING =
        Setting.boolSetting("index.mapping.ignore_malformed", false, Setting.Property.IndexScope);
    public static final Setting<Boolean> COERCE_SETTING =
        Setting.boolSetting("index.mapping.coerce", false, Setting.Property.IndexScope);
}
