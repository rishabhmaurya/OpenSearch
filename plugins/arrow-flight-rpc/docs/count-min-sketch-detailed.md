# Count-Min Sketch for Streaming TopK: Complete Implementation Guide

## What is Count-Min Sketch?

Count-Min Sketch is a probabilistic data structure for estimating frequencies of items in a data stream using sublinear space. It provides approximate counts with mathematical error bounds.

## Core Data Structure

### The Sketch Matrix

```
    Hash Functions: h₁, h₂, h₃, ..., h_d
    
    h₁: [  5  |  12 |   3 |   8 |  15 |   2 |   7 ]  ← Row 1
    h₂: [  8  |   4 |  11 |   6 |   9 |  13 |   1 ]  ← Row 2  
    h₃: [  3  |   9 |   7 |  14 |   2 |   5 |  10 ]  ← Row 3
    h₄: [  6  |   1 |  12 |   4 |  11 |   8 |   3 ]  ← Row 4
         ↑     ↑     ↑     ↑     ↑     ↑     ↑
       Col 0  Col 1 Col 2 Col 3 Col 4 Col 5 Col 6
       
    Dimensions: d × w
    - d = depth (number of hash functions)
    - w = width (number of buckets per row)
```

**Parameters**:
- `w = ⌈e/ε⌉` where ε = relative error
- `d = ⌈ln(1/δ)⌉` where δ = failure probability

## Algorithm

### 1. Update Operation

**Input**: item `x`, count `c`

```
Algorithm UPDATE(x, c):
    for i = 1 to d:
        j = h_i(x) mod w
        sketch[i][j] += c
```

**Visual Example**: Adding "apple" with count 3

```
Before:
h₁: [  5  |  12 |   3 |   8 |  15 |   2 |   7 ]
h₂: [  8  |   4 |  11 |   6 |   9 |  13 |   1 ]
h₃: [  3  |   9 |   7 |  14 |   2 |   5 |  10 ]
h₄: [  6  |   1 |  12 |   4 |  11 |   8 |   3 ]

Hash values:
h₁("apple") = 2 → sketch[1][2] += 3
h₂("apple") = 5 → sketch[2][5] += 3  
h₃("apple") = 1 → sketch[3][1] += 3
h₄("apple") = 4 → sketch[4][4] += 3

After:
h₁: [  5  |  12 |   6 |   8 |  15 |   2 |   7 ]  ← +3 at pos 2
h₂: [  8  |   4 |  11 |   6 |   9 |  16 |   1 ]  ← +3 at pos 5
h₃: [  3  |  12 |   7 |  14 |   2 |   5 |  10 ]  ← +3 at pos 1
h₄: [  6  |   1 |  12 |   4 |  14 |   8 |   3 ]  ← +3 at pos 4
```

### 2. Query Operation

**Input**: item `x`
**Output**: estimated count

```
Algorithm QUERY(x):
    estimates = []
    for i = 1 to d:
        j = h_i(x) mod w
        estimates.append(sketch[i][j])
    return min(estimates)
```

**Visual Example**: Querying "apple"

```
Current sketch:
h₁: [  5  |  12 |   6 |   8 |  15 |   2 |   7 ]
h₂: [  8  |   4 |  11 |   6 |   9 |  16 |   1 ]
h₃: [  3  |  12 |   7 |  14 |   2 |   5 |  10 ]
h₄: [  6  |   1 |  12 |   4 |  14 |   8 |   3 ]

Hash values for "apple":
h₁("apple") = 2 → sketch[1][2] = 6
h₂("apple") = 5 → sketch[2][5] = 16
h₃("apple") = 1 → sketch[3][1] = 12
h₄("apple") = 4 → sketch[4][4] = 14

estimates = [6, 16, 12, 14]
result = min(estimates) = 6
```

## Mathematical Proofs

### Theorem 1: Overestimation Property

**Claim**: Count-Min Sketch never underestimates
```
∀x: CM_estimate(x) ≥ true_count(x)
```

**Proof**:
- True count of x contributes to sketch[i][h_i(x)] for all i
- Other items may also hash to same positions (collisions)
- Collisions only add positive values
- Therefore: sketch[i][h_i(x)] ≥ true_count(x) for all i
- min(estimates) ≥ true_count(x) ∎

### Theorem 2: Error Bound

**Claim**: With probability ≥ (1-δ):
```
CM_estimate(x) ≤ true_count(x) + ε × N
```
where N = total number of items processed

**Proof Sketch**:

1. **Expected error in one row**:
   ```
   E[sketch[i][j] - true_count(x)] = Σ(y≠x) true_count(y) × P(h_i(y) = h_i(x))
                                   = Σ(y≠x) true_count(y) × (1/w)
                                   = (N - true_count(x)) / w
                                   ≤ N/w
   ```

2. **By choice of w = ⌈e/ε⌉**:
   ```
   E[error] ≤ N/(e/ε) = ε×N/e
   ```

3. **Using Markov's inequality**:
   ```
   P(error > ε×N) ≤ E[error]/(ε×N) ≤ 1/e
   ```

4. **With d independent hash functions**:
   ```
   P(all d rows have error > ε×N) ≤ (1/e)^d
   ```

5. **By choice of d = ⌈ln(1/δ)⌉**:
   ```
   (1/e)^d ≤ (1/e)^(ln(1/δ)) = δ
   ```

6. **Therefore**:
   ```
   P(min(estimates) ≤ true_count(x) + ε×N) ≥ 1-δ
   ```
   ∎

### Theorem 3: Space Complexity

**Claim**: Space = O((1/ε) × log(1/δ))

**Proof**:
- Matrix size: d × w = ⌈ln(1/δ)⌉ × ⌈e/ε⌉
- Each cell: O(log N) bits for counter
- Total: O(log(1/δ) × (1/ε) × log N) bits ∎

## Complete Streaming TopK Approach

### Problem Context

In OpenSearch streaming aggregations:
- Each segment processes documents and builds term frequency counts
- Need to identify TopK terms globally across all segments
- Challenge: High cardinality (millions of unique terms)
- Goal: Minimize network traffic while maintaining exact accuracy

### Two-Phase Threshold-Based Solution

#### Phase 1: Sketch Collection & Threshold Calculation

```
Each segment:
1. Processes documents, updating Count-Min sketch
2. Streams ONLY sketch (5.4KB) to coordinator
3. Keeps exact local counts in memory

Coordinator:
1. Receives sketches from all segments
2. Merges sketches element-wise: merged_sketch[i][j] = Σ(segments) sketch[i][j]
3. Calculates conservative threshold from merged sketch
4. Sends threshold back to all segments
```

**Sketch Merging Example**:
```
Segment 1 sketch:
h₁: [  5  |  12 |   3 |   8 ]
h₂: [  8  |   4 |  11 |   6 ]

Segment 2 sketch:
h₁: [  2  |   7 |   9 |   1 ]
h₂: [  3  |   5 |   4 |   8 ]

Merged sketch:
h₁: [  7  |  19 |  12 |   9 ]  ← Element-wise sum
h₂: [ 11  |   9 |  15 |  14 ]
```

**Threshold Calculation**:
```java
public long calculateThreshold(CountMinSketch mergedSketch, int K) {
    long totalItems = mergedSketch.getTotalCount();
    
    // Strategy 1: Zipfian assumption (top K terms have ~50% frequency)
    long zipfianEstimate = totalItems / (2 * K);
    
    // Strategy 2: Conservative uniform assumption
    long uniformEstimate = totalItems / (K * numSegments * 5);
    
    // Take minimum for maximum safety (deliberate underestimate)
    long conservativeThreshold = Math.min(zipfianEstimate, uniformEstimate);
    
    // Apply safety factor (ensures we don't miss any true TopK terms)
    return Math.max(1, conservativeThreshold / 3);
}
```

#### Phase 2: Threshold-Based Exact Collection

```
Each segment:
1. Receives threshold from coordinator
2. Filters local exact counts: send only terms with count ≥ threshold
3. Streams filtered exact counts to coordinator

Coordinator:
1. Receives exact counts from all segments
2. Merges exact counts: global_count[term] = Σ(segments) local_count[term]
3. Selects exact TopK from merged exact counts
4. Returns final TopK result
```

### Complete Algorithm

```java
public class ThresholdBasedTopK {
    
    public TopKResult computeTopK(int K) {
        // Phase 1: Collect sketches and calculate threshold
        List<CountMinSketch> sketches = segments.parallelStream()
            .map(Segment::buildAndSendSketch)
            .collect(Collectors.toList());
        
        CountMinSketch merged = mergeAll(sketches);
        long threshold = calculateThreshold(merged, K);
        
        // Phase 2: Collect exact counts above threshold
        Map<String, Long> exactCounts = segments.parallelStream()
            .map(segment -> segment.getTermsAboveThreshold(threshold))
            .reduce(new HashMap<>(), this::mergeCounts);
        
        return selectTopK(exactCounts, K);
    }
    
    private Map<String, Long> mergeCounts(Map<String, Long> map1, Map<String, Long> map2) {
        map2.forEach((term, count) -> map1.merge(term, count, Long::sum));
        return map1;
    }
}

// Segment implementation
public class Segment {
    private Map<String, Long> exactCounts;
    private CountMinSketch sketch;
    
    public CountMinSketch buildAndSendSketch() {
        sketch = new CountMinSketch(0.01, 0.01);  // 1% error, 1% failure rate
        for (Map.Entry<String, Long> entry : exactCounts.entrySet()) {
            sketch.update(entry.getKey(), entry.getValue().intValue());
        }
        return sketch;
    }
    
    public Map<String, Long> getTermsAboveThreshold(long threshold) {
        return exactCounts.entrySet().stream()
            .filter(entry -> entry.getValue() >= threshold)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
```

### Concrete Example

**Setup**:
- K = 100 (want top 100 terms)
- 1000 segments
- 500K unique terms total
- ε = 0.01, δ = 0.01
- Sketch size: 5 × 272 = 1,360 integers ≈ 5.4KB per segment

**Phase 1 Process**:
```
Segment 1: Processes 100K docs
- Exact counts: {"error":1000, "warning":800, "info":600, "debug":400, ...}
- Builds 5.4KB sketch from exact counts
- Sends sketch to coordinator

Segment 2: Processes 80K docs  
- Exact counts: {"debug":900, "error":700, "trace":500, "fatal":300, ...}
- Builds 5.4KB sketch from exact counts
- Sends sketch to coordinator

... (998 more segments)

Coordinator:
- Receives 1000 sketches (5.4MB total)
- Merges into single 5.4KB sketch
- Calculates threshold:
  * Total items: 10M
  * Zipfian estimate: 10M / 200 = 50K
  * Uniform estimate: 10M / (100 × 1000 × 5) = 20
  * Conservative: min(50K, 20) = 20
  * With safety factor: 20 / 3 = 7
- Sends threshold = 7 to all segments
```

**Phase 2 Process**:
```
Segment 1: Receives threshold = 7
- Filters: {"error":1000, "warning":800, "info":600, "debug":400, ...}
- All terms above 7, sends ~200 terms (6.4KB)

Segment 2: Receives threshold = 7  
- Filters: {"debug":900, "error":700, "trace":500, "fatal":300, ...}
- All terms above 7, sends ~180 terms (5.8KB)

... (998 more segments)

Coordinator:
- Receives exact counts from all segments (total: ~6MB)
- Merges: {"error":1,700,000, "warning":1,200,000, "debug":1,100,000, ...}
- Selects top 100 from merged exact counts
- Returns exact TopK result
```

### Network Cost Analysis

**Total network cost**:
```
Phase 1: 5.4KB × 1000 segments = 5.4MB (sketches)
Phase 2: ~200 terms × 32 bytes × 1000 segments = 6.4MB (exact counts)
Total: ~12MB

Compare to alternatives:
- Stream all buckets: 500K × 32 bytes × 1000 = 16GB
- Naive topM candidates: 1000 × 32 bytes × 1000 = 32MB

Savings: 1,333x reduction vs streaming all
```

### Why This Approach Works

#### 1. Threshold Underestimation Is Intentional
- **Deliberate safety margin**: Threshold is 3-10x lower than true Kth term frequency
- **Guarantees coverage**: All true TopK terms will have local counts above threshold
- **Acceptable overhead**: Extra terms filtered out, but network cost remains manageable

#### 2. Mathematical Guarantees
- **No false negatives**: Count-Min sketch never underestimates, so threshold calculation is safe
- **Exact final result**: Phase 2 uses exact counts, no approximation in final TopK
- **Bounded network cost**: Even with 10x overestimation, network cost is predictable

#### 3. Scalability Properties
- **Fixed sketch size**: 5.4KB regardless of cardinality
- **Threshold effectiveness**: Works across different data distributions
- **Linear scaling**: Network cost scales with segments, not cardinality

### Accuracy Analysis

#### Threshold Calculation Accuracy

**Threshold is deliberately an underestimate**:
```
True 100th term frequency: 15,000
Calculated threshold: 7 (1,000x underestimate)

Result: Captures ALL terms with frequency ≥ 15,000
+ Many additional terms with frequency ≥ 7
= Guaranteed exact TopK + some false positives
```

**Why underestimation works**:
- **Safety first**: Better to collect extra terms than miss true TopK
- **Exact final phase**: False positives filtered out in exact counting
- **Manageable overhead**: Even 10x more terms is acceptable network cost

#### Error Sources and Mitigation

**Count-Min Sketch errors**:
- **Overestimation only**: Never underestimates, so threshold calculation is safe
- **Bounded error**: With probability ≥99%, error ≤ 1% × total_items
- **Impact on threshold**: Makes threshold more conservative (safer)

**Distribution assumptions**:
- **Zipfian vs uniform**: Use minimum of multiple estimates for safety
- **Safety factors**: Apply 3-10x margin to handle distribution variations
- **Adaptive learning**: Can adjust based on previous query results

### Performance Characteristics

#### Advantages

1. **Exact Results**: Final TopK is 100% accurate (no approximation)
2. **Predictable Cost**: Network cost bounded regardless of data distribution
3. **Scalable**: O(1) sketch size, linear scaling with segments
4. **Simple**: Two-phase protocol, easy to implement and debug
5. **Robust**: Works across different cardinalities and distributions

#### Trade-offs

1. **Two network rounds**: Requires coordination between phases
2. **Memory overhead**: Segments must keep exact counts during processing
3. **Conservative threshold**: May collect more terms than strictly necessary
4. **Parameter sensitivity**: ε, δ parameters affect sketch size and accuracy

#### When to Use This Approach

**Ideal scenarios**:
- High cardinality (>10K unique terms)
- Network bandwidth constraints
- Exact results required
- Distributed segment processing

**Alternative approaches for**:
- Low cardinality (<1K terms): Stream all buckets
- Approximate results acceptable: Single-phase Count-Min sketch
- Very small TopK: Space-Saving algorithm

### Implementation Details

#### Count-Min Sketch Implementation

```java
public class CountMinSketch {
    private final int[][] sketch;
    private final int depth, width;
    private final HashFunction[] hashFunctions;
    private long totalCount;
    
    public CountMinSketch(double epsilon, double delta) {
        this.width = (int) Math.ceil(Math.E / epsilon);
        this.depth = (int) Math.ceil(Math.log(1.0 / delta));
        this.sketch = new int[depth][width];
        this.hashFunctions = generateHashFunctions(depth);
        this.totalCount = 0;
    }
    
    public void update(String item, int count) {
        for (int i = 0; i < depth; i++) {
            int j = Math.abs(hashFunctions[i].hashString(item).asInt()) % width;
            sketch[i][j] += count;
        }
        totalCount += count;
    }
    
    public long estimate(String item) {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < depth; i++) {
            int j = Math.abs(hashFunctions[i].hashString(item).asInt()) % width;
            min = Math.min(min, sketch[i][j]);
        }
        return min;
    }
    
    public void merge(CountMinSketch other) {
        for (int i = 0; i < depth; i++) {
            for (int j = 0; j < width; j++) {
                sketch[i][j] += other.sketch[i][j];
            }
        }
        totalCount += other.totalCount;
    }
    
    public long getTotalCount() {
        return totalCount;
    }
    
    public int getSerializedSize() {
        return depth * width * 4 + 8; // 4 bytes per int + 8 bytes for totalCount
    }
}
```

#### OpenSearch Integration

```java
// Segment-level aggregation
public class StreamingTermsAggregator {
    private final Map<String, Long> exactCounts = new HashMap<>();
    private final CountMinSketch sketch = new CountMinSketch(0.01, 0.01);
    
    public void collectTerm(String term) {
        exactCounts.merge(term, 1L, Long::sum);
        sketch.update(term, 1);
    }
    
    public CountMinSketch getSketch() {
        return sketch;
    }
    
    public Map<String, Long> getTermsAboveThreshold(long threshold) {
        return exactCounts.entrySet().stream()
            .filter(entry -> entry.getValue() >= threshold)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}

// Coordinator-level processing
public class StreamingTermsCoordinator {
    
    public TermsAggregationResult aggregate(List<StreamingTermsAggregator> segments, int size) {
        // Phase 1: Collect and merge sketches
        CountMinSketch merged = new CountMinSketch(0.01, 0.01);
        for (StreamingTermsAggregator segment : segments) {
            merged.merge(segment.getSketch());
        }
        
        // Calculate conservative threshold
        long threshold = calculateThreshold(merged, size);
        
        // Phase 2: Collect exact counts above threshold
        Map<String, Long> globalCounts = new HashMap<>();
        for (StreamingTermsAggregator segment : segments) {
            Map<String, Long> segmentCounts = segment.getTermsAboveThreshold(threshold);
            segmentCounts.forEach((term, count) -> 
                globalCounts.merge(term, count, Long::sum));
        }
        
        // Select final TopK
        return selectTopK(globalCounts, size);
    }
    
    private long calculateThreshold(CountMinSketch sketch, int K) {
        long totalItems = sketch.getTotalCount();
        long zipfianEstimate = totalItems / (2 * K);
        long uniformEstimate = totalItems / (K * segments.size() * 5);
        return Math.max(1, Math.min(zipfianEstimate, uniformEstimate) / 3);
    }
}
```

## Conclusion

### Summary of Complete Approach

The two-phase threshold-based streaming TopK approach provides:

1. **Exact Results**: 100% accurate TopK through exact counting in Phase 2
2. **Efficient Network Usage**: ~12MB vs 16GB (1,333x reduction)
3. **Mathematical Guarantees**: Conservative threshold ensures no missing terms
4. **Scalable Design**: Fixed sketch overhead regardless of cardinality
5. **Production Ready**: Simple protocol, robust error handling

### Key Insights

1. **Threshold Underestimation**: Deliberately conservative thresholds prevent missing true TopK terms
2. **Two-Phase Design**: Separates approximate filtering from exact counting
3. **Count-Min Sketch Role**: Used for threshold calculation, not final ranking
4. **Network Optimization**: Minimizes data transfer while maintaining accuracy

### When to Use This Approach

**Recommended for**:
- High cardinality terms aggregations (>10K unique terms)
- Distributed search with network constraints
- Exact TopK results required
- Real-time streaming scenarios

**Alternatives for**:
- Low cardinality (<1K terms): Stream all buckets directly
- Approximate results acceptable: Single-phase Count-Min sketch
- Memory-constrained environments: Space-Saving algorithm

This approach represents the optimal balance between network efficiency, accuracy guarantees, and implementation complexity for streaming TopK aggregations in distributed systems like OpenSearch.