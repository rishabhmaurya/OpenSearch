/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.action.search;

import org.opensearch.core.action.ActionListener;
import org.opensearch.node.ResponseCollectorService;
import org.opensearch.search.SearchPhaseResult;
import org.opensearch.search.query.QuerySearchResult;
import org.opensearch.transport.Transport;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Streaming wrapper for SearchExecutionStatsCollector that handles batched responses
 * and proper delta computation for network outbound time.
 */
public final class StreamSearchExecutionStatsCollector {

    private final String nodeId;
    private final ResponseCollectorService collector;
    private final long startNanos;
    private long accumulatedNetworkTime;

    StreamSearchExecutionStatsCollector(ResponseCollectorService collector, String nodeId) {
        this.collector = Objects.requireNonNull(collector, "response collector cannot be null");
        this.startNanos = System.nanoTime();
        this.accumulatedNetworkTime = 0;
        this.nodeId = nodeId;
    }

    public static BiFunction<Transport.Connection, SearchActionListener, ActionListener> makeWrapper(ResponseCollectorService service) {
        return (connection, originalListener) -> {
            if (originalListener instanceof StreamSearchActionListener) {
                StreamSearchActionListener<SearchPhaseResult> streamListener = (StreamSearchActionListener<SearchPhaseResult>) originalListener;
                StreamSearchExecutionStatsCollector statsCollector = new StreamSearchExecutionStatsCollector(service, connection.getNode().getId());

                return new StreamSearchActionListener<SearchPhaseResult>(streamListener.searchShardTarget, streamListener.requestIndex) {
                    @Override
                    protected void innerOnStreamResponse(SearchPhaseResult response) {
                        statsCollector.processResponse(response, false);
                        streamListener.innerOnStreamResponse(response);
                    }

                    @Override
                    protected void innerOnCompleteResponse(SearchPhaseResult response) {
                        statsCollector.processResponse(response, true);
                        streamListener.innerOnCompleteResponse(response);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        streamListener.onFailure(e);
                    }
                };
            }
            return new SearchExecutionStatsCollector(originalListener, service, connection.getNode().getId());
        };
    }

    void processResponse(SearchPhaseResult response, boolean isLast) {
        QuerySearchResult queryResult = response.queryResult();
        if (response.getShardSearchRequest() != null) {
            // Calculate delta for this batch and accumulate (for both local and remote)
            long batchDelta = Math.max(0, System.currentTimeMillis() - response.getShardSearchRequest().getOutboundNetworkTime());
            accumulatedNetworkTime += batchDelta;
            // Set accumulated network time only for final response
            if (isLast) {
                response.getShardSearchRequest().setOutboundNetworkTime(accumulatedNetworkTime);
            }
        }

        if (nodeId != null && queryResult != null && isLast) {
            final long serviceTimeEWMA = queryResult.serviceTimeEWMA();
            final int queueSize = queryResult.nodeQueueSize();
            final long responseDuration = System.nanoTime() - startNanos;

            if (serviceTimeEWMA > 0 && queueSize >= 0) {
                collector.addNodeStatistics(nodeId, queueSize, responseDuration, serviceTimeEWMA);
            }
        }
    }
}
