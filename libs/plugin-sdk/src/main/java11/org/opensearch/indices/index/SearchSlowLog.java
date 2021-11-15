/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index;

import org.opensearch.common.settings.Setting;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.ToXContent;

import java.util.Collections;

public class SearchSlowLog {
    static final String INDEX_SEARCH_SLOWLOG_PREFIX = "index.search.slowlog";
    public static final Setting<TimeValue> INDEX_SEARCH_SLOWLOG_THRESHOLD_QUERY_WARN_SETTING =
        Setting.timeSetting(INDEX_SEARCH_SLOWLOG_PREFIX + ".threshold.query.warn", TimeValue.timeValueNanos(-1),
            TimeValue.timeValueMillis(-1), Setting.Property.Dynamic, Setting.Property.IndexScope);
    public static final Setting<TimeValue> INDEX_SEARCH_SLOWLOG_THRESHOLD_QUERY_INFO_SETTING =
        Setting.timeSetting(INDEX_SEARCH_SLOWLOG_PREFIX + ".threshold.query.info", TimeValue.timeValueNanos(-1),
            TimeValue.timeValueMillis(-1), Setting.Property.Dynamic, Setting.Property.IndexScope);
    public static final Setting<TimeValue> INDEX_SEARCH_SLOWLOG_THRESHOLD_QUERY_DEBUG_SETTING =
        Setting.timeSetting(INDEX_SEARCH_SLOWLOG_PREFIX + ".threshold.query.debug", TimeValue.timeValueNanos(-1),
            TimeValue.timeValueMillis(-1), Setting.Property.Dynamic, Setting.Property.IndexScope);
    public static final Setting<TimeValue> INDEX_SEARCH_SLOWLOG_THRESHOLD_QUERY_TRACE_SETTING =
        Setting.timeSetting(INDEX_SEARCH_SLOWLOG_PREFIX + ".threshold.query.trace", TimeValue.timeValueNanos(-1),
            TimeValue.timeValueMillis(-1), Setting.Property.Dynamic, Setting.Property.IndexScope);
    public static final Setting<TimeValue> INDEX_SEARCH_SLOWLOG_THRESHOLD_FETCH_WARN_SETTING =
        Setting.timeSetting(INDEX_SEARCH_SLOWLOG_PREFIX + ".threshold.fetch.warn", TimeValue.timeValueNanos(-1),
            TimeValue.timeValueMillis(-1), Setting.Property.Dynamic, Setting.Property.IndexScope);
    public static final Setting<TimeValue> INDEX_SEARCH_SLOWLOG_THRESHOLD_FETCH_INFO_SETTING =
        Setting.timeSetting(INDEX_SEARCH_SLOWLOG_PREFIX + ".threshold.fetch.info", TimeValue.timeValueNanos(-1),
            TimeValue.timeValueMillis(-1), Setting.Property.Dynamic, Setting.Property.IndexScope);
    public static final Setting<TimeValue> INDEX_SEARCH_SLOWLOG_THRESHOLD_FETCH_DEBUG_SETTING =
        Setting.timeSetting(INDEX_SEARCH_SLOWLOG_PREFIX + ".threshold.fetch.debug", TimeValue.timeValueNanos(-1),
            TimeValue.timeValueMillis(-1), Setting.Property.Dynamic, Setting.Property.IndexScope);
    public static final Setting<TimeValue> INDEX_SEARCH_SLOWLOG_THRESHOLD_FETCH_TRACE_SETTING =
        Setting.timeSetting(INDEX_SEARCH_SLOWLOG_PREFIX + ".threshold.fetch.trace", TimeValue.timeValueNanos(-1),
            TimeValue.timeValueMillis(-1), Setting.Property.Dynamic, Setting.Property.IndexScope);
    public static final Setting<SlowLogLevel> INDEX_SEARCH_SLOWLOG_LEVEL =
        new Setting<>(INDEX_SEARCH_SLOWLOG_PREFIX + ".level", SlowLogLevel.TRACE.name(), SlowLogLevel::parse, Setting.Property.Dynamic,
            Setting.Property.IndexScope);

    private static final ToXContent.Params FORMAT_PARAMS = new ToXContent.MapParams(Collections.singletonMap("pretty", "false"));

}
