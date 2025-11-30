/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.arrow.flight.transport;

import org.opensearch.common.util.concurrent.ThreadContext;

public class FlightTimingCapture {
    private static final String SERVER_SENT_TO_QUEUE = "flight.server_sent_to_queue_ns";
    private static final String PICKED_FROM_QUEUE = "flight.picked_from_queue_ns";
    private static final String WRITTEN_TO_CHANNEL = "flight.written_to_channel_ns";
    private static final String CLIENT_RECEIVED_HEADER = "flight.client_received_header_ns";
    private static final String CLIENT_RECEIVED_MESSAGE = "flight.client_received_message_ns";

    public static void recordServerSentToQueue(ThreadContext ctx) {
        ctx.putTransient(SERVER_SENT_TO_QUEUE, System.nanoTime());
    }

    public static void recordPickedFromQueue(ThreadContext ctx) {
        ctx.putTransient(PICKED_FROM_QUEUE, System.nanoTime());
    }

    public static void recordWrittenToChannel(ThreadContext ctx) {
        ctx.putTransient(WRITTEN_TO_CHANNEL, System.nanoTime());
    }

    public static void recordClientReceivedHeader(ThreadContext ctx) {
        ctx.putTransient(CLIENT_RECEIVED_HEADER, System.nanoTime());
    }

    public static void recordClientReceivedMessage(ThreadContext ctx) {
        ctx.putTransient(CLIENT_RECEIVED_MESSAGE, System.nanoTime());
    }

    public static Long getServerSentToQueue(ThreadContext ctx) {
        return ctx.getTransient(SERVER_SENT_TO_QUEUE);
    }

    public static Long getPickedFromQueue(ThreadContext ctx) {
        return ctx.getTransient(PICKED_FROM_QUEUE);
    }

    public static Long getWrittenToChannel(ThreadContext ctx) {
        return ctx.getTransient(WRITTEN_TO_CHANNEL);
    }

    public static Long getClientReceivedHeader(ThreadContext ctx) {
        return ctx.getTransient(CLIENT_RECEIVED_HEADER);
    }

    public static Long getClientReceivedMessage(ThreadContext ctx) {
        return ctx.getTransient(CLIENT_RECEIVED_MESSAGE);
    }
}
