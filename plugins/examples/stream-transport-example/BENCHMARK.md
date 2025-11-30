# Transport Benchmark API

REST API for comparing Flight (stream) vs Netty4 (regular) transport performance.

## Quick Start

```bash
# Run integration tests
./gradlew :example-plugins:stream-transport-example:internalClusterTest --tests "org.opensearch.example.stream.BenchmarkStreamIT"

# Test Flight transport
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=10&use_stream_transport=true"

# Test Netty4 transport
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=10&use_stream_transport=false"
```

## API Reference

**Endpoint**: `POST /_benchmark/stream`

**Parameters**:
- `rows` (int, default: 100) - Rows per request
- `columns` (int, default: 10) - Columns per row
- `avg_column_length` (int, default: 100) - Bytes per column
- `parallel_requests` (int, default: 1) - Max concurrent requests at once
- `total_requests` (int, default: 0) - Total requests to send (0 = use parallel_requests only)
- `use_stream_transport` (boolean, default: true) - Flight vs Netty4
- `thread_pool` (string, default: "benchmark") - Thread pool name
- `batch_size` (int, default: 100) - Rows per batch for stream transport
- `target_tps` (int, default: 0) - Rate limit in requests/sec (0 = unlimited, **not implemented**)

**Response**:
```json
{
  "total_rows": 1000,
  "total_bytes": 1000000,
  "total_size": "976.6kb",
  "duration_ms": 523,
  "throughput_rows_per_sec": "1912.16",
  "throughput_mb_per_sec": "1.82",
  "latency_ms": {
    "min": 45,
    "max": 156,
    "avg": 89,
    "p90": 134,
    "p99": 152
  },
  "parallel_requests": 10,
  "used_stream_transport": true
}
```

## How It Works

1. **Coordinator** receives REST request
2. **Sends requests** to data nodes (round-robin):
   - If `total_requests` specified: sends that many requests with max `parallel_requests` concurrent
   - Otherwise: sends `parallel_requests` concurrent requests
3. **Data nodes** generate synthetic data (non-compressible pattern):
   - **Flight**: Sends in batches (configurable via `batch_size`, default 100 rows/batch) using `sendResponseBatch()` + `completeStream()`
   - **Netty4**: Sends all rows at once using `sendResponse()`
4. **Coordinator** aggregates results and calculates metrics
5. **Returns** statistics (throughput, latency percentiles)

## Usage Examples

### Compare Transports

```bash
# Flight transport
curl -s -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=10&use_stream_transport=true" \
  | jq '{transport: "flight", throughput_mb_per_sec, p99: .latency_ms.p99}'

# Netty4 transport
curl -s -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=10&use_stream_transport=false" \
  | jq '{transport: "netty4", throughput_mb_per_sec, p99: .latency_ms.p99}'
```

### High Concurrency Test

```bash
# Send 10,000 total requests with max 100 concurrent
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=100&total_requests=10000&use_stream_transport=true"

# Send 1,000 requests with max 500 concurrent
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=500&total_requests=1000&use_stream_transport=true"
```

### Find Optimal Concurrency

```bash
for parallel in 1 5 10 20 30; do
  echo "parallel=$parallel"
  curl -s -X POST "localhost:9200/_benchmark/stream?rows=100&parallel_requests=$parallel" \
    | jq '{throughput_rows_per_sec, p99: .latency_ms.p99}'
done
```

### Test Large Payloads

```bash
# 10K rows × 50 columns × 1KB = ~500MB
curl -X POST "localhost:9200/_benchmark/stream?rows=10000&columns=50&avg_column_length=1024&parallel_requests=5"
```

### Test Different Batch Sizes

```bash
# Small batches (10 rows/batch)
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&batch_size=10&use_stream_transport=true"

# Large batches (500 rows/batch)
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&batch_size=500&use_stream_transport=true"

# Compare batch sizes
for batch in 10 50 100 200 500; do
  echo "batch_size=$batch"
  curl -s -X POST "localhost:9200/_benchmark/stream?rows=1000&batch_size=$batch" \
    | jq '{batch_size: '$batch', throughput_mb_per_sec, p99: .latency_ms.p99}'
done
```

### Monitor Thread Pool

```bash
curl -s "localhost:9200/_cat/thread_pool/benchmark?v&h=name,active,queue,rejected"
```

## Architecture

### Custom Thread Pool

The plugin creates a dedicated "benchmark" thread pool:
- **Size**: 10× number of processors (minimum 100 threads)
- **Queue**: 10,000 requests
- **Purpose**: Handle high concurrency (1000s of parallel requests) without affecting other operations

### Transport Actions

**TransportBenchmarkStreamAction** (coordinator):
- Receives REST requests
- Sends parallel requests to data nodes
- Aggregates responses and calculates metrics

**Shard-level handlers** (data nodes):
- **Flight handler**: Streams data in batches (configurable via `batch_size` parameter)
- **Netty4 handler**: Sends all data at once

## Performance Tuning

### Batch Size Guidelines

**Small Batches (10-50 rows)**:
- ✅ Lower latency per batch
- ✅ More responsive to cancellation
- ❌ Higher network overhead
- ❌ More CPU for serialization
- **Use case**: Real-time applications, interactive queries

**Medium Batches (100-200 rows)** - Default:
- ✅ Balanced latency/throughput
- ✅ Good for most workloads
- **Use case**: General purpose benchmarking

**Large Batches (500-1000 rows)**:
- ✅ Higher throughput
- ✅ Lower CPU overhead
- ❌ Higher latency per batch
- ❌ More memory per batch
- **Use case**: Bulk data transfer, analytics

### Finding Optimal Settings

```bash
# Test matrix: batch_size × parallel_requests
for batch in 50 100 200 500; do
  for parallel in 5 10 20; do
    echo "Testing batch=$batch, parallel=$parallel"
    curl -s -X POST "localhost:9200/_benchmark/stream?rows=1000&batch_size=$batch&parallel_requests=$parallel" \
      | jq '{batch_size: '$batch', parallel: '$parallel', throughput_mb_per_sec, p99: .latency_ms.p99}'
  done
done
```

## Expected Results

On r5.2xlarge (8 vCPU, 32GB heap):

**Flight Transport**:
- Throughput: 50-100 MB/s
- P99 Latency: 50-150ms (at 20-30 parallel requests)
- Memory: Direct memory (Arrow allocator)
- Batch size impact: 500 rows/batch ~20% faster than 50 rows/batch

**Netty4 Transport**:
- Throughput: 40-80 MB/s
- P99 Latency: 60-200ms (higher variance)
- Memory: Heap memory
- No batching (sends all data at once)

**Key Differences**:
- Flight: 20-30% better throughput for large payloads
- Flight: Lower P99 latency under high concurrency
- Flight: Uses configurable batching for streaming
- Flight: Better memory efficiency with large datasets

## Troubleshooting

### Stream Transport Not Working

**Error**: `used_stream_transport: false` even when `use_stream_transport=true`

**Solution**: Ensure arrow-flight-rpc plugin is installed and feature flag is enabled:
```yaml
# opensearch.yml
opensearch.experimental.feature.transport.stream.enabled: true
```

### High Latency

**Symptoms**: P99 latency > 500ms

**Possible causes**:
- Too many parallel requests (thread pool saturation)
- Batch size too large (memory pressure)
- GC pauses (check with `-prof gc`)

**Solutions**:
```bash
# Reduce parallel requests
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=5"

# Reduce batch size
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&batch_size=50"

# Check thread pool stats
curl "localhost:9200/_cat/thread_pool/benchmark?v"
```

### Low Throughput

**Symptoms**: < 10 MB/s on modern hardware

**Possible causes**:
- Batch size too small (network overhead)
- Not enough parallel requests
- Single node cluster (no parallelism)

**Solutions**:
```bash
# Increase batch size
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&batch_size=500"

# Increase parallel requests
curl -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=20"
```

## Installation

### Integration Tests (Recommended)

```bash
./gradlew :example-plugins:stream-transport-example:internalClusterTest --tests "org.opensearch.example.stream.BenchmarkStreamIT"
```

### Manual Installation

```bash
# Build plugin
./gradlew :example-plugins:stream-transport-example:bundlePlugin -x missingJavadoc

# Install
bin/opensearch-plugin install file:///full/path/to/plugins/examples/stream-transport-example/build/distributions/stream-transport-example-3.4.0-SNAPSHOT.zip

# Enable stream transport in opensearch.yml
opensearch.experimental.feature.transport.stream.enabled: true

# Restart OpenSearch
```

## Parameters Explained

### `parallel_requests`
Maximum number of concurrent requests at once:
- Controls concurrency level
- Thread pool must be large enough to handle this many concurrent requests
- Default: 1

### `total_requests`
Total number of requests to send:
- If set to 0 (default): sends only `parallel_requests` concurrent requests
- If > 0: sends this many total requests, maintaining max `parallel_requests` concurrent
- Example: `parallel_requests=100&total_requests=10000` sends 10K requests with max 100 concurrent
- Use for sustained load testing

### `target_tps` (Not Implemented)
This parameter is defined but **not currently implemented**. If implemented, it would:
- Throttle requests to a specific rate (e.g., 100 requests/sec)
- Enable controlled load testing
- Simulate production traffic patterns

**Current behavior**: Setting this parameter has no effect (always runs at maximum speed).

### `batch_size` (Stream Transport Only)
Controls how many rows are sent in each batch when using stream transport:
- **Only applies when** `use_stream_transport=true`
- **Ignored** when using regular Netty4 transport (sends all rows at once)
- **Trade-off**: Smaller batches = lower latency, larger batches = higher throughput
- **Validation**: Must be > 0

### `thread_pool`
Specifies which thread pool executes the benchmark:
- **Default**: `"benchmark"` (custom pool with 10× CPU cores, min 100 threads)
- **Alternatives**: `"generic"`, `"search"`, `"write"`, etc.
- **Purpose**: Test thread pool behavior under load

## Limitations

- Synthetic data only (no actual computation)
- Measures transport + serialization overhead
- Requires arrow-flight-rpc plugin for stream transport
- Node-to-node only (not REST client → coordinator)
- `target_tps` parameter not implemented (placeholder for future rate limiting)
- Data uses non-compressible pattern to prevent Flight compression optimization
