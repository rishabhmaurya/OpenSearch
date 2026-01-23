# Streaming Aggregation Use Cases

This document analyzes scenarios where streaming aggregations can reduce latency or memory pressure on data nodes without overwhelming coordinators.

## Table of Contents
- [Evaluation Criteria](#evaluation-criteria)
- [Viable Use Cases](#viable-use-cases)
- [Cost Analysis](#cost-analysis)
- [Comparison Matrix](#comparison-matrix)
- [Implementation Priorities](#implementation-priorities)

## Evaluation Criteria

Each use case is evaluated on:
- **Memory Reduction**: Data node memory pressure reduction
- **Latency Impact**: Effect on query response time
- **Coordinator Load**: Additional burden on coordinator nodes
- **Network Overhead**: Bandwidth requirements change
- **Implementation Complexity**: Development and maintenance cost

## Viable Use Cases

### 1. Concurrent Segment Search with Streaming ⭐⭐⭐⭐⭐

**Scenario**: When concurrent segment search is enabled, each slice processes segments in parallel.

**Current Approach**:
- Each slice builds complete aggregation state
- Slices merge results on data node
- Data node sends merged result to coordinator

**Streaming Approach**:
- Each slice streams results directly to coordinator as segments complete
- Eliminates intermediate merge on data node
- Results arrive incrementally, reducing time-to-first-byte

**Benefits**:
- ✅ **Memory**: 40-60% reduction (no cross-slice state)
- ✅ **Latency**: 15-25% improvement (eliminates merge + streaming)
- ✅ **Natural Fit**: Synergy with concurrent execution
- ⚠️ **Coordinator**: Moderate increase (more partial results)
- ⚠️ **Network**: 20-30% increase (frequent batches)

---

### 2. Cardinality/Distinct Count Aggregations ⭐⭐⭐⭐⭐

**Scenario**: Counting unique values using HyperLogLog++ sketches.

**Current Approach**:
- Each shard builds complete HLL sketch
- Sends entire sketch to coordinator

**Streaming Approach**:
- Stream HLL updates incrementally as new unique values discovered
- Only send hash updates, not full sketch

**Benefits**:
- ✅ **Memory**: 70-80% reduction (no sketch buffering)
- ✅ **Latency**: 10-20% improvement (incremental processing)
- ✅ **Coordinator**: Minimal increase (HLL merges are cheap)
- ✅ **Network**: 50-60% reduction (only new hashes)
- ✅ **Accuracy**: Identical to current approach

---

### 3. Percentile/Stats with Sketch-Based Metrics ⭐⭐⭐⭐

**Scenario**: Terms aggregation with percentile or extended stats metrics.

**Current Approach**:
- Build complete bucket structure with TDigest/stats per bucket
- High memory for sketches and intermediate state

**Streaming Approach**:
- Stream (term, TDigest_update) or (term, stats_update) per segment
- Coordinator merges sketches incrementally

**Benefits**:
- ✅ **Memory**: 65-80% reduction (segment-level + incremental sketches)
- ✅ **Latency**: 15-25% improvement (parallel processing)
- ⚠️ **Coordinator**: Moderate increase (merge operations)
- ⚠️ **Network**: 35-45% increase (repeated buckets)
- ✅ **Accuracy**: Exact (within sketch bounds)

---

### 4. High Cardinality Terms with Cardinality Sub-Aggs ⭐⭐⭐⭐

**Scenario**: Terms aggregation with cardinality sub-aggregation (e.g., unique visitors per product).

**Current Approach**:
- Build complete terms buckets with HLL sketches
- High memory for both bucket keys and sketches

**Streaming Approach**:
- Stream (term, HLL_update) pairs per segment
- Coordinator maintains running state per term

**Benefits**:
- ✅ **Memory**: 60-75% reduction (segment-level + incremental HLL)
- ✅ **Latency**: 20-30% improvement (no global ordinals)
- ⚠️ **Coordinator**: High increase (track all unique terms)
- ⚠️ **Network**: 40-50% increase (repeated terms)
- ✅ **Accuracy**: Exact results maintained

---

### 5. Date Histogram with High Cardinality Sub-Aggs ⭐⭐⭐

**Scenario**: Date histogram with terms sub-aggregation (e.g., daily unique products).

**Current Approach**:
- Build complete date buckets with nested terms buckets
- High memory for nested structure

**Streaming Approach**:
- Stream (date, term) pairs per segment
- Coordinator maintains nested structure incrementally

**Benefits**:
- ✅ **Memory**: 45-60% reduction (segment-level processing)
- ✅ **Latency**: 10-20% improvement (parallel processing)
- ⚠️ **Coordinator**: Moderate increase (nested maintenance)
- ⚠️ **Network**: 30-40% increase (repeated pairs)
- ✅ **Accuracy**: Exact results

---

### 6. Sampler Aggregation with Expensive Sub-Aggs ⭐⭐⭐

**Scenario**: Sampler aggregation processing subset with expensive sub-aggregations.

**Current Approach**:
- Sample documents on each shard
- Build complete sub-aggregation state on sample

**Streaming Approach**:
- Stream sampled results per segment
- Coordinator aggregates across sampled segments

**Benefits**:
- ✅ **Memory**: 40-55% reduction (no buffering sampled results)
- ✅ **Latency**: 15-25% improvement (incremental sampling)
- ✅ **Coordinator**: Low increase (working with samples)
- ✅ **Network**: Minimal increase (small sample size)
- ✅ **Accuracy**: Same sampling accuracy

## Cost Analysis

### Memory Cost Breakdown

| Component | Regular Agg | Streaming Agg | Savings |
|-----------|-------------|---------------|----------|
| **Data Node** | | | |
| Global Ordinals | 200-500MB | 0MB | 100% |
| Bucket State | 100-300MB | 20-50MB | 70-80% |
| Intermediate Merges | 50-150MB | 0MB | 100% |
| **Coordinator** | | | |
| Final Merge | 10-50MB | 50-200MB | -300-400% |
| **Total Cluster** | 360-1000MB | 70-250MB | **65-80%** |

### Network Cost Analysis

| Aggregation Type | Regular | Streaming | Overhead |
|------------------|---------|-----------|----------|
| Cardinality | 1x | 0.4x | **-60%** |
| Terms (low card) | 1x | 1.3x | +30% |
| Terms (high card) | 1x | 1.8x | +80% |
| Percentiles | 1x | 1.4x | +40% |
| Date Histogram | 1x | 1.2x | +20% |

### CPU Cost Analysis

| Operation | Regular | Streaming | Change |
|-----------|---------|-----------|--------|
| **Data Node** | | | |
| Global Ordinal Build | High | None | **-100%** |
| Bucket Maintenance | Medium | Low | **-60%** |
| Serialization | Low | Medium | +100% |
| **Coordinator** | | | |
| Deserialization | Low | Medium | +100% |
| Incremental Merge | Low | Medium | +150% |
| **Net Effect** | 100% | **70-85%** | **-15-30%** |

## Comparison Matrix

### When to Use Streaming vs Regular Aggregations

| Scenario | Data Size | Cardinality | Shards | Recommendation | Reason |
|----------|-----------|-------------|--------|----------------|--------|
| **Concurrent Segment Search** | Any | Any | Any | **Always Stream** | Natural architectural fit |
| **Cardinality Aggs** | Any | Any | Any | **Always Stream** | Pure win scenario |
| **Terms + Percentiles** | >1GB | <100K | >5 | **Stream** | Memory savings dominate |
| **Terms + Percentiles** | <1GB | >1M | <3 | **Regular** | Coordinator overwhelmed |
| **Date Histogram** | >5GB | <50K | >10 | **Stream** | Good memory/latency trade-off |
| **High Card Terms** | Any | >1M | Any | **Regular** | Network/coordinator costs too high |
| **Multi-field Composite** | >10GB | <10K each | >20 | **Stream** | Avoids ordinal explosion |
| **Simple Terms** | <1GB | <10K | <5 | **Regular** | Overhead not justified |

### Performance Characteristics

| Metric | Small Data (<1GB) | Medium Data (1-10GB) | Large Data (>10GB) |
|--------|-------------------|----------------------|--------------------|
| **Memory Savings** | 20-40% | 40-60% | 60-80% |
| **Latency Improvement** | 0-10% | 10-20% | 20-30% |
| **Network Overhead** | +50-100% | +20-40% | +10-20% |
| **Coordinator Load** | +100-200% | +50-100% | +20-50% |

### Decision Tree

```
Is Concurrent Segment Search enabled?
├─ YES → Use Streaming (always beneficial)
└─ NO → Continue evaluation

Is it a Cardinality aggregation?
├─ YES → Use Streaming (pure win)
└─ NO → Continue evaluation

Data size > 5GB AND Cardinality < 100K?
├─ YES → Use Streaming (memory savings justify overhead)
└─ NO → Continue evaluation

Shards > 10 AND Memory pressure detected?
├─ YES → Use Streaming (distributed benefits)
└─ NO → Use Regular (overhead not justified)
```

## Implementation Priorities

### Phase 1: High-Impact, Low-Risk
1. **Cardinality Aggregations** - Pure win scenario
2. **Concurrent Segment Search Integration** - Natural architectural fit

### Phase 2: High-Impact, Medium-Risk
3. **Percentile/Stats with Sketches** - Good streaming patterns
4. **Terms + Cardinality Sub-aggs** - Memory-constrained scenarios

### Phase 3: Conditional Benefits
5. **Date Histogram + Sub-aggs** - When parent provides partitioning
6. **Sampler Aggregations** - Reduced data volume scenarios

### Success Metrics
- **Memory**: 50-70% reduction in data node heap usage
- **Latency**: 15-25% improvement in P95 response times
- **Throughput**: Maintain or improve QPS under memory pressure
- **Stability**: No increase in coordinator OOM incidents