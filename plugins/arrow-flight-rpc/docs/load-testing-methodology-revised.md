# Load Testing Methodology: Netty4 vs Flight Transport (Revised)

## Test Environment

### Hardware
- **Data Nodes**: 2x r5.2xlarge (8 vCPUs, 64GB RAM)
- **Coordinator**: 1x r5.2xlarge (8 vCPUs, 64GB RAM)
- **Heap**: 32GB per node
- **Direct Memory**: 8GB (for flight transport)

### Key Constraint
- **Bottleneck**: Search thread pool size (default: 13 threads on 8 vCPU)
- **Client→Coordinator**: Regular REST (not measured)
- **Coordinator→Data**: Transport under test (netty4 vs flight)

## Problem: Isolating Node-to-Node Transport

Since flight transport is only used for **coordinator→data node** communication during streaming aggregations, we need to:

1. **Bypass business logic** - Use noop endpoints or minimal operations
2. **Force coordinator→data communication** - Ensure queries hit data nodes
3. **Measure only transport layer** - Exclude REST and business logic overhead

## Solution: Synthetic Streaming Action

### Approach 1: Extend Noop Plugin (Recommended)

Create a **streaming noop action** that mimics aggregation behavior without computation:

```java
// In client-benchmark-noop-api-plugin
public class NoopStreamAction extends ActionType<NoopStreamResponse> {
    public static final String NAME = "indices:data/read/noop_stream";
}

public class TransportNoopStreamAction extends TransportBroadcastAction {
    // Coordinator receives request, broadcasts to data nodes
    // Data nodes return N batches of dummy data via stream transport
    // No actual computation, just serialization/deserialization
    
    @Override
    protected void shardOperation(NoopStreamRequest request, TransportChannel channel) {
        // Send N batches (configurable via request parameter)
        for (int i = 0; i < request.getBatchCount(); i++) {
            channel.sendResponseBatch(new NoopBatch(request.getBatchSize()));
        }
        channel.completeStream();
    }
}
```

**Usage**:
```bash
# JMeter hits coordinator
POST /_noop_stream
{
  "batch_count": 100,      # Number of batches per shard
  "batch_size": 1024,      # Bytes per batch
  "shard_count": 2         # Force hitting both data nodes
}
```

### Approach 2: Use Existing Search with Minimal Data

If extending noop plugin is too complex:

```bash
# Create index with 2 shards (one per data node)
PUT /transport-bench
{
  "settings": {
    "number_of_shards": 2,
    "number_of_replicas": 0
  }
}

# Index minimal documents (just enough to trigger shard queries)
POST /transport-bench/_bulk
{"index":{}}
{"id":1}
{"index":{}}
{"id":2}

# Query that forces coordinator→data communication
POST /transport-bench/_search
{
  "size": 0,  # No fetch phase
  "aggs": {
    "dummy": {
      "terms": {
        "field": "id",
        "size": 10000  # Large enough to trigger streaming
      }
    }
  }
}
```

**Limitation**: Still executes aggregation logic, but minimal overhead.

## Test Variables

### Primary Variables

| Variable | Values | Notes |
|----------|--------|-------|
| **Transport** | netty4, flight | Baseline vs test |
| **Arenas** (flight only) | 2, 4, 8 | Match vCPU count (8) |
| **Max Direct Memory** | 4GB, 8GB | Flight only |
| **Concurrent Clients** | 5, 10, 20, 30, 40 | Limited by search pool (13 threads) |
| **Batch Count** | 10, 100, 1000 | Batches per shard |
| **Batch Size** | 1KB, 10KB, 100KB | Payload size |

### Fixed Configuration
- Search thread pool: 13 (default for 8 vCPU)
- Heap: 32GB
- Shards: 2 (one per data node)

## Metrics

### Primary Metrics (JMeter)
- **Throughput**: Requests/sec at coordinator
- **Latency**: End-to-end response time (min, max, avg, p90, p99)

### Transport Metrics (OpenSearch APIs)

**Flight Transport**:
```bash
GET /_flight/stats
# Focus on:
# - cluster_stats.server.calls.avg_duration (data node processing)
# - cluster_stats.server.batches.sent (throughput)
# - resources.arrow_peak_bytes (memory usage)
# - status.server.* (error rates)
```

**Netty4 Transport**:
```bash
GET /_nodes/stats/transport
# Focus on:
# - transport.server_open (connections)
# - transport.tx_size / transport.rx_size (data transferred)
```

**Thread Pool Saturation**:
```bash
GET /_cat/thread_pool/search?v&h=node_name,active,queue,rejected
# Monitor search pool saturation
```

## JMeter Test Plan

### Thread Group Configuration

```
Thread Group
├── Number of Threads: ${CLIENTS}  # 5, 10, 20, 30, 40
├── Ramp-Up Period: 30s
├── Loop Count: Infinite
└── Duration: 600s (10 min)

HTTP Request
├── Server: coordinator-ip
├── Port: 9200
├── Method: POST
├── Path: /_noop_stream (or /transport-bench/_search)
└── Body: {"batch_count": ${BATCHES}, "batch_size": ${SIZE}}

Listeners
├── Summary Report (TPS, latency)
├── Response Time Graph
└── Aggregate Report (percentiles)
```

### Test Execution Script

```bash
#!/bin/bash

COORDINATOR="coordinator-ip:9200"
JMETER_HOME="/path/to/jmeter"
RESULTS_DIR="./results"

TRANSPORTS=("netty4" "flight")
CLIENTS=(5 10 20 30 40)
BATCHES=(10 100 1000)
SIZES=(1024 10240 102400)  # 1KB, 10KB, 100KB

for transport in "${TRANSPORTS[@]}"; do
  echo "=== Testing $transport transport ==="
  
  # Reconfigure cluster for transport
  ./configure-cluster.sh $transport
  sleep 60  # Wait for cluster restart
  
  for clients in "${CLIENTS[@]}"; do
    for batches in "${BATCHES[@]}"; do
      for size in "${SIZES[@]}"; do
        
        echo "Running: clients=$clients batches=$batches size=$size"
        
        # Run JMeter test
        $JMETER_HOME/bin/jmeter -n -t transport-bench.jmx \
          -JCLIENTS=$clients \
          -JBATCHES=$batches \
          -JSIZE=$size \
          -l "$RESULTS_DIR/${transport}_${clients}_${batches}_${size}.jtl"
        
        # Collect OpenSearch metrics
        curl -s "$COORDINATOR/_flight/stats" > "$RESULTS_DIR/${transport}_${clients}_${batches}_${size}_flight.json"
        curl -s "$COORDINATOR/_nodes/stats/transport,thread_pool" > "$RESULTS_DIR/${transport}_${clients}_${batches}_${size}_nodes.json"
        
        sleep 30  # Cool down between tests
      done
    done
  done
done
```

## Configuration Templates

### Netty4 (opensearch.yml)
```yaml
cluster.name: transport-benchmark
node.roles: [cluster_manager, data]  # or just [data] for data nodes

# Keep defaults
thread_pool.search.size: 13
```

### Flight (opensearch.yml)
```yaml
cluster.name: transport-benchmark
node.roles: [cluster_manager, data]

opensearch.experimental.feature.transport.stream.enabled: true
thread_pool.search.size: 13
```

### Flight JVM Options (jvm.options)
```
-Xms32g
-Xmx32g
-XX:MaxDirectMemorySize=8g

# Arrow/Netty
-Dio.netty.allocator.numDirectArenas=4
-Dio.netty.noUnsafe=false
-Dio.netty.tryUnsafe=true
--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED

# GC logging
-Xlog:gc*:file=logs/gc.log:time,uptime:filecount=5,filesize=100m
```

## Simplified Test Matrix

Focus on configurations that matter:

| Priority | Transport | Arenas | Clients | Batches | Size | Replicates |
|----------|-----------|--------|---------|---------|------|------------|
| P0 | netty4 | N/A | 10 | 100 | 10KB | 3 |
| P0 | flight | 4 | 10 | 100 | 10KB | 3 |
| P1 | netty4 | N/A | 5,20,30 | 100 | 10KB | 3 |
| P1 | flight | 4 | 5,20,30 | 100 | 10KB | 3 |
| P2 | flight | 2,8 | 10 | 100 | 10KB | 3 |
| P3 | both | optimal | 10 | 10,1000 | 1KB,100KB | 3 |

**Total tests**: ~50 runs (~8-10 hours)

## Analysis

### Key Comparisons

1. **Throughput at saturation**: Max TPS before thread pool saturates
2. **Latency under load**: P90/P99 at 20-30 concurrent clients
3. **Memory efficiency**: `arrow_peak_bytes` vs throughput
4. **Scalability**: TPS increase from 5→30 clients

### Expected Results

**Flight advantages**:
- Higher throughput for large batch counts (100-1000)
- Lower P99 latency (less GC pressure from streaming)
- Better memory efficiency (Arrow columnar format)

**Netty4 advantages**:
- Lower overhead for small batches (10)
- Simpler configuration
- More predictable behavior

### Success Criteria

Flight transport is successful if:
- **Throughput**: ≥20% improvement for batch_count=1000
- **P99 Latency**: ≤10% increase (or improvement)
- **Memory**: No OOM errors at peak load
- **Stability**: No connection errors over 10-min runs
