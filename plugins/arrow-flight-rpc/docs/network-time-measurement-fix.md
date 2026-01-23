# Network Time Measurement Fix for Streaming Aggregations

## Problem

The outbound network time metric was showing absurdly high values (e.g., 1768771317514ms = 48 years) for streaming aggregations.

## Root Cause

Two issues were identified:

### Issue 1: Uninitialized Timestamp
- `outboundNetworkTime` is initialized to **0** in ShardSearchRequest
- When SearchExecutionStatsCollector calculates: `System.currentTimeMillis() - 0`
- Result: ~53 years (milliseconds since Unix epoch)

### Issue 2: Incorrect Measurement Approach
The original "fix" set timestamp only on the first batch, but this was wrong because:

1. **Server side**: FlightTransportChannel sets timestamp on first batch
2. **Client side**: StreamSearchTransportService reads ALL batches serially:
   ```java
   while ((currentResult = response.nextResponse()) != null) {
       // Process each batch...
   }
   ```
3. **Measurement**: SearchExecutionStatsCollector calculates delta AFTER all batches are read

This meant the "network time" included:
- Network time for first batch
- ALL serial processing time for reading subsequent batches  
- Network time for all subsequent batches

## Solution: Per-Batch Accumulation

### Traditional Transport (Netty4)
- Sets timestamp once before sending response
- Calculates delta once when response arrives
- Works because there's only ONE response

### Streaming Transport (Arrow Flight)
- Sets timestamp **before sending each batch**
- Accumulates delta **for each batch received**
- Final result contains total accumulated network time

## Implementation

### 1. FlightTransportChannel (Server)
```java
// Set timestamp before sending EACH batch
if (response instanceof QuerySearchResult && ((QuerySearchResult) response).getShardSearchRequest() != null) {
    ((QuerySearchResult) response).getShardSearchRequest().setOutboundNetworkTime(System.currentTimeMillis());
}
```

### 2. StreamSearchTransportService (Client)
```java
long accumulatedOutboundNetworkTime = 0;

while ((currentResult = response.nextResponse()) != null) {
    // Calculate and accumulate network time for each batch
    if (currentResult.getShardSearchRequest() != null && currentResult.getShardSearchRequest().getOutboundNetworkTime() > 0) {
        long batchNetworkTime = System.currentTimeMillis() - currentResult.getShardSearchRequest().getOutboundNetworkTime();
        accumulatedOutboundNetworkTime += Math.max(0, batchNetworkTime);
    }
    // ... process batch
}

// Set accumulated time on final result
lastResult.getShardSearchRequest().setOutboundNetworkTime(accumulatedOutboundNetworkTime);
```

### 3. SearchExecutionStatsCollector (Coordinator)
```java
long outboundTime = response.getShardSearchRequest().getOutboundNetworkTime();
if (outboundTime > 1000000000000L) { // If it's a timestamp (> year 2001 in ms)
    // Traditional: calculate delta from timestamp
    response.getShardSearchRequest().setOutboundNetworkTime(Math.max(0, System.currentTimeMillis() - outboundTime));
} 
// else: Streaming - already accumulated delta, use as-is
```

## Benefits

1. **Accurate Network Time**: Only measures actual network transmission time per batch
2. **Excludes Processing Time**: Serial batch processing time is not included
3. **Backward Compatible**: Traditional transport still works with timestamp-based approach
4. **Per-Batch Granularity**: Can see network time for each batch if needed for debugging

## Verification

After this fix, streaming network times should be:
- Comparable to traditional transport for low-cardinality queries
- Higher for high-cardinality queries (more batches = more network round trips)
- Reasonable values (milliseconds to seconds, not years!)
