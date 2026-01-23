# Bloom Filters: Complete Guide

## What is a Bloom Filter?

A Bloom Filter is a probabilistic data structure for **set membership testing**. It answers "Is item X in the set?" with:
- **No false negatives**: If it says "NO", the item is definitely not in the set
- **Possible false positives**: If it says "YES", the item might be in the set

## Core Data Structure

### The Bit Array

```
    Hash Functions: h₁, h₂, h₃
    
    Bit Array (m bits):
    [0|1|0|1|1|0|1|0|1|0|1|1|0|0|1|0]
     0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15
     
    Parameters:
    - m = number of bits in array
    - k = number of hash functions
    - n = expected number of items
```

## Algorithm

### 1. Add Operation

**Input**: item `x`

```
Algorithm ADD(x):
    for i = 1 to k:
        j = h_i(x) mod m
        bitArray[j] = 1
```

**Visual Example**: Adding "apple"

```
Before:
[0|0|0|0|0|0|0|0|0|0|0|0|0|0|0|0]
 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15

Hash values for "apple":
h₁("apple") = 3  → bitArray[3] = 1
h₂("apple") = 7  → bitArray[7] = 1  
h₃("apple") = 12 → bitArray[12] = 1

After:
[0|0|0|1|0|0|0|1|0|0|0|0|1|0|0|0]
 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15
       ↑       ↑           ↑
     Set by "apple"
```

### 2. Query Operation

**Input**: item `x`
**Output**: boolean (might be present)

```
Algorithm CONTAINS(x):
    for i = 1 to k:
        j = h_i(x) mod m
        if bitArray[j] == 0:
            return false  // Definitely not present
    return true  // Might be present
```

**Visual Example**: Querying "apple" and "banana"

```
Current state:
[0|0|0|1|0|0|0|1|0|0|0|0|1|0|0|0]
 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15

Query "apple":
h₁("apple") = 3  → bitArray[3] = 1 ✓
h₂("apple") = 7  → bitArray[7] = 1 ✓
h₃("apple") = 12 → bitArray[12] = 1 ✓
Result: true (might be present)

Query "banana":
h₁("banana") = 2  → bitArray[2] = 0 ✗
Result: false (definitely not present)
```

## Mathematical Analysis

### False Positive Probability

**After inserting n items**:
```
Probability that a specific bit is still 0:
P(bit = 0) = (1 - 1/m)^(kn) ≈ e^(-kn/m)

Probability that all k bits are 1 for a non-member:
P(false positive) = (1 - e^(-kn/m))^k
```

**Optimal number of hash functions**:
```
k_optimal = (m/n) × ln(2)
```

**Minimum false positive rate**:
```
P(false positive) = (1/2)^k ≈ 0.6185^(m/n)
```

### Space Efficiency

**Bits per item for 1% false positive rate**:
```
m/n ≈ 9.6 bits per item
k ≈ 7 hash functions
```

**Example**: 1M items with 1% false positive rate
- Bit array size: 9.6M bits = 1.2MB
- Hash functions: 7

## Bloom Filter vs Count-Min Sketch

| Feature | Bloom Filter | Count-Min Sketch |
|---------|--------------|------------------|
| **Purpose** | Set membership | Frequency estimation |
| **Query** | "Is X present?" | "How many times X?" |
| **Storage** | Bit array | Integer matrix |
| **False negatives** | Never | Never |
| **False positives** | Yes | Yes (overestimation) |
| **Space per item** | ~10 bits | ~32 bits |
| **Use case** | Membership testing | Heavy hitters, TopK |

## Implementation

```java
public class BloomFilter {
    private final BitSet bitArray;
    private final int numBits;
    private final int numHashFunctions;
    private final HashFunction[] hashFunctions;
    
    public BloomFilter(int expectedItems, double falsePositiveRate) {
        this.numBits = (int) (-expectedItems * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
        this.numHashFunctions = (int) (numBits * Math.log(2) / expectedItems);
        this.bitArray = new BitSet(numBits);
        this.hashFunctions = generateHashFunctions(numHashFunctions);
    }
    
    public void add(String item) {
        for (HashFunction hash : hashFunctions) {
            int index = Math.abs(hash.hashString(item).asInt()) % numBits;
            bitArray.set(index);
        }
    }
    
    public boolean mightContain(String item) {
        for (HashFunction hash : hashFunctions) {
            int index = Math.abs(hash.hashString(item).asInt()) % numBits;
            if (!bitArray.get(index)) {
                return false;  // Definitely not present
            }
        }
        return true;  // Might be present
    }
    
    public void merge(BloomFilter other) {
        if (this.numBits != other.numBits || this.numHashFunctions != other.numHashFunctions) {
            throw new IllegalArgumentException("Incompatible Bloom filters");
        }
        this.bitArray.or(other.bitArray);  // Bitwise OR
    }
}
```

## Application to Streaming TopK

### Hybrid Approach: Bloom Filter + Threshold

```java
public class BloomFilterTopK {
    
    public TopKResult computeTopK(int K) {
        // Phase 1: Collect Bloom filters from segments
        List<BloomFilter> bloomFilters = segments.parallelStream()
            .map(Segment::buildBloomFilter)
            .collect(Collectors.toList());
        
        // Phase 2: Merge Bloom filters
        BloomFilter globalBloom = mergeAll(bloomFilters);
        
        // Phase 3: Each segment filters using global Bloom filter
        Map<String, Long> exactCounts = segments.parallelStream()
            .map(segment -> segment.getTermsInBloomFilter(globalBloom))
            .reduce(new HashMap<>(), this::mergeCounts);
        
        return selectTopK(exactCounts, K);
    }
}

// Segment implementation
public class Segment {
    
    public BloomFilter buildBloomFilter() {
        BloomFilter bloom = new BloomFilter(localTerms.size(), 0.01);
        for (String term : localTerms.keySet()) {
            bloom.add(term);
        }
        return bloom;
    }
    
    public Map<String, Long> getTermsInBloomFilter(BloomFilter globalBloom) {
        return localTerms.entrySet().stream()
            .filter(entry -> globalBloom.mightContain(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
```

### Problems with Bloom Filter Approach

#### 1. High False Positive Rate

```
Example scenario:
- 1000 segments, each with 1000 unique terms
- Global Bloom filter represents union: ~100K unique terms
- False positive rate: 1% of 100K = 1000 false positives per segment
- Total false positives: 1000 × 1000 = 1M unnecessary term transmissions
```

#### 2. Large Size

```
For 100K unique terms with 1% false positive rate:
- Bloom filter size: 100K × 9.6 bits = 960K bits = 120KB
- vs Count-Min sketch: 5.4KB
- Bloom filter is 22x larger
```

#### 3. No Frequency Information

```
Bloom filter only answers: "Is term present somewhere?"
Cannot answer: "Is term frequent enough to be in TopK?"

Result: Sends many low-frequency terms that are globally irrelevant
```

## When to Use Bloom Filters

### Good Use Cases

#### 1. Distributed Cache Invalidation
```java
// Check if cache key might be invalid before expensive lookup
if (invalidationBloom.mightContain(cacheKey)) {
    // Might be invalid, check authoritative source
    return checkDatabase(cacheKey);
} else {
    // Definitely valid, use cached value
    return cache.get(cacheKey);
}
```

#### 2. Database Query Optimization
```java
// Skip expensive joins if foreign key definitely doesn't exist
if (!foreignKeyBloom.mightContain(foreignKey)) {
    return emptyResult();  // No matching records
} else {
    return executeExpensiveJoin(foreignKey);
}
```

#### 3. Duplicate Detection
```java
// Fast duplicate check before expensive processing
if (processedBloom.mightContain(documentId)) {
    // Might be duplicate, check exact set
    if (processedSet.contains(documentId)) {
        return; // Skip duplicate
    }
}
processDocument(documentId);
processedBloom.add(documentId);
```

### Poor Use Cases

#### 1. TopK/Heavy Hitters
- No frequency information
- High false positive rate for large sets
- Better: Count-Min Sketch, Space-Saving

#### 2. Exact Membership (Small Sets)
- HashSet is more efficient for small sets
- Bloom filter overhead not justified

#### 3. Frequent Updates/Deletes
- Standard Bloom filters don't support deletions
- Counting Bloom filters are more complex

## Bloom Filter Variants

### 1. Counting Bloom Filter
```java
// Uses counters instead of bits, supports deletions
private int[] counters;  // Instead of BitSet

public void remove(String item) {
    for (HashFunction hash : hashFunctions) {
        int index = Math.abs(hash.hashString(item).asInt()) % numBits;
        if (counters[index] > 0) {
            counters[index]--;
        }
    }
}
```

### 2. Scalable Bloom Filter
```java
// Dynamically grows as more items are added
// Maintains constant false positive rate
```

### 3. Cuckoo Filter
```java
// Alternative to Bloom filter with deletion support
// Better space efficiency for low false positive rates
```

## Comparison Summary

| Data Structure | Membership | Frequency | Space | Use Case |
|----------------|------------|-----------|-------|----------|
| **HashSet** | Exact | No | O(n) | Small exact sets |
| **Bloom Filter** | Approximate | No | O(1) | Large membership testing |
| **Count-Min Sketch** | Approximate | Yes | O(1) | Frequency estimation |
| **HyperLogLog** | No | No (cardinality only) | O(1) | Cardinality estimation |

## Conclusion

**Bloom Filters are excellent for**:
- Fast membership testing with space constraints
- Reducing expensive operations (DB queries, network calls)
- Pre-filtering before exact checks

**For streaming TopK problems**:
- **Count-Min Sketch** is superior (frequency estimation)
- **Threshold-based filtering** is more efficient
- **Bloom Filters** add complexity without significant benefit

The key insight: Bloom Filters solve **membership** problems, while TopK requires **frequency ranking** - different problem domains requiring different tools.