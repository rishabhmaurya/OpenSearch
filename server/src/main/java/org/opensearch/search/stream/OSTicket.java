/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.stream;

import org.opensearch.arrow.StreamTicket;
import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;
import org.opensearch.core.xcontent.ToXContentFragment;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.search.SearchShardTarget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * OpenSearch Ticket
 */
@ExperimentalApi
public class OSTicket extends StreamTicket implements Writeable, ToXContentFragment {

    SearchShardTarget shard;

    public OSTicket(byte[] bytes, SearchShardTarget shard) {
        super(bytes);
        this.shard = shard;
    }

    public OSTicket(StreamInput in) throws IOException {
        this(in.readByteArray(), new SearchShardTarget(in));
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.value(new String(this.getBytes(), StandardCharsets.UTF_8));
        return builder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeByteArray(this.getBytes());
        shard.writeTo(out);
    }
}
