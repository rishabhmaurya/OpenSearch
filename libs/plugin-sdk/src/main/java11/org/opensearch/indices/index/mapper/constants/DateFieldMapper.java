/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.index.mapper.constants;

import org.opensearch.common.time.DateFormatter;

public class DateFieldMapper {
    public static final DateFormatter DEFAULT_DATE_TIME_FORMATTER = DateFormatter.forPattern("strict_date_optional_time||epoch_millis");

}
