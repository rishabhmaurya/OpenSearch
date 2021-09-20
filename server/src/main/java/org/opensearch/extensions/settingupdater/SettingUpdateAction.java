/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.settingupdater;

import org.opensearch.action.ActionType;

public class SettingUpdateAction extends ActionType<SettingUpdateResponse> {

    public static final String NAME = "extension:setting/update";
    public static final SettingUpdateAction INSTANCE = new SettingUpdateAction();

    public SettingUpdateAction() {
        super(NAME, SettingUpdateResponse::new);
    }
}

