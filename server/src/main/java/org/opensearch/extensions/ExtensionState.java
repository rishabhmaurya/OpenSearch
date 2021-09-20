/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions;

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.opensearch.LegacyESVersion;
import org.opensearch.client.transport.TransportClient;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.Diff;
import org.opensearch.cluster.Diffable;
import org.opensearch.cluster.IncompatibleClusterStateVersionException;
import org.opensearch.cluster.NamedDiffable;
import org.opensearch.cluster.NamedDiffableValueSerializer;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.cluster.coordination.CoordinationMetadata.VotingConfigExclusion;
import org.opensearch.cluster.coordination.CoordinationMetadata.VotingConfiguration;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.common.Strings;
import org.opensearch.common.UUIDs;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.collect.ImmutableOpenMap;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.io.stream.VersionedNamedWriteable;
import org.opensearch.common.regex.Regex;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.ToXContentFragment;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.opensearch.cluster.coordination.Coordinator.ZEN1_BWC_TERM;

public class ExtensionState implements ToXContentFragment, Diffable<ExtensionState> {

    public static final ExtensionState EMPTY_STATE = builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)).build();

    /**
     * An interface that implementors use when a class requires a client to maybe have a feature.
     */
    public interface FeatureAware {

        /**
         * An optional feature that is required for the client to have.
         *
         * @return an empty optional if no feature is required otherwise a string representing the required feature
         */
        default Optional<String> getRequiredFeature() {
            return Optional.empty();
        }

        /**
         * Tests whether or not the custom should be serialized. The criteria are:
         * <ul>
         * <li>the output stream must be at least the minimum supported version of the custom</li>
         * <li>the output stream must have the feature required by the custom (if any) or not be a transport client</li>
         * </ul>
         * <p>
         * That is, we only serialize customs to clients than can understand the custom based on the version of the client and the features
         * that the client has. For transport clients we can be lenient in requiring a feature in which case we do not send the custom but
         * for connected nodes we always require that the node has the required feature.
         *
         * @param out    the output stream
         * @param custom the custom to serialize
         * @param <T>    the type of the custom
         * @return true if the custom should be serialized and false otherwise
         */
        static <T extends VersionedNamedWriteable & FeatureAware> boolean shouldSerialize(final StreamOutput out, final T custom) {
            if (out.getVersion().before(custom.getMinimalSupportedVersion())) {
                return false;
            }
            if (custom.getRequiredFeature().isPresent()) {
                final String requiredFeature = custom.getRequiredFeature().get();
                // if it is a transport client we are lenient yet for a connected node it must have the required feature
                return out.hasFeature(requiredFeature) || out.hasFeature(TransportClient.TRANSPORT_CLIENT_FEATURE) == false;
            }
            return true;
        }

    }

    public interface Custom extends NamedDiffable<Custom>, ToXContentFragment, FeatureAware {

        /**
         * Returns <code>true</code> iff this {@link org.opensearch.cluster.ClusterState.Custom} is private to the cluster and should never be send to a client.
         * The default is <code>false</code>;
         */
        default boolean isPrivate() {
            return false;
        }

    }

    private static final NamedDiffableValueSerializer<org.opensearch.cluster.ClusterState.Custom> CUSTOM_VALUE_SERIALIZER = new NamedDiffableValueSerializer<>(org.opensearch.cluster.ClusterState.Custom.class);

    public static final String UNKNOWN_UUID = "_na_";

    public static final long UNKNOWN_VERSION = -1;

    private final long version;

    private final String stateUUID;

    private final Metadata metadata;

    private final ClusterName clusterName;

    private final boolean wasReadFromDiff;

    public ExtensionState(long version, String stateUUID, ExtensionState state) {
        this(state.clusterName, version, stateUUID, state.metadata(), false);
    }

    public ExtensionState(long version, String stateUUID, ClusterState state, Set<String> subscribedIndices) {
        this(state.getClusterName(), version, stateUUID, getMetadata(state, subscribedIndices), false);
    }

    private static Metadata getMetadata(ClusterState state, Set<String> subscribedIndices) {
        Metadata.Builder metadataBuilder = new Metadata.Builder();
        for (IndexMetadata indexMetadata: state.metadata()) {
            for (String subscribedIndex : subscribedIndices) {
                if (Regex.simpleMatch(subscribedIndex, indexMetadata.getIndex().getName())) {
                    metadataBuilder.put(indexMetadata, false);
                }
            }
        }
        return metadataBuilder.build();
    }

    public ExtensionState(ClusterName clusterName, long version, String stateUUID, Metadata metadata,
                          boolean wasReadFromDiff) {
        this.version = version;
        this.stateUUID = stateUUID;
        this.clusterName = clusterName;
        this.metadata = metadata;
        this.wasReadFromDiff = wasReadFromDiff;
    }

    public long term() {
        return coordinationMetadata().term();
    }

    public long version() {
        return this.version;
    }

    public long getVersion() {
        return version();
    }

    public long getVersionOrMetadataVersion() {
        // When following a Zen1 master, the cluster state version is not guaranteed to increase, so instead it is preferable to use the
        // metadata version to determine the freshest node. However when following a Zen2 master the cluster state version should be used.
        return term() == ZEN1_BWC_TERM ? metadata().version() : version();
    }

    /**
     * This stateUUID is automatically generated for for each version of cluster state. It is used to make sure that
     * we are applying diffs to the right previous state.
     */
    public String stateUUID() {
        return this.stateUUID;
    }

    public Metadata metadata() {
        return this.metadata;
    }

    public Metadata getMetadata() {
        return metadata();
    }

    public CoordinationMetadata coordinationMetadata() {
        return metadata.coordinationMetadata();
    }

    public ClusterName getClusterName() {
        return this.clusterName;
    }

    public VotingConfiguration getLastAcceptedConfiguration() {
        return coordinationMetadata().getLastAcceptedConfiguration();
    }

    public VotingConfiguration getLastCommittedConfiguration() {
        return coordinationMetadata().getLastCommittedConfiguration();
    }

    public Set<VotingConfigExclusion> getVotingConfigExclusions() {
        return coordinationMetadata().getVotingConfigExclusions();
    }

    // Used for testing and logging to determine how this cluster state was send over the wire
    public boolean wasReadFromDiff() {
        return wasReadFromDiff;
    }



    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        final String TAB = "   ";
        sb.append("cluster uuid: ").append(metadata.clusterUUID())
            .append(" [committed: ").append(metadata.clusterUUIDCommitted()).append("]").append("\n");
        sb.append("version: ").append(version).append("\n");
        sb.append("state uuid: ").append(stateUUID).append("\n");
        sb.append("from_diff: ").append(wasReadFromDiff).append("\n");
        sb.append("meta data version: ").append(metadata.version()).append("\n");
        sb.append(TAB).append("coordination_metadata:\n");
        sb.append(TAB).append(TAB).append("term: ").append(coordinationMetadata().term()).append("\n");
        sb.append(TAB).append(TAB)
            .append("last_committed_config: ").append(coordinationMetadata().getLastCommittedConfiguration()).append("\n");
        sb.append(TAB).append(TAB)
            .append("last_accepted_config: ").append(coordinationMetadata().getLastAcceptedConfiguration()).append("\n");
        sb.append(TAB).append(TAB)
            .append("voting tombstones: ").append(coordinationMetadata().getVotingConfigExclusions()).append("\n");
        for (IndexMetadata indexMetadata : metadata) {
            sb.append(TAB).append(indexMetadata.getIndex());
            sb.append(": v[").append(indexMetadata.getVersion())
                .append("], mv[").append(indexMetadata.getMappingVersion())
                .append("], sv[").append(indexMetadata.getSettingsVersion())
                .append("], av[").append(indexMetadata.getAliasesVersion())
                .append("]\n");
            for (int shard = 0; shard < indexMetadata.getNumberOfShards(); shard++) {
                sb.append(TAB).append(TAB).append(shard).append(": ");
                sb.append("p_term [").append(indexMetadata.primaryTerm(shard)).append("], ");
                sb.append("isa_ids ").append(indexMetadata.inSyncAllocationIds(shard)).append("\n");
            }
        }
        if (metadata.customs().isEmpty() == false) {
            sb.append("metadata customs:\n");
            for (final ObjectObjectCursor<String, Metadata.Custom> cursor : metadata.customs()) {
                final String type = cursor.key;
                final Metadata.Custom custom = cursor.value;
                sb.append(TAB).append(type).append(": ").append(custom);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public enum Metric {
        VERSION("version"),
        MASTER_NODE("master_node"),
        BLOCKS("blocks"),
        NODES("nodes"),
        METADATA("metadata"),
        ROUTING_TABLE("routing_table"),
        ROUTING_NODES("routing_nodes"),
        CUSTOMS("customs");

        private static Map<String, Metric> valueToEnum;

        static {
            valueToEnum = new HashMap<>();
            for (Metric metric : Metric.values()) {
                valueToEnum.put(metric.value, metric);
            }
        }

        private final String value;

        Metric(String value) {
            this.value = value;
        }

        public static EnumSet<Metric> parseString(String param, boolean ignoreUnknown) {
            String[] metrics = Strings.splitStringByCommaToArray(param);
            EnumSet<Metric> result = EnumSet.noneOf(Metric.class);
            for (String metric : metrics) {
                if ("_all".equals(metric)) {
                    result = EnumSet.allOf(Metric.class);
                    break;
                }
                Metric m = valueToEnum.get(metric);
                if (m == null) {
                    if (!ignoreUnknown) {
                        throw new IllegalArgumentException("Unknown metric [" + metric + "]");
                    }
                } else {
                    result.add(m);
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        EnumSet<Metric> metrics = Metric.parseString(params.param("metric", "_all"), true);

        // always provide the cluster_uuid as part of the top-level response (also part of the metadata response)
        builder.field("cluster_uuid", metadata().clusterUUID());

        if (metrics.contains(Metric.VERSION)) {
            builder.field("version", version);
            builder.field("state_uuid", stateUUID);
        }

        // meta data
        if (metrics.contains(Metric.METADATA)) {
            metadata.toXContent(builder, params);
        }

        return builder;
    }

    public static Builder builder(ClusterName clusterName) {
        return new Builder(clusterName);
    }

    public static org.opensearch.cluster.ClusterState.Builder builder(org.opensearch.cluster.ClusterState state) {
        return new org.opensearch.cluster.ClusterState.Builder(state);
    }

    public static class Builder {

        private final ClusterName clusterName;
        private long version = 0;
        private String uuid = UNKNOWN_UUID;
        private Metadata metadata = Metadata.EMPTY_METADATA;
        private RoutingTable routingTable = RoutingTable.EMPTY_ROUTING_TABLE;
        private DiscoveryNodes nodes = DiscoveryNodes.EMPTY_NODES;
        private ClusterBlocks blocks = ClusterBlocks.EMPTY_CLUSTER_BLOCK;
        private boolean fromDiff;
        private int minimumMasterNodesOnPublishingMaster = -1;

        public Builder(ExtensionState state) {
            this.clusterName = state.clusterName;
            this.version = state.version();
            this.uuid = state.stateUUID();
            this.metadata = state.metadata();
            this.fromDiff = false;
        }

        public Builder(ClusterName clusterName) {
            this.clusterName = clusterName;
        }

        public Builder nodes(DiscoveryNodes.Builder nodesBuilder) {
            return nodes(nodesBuilder.build());
        }

        public Builder nodes(DiscoveryNodes nodes) {
            this.nodes = nodes;
            return this;
        }

        public DiscoveryNodes nodes() {
            return nodes;
        }

        public Builder routingTable(RoutingTable routingTable) {
            this.routingTable = routingTable;
            return this;
        }

        public Builder metadata(Metadata.Builder metadataBuilder) {
            return metadata(metadataBuilder.build());
        }

        public Builder metadata(Metadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder blocks(ClusterBlocks.Builder blocksBuilder) {
            return blocks(blocksBuilder.build());
        }

        public Builder blocks(ClusterBlocks blocks) {
            this.blocks = blocks;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder incrementVersion() {
            this.version = version + 1;
            this.uuid = UNKNOWN_UUID;
            return this;
        }

        public Builder stateUUID(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder minimumMasterNodesOnPublishingMaster(int minimumMasterNodesOnPublishingMaster) {
            this.minimumMasterNodesOnPublishingMaster = minimumMasterNodesOnPublishingMaster;
            return this;
        }

        public Builder putCustom(String type, Custom custom) {
            return this;
        }

        public Builder removeCustom(String type) {
            return this;
        }

        public Builder customs(ImmutableOpenMap<String, Custom> customs) {
            StreamSupport.stream(customs.spliterator(), false).forEach(cursor -> Objects.requireNonNull(cursor.value, cursor.key));
            return this;
        }

        public Builder fromDiff(boolean fromDiff) {
            this.fromDiff = fromDiff;
            return this;
        }

        public ExtensionState build() {
            if (UNKNOWN_UUID.equals(uuid)) {
                uuid = UUIDs.randomBase64UUID();
            }
            return new ExtensionState(clusterName, version, uuid, metadata, fromDiff);
        }

        public static byte[] toBytes(org.opensearch.cluster.ClusterState state) throws IOException {
            BytesStreamOutput os = new BytesStreamOutput();
            state.writeTo(os);
            return BytesReference.toBytes(os.bytes());
        }

        /**
         * @param data      input bytes
         * @param localNode used to set the local node in the cluster state.
         */
        public static ExtensionState fromBytes(byte[] data, DiscoveryNode localNode, NamedWriteableRegistry registry) throws IOException {
            StreamInput in = new NamedWriteableAwareStreamInput(StreamInput.wrap(data), registry);
            return readFrom(in, localNode);

        }
    }

    @Override
    public Diff<ExtensionState> diff(ExtensionState previousState) {
        return new ExtensionStateDiff(previousState, this);
    }

    public static Diff<ExtensionState> readDiffFrom(StreamInput in, DiscoveryNode localNode) throws IOException {
        return new ExtensionStateDiff(in, localNode);
    }

    public static ExtensionState readFrom(StreamInput in, DiscoveryNode localNode) throws IOException {
        ClusterName clusterName = new ClusterName(in);
        Builder builder = new Builder(clusterName);
        builder.version = in.readLong();
        builder.uuid = in.readString();
        builder.metadata = Metadata.readFrom(in);
        /*
        builder.routingTable = RoutingTable.readFrom(in);
        builder.nodes = DiscoveryNodes.readFrom(in, localNode);
        builder.blocks = ClusterBlocks.readFrom(in);
        int customSize = in.readVInt();
        for (int i = 0; i < customSize; i++) {
            Custom customIndexMetadata = in.readNamedWriteable(Custom.class);
            builder.putCustom(customIndexMetadata.getWriteableName(), customIndexMetadata);
        }
        builder.minimumMasterNodesOnPublishingMaster = in.getVersion().onOrAfter(LegacyESVersion.V_6_7_0) ? in.readVInt() : -1;
        */
        return builder.build();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        clusterName.writeTo(out);
        out.writeLong(version);
        out.writeString(stateUUID);
        metadata.writeTo(out);
    }

    private static class ExtensionStateDiff implements Diff<ExtensionState> {

        private final long toVersion;

        private final String fromUuid;

        private final String toUuid;

        private final ClusterName clusterName;

        private final Diff<Metadata> metadata;

        ExtensionStateDiff(ExtensionState before, ExtensionState after) {
            fromUuid = before.stateUUID;
            toUuid = after.stateUUID;
            toVersion = after.version;
            clusterName = after.clusterName;
            metadata = after.metadata.diff(before.metadata);
        }

        ExtensionStateDiff(StreamInput in, DiscoveryNode localNode) throws IOException {
            clusterName = new ClusterName(in);
            fromUuid = in.readString();
            toUuid = in.readString();
            toVersion = in.readLong();
            metadata = Metadata.readDiffFrom(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            clusterName.writeTo(out);
            out.writeString(fromUuid);
            out.writeString(toUuid);
            out.writeLong(toVersion);
            metadata.writeTo(out);
        }

        @Override
        public ExtensionState apply(ExtensionState state) {
            Builder builder = new Builder(clusterName);
            if (toUuid.equals(state.stateUUID)) {
                // no need to read the rest - cluster state didn't change
                return state;
            }
            if (fromUuid.equals(state.stateUUID) == false) {
                throw new IncompatibleClusterStateVersionException(state.version, state.stateUUID, toVersion, fromUuid);
            }
            builder.stateUUID(toUuid);
            builder.version(toVersion);
            builder.metadata(metadata.apply(state.metadata));
            builder.fromDiff(true);
            return builder.build();
        }
    }
}

