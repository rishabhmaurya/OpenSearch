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
 *    http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.client.benchmark.transport;

import org.opensearch.OpenSearchException;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.benchmark.AbstractBenchmark;
import org.opensearch.client.benchmark.ops.bulk.BulkRequestExecutor;
import org.opensearch.client.benchmark.ops.search.SearchRequestExecutor;
import org.opensearch.client.transport.TransportClient;
import org.opensearch.mod.common.settings.Settings;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.plugin.noop.NoopPlugin;
import org.opensearch.plugin.noop.action.bulk.NoopBulkAction;
import org.opensearch.plugin.noop.action.search.NoopSearchAction;
import org.opensearch.mod.rest.RestStatus;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.PreBuiltTransportClient;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class TransportClientBenchmark extends AbstractBenchmark<TransportClient> {
    public static void main(String[] args) throws Exception {
        TransportClientBenchmark benchmark = new TransportClientBenchmark();
        benchmark.run(args);
    }

    @Override
    protected TransportClient client(String benchmarkTargetHost) throws Exception {
        TransportClient client = new PreBuiltTransportClient(Settings.EMPTY, NoopPlugin.class);
        client.addTransportAddress(new TransportAddress(InetAddress.getByName(benchmarkTargetHost), 9300));
        return client;
    }

    @Override
    protected BulkRequestExecutor bulkRequestExecutor(TransportClient client, String indexName, String typeName) {
        return new TransportBulkRequestExecutor(client, indexName, typeName);
    }

    @Override
    protected SearchRequestExecutor searchRequestExecutor(TransportClient client, String indexName) {
        return new TransportSearchRequestExecutor(client, indexName);
    }

    private static final class TransportBulkRequestExecutor implements BulkRequestExecutor {
        private final TransportClient client;
        private final String indexName;
        private final String typeName;

        TransportBulkRequestExecutor(TransportClient client, String indexName, String typeName) {
            this.client = client;
            this.indexName = indexName;
            this.typeName = typeName;
        }

        @Override
        public boolean bulkIndex(List<String> bulkData) {
            BulkRequest bulkRequest = new BulkRequest();
            for (String bulkItem : bulkData) {
                bulkRequest.add(new IndexRequest(indexName, typeName).source(bulkItem.getBytes(StandardCharsets.UTF_8), XContentType.JSON));
            }
            BulkResponse bulkResponse;
            try {
                bulkResponse = client.execute(NoopBulkAction.INSTANCE, bulkRequest).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                throw new OpenSearchException(e);
            }
            return !bulkResponse.hasFailures();
        }
    }

    private static final class TransportSearchRequestExecutor implements SearchRequestExecutor {
        private final TransportClient client;
        private final String indexName;

        private TransportSearchRequestExecutor(TransportClient client, String indexName) {
            this.client = client;
            this.indexName = indexName;
        }

        @Override
        public boolean search(String source) {
            final SearchResponse response;
            try {
                final SearchRequest searchRequest = new SearchRequest(indexName);
                SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
                searchRequest.source(searchSourceBuilder);
                searchSourceBuilder.query(QueryBuilders.wrapperQuery(source));
                response = client.execute(NoopSearchAction.INSTANCE, searchRequest).get();
                return response.status() == RestStatus.OK;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                throw new OpenSearchException(e);
            }
        }
    }
}
