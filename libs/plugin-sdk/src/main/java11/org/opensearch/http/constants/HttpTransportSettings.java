/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.http.constants;

import org.opensearch.common.settings.Setting;
import org.opensearch.common.unit.ByteSizeValue;

import static org.opensearch.common.settings.Setting.intSetting;

public class HttpTransportSettings {
    public static final Setting<Integer> SETTING_HTTP_MAX_WARNING_HEADER_COUNT =
        intSetting("http.max_warning_header_count", -1, -1, Setting.Property.NodeScope);

    public static final Setting<ByteSizeValue> SETTING_HTTP_MAX_WARNING_HEADER_SIZE =
        Setting.byteSizeSetting("http.max_warning_header_size", new ByteSizeValue(-1), Setting.Property.NodeScope);
}
