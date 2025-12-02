# Flight Request Timing via ThreadContext

## Overview
Track Flight request timing using ThreadContext headers (propagate across network). Retrieve metrics in benchmark API response.

## Implementation

### FlightRequestTiming.java
Utility class with methods to inject/extract timing from ThreadContext headers.

### Injection Points (8 total)

1. **Client Send** - `FlightClientChannel.sendMessage()` ✅
2. **Server Receive** - `FlightMessageHandler.createTcpTransportChannel()` ✅  
3. **Handler Start** - `ArrowFlightProducer.getStream()` ✅
4. **Server Queue** - `FlightOutboundHandler.processBatchTask()` (first batch) ⏳
5. **Server Complete** - `FlightOutboundHandler.processCompleteTask()` ⏳
6. **Client First Batch** - `FlightTransportResponse.nextResponse()` ⏳
7. **Client Batch Count** - `FlightTransportResponse.nextResponse()` (every batch) ⏳
8. **Client Complete** - `FlightTransportResponse.nextResponse()` (stream end) ⏳

### Executor State Capture
Capture where executors accessible (FlightOutboundHandler has access to executor and FlightTransport):
```java
String state = FlightRequestTiming.captureExecutorState(
    executor.getActiveCount(), 
    executor.getQueue().size(),
    0, 0,
    flightTransport.getEventLoopTaskCounts()
);
```

### Retrieve in Benchmark API
In `TransportBenchmarkStreamAction` response handler:
```java
Map<String, Object> timing = FlightRequestTiming.extractMetrics(threadPool.getThreadContext());
response.setFlightTiming(timing);
```

## Output Example
```json
{
  "total_latency_ms": 1200,
  "network_latency_ms": 50,
  "handler_to_queue_ms": 20,
  "queue_to_first_batch_ms": 25,
  "batch_count": 10,
  "executor_server_queue": "srv_a=4,srv_q=12,evl=[3,10,5,4]"
}
```

## Benefits
- Headers propagate automatically across network
- No new API needed
- No logging overhead
- Executor state captured where accessible
