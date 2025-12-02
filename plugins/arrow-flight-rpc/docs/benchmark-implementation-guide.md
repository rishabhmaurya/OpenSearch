# Benchmark Implementation Guide

## Quick Start

### 1. Setup Noop Plugin

```bash
# Build and install noop plugin on all nodes
./gradlew :client:client-benchmark-noop-api-plugin:assemble
bin/opensearch-plugin install file:///full/path/to/client-benchmark-noop-api-plugin.zip
```

### 2. Create Benchmark Script

```bash
# benchmark-transport.sh
#!/bin/bash

COORDINATOR="localhost:9200"
RESULTS_DIR="./benchmark-results"
mkdir -p $RESULTS_DIR

# Test configurations
TRANSPORTS=("netty4" "flight")
CLIENTS=(1 5 10 20 50)
RESULT_SIZES=(100 1000 5000)
ARENAS=(1 2 4 8)

# Run benchmark
for transport in "${TRANSPORTS[@]}"; do
  for clients in "${CLIENTS[@]}"; do
    for size in "${RESULT_SIZES[@]}"; do
      echo "Testing: transport=$transport clients=$clients size=$size"
      
      # Run test
      ./run-benchmark.sh $transport $clients $size
      
      # Collect metrics
      curl -s "$COORDINATOR/_flight/stats" > "$RESULTS_DIR/${transport}_${clients}_${size}_flight.json"
      curl -s "$COORDINATOR/_nodes/stats" > "$RESULTS_DIR/${transport}_${clients}_${size}_nodes.json"
      
      sleep 5
    done
  done
done
```

### 3. Minimal Benchmark Client

```java
// TransportBenchmarkClient.java
public class TransportBenchmarkClient {
    
    private final RestClient client;
    private final int concurrency;
    private final int iterations;
    
    public BenchmarkResult runSearchBenchmark(int resultSize) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(iterations);
        
        List<Long> latencies = new CopyOnWriteArrayList<>();
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            executor.submit(() -> {
                try {
                    long reqStart = System.nanoTime();
                    
                    // Use noop search endpoint
                    Request request = new Request("POST", "/_noop_search");
                    request.setJsonEntity(String.format(
                        "{\"size\": %d, \"query\": {\"match_all\": {}}}", 
                        resultSize
                    ));
                    
                    client.performRequest(request);
                    
                    long reqEnd = System.nanoTime();
                    latencies.add(reqEnd - reqStart);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long endTime = System.nanoTime();
        executor.shutdown();
        
        return calculateMetrics(latencies, startTime, endTime);
    }
    
    private BenchmarkResult calculateMetrics(List<Long> latencies, long start, long end) {
        Collections.sort(latencies);
        
        double totalSeconds = (end - start) / 1_000_000_000.0;
        double tps = iterations / totalSeconds;
        
        return new BenchmarkResult(
            tps,
            latencies.get(0) / 1_000_000.0,  // min (ms)
            latencies.get(latencies.size() - 1) / 1_000_000.0,  // max (ms)
            latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0,  // avg (ms)
            latencies.get((int)(latencies.size() * 0.90)) / 1_000_000.0  // p90 (ms)
        );
    }
}

class BenchmarkResult {
    final double tps;
    final double minLatency;
    final double maxLatency;
    final double avgLatency;
    final double p90Latency;
    
    // Constructor and toString()
}
```

## Metrics Collection

### Flight Transport Metrics

```bash
# Collect flight-specific metrics
curl -s "localhost:9200/_flight/stats" | jq '{
  arrow_memory: .nodes[].flight_metrics.resources.arrow_allocated,
  peak_memory: .nodes[].flight_metrics.resources.arrow_peak,
  client_calls: .cluster_stats.client.calls.completed,
  avg_duration: .cluster_stats.client.calls.avg_duration,
  throughput: .cluster_stats.client.batches.received
}'
```

### Netty4 Transport Metrics

```bash
# Collect netty4 metrics
curl -s "localhost:9200/_nodes/stats/transport,thread_pool" | jq '{
  transport_tx: .nodes[].transport.tx_size,
  transport_rx: .nodes[].transport.rx_size,
  search_threads: .nodes[].thread_pool.search,
  write_threads: .nodes[].thread_pool.write
}'
```

## Configuration Templates

### Netty4 Baseline (opensearch.yml)

```yaml
cluster.name: transport-benchmark
node.name: node-1
network.host: 0.0.0.0

# Thread pools
thread_pool.search.size: 16
thread_pool.write.size: 16

# JVM options (config/jvm.options)
-Xms4g
-Xmx4g
-XX:+UseConcMarkSweepGC
-verbose:gc
-XX:+PrintGCDetails
```

### Flight Transport (opensearch.yml)

```yaml
cluster.name: transport-benchmark
node.name: node-1
network.host: 0.0.0.0

# Enable flight transport
opensearch.experimental.feature.transport.stream.enabled: true

# Thread pools
thread_pool.search.size: 16
thread_pool.write.size: 16

# JVM options (config/jvm.options)
-Xms4g
-Xmx4g
-XX:MaxDirectMemorySize=2g
-XX:+UseConcMarkSweepGC
-verbose:gc
-XX:+PrintGCDetails

# Arrow/Netty options
-Dio.netty.allocator.numDirectArenas=4
-Dio.netty.noUnsafe=false
-Dio.netty.tryUnsafe=true
--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED
```

## Analysis Scripts

### Calculate Statistics

```python
# analyze_results.py
import json
import numpy as np
import matplotlib.pyplot as plt

def analyze_benchmark(results_file):
    with open(results_file) as f:
        data = json.load(f)
    
    latencies = data['latencies']
    
    stats = {
        'min': np.min(latencies),
        'max': np.max(latencies),
        'mean': np.mean(latencies),
        'p50': np.percentile(latencies, 50),
        'p90': np.percentile(latencies, 90),
        'p95': np.percentile(latencies, 95),
        'p99': np.percentile(latencies, 99),
        'tps': data['tps']
    }
    
    return stats

def plot_comparison(netty4_stats, flight_stats):
    metrics = ['min', 'p50', 'p90', 'p99', 'max']
    netty4_vals = [netty4_stats[m] for m in metrics]
    flight_vals = [flight_stats[m] for m in metrics]
    
    x = np.arange(len(metrics))
    width = 0.35
    
    fig, ax = plt.subplots()
    ax.bar(x - width/2, netty4_vals, width, label='Netty4')
    ax.bar(x + width/2, flight_vals, width, label='Flight')
    
    ax.set_ylabel('Latency (ms)')
    ax.set_title('Transport Latency Comparison')
    ax.set_xticks(x)
    ax.set_xticklabels(metrics)
    ax.legend()
    
    plt.savefig('latency_comparison.png')
```

## Test Execution Checklist

- [ ] Build and install noop plugin on all nodes
- [ ] Configure opensearch.yml for baseline (netty4)
- [ ] Restart cluster and verify health
- [ ] Run warmup phase (5 min)
- [ ] Run measurement phase (10 min, 3 replicates)
- [ ] Collect metrics and logs
- [ ] Configure opensearch.yml for flight transport
- [ ] Restart cluster and verify health
- [ ] Run warmup phase (5 min)
- [ ] Run measurement phase (10 min, 3 replicates)
- [ ] Collect metrics and logs
- [ ] Repeat for each configuration in test matrix
- [ ] Analyze results and generate report

## Troubleshooting

### High Latency Variance
- Check GC logs for pause times
- Verify no CPU throttling
- Check network latency between nodes

### Memory Issues (Flight)
- Monitor `arrow_peak_bytes` in `/_flight/stats`
- Increase `-XX:MaxDirectMemorySize`
- Reduce `numDirectArenas` if contention is low

### Low Throughput
- Check thread pool saturation in `/_cat/thread_pool`
- Verify noop plugin is installed correctly
- Check for errors in opensearch.log

## Expected Timeline

- Setup and configuration: 2-4 hours
- Baseline testing (netty4): 4-6 hours
- Flight transport testing: 8-12 hours (multiple configurations)
- Analysis and reporting: 4-8 hours
- **Total**: 2-3 days
