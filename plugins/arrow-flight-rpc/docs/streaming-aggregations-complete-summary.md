# Streaming Aggregations Analysis - Complete Summary

## Executive Summary

This document summarizes the complete analysis of streaming aggregations for OpenSearch, including use cases, current implementation details, and opportunities for improvement.

## Key Documents Created

1. **streaming-aggregation-use-cases.md** - Comprehensive analysis of where streaming helps/hurts
2. **concurrent-segment-search-aggregation-analysis-corrected.md** - Detailed flow analysis with ordinals clarification

## Critical Discovery: Global vs Segment Ordinals

### The Truth About Current Implementation

**Standard GlobalOrdinalsStringTermsAggregator uses GLOBAL ORDINALS:**
- Code: `valuesSource.globalOrdinalsValues(ctx)` (line 147)
- Global ordinals are built ONCE per shard across ALL segments
- Building cost: 200-400ms for high cardinality (1M unique terms)
- This cost happens BEFORE concurrent segment search begins
- Memory: 8-16 MB for 1M unique terms

**LowCardinality variant uses SEGMENT ORDINALS:**
- Code: `valuesSource.ordinalsValues(ctx)` (line 1009)
- Only used for low cardinality fields without sub-aggregations
- Segment ordinals are free (already exist)
- Requires mapping to global ordinals after collection

### Performance Impact

```
Current with Global Ordinals (uncached):
├─ Global ordinals building: 200ms  ← HIDDEN COST!
├─ Collection: 140ms
├─ Slice-level TopN: 320ms  ← BOTTLENECK
└─ Shard reduce: 40ms
Total: 700ms

Current with Global Ordinals (cached):
├─ Collection: 140ms
├─ Slice-level TopN: 320ms  ← BOTTLENECK
└─ Shard reduce: 40ms
Total: 500ms

Streaming with Segment Ordinals:
├─ Collection: 50ms (segment ords are free)
├─ Streaming: 160ms (incremental)
└─ Coordinator: 20ms
Total: 230ms (67% faster than uncached, 54% faster than cached!)
```

## Current Concurrent Segment Search Flow

### Phase 0: Global Ordinals Building
- Happens BEFORE any slice execution
- Scans all segments to build unified ordinal space
- 200-400ms for high cardinality
- Can be cached but often isn't

### Phase 1: Collection (Per Slice, Parallel)
- Each slice uses pre-built global ordinals
- Collects documents and increments bucket counts
- Fast because ordinals are already global

### Phase 2: Slice-Level TopN (Per Slice)
- **BOTTLENECK #1**: 64% of query time
- Each slice independently:
  1. Iterates all buckets
  2. Materializes keys (global ord → BytesRef)
  3. Builds priority queue for TopN selection
  4. Keeps only shard_size buckets (e.g., 10K)

### Phase 3: Shard-Level Reduce
- **BOTTLENECK #2**: Redundant work
- Merges slice results
- Performs SECOND TopN computation
- Returns TopN to coordinator

### Phase 4: Coordinator Reduce
- Merges shard results
- Performs THIRD TopN computation
- Returns final results

## Streaming Aggregation Use Cases

### Perfect Candidates (Implement First)

1. **Cardinality/Distinct Count Aggregations**
   - HyperLogLog sketches support incremental updates
   - 70-80% memory reduction
   - 10-20% latency improvement
   - Minimal coordinator overhead

2. **Concurrent Segment Search Integration**
   - Natural fit with slice-based execution
   - Eliminates slice-level TopN (saves 320ms)
   - 40-60% memory reduction on data nodes
   - 15-25% overall latency improvement

3. **High Cardinality Terms + Cardinality Metrics**
   - Stream (term, HLL_update) pairs
   - 60-75% memory reduction
   - Coordinator handles all unique terms
   - Requires coordinator capacity planning

### Strong Candidates

4. **Percentile/Stats Aggregations**
   - TDigest/stats support incremental merging
   - 65-80% memory reduction
   - 15-25% latency improvement

5. **Date Histogram + Sub-Aggregations**
   - Parent aggregation provides natural partitioning
   - 45-60% memory reduction
   - 10-20% latency improvement

### Conditional Candidates (Needs Query Planning)

6. **High shard_size Scenarios**
   - When shard_size >> size (e.g., 10K vs 100)
   - Sweet spot: 10K-100K cardinality
   - Problematic: > 1M cardinality (coordinator overwhelmed)
   - Solution: Hash-based streaming

7. **Multi-Term Aggregations**
   - High memory pressure on data nodes
   - Coordinator must handle Cartesian product
   - Needs cardinality estimation

### Not Recommended

8. **Low Cardinality (< 1K)**
   - Traditional approach already efficient
   - Streaming adds unnecessary overhead

9. **Simple Metrics (sum, avg, min, max)**
   - Already minimal state
   - No benefit from streaming

## Two Streaming Approaches

### Option 1: Stream All Buckets (Simple, Exact)

```java
// Stream everything per segment
for each segment:
  collect all buckets
  stream all buckets to coordinator
```

**Pros:**
- ✅ Exact accuracy guaranteed
- ✅ Simple implementation
- ✅ No risk of missing data

**Cons:**
- ❌ Very high network traffic
- ❌ High coordinator memory
- ❌ High coordinator CPU

**Performance:** 230ms (67% faster than uncached, 54% faster than cached)
**Network:** ~150 MB for 1M cardinality

### Option 2: Stream TopK with Retroactive Updates (Optimal)

```java
// Segment side: Keep summary, stream top K
class SegmentAggregator {
    private Map<String, Long> allBucketCounts; // ALL buckets
    
    public List<Bucket> selectToStream(int K) {
        return getTopK(allBucketCounts, K); // K = size × 3
    }
    
    public long getRetroactiveCount(String term) {
        return allBucketCounts.getOrDefault(term, 0L);
    }
}

// Coordinator: Request retroactive when needed
for each new term entering top K:
  if not seen before:
    request retroactive counts from previous segments
    backfill missing data
```

**Pros:**
- ✅ Exact accuracy (with retroactive)
- ✅ 80-90% network reduction vs stream all
- ✅ Retroactive requests are rare (5-10%)
- ✅ Adapts to data distribution

**Cons:**
- ⚠️ Requires segment summaries (280 KB per segment)
- ⚠️ More complex implementation
- ⚠️ Retroactive requests add latency (rare)

**Performance:** 240ms (66% faster than uncached, 52% faster than cached)
**Network:** ~30 MB for 1M cardinality (80% reduction!)

**Configuration:**
```
K = size × 3  // Stream top K per segment

Examples:
- size=10 → K=30
- size=100 → K=300  
- size=500 → K=1000 (cap at 1000)
```

**Why K = size × 3 works:**
- Catches terms that grow from rank 25 to top 10
- Handles most distribution skews
- Retroactive only needed for terms jumping from rank 100+ to top 10 (rare)

**The Missing Bucket Problem (Solved by Retroactive)**:
```
Without retroactive:
  "critical_error": rank 25 in segments 1-3 (not streamed)
  → Enters top 10 in segment 4
  → Lost 105 docs from segments 1-3 ❌

With retroactive:
  "critical_error": rank 25 in segments 1-3 (not streamed)
  → Enters top 10 in segment 4
  → Coordinator requests retroactive from segments 1-3
  → Backfills 105 docs ✅ EXACT!
```

## Query Planning Decision Matrix

| Cardinality | shard_size/size | Concurrent Search | Recommendation |
|-------------|-----------------|-------------------|----------------|
| < 1K | Any | Any | Traditional |
| 1K-10K | < 10x | No | Traditional |
| 1K-10K | < 10x | Yes | Streaming |
| 1K-10K | > 10x | Any | Streaming |
| 10K-100K | Any | Yes | Streaming |
| 10K-100K | > 10x | No | Streaming |
| 100K-1M | Any | Any | Streaming (monitor) |
| > 1M | Any | Any | Hash-based Streaming |

### Decision Factors

1. **Estimated Cardinality** (from index stats)
2. **shard_size / size ratio**
3. **Concurrent segment search enabled**
4. **Coordinator memory budget**
5. **Network bandwidth**
6. **Global ordinals cache status**

## Implementation Priorities

### Phase 1: Foundation (Immediate)
1. **Stream All Buckets** - Simple, validates infrastructure, exact accuracy
2. **Cardinality Aggregations** - Perfect streaming pattern, minimal risk

### Phase 2: Optimize Network (Near-term)  
3. **Stream TopK (K = size × 3)** - 80% network reduction
4. **Add Retroactive Updates** - Maintain exact accuracy
5. **Concurrent Segment Search Integration** - High impact, natural fit

### Phase 3: High-Value Extensions (Medium-term)
6. **Terms + Cardinality Metrics** - Combines streaming patterns
7. **Percentile/Stats Metrics** - Sketches support incremental merging
8. **Date Histogram + Sub-Aggs** - Natural partitioning

### Phase 4: Complex Scenarios (Long-term)
9. **High shard_size with Query Planning** - Intelligent decisions
10. **Multi-Term Aggregations** - Needs cardinality estimation
11. **Hash-based Streaming** - For extreme cardinality
12. **Adaptive Query Planning** - ML-based approach selection

## Key Code Locations

### Current Implementation
- **Concurrent execution**: `ConcurrentQueryPhaseSearcher.searchWithCollectorManager()`
- **Slice collection**: `GlobalOrdinalsStringTermsAggregator.getLeafCollector()`
- **Slice TopN**: `GlobalOrdinalsStringTermsAggregator.buildAggregations()`
- **Shard reduce**: `NonGlobalAggCollectorManager.reduce()`
- **Terms reduce**: `StringTerms.reduce()`

### Key Classes
- `GlobalOrdinalsStringTermsAggregator`: Main terms aggregator
- `CollectionStrategy`: Ordinal-to-bucket mapping
  - `DenseGlobalOrds`: Direct mapping
  - `RemapGlobalOrds`: Hash-based mapping
- `ResultStrategy`: Builds final results
  - `StandardTermsResults`: Terms aggregation
  - `SignificantTermsResults`: Significant terms
- `BucketSelectionStrategy`: TopN algorithm selection

## Memory Budget Calculation

```
Coordinator Memory Required = 
  (Estimated Cardinality × Key Size) + 
  (Estimated Cardinality × Metric State Size) +
  (Number of Shards × Streaming Buffer Size)

If Required > Available:
  - Use hash-based streaming
  - Enable adaptive bucketing
  - Fall back to traditional with TopN
```

## Monitoring Metrics

### Data Node Metrics
- Peak memory during aggregation
- Segment processing time
- Batch flush frequency
- Network bytes sent
- Global ordinals building time

### Coordinator Metrics
- Peak memory during merge
- Batch processing latency
- Number of unique buckets tracked
- Memory budget utilization

### Query Metrics
- Total query latency
- Time-to-first-byte
- Network transfer time
- Coordinator merge time

## Next Steps

1. **Validate Analysis**: Run benchmarks to confirm timing estimates
2. **Prototype**: Implement streaming for cardinality aggregations
3. **Query Planning**: Build cardinality estimation and decision logic
4. **Concurrent Integration**: Integrate with concurrent segment search
5. **Hash-based Streaming**: For extreme cardinality cases
6. **Production Testing**: Gradual rollout with monitoring

## References

- Arrow Flight RPC plugin: `/plugins/arrow-flight-rpc/`
- Aggregation framework: `/server/src/main/java/org/opensearch/search/aggregations/`
- Terms aggregator: `GlobalOrdinalsStringTermsAggregator.java`
- Concurrent search: `ConcurrentQueryPhaseSearcher.java`
- Collector manager: `NonGlobalAggCollectorManager.java`
