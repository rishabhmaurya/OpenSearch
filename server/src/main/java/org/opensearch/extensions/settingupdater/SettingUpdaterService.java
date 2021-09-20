/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.settingupdater;

import org.opensearch.action.ActionListener;
import org.opensearch.client.Client;
import org.opensearch.common.settings.AbstractScopedSettings;
import org.opensearch.common.settings.Settings;

public class SettingUpdaterService<T> implements AbstractScopedSettings.SettingUpdater<T> {
    Client client;

    public SettingUpdaterService(Client client) {
        this.client = client;
    }

    public void updateSetting(Settings current, Settings previous) {
        client.execute(SettingUpdateAction.INSTANCE, new SettingUpdateRequest(current, previous));
    }

    public void updateSetting(Settings current, Settings previous, ActionListener<SettingUpdateResponse> listener) {
        client.execute(SettingUpdateAction.INSTANCE, new SettingUpdateRequest(current, previous), listener);
    }

    @Override
    public boolean hasChanged(Settings current, Settings previous) {
        return true;
    }

    @Override
    public T getValue(Settings current, Settings previous) {
        return null;
    }

    @Override
    public void apply(T value, Settings current, Settings previous) {
        this.updateSetting(current, previous);
    }
}
