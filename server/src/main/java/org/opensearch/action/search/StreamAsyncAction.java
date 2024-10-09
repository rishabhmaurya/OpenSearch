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

package org.opensearch.action.search;

import org.apache.logging.log4j.Logger;
import org.opensearch.arrow.StreamManager;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.routing.GroupShardsIterator;
import org.opensearch.common.util.concurrent.AbstractRunnable;
import org.opensearch.core.action.ActionListener;
import org.opensearch.search.SearchHits;
import org.opensearch.search.SearchPhaseResult;
import org.opensearch.search.internal.AliasFilter;
import org.opensearch.search.internal.InternalSearchResponse;
import org.opensearch.search.stream.OSTicket;
import org.opensearch.search.stream.StreamSearchResult;
import org.opensearch.search.stream.join.Join;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.transport.Transport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

/**
 * Stream at coordinator layer
 *
 * @opensearch.internal
 */
class StreamAsyncAction extends SearchQueryThenFetchAsyncAction {

    private final StreamManager streamManager;
    private final Join join;

    public StreamAsyncAction(
        Logger logger,
        SearchTransportService searchTransportService,
        BiFunction<String, String, Transport.Connection> nodeIdToConnection,
        Map<String, AliasFilter> aliasFilter,
        Map<String, Float> concreteIndexBoosts,
        Map<String, Set<String>> indexRoutings,
        SearchPhaseController searchPhaseController,
        Executor executor,
        QueryPhaseResultConsumer resultConsumer,
        SearchRequest request,
        ActionListener<SearchResponse> listener,
        GroupShardsIterator<SearchShardIterator> shardsIts,
        TransportSearchAction.SearchTimeProvider timeProvider,
        ClusterState clusterState,
        SearchTask task,
        SearchResponse.Clusters clusters,
        SearchRequestContext searchRequestContext,
        Tracer tracer,
        StreamManager streamManager
    ) {
        super(
            logger,
            searchTransportService,
            nodeIdToConnection,
            aliasFilter,
            concreteIndexBoosts,
            indexRoutings,
            searchPhaseController,
            executor,
            resultConsumer,
            request,
            listener,
            shardsIts,
            timeProvider,
            clusterState,
            task,
            clusters,
            searchRequestContext,
            tracer
        );
        this.streamManager = streamManager;
        this.join = searchRequestContext.getRequest().source().getJoin();
    }

    @Override
    protected SearchPhase getNextPhase(final SearchPhaseResults<SearchPhaseResult> results, SearchPhaseContext context) {
        return new StreamSearchReducePhase("stream_reduce", context);
    }

    class StreamSearchReducePhase extends SearchPhase {
        private final SearchPhaseContext context;

        protected StreamSearchReducePhase(String name, SearchPhaseContext context) {
            super(name);
            this.context = context;
        }

        @Override
        public void run() {
            context.execute(new StreamReduceAction(context, this));
        }
    }

    class StreamReduceAction extends AbstractRunnable {
        private final SearchPhaseContext context;
        private SearchPhase phase;

        StreamReduceAction(SearchPhaseContext context, SearchPhase phase) {
            this.context = context;
        }

        @Override
        protected void doRun() throws Exception {

            List<OSTicket> tickets = new ArrayList<>();
            for (SearchPhaseResult entry : results.getAtomicArray().asList()) {
                if (entry instanceof StreamSearchResult) {
                    tickets.addAll(((StreamSearchResult) entry).getFlightTickets());
                    ((StreamSearchResult) entry).getFlightTickets().forEach(osTicket -> {
                        System.out.println("Ticket: " + new String(osTicket.getBytes(), StandardCharsets.UTF_8));
                        // VectorSchemaRoot root = streamManager.getVectorSchemaRoot(osTicket);
                        // System.out.println("Number of rows: " + root.getRowCount());
                    });
                }
            }

            // shard/table, schema

            // ticket should contain which IndexShard it comes from
            // based on the search request, perform join using these tickets

            // join operate on 2 indexes using condition
            // join contain already contain the schema, or at least hold the schema data

            // StreamTicket joinResult = streamManager.registerStream((allocator) -> new ArrowStreamProvider.Task() {
            // @Override
            // public VectorSchemaRoot init(BufferAllocator allocator) {
            // IntVector docIDVector = new IntVector("docID", allocator);
            // FieldVector[] vectors = new FieldVector[]{
            // docIDVector
            // };
            // VectorSchemaRoot root = new VectorSchemaRoot(Arrays.asList(vectors));
            // return root;
            // }
            //
            // public void run(VectorSchemaRoot root, ArrowStreamProvider.FlushSignal flushSignal) {
            // // TODO perform join algo
            // IntVector docIDVector = (IntVector) root.getVector("docID");
            // for (int i = 0; i < 10; i++) {
            // docIDVector.set(i, i);
            // }
            // root.setRowCount(10);
            // flushSignal.awaitConsumption(1000);
            // }
            //
            // @Override
            // public void onCancel() {
            //
            // }
            // });

            InternalSearchResponse internalSearchResponse = new InternalSearchResponse(
                SearchHits.empty(),
                null,
                null,
                null,
                false,
                false,
                1,
                Collections.emptyList(),
                List.of(new OSTicket("456".getBytes(), null))
            );
            context.sendSearchResponse(internalSearchResponse, results.getAtomicArray());
        }

        @Override
        public void onFailure(Exception e) {
            context.onPhaseFailure(phase, "", e);
        }
    }
}
