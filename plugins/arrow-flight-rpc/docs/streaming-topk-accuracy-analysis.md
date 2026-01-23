# Streaming TopK Accuracy Analysis

## Problem Statement

Can we stream only TopK buckets per segment instead of all buckets while maintaining exact accuracy for terms aggregations?

## Mathematical Foundation

### Exact Accuracy Requirement

For exact TopK results, we need the global count for each term:
```
Global_Count(term) = Σ(i=1 to N) Segment_Count_i(term)
```

Where N is the total number of segments.

### The Fundamental Problem

Streaming only TopK per segment can lose data:
- A globally top term may rank low in individual segments
- Missing even one segment's contribution breaks exactness

## Mathematically Sound Approaches

### Approach 1: Stream All Buckets (Baseline)

**Method**: Stream complete bucket counts from each segment

**Accuracy**: Exact (100%)

**Complexity**:
- Network: O(C) where C = total unique terms
- Memory: O(C) at coordinator
- CPU: O(C log K) for TopK selection

**When to use**: C ≤ 10K terms (acceptable network overhead)

### Approach 2: Bounded TopK with Mathematical Guarantee

**Method**: Stream TopM per segment where M is calculated to guarantee exact TopK

**Mathematical Bound**:
```
For exact TopK globally:
M ≥ K × N / min_segments_containing_kth_term

Worst case (Kth term appears in only 1 segment):
M = K × N
```

**Conservative Formula**:
```
M = min(total_unique_terms_in_segment, K × N)
```

**Accuracy**: Exact (100%) with sufficient M

**Example**:
- K = 100 (desired TopK)
- N = 160 segments
- M = 100 × 160 = 16,000 per segment
- Result: Exact, but high network cost

**Practical Limitation**: Often M ≈ total terms, negating benefits

### Approach 3: Probabilistic Sketches (Approximate)

**Method**: Use Count-Min Sketch or HyperLogLog for approximate counts

**Count-Min Sketch Parameters**:
- Width: w = ⌈e/ε⌉ where ε = error rate
- Depth: d = ⌈ln(1/δ)⌉ where δ = failure probability

**Error Bounds**:
```
With probability ≥ (1-δ):
|estimated_count - true_count| ≤ ε × total_stream_size
```

**Example Configuration**:
- ε = 0.01 (1% error)
- δ = 0.01 (1% failure probability)
- Memory per segment: ~4KB
- Network per segment: ~4KB

**Accuracy**: (1-ε) × 100% = 99% typical

### Approach 4: Two-Phase Exact Method

**Phase 1**: Collect approximate TopK using sketches
**Phase 2**: Request exact counts for candidate terms

**Method**:
1. Each segment streams Count-Min sketch
2. Coordinator identifies candidate TopK terms
3. Coordinator requests exact counts for candidates from all segments
4. Coordinator computes exact TopK from exact counts

**Network Complexity**:
- Phase 1: O(sketch_size × N) = O(N)
- Phase 2: O(candidate_terms × N) ≈ O(K × N)
- Total: O(K × N)

**Accuracy**: Exact (100%)

**Advantage**: Reduces network when K << total_unique_terms

## Accuracy Comparison

| Approach | Accuracy | Network | Memory | CPU | Use Case |
|----------|----------|---------|--------|-----|----------|
| Stream All | 100% | O(C) | O(C) | O(C log K) | C ≤ 10K |
| Bounded TopK | 100% | O(K×N) | O(K×N) | O(K×N log K) | Rarely practical |
| Sketches | ~99% | O(N) | O(1) | O(N) | High cardinality |
| Two-Phase | 100% | O(K×N) | O(K) | O(K×N) | K << C |

Where:
- C = total unique terms across all segments
- K = desired TopK size
- N = number of segments

## Recommended Strategy

### Decision Tree

```
if (estimated_cardinality ≤ 10K):
    use Stream_All_Buckets()
else if (required_accuracy == "exact" AND K × N < estimated_cardinality):
    use Two_Phase_Exact()
else if (acceptable_error_rate > 0):
    use Probabilistic_Sketches(error_rate)
else:
    use Stream_All_Buckets()  // Accept high network cost
```

### Implementation Priority

1. **Stream All Buckets**: Simple, exact, works for most real queries
2. **Probabilistic Sketches**: For high-cardinality scenarios where approximation is acceptable
3. **Two-Phase Exact**: For high-cardinality scenarios requiring exactness

### Not Recommended

- **Naive TopK per segment**: No mathematical guarantee of accuracy
- **Adaptive streaming without retroactive**: Loses data, unpredictable accuracy
- **Heuristic-based segment_size**: No theoretical foundation

## Conclusion

For streaming TopK aggregations:

1. **Exact accuracy** requires either streaming all data or two-phase approaches
2. **Simple TopK per segment** cannot guarantee accuracy without streaming most/all terms
3. **Probabilistic methods** offer good accuracy-performance trade-offs
4. **Network cost** is the primary constraint, not computational complexity

The choice depends on cardinality, accuracy requirements, and acceptable network overhead.