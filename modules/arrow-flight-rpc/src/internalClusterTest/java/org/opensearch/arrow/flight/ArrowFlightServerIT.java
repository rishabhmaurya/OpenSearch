/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.OpenSearchFlightClient;
import org.apache.arrow.flight.Result;
import org.opensearch.arrow.flight.bootstrap.FlightService;
import org.opensearch.arrow.flight.bootstrap.client.FlightClientManager;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.SUITE, numDataNodes = 10)
public class ArrowFlightServerIT extends OpenSearchIntegTestCase {

    private FlightClientManager flightClientManager;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(FlightStreamPlugin.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        ensureGreen();
        FlightService flightService = internalCluster().getInstance(FlightService.class);
        flightClientManager = flightService.getFlightClientManager();
    }


    public void testArrowFlightEndpoint() throws Exception {
        Action pingAction = new Action("ping");
        try(OpenSearchFlightClient flightClient = flightClientManager.getFlightClient(flightClientManager.getLocalNodeId())) {
            assertNotNull(flightClient);
            Iterator<Result> results = flightClient.doAction(pingAction);
        }
    }

}
