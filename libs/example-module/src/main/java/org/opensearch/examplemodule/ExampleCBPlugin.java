/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.examplemodule;

import org.apache.lucene.search.spell.LuceneLevenshteinDistance;
import org.opensearch.mod.common.breaker.CircuitBreaker;
import org.opensearch.mod.common.breaker.fromindices.breaker.BreakerSettings;
import org.opensearch.mod.common.settings.Settings;
import org.opensearch.plugins.CircuitBreakerPlugin;

public class ExampleCBPlugin implements CircuitBreakerPlugin {

    @Override
    public BreakerSettings getCircuitBreaker(Settings settings) {
        LuceneLevenshteinDistance levenshteinDistance = new LuceneLevenshteinDistance();
        return new BreakerSettings("example-cb", 1000000, levenshteinDistance.getDistance("sds", "dsdsdsdsd"));
    }

    @Override
    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {}
}
