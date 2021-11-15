/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.allocation.decider.constants;

import org.opensearch.common.settings.Setting;

import java.util.Locale;

public class EnableAllocationDecider {

    public static final String NAME = "enable";

    public static final Setting<Allocation> CLUSTER_ROUTING_ALLOCATION_ENABLE_SETTING =
        new Setting<>("cluster.routing.allocation.enable", Allocation.ALL.toString(), Allocation::parse,
            Setting.Property.Dynamic, Setting.Property.NodeScope);
    public static final Setting<Allocation> INDEX_ROUTING_ALLOCATION_ENABLE_SETTING =
        new Setting<>("index.routing.allocation.enable", Allocation.ALL.toString(), Allocation::parse,
            Setting.Property.Dynamic, Setting.Property.IndexScope);

    public static final Setting<Rebalance> CLUSTER_ROUTING_REBALANCE_ENABLE_SETTING =
        new Setting<>("cluster.routing.rebalance.enable", Rebalance.ALL.toString(), Rebalance::parse,
            Setting.Property.Dynamic, Setting.Property.NodeScope);
    public static final Setting<Rebalance> INDEX_ROUTING_REBALANCE_ENABLE_SETTING =
        new Setting<>("index.routing.rebalance.enable", Rebalance.ALL.toString(), Rebalance::parse,
            Setting.Property.Dynamic, Setting.Property.IndexScope);



    /**
     * Allocation values or rather their string representation to be used used with
     * {@link EnableAllocationDecider#CLUSTER_ROUTING_ALLOCATION_ENABLE_SETTING} /
     * {@link EnableAllocationDecider#INDEX_ROUTING_ALLOCATION_ENABLE_SETTING}
     * via cluster / index settings.
     */
    public enum Allocation {

        NONE,
        NEW_PRIMARIES,
        PRIMARIES,
        ALL;

        public static Allocation parse(String strValue) {
            if (strValue == null) {
                return null;
            } else {
                strValue = strValue.toUpperCase(Locale.ROOT);
                try {
                    return Allocation.valueOf(strValue);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Illegal allocation.enable value [" + strValue + "]");
                }
            }
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }


    /**
     * Rebalance values or rather their string representation to be used used with
     * {@link EnableAllocationDecider#CLUSTER_ROUTING_REBALANCE_ENABLE_SETTING} /
     * {@link EnableAllocationDecider#INDEX_ROUTING_REBALANCE_ENABLE_SETTING}
     * via cluster / index settings.
     */
    public enum Rebalance {

        NONE,
        PRIMARIES,
        REPLICAS,
        ALL;

        public static Rebalance parse(String strValue) {
            if (strValue == null) {
                return null;
            } else {
                strValue = strValue.toUpperCase(Locale.ROOT);
                try {
                    return Rebalance.valueOf(strValue);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Illegal rebalance.enable value [" + strValue + "]");
                }
            }
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
