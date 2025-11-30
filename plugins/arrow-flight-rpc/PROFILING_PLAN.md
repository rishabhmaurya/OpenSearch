# Profiling Plan: Rule Out Plugin Overhead

## Goal
Determine if arrow-flight-rpc plugin is causing the 7x slowdown at 100 parallel requests, or if it's FlightClient/gRPC.

## Key Paths to Measure

### Client-Side (FlightClientChannel)
1. **sendMessage() entry to FlightClient.getStream() call**
   - Measures: Ticket serialization, header setup
   - Expected: <1ms

2. **FlightClient.getStream() call duration**
   - Measures: gRPC/Flight client overhead
   - This is the suspect!

3. **Handler invocation to first nextResponse() call**
   - Measures: Thread pool scheduling
   - Expected: <1ms

4. **nextResponse() call duration (per call)**
   - Measures: FlightStream.next() + deserialization
   - Expected: 1-2ms per batch

### Server-Side (FlightServerChannel)
1. **Request receive to handler invocation**
   - Measures: Request deserialization, routing
   - Expected: <1ms

2. **sendBatch() entry to putNext() call**
   - Measures: VectorSchemaRoot setup, serialization
   - Expected: 1-2ms

3. **putNext() call duration**
   - Measures: Flight server sending overhead
   - This is also a suspect!

## Instrumentation Strategy

Add timing logs at these points:

```java
// Client side - FlightClientChannel.sendMessage()
long t1 = System.nanoTime();
Ticket ticket = serializeToTicket(reference);
long t2 = System.nanoTime();
FlightStream stream = client.getStream(ticket, ...);
long t3 = System.nanoTime();

logger.debug("Timing: serialize={}us, getStream={}us", 
    (t2-t1)/1000, (t3-t2)/1000);
```

```java
// Client side - FlightTransportResponse.nextResponse()
long t1 = System.nanoTime();
boolean hasNext = flightStream.next();
long t2 = System.nanoTime();
T response = handler.read(input);
long t3 = System.nanoTime();

logger.debug("Timing: next={}us, deserialize={}us", 
    (t2-t1)/1000, (t3-t2)/1000);
```

```java
// Server side - FlightServerChannel.sendBatch()
long t1 = System.nanoTime();
// VectorSchemaRoot setup
long t2 = System.nanoTime();
serverStreamListener.putNext();
long t3 = System.nanoTime();

logger.debug("Timing: setup={}us, putNext={}us", 
    (t2-t1)/1000, (t3-t2)/1000);
```

## What to Look For

### If Plugin is the Problem:
- `serialize` or `deserialize` times increase with parallelism
- `setup` times show contention (high variance)
- Our code shows lock contention in thread dumps

### If FlightClient/gRPC is the Problem:
- `getStream()` times increase with parallelism
- `flightStream.next()` times increase with parallelism
- `putNext()` times increase with parallelism
- Thread dumps show contention in gRPC/Netty code

## Quick Test

Add this to FlightClientChannel.sendMessage():

```java
long start = System.nanoTime();
// ... existing code ...
long end = System.nanoTime();
if ((end - start) > 5_000_000) { // > 5ms
    logger.warn("Slow sendMessage: {}ms, parallel={}", 
        (end-start)/1_000_000, 
        Thread.currentThread().getName());
}
```

Run with 100 parallel. If you see many "Slow sendMessage" warnings, the plugin has contention.
If not, it's FlightClient/gRPC.

## Alternative: Thread Dump Analysis

During 100 parallel test, take thread dumps:
```bash
jstack <pid> > thread_dump.txt
```

Look for:
- Threads blocked in our plugin code → Plugin problem
- Threads blocked in gRPC/Netty code → FlightClient problem
- Threads waiting on locks → Identify the lock owner
