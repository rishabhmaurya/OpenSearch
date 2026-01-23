# Previous Benchmark Results (High Cardinality - Streaming Lost)

## Results Summary

| Query | Field | Card | size | shard_size | Trad(ms) | Stream(ms) | Winner | Improvement |
|-------|-------|------|------|------------|----------|------------|--------|-------------|
| Q1 | RegionID | 9K | 10 | default | 533 | 567 | Traditional | 5.9% |
| Q2 | RegionID | 9K | 10 | 1000 | 480 | 548 | Traditional | 12.4% |
| Q3 | SearchPhrase | 5.9M | 100 | 1000 | 672 | 12260 | Traditional | 94.5% |
| Q4 | SearchPhrase | 5.9M | 100 | 10000 | 928 | 12351 | Traditional | 92.4% |
| Q5 | Title | 9.3M | 100 | 1000 | 1093 | 27519 | Traditional | 96.0% |
| Q6 | Title | 9.3M | 1000 | 10000 | 1506 | 28056 | Traditional | 94.6% |
| Q7 | UserID | 17.5M | 100 | 1000 | 1904 | 27704 | Traditional | 93.1% |
| Q8 | URL | 18.2M | 100 | 1000 | 1322 | 47386 | Traditional | 97.2% |
| Q9 | UserID (filtered) | 2.8M | 100 | 1000 | 283 | 3500 | Traditional | 91.9% |
| Q10 | UserID (filtered) | 2.8M | 100 | 1000 | 333 | 4592 | Traditional | 92.7% |
| Q11 | UserID (filtered) | ~12M | 100 | 1000 | 1319 | 18415 | Traditional | 92.8% |

## Key Findings

1. **Streaming loses dramatically on high cardinality fields** (5M-18M unique values)
   - 10-40x slower than traditional
   - Reason: Streams ALL buckets from each segment without TopK filtering

2. **Even with query filters, streaming loses** (Q9-Q11)
   - Despite reduced doc count, cardinality remains high
   - Still streaming millions of buckets

3. **Low cardinality also loses slightly** (Q1-Q2)
   - RegionID with 9K cardinality
   - 5-12% slower, likely due to streaming overhead

## Root Cause

**Traditional approach**: 
- Maintains TopK priority queue at segment level
- Only sends top 100-1000 buckets per shard to coordinator

**Streaming approach (current)**:
- Streams ALL buckets from each segment
- No TopK filtering at segment level
- Coordinator receives millions of buckets

## Conclusion

For streaming aggregations to be competitive, they MUST implement:
1. **Segment-level TopK filtering** before streaming
2. Only stream top-K buckets per segment, not all buckets
3. Otherwise only viable for:
   - Very low cardinality (<1K)
   - Cardinality aggregations (HLL sketches)
   - Cases requiring ALL buckets
