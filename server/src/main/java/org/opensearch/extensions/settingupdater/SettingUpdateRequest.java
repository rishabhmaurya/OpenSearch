/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.settingupdater;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.settings.Settings;

import java.io.IOException;


public class SettingUpdateRequest extends ActionRequest {
    Settings previous;
    Settings current;

    public SettingUpdateRequest(Settings previous, Settings current) {
        this.previous = previous;
        this.current = current;
    }

    public SettingUpdateRequest(StreamInput in) throws IOException {
        super(in);
        this.previous = Settings.readSettingsFromStream(in);
        this.current = Settings.readSettingsFromStream(in);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        Settings.writeSettingsToStream(previous, out);
        Settings.writeSettingsToStream(current, out);
    }

}
