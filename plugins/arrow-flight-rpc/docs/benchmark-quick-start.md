# Benchmark Quick Start Guide

## Setup (5 minutes)

### 1. Build and Install Plugin

```bash
# From OpenSearch root
./gradlew :plugins:examples:stream-transport-example:assemble

# Install on all nodes (coordinator + 2 data nodes)
bin/opensearch-plugin install file:///full/path/to/stream-transport-example.zip
```

### 2. Configure Cluster

**For netty4 baseline** (opensearch.yml):
```yaml
cluster.name: transport-benchmark
node.name: node-1  # node-2, node-3
network.host: 0.0.0.0
```

**For flight transport** (opensearch.yml):
```yaml
cluster.name: transport-benchmark
node.name: node-1
network.host: 0.0.0.0
opensearch.experimental.feature.transport.stream.enabled: true
```

**Flight JVM options** (config/jvm.options):
```
-Xms32g
-Xmx32g
-XX:MaxDirectMemorySize=8g
-Dio.netty.allocator.numDirectArenas=4
-Dio.netty.noUnsafe=false
-Dio.netty.tryUnsafe=true
--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED
```

### 3. Start Cluster

```bash
bin/opensearch
```

## Quick Tests

### Single Request Test

```bash
# Stream transport
curl -X POST "localhost:9200/_benchmark/stream?rows=100&columns=10&parallel_requests=1"

# Output:
{
  "total_rows": 100,
  "total_bytes": 100000,
  "duration_ms": 45,
  "throughput_rows_per_sec": "2222.22",
  "throughput_mb_per_sec": "2.12",
  "latency_ms": {
    "min": 45,
    "max": 45,
    "avg": 45,
    "p90": 45,
    "p99": 45
  },
  "parallel_requests": 1,
  "used_stream_transport": true
}
```

### Compare Transports

```bash
# Stream (flight)
curl -s -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=10&use_stream_transport=true" | jq '{transport: "flight", throughput_mb_per_sec, p99: .latency_ms.p99}'

# Regular (netty4)
curl -s -X POST "localhost:9200/_benchmark/stream?rows=1000&parallel_requests=10&use_stream_transport=false" | jq '{transport: "netty4", throughput_mb_per_sec, p99: .latency_ms.p99}'
```

## Full Benchmark Script

```bash
#!/bin/bash

COORDINATOR="localhost:9200"
RESULTS="./results"
mkdir -p $RESULTS

echo "=== Transport Benchmark ==="

# Test configurations
ROWS=(100 1000 10000)
PARALLEL=(1 5 10 20 30)
TRANSPORTS=("true" "false")

for use_stream in "${TRANSPORTS[@]}"; do
  transport_name=$([ "$use_stream" = "true" ] && echo "flight" || echo "netty4")
  echo "Testing $transport_name transport..."
  
  for rows in "${ROWS[@]}"; do
    for parallel in "${PARALLEL[@]}"; do
      echo "  rows=$rows parallel=$parallel"
      
      # Run 3 replicates
      for rep in {1..3}; do
        curl -s -X POST "$COORDINATOR/_benchmark/stream?rows=$rows&columns=10&avg_column_length=100&parallel_requests=$parallel&use_stream_transport=$use_stream" \
          > "$RESULTS/${transport_name}_${rows}_${parallel}_${rep}.json"
        
        sleep 2
      done
    done
  done
  
  echo ""
done

echo "Results saved to $RESULTS/"
```

## Analyze Results

```bash
# Extract key metrics
for file in results/*.json; do
  echo "$file:"
  jq '{throughput_mb_per_sec, p99: .latency_ms.p99}' "$file"
done

# Compare flight vs netty4 for specific config
echo "Flight:"
jq -s 'map(.throughput_mb_per_sec | tonumber) | add / length' results/flight_1000_10_*.json

echo "Netty4:"
jq -s 'map(.throughput_mb_per_sec | tonumber) | add / length' results/netty4_1000_10_*.json
```

## Monitor During Test

### Terminal 1: Run benchmark
```bash
./benchmark.sh
```

### Terminal 2: Monitor flight metrics
```bash
watch -n 2 'curl -s "localhost:9200/_flight/stats" | jq ".cluster_stats.server | {calls: .calls.completed, avg_duration: .calls.avg_duration, arrow_peak: .resources.arrow_peak}"'
```

### Terminal 3: Monitor thread pools
```bash
watch -n 2 'curl -s "localhost:9200/_cat/thread_pool/generic,search?v&h=name,active,queue,rejected"'
```

## JMeter Test Plan

### 1. Create Test Plan

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2">
  <hashTree>
    <TestPlan>
      <stringProp name="TestPlan.comments">Transport Benchmark</stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup>
        <stringProp name="ThreadGroup.num_threads">${__P(threads,10)}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">30</stringProp>
        <stringProp name="ThreadGroup.duration">600</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy>
          <stringProp name="HTTPSampler.domain">localhost</stringProp>
          <stringProp name="HTTPSampler.port">9200</stringProp>
          <stringProp name="HTTPSampler.path">/_benchmark/stream</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <elementProp name="HTTPsampler.Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="rows" elementType="HTTPArgument">
                <stringProp name="Argument.value">${__P(rows,1000)}</stringProp>
              </elementProp>
              <elementProp name="parallel_requests" elementType="HTTPArgument">
                <stringProp name="Argument.value">${__P(parallel,10)}</stringProp>
              </elementProp>
              <elementProp name="use_stream_transport" elementType="HTTPArgument">
                <stringProp name="Argument.value">${__P(use_stream,true)}</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
        </HTTPSamplerProxy>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

### 2. Run JMeter

```bash
# Flight transport
jmeter -n -t transport-bench.jmx \
  -Jthreads=10 \
  -Jrows=1000 \
  -Jparallel=10 \
  -Juse_stream=true \
  -l results/flight_jmeter.jtl

# Netty4 transport
jmeter -n -t transport-bench.jmx \
  -Jthreads=10 \
  -Jrows=1000 \
  -Jparallel=10 \
  -Juse_stream=false \
  -l results/netty4_jmeter.jtl
```

## Expected Results (r5.2xlarge)

### Flight Transport
- **Throughput**: 50-100 MB/s (depends on payload size)
- **P99 Latency**: 50-150ms (at 20-30 parallel requests)
- **Arrow Memory**: Peak 100-500MB (depends on batch size)

### Netty4 Transport
- **Throughput**: 40-80 MB/s
- **P99 Latency**: 60-200ms (higher variance under load)
- **Heap Pressure**: More GC activity for large payloads

### Key Differences
- Flight should show **20-30% better throughput** for large payloads (rows > 1000)
- Flight should have **lower P99 latency** under high concurrency (parallel > 20)
- Flight uses **direct memory** (monitor with `/_flight/stats`)
- Netty4 uses **heap memory** (monitor with `/_nodes/stats/jvm`)

## Troubleshooting

### Low Throughput
```bash
# Check thread pool saturation
curl "localhost:9200/_cat/thread_pool/generic?v&h=active,queue,rejected"

# If queue/rejected > 0, increase thread pool or reduce parallel_requests
```

### High Latency
```bash
# Check GC activity
tail -f logs/gc.log | grep "Pause"

# Check network latency between nodes
ping <data-node-ip>
```

### Flight Transport Errors
```bash
# Check Arrow memory
curl -s "localhost:9200/_flight/stats" | jq '.nodes[].flight_metrics.resources | {arrow_allocated, arrow_peak, direct_memory}'

# If arrow_peak close to MaxDirectMemorySize, increase -XX:MaxDirectMemorySize
```

### Connection Errors
```bash
# Check active channels
curl -s "localhost:9200/_flight/stats" | jq '.nodes[].flight_metrics.resources | {client_channels_active, server_channels_active}'

# Check for errors in logs
grep -i "error\|exception" logs/opensearch.log | tail -20
```

## Next Steps

1. Run baseline tests with both transports
2. Vary `rows`, `parallel_requests`, and `avg_column_length`
3. Test with different `thread_pool` settings (generic vs search)
4. Monitor Arrow memory usage and GC activity
5. Compare results and document findings
