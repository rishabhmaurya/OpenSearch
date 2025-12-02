# Load Testing Methodology: Netty4 vs Flight Transport

## Overview

This document outlines the methodology for comparing OpenSearch's default transport-netty4 with the arrow-flight-rpc streaming transport under various load conditions.

## Test Environment

### Cluster Configuration
- **Topology**: 3-node cluster (2 data nodes + 1 coordinator node)
- **Test Execution**: Run benchmarks from coordinator node
- **Network**: Ensure consistent network conditions between nodes

### Hardware Requirements
- Document CPU, RAM, disk specs for each node
- Pin CPU cores to avoid frequency scaling (`cpufreq-set` with `performance` governor on Linux)
- Disable unnecessary background processes

## Variables Under Test

### 1. Arrow Memory Configuration (Flight Transport Only)

| Variable | Description | Test Values |
|----------|-------------|-------------|
| `io.netty.allocator.numDirectArenas` | Number of direct memory arenas | 1, 2, 4, 8 |
| Arrow allocator limit | Max direct memory per allocator | 512MB, 1GB, 2GB, 4GB |
| `-XX:MaxDirectMemorySize` | JVM direct memory limit | 2GB, 4GB, 8GB |

### 2. Thread Pool Configuration

| Variable | Description | Test Values |
|----------|-------------|-------------|
| `thread_pool.search.size` | Search thread pool size | 2x cores, 4x cores, 8x cores |
| `thread_pool.write.size` | Write thread pool size | 2x cores, 4x cores, 8x cores |

### 3. Workload Parameters

| Variable | Description | Test Values |
|----------|-------------|-------------|
| Concurrent clients | Number of parallel requests | 1, 5, 10, 20, 50, 100 |
| Request size | Payload size | 1KB, 10KB, 100KB, 1MB |
| Result set size | Number of documents returned | 10, 100, 1000, 10000 |

## Performance Metrics

### Primary Metrics

1. **Throughput (TPS)**
   - Maximum sustainable TPS before degradation
   - TPS at various concurrency levels
   - Measure via: `GET /_flight/stats` (flight) or `GET /_nodes/stats` (netty4)

2. **Latency Distribution**
   - Min, Max, Mean, P50, P90, P95, P99
   - Measure end-to-end request latency
   - Track both client-side and server-side latency

3. **Resource Utilization**
   - CPU usage per node
   - Memory usage (heap + direct)
   - GC frequency and pause times
   - Network bandwidth

### Secondary Metrics (Flight Transport)

From `GET /_flight/stats`:
- `arrow_allocated_bytes` / `arrow_peak_bytes`
- `client_calls.duration` (min/max/avg)
- `server_calls.duration` (min/max/avg)
- `client_batches.processing_time`
- `server_batches.processing_time`
- `status.client.*` / `status.server.*` (error rates)
- Thread pool utilization

## Test Scenarios

### Scenario 1: Streaming Search (Flight's Strength)

**Purpose**: Test server-side streaming with large result sets

**Setup**:
```bash
# Index 1M documents
PUT /test-index
{
  "settings": {
    "number_of_shards": 2,
    "number_of_replicas": 0
  }
}

# Bulk index documents (1KB each)
```

**Test**:
```bash
# Query returning 1000-10000 documents
POST /test-index/_search
{
  "size": 10000,
  "query": { "match_all": {} }
}
```

**Variables**:
- Result set size: 100, 1000, 5000, 10000 docs
- Concurrent clients: 1, 5, 10, 20, 50
- Arena count (flight): 1, 2, 4, 8

### Scenario 2: High Concurrency Small Requests

**Purpose**: Test allocator contention and thread pool efficiency

**Setup**: Same index as Scenario 1

**Test**:
```bash
# Small queries (10 results)
POST /test-index/_search
{
  "size": 10,
  "query": { "term": { "field": "value" } }
}
```

**Variables**:
- Concurrent clients: 10, 20, 50, 100, 200
- Thread pool size: 2x, 4x, 8x cores
- Arena count (flight): 1, 2, 4, 8

### Scenario 3: Memory Pressure

**Purpose**: Test behavior under memory constraints

**Setup**:
- Limit JVM heap: `-Xmx2g`
- Limit direct memory: `-XX:MaxDirectMemorySize=1g`

**Test**: Run Scenario 1 with increasing load until memory exhaustion

**Metrics**:
- GC frequency and pause times
- `arrow_peak_bytes` (flight)
- Error rates (OOM, RESOURCE_EXHAUSTED)
- Throughput degradation point

### Scenario 4: Sustained Load

**Purpose**: Test stability over time

**Test**: Run constant load (50% of max TPS) for 1 hour

**Metrics**:
- Latency stability over time
- Memory leak detection
- Connection/channel lifecycle
- Error rate trends

## Benchmark Implementation

### Option 1: Custom Microbenchmark (Recommended)

Create a dedicated benchmark similar to `client-benchmark-noop-api-plugin`:

```
plugins/transport-benchmark/
├── src/main/java/
│   └── org/opensearch/benchmark/transport/
│       ├── TransportBenchmark.java
│       ├── StreamingSearchBenchmark.java
│       └── metrics/MetricsCollector.java
└── build.gradle
```

**Key Features**:
- Use noop endpoints to isolate transport performance
- Implement JMH-style warmup and measurement phases
- Collect metrics via `/_flight/stats` and `/_nodes/stats`
- Output results in CSV/JSON for analysis

### Option 2: Extend Existing Benchmarks

Modify `client/benchmark` to support:
- Flight transport testing
- Streaming search operations
- Configurable arena/thread pool settings

## Execution Protocol

### 1. Pre-Test Setup

```bash
# Build noop plugin
./gradlew :client:client-benchmark-noop-api-plugin:assemble

# Install on all nodes
bin/opensearch-plugin install file:///path/to/noop-plugin.zip

# Configure test environment
# opensearch.yml (netty4 baseline)
cluster.name: transport-benchmark
node.name: node-1
network.host: 0.0.0.0

# opensearch.yml (flight transport)
opensearch.experimental.feature.transport.stream.enabled: true
# Add JVM options for flight
```

### 2. Warmup Phase

- Run 5-10 minutes of load to stabilize JIT compilation
- Allow GC to reach steady state
- Verify no errors in logs

### 3. Measurement Phase

For each configuration:
1. Restart cluster with specific settings
2. Run warmup (5 min)
3. Run measurement (10 min minimum)
4. Collect metrics
5. Wait 2 min between runs
6. Repeat 3 times for statistical significance

### 4. Data Collection

```bash
# During test, collect metrics every 10s
while true; do
  curl -s "localhost:9200/_flight/stats" >> flight_metrics.jsonl
  curl -s "localhost:9200/_nodes/stats/jvm,os,thread_pool" >> node_metrics.jsonl
  sleep 10
done
```

## Analysis and Reporting

### Statistical Analysis

- Use box plots for latency distributions (like jemalloc paper)
- Show median + whiskers for min/max
- Calculate statistical significance (t-test, p < 0.05)
- Normalize results for comparison graphs

### Key Comparisons

1. **Scalability**: TPS vs concurrent clients (linear scaling expected)
2. **Latency**: P90/P99 latency across configurations
3. **Memory Efficiency**: Peak memory vs throughput
4. **Stability**: Latency variance over time

### Report Structure

```
1. Executive Summary
   - Key findings
   - Recommended configurations

2. Methodology
   - Test environment
   - Benchmark descriptions
   - Measurement approach

3. Results
   - Throughput comparison
   - Latency distribution
   - Resource utilization
   - Scalability analysis

4. Configuration Recommendations
   - Optimal arena count
   - Thread pool sizing
   - Memory limits

5. Limitations and Future Work
```

## Configuration Matrix

Test the following combinations (prioritize based on time):

| Priority | Transport | Arenas | Max Direct Mem | Thread Pool | Clients | Result Size |
|----------|-----------|--------|----------------|-------------|---------|-------------|
| P0 | netty4 | N/A | N/A | 4x cores | 1,10,50 | 100,1000 |
| P0 | flight | 4 | 2GB | 4x cores | 1,10,50 | 100,1000 |
| P1 | flight | 1,2,8 | 2GB | 4x cores | 10,50 | 1000 |
| P1 | flight | 4 | 1GB,4GB | 4x cores | 10,50 | 1000 |
| P2 | flight | 4 | 2GB | 2x,8x cores | 10,50 | 1000 |
| P2 | both | optimal | optimal | optimal | 100,200 | 10000 |

## Expected Outcomes

### Flight Transport Advantages
- Better throughput for large result sets (streaming)
- Lower P99 latency under high concurrency
- Better memory efficiency with proper arena configuration

### Netty4 Advantages
- Lower overhead for small requests
- Simpler configuration
- More mature/stable

### Optimal Flight Configuration (Hypothesis)
- Arenas: 4 (matches 4-processor system from jemalloc paper)
- Max direct memory: 2-4GB
- Thread pool: 4x cores for search operations
