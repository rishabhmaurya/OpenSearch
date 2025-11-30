# Reactive Handler Invocation Approach

## Problem
Previously, `handler.handleStreamResponse()` was called immediately when `sendRequest()` was invoked, even though no data was available yet. This meant the handler thread would block on the first `nextResponse()` call waiting for network I/O.

## Solution
**Invoke handler only when first batch is ready** - making the client truly reactive and non-blocking.

## Implementation

### Flow
1. **sendRequest() returns immediately** - no blocking
2. **Background thread fetches first batch** - in virtual thread
3. **Handler invoked only when data ready** - reactive pattern
4. **Continue prefetching** - up to 100MB buffer for throughput

### Code Changes

#### FlightClientChannel.java
```java
private void startFetchingAndInvokeHandler(FlightTransportResponse<?> streamResponse) {
    // Start prefetching in background
    streamResponse.startFetchingAndPrefetch();
    
    // Wait for first batch to be ready, then invoke handler
    streamResponse.getFirstBatchReadyFuture().thenAccept(v -> {
        TransportResponseHandler handler = streamResponse.getHandler();
        String executor = handler.executor();
        
        if (ThreadPool.Names.SAME.equals(executor)) {
            handler.handleStreamResponse(streamResponse);
        } else {
            threadPool.executor(executor).execute(() -> handler.handleStreamResponse(streamResponse));
        }
    }).exceptionally(ex -> {
        handleStreamException(streamResponse, ex);
        return null;
    });
}
```

#### FlightTransportResponse.java
```java
private final CompletableFuture<Void> firstBatchReady = new CompletableFuture<>();

void startFetchingAndPrefetch() {
    this.prefetchInProgress = true;
    Thread.ofVirtual().start(this::prefetchLoop);
}

CompletableFuture<Void> getFirstBatchReadyFuture() {
    return firstBatchReady;
}

private void prefetchLoop() {
    try {
        while (!streamExhausted) {
            // ... prefetch logic ...
            
            T next = fetchNextBatch();
            if (next != null) {
                prefetchQueue.offer(new BatchWithSize(next, batchSize));
                // Complete future when first batch is ready (only once)
                if (!firstBatchReady.isDone()) {
                    firstBatchReady.complete(null);
                }
            } else {
                firstBatchReady.complete(null);
                break;
            }
        }
    } catch (Exception e) {
        firstBatchReady.completeExceptionally(e);
    }
}
```

## Benefits

1. **Non-blocking sendRequest()** - returns immediately, no client thread blocking
2. **Reactive handler invocation** - handler called only when data available
3. **Better concurrency** - client threads free to handle other work
4. **Prefetching continues** - maintains high throughput with 100MB buffer
5. **Backpressure control** - prefetch stops when buffer full, restarts on consumption

## Comparison with Netty4

### Netty4 (Fully Async)
- Client thread: Never blocks
- Network thread: Calls handler when response arrives
- I/O: Fully async, event-driven

### Flight (Reactive with Blocking I/O)
- Client thread: Never blocks (returns immediately)
- Virtual thread: Blocks on flightStream.next() waiting for network
- Handler thread: Only invoked when first batch ready
- I/O: Blocking but hidden in virtual threads

## Implementation Details

### CompletableFuture-Based Signaling
The implementation uses CompletableFuture for efficient signaling:
1. **firstBatchReady** - CompletableFuture that completes when first batch arrives
2. **startFetchingAndPrefetch()** - Starts virtual thread to fetch batches
3. **getFirstBatchReadyFuture().thenAccept()** - Invokes handler when ready (no polling/sleep)
4. **exceptionally()** - Handles errors gracefully

This provides clean async composition with efficient waiting (no busy-wait or sleep).

## Key Insight
While we can't make Arrow Flight's blocking I/O truly async (API limitation), we can make the **client experience reactive** by:
- Returning immediately from sendRequest()
- Invoking handler only when data is ready (via CompletableFuture signaling)
- Using virtual threads for blocking I/O (cheap to block)
- No polling or sleep - efficient event-driven waiting

This gives similar **client-side behavior** to Netty4's async model, even though the underlying I/O is still blocking.
