/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.arrow.flight.transport;

import org.opensearch.common.util.concurrent.ThreadContext;

/**
 * Records Flight timing events in ThreadContext transient headers.
 * Events are stored as comma-separated "event:nanos" pairs.
 */
public class FlightTimingEvents {
    private static final String TIMING_EVENTS_KEY = "flight_timing_events";
    
    public static void recordEvent(ThreadContext ctx, String event) {
        String existing = ctx.getHeader(TIMING_EVENTS_KEY);
        String newEvent = event + ":" + System.nanoTime();
        ctx.putHeader(TIMING_EVENTS_KEY, existing == null ? newEvent : existing + "," + newEvent);
    }
    
    public static String getEvents(ThreadContext ctx) {
        return ctx.getHeader(TIMING_EVENTS_KEY);
    }
}
