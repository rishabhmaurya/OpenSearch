# Terms Aggregation Benchmark Plan

Based on dataset analysis of 100M documents with various cardinality fields.

## Test Variables Summary

| Variable | Low | Medium | High |
|----------|-----|--------|------|
| **Cardinality** | <10K | 10K-1M | >1M |
| **size** | 10 | 100 | 1000 |
| **shard_size** | 100 | 1000 | 10000 |
| **shard_size/size ratio** | 1-10 | 10-100 | 100+ |
| **Query selectivity** | 10% | 18-28% | 67% |

## Benchmark Queries

### Q1: Low Cardinality, Low size (Baseline - Traditional Wins)
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "RegionID",
        "size": 10
      }
    }
  }
}'
```
**Variables**: cardinality=9K, size=10, shard_size=default, ratio=~1, docs=100M  
**Expected**: Traditional (overhead not justified)

---

### Q2: Low Cardinality, High shard_size ratio
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "RegionID",
        "size": 10,
        "shard_size": 1000
      }
    }
  }
}'
```
**Variables**: cardinality=9K, size=10, shard_size=1000, ratio=100, docs=100M  
**Expected**: Traditional (low cardinality, small result)

---

### Q3: Medium Cardinality, Medium size
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "SearchPhrase",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Variables**: cardinality=5.9M, size=100, shard_size=1000, ratio=10, docs=100M, avg_key_len=1  
**Expected**: Streaming (avoids global ordinals)

---

### Q4: Medium Cardinality, High shard_size ratio
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "SearchPhrase",
        "size": 100,
        "shard_size": 10000
      }
    }
  }
}'
```
**Variables**: cardinality=5.9M, size=100, shard_size=10000, ratio=100, docs=100M  
**Expected**: Streaming (high ratio + avoids ordinals)

---

### Q5: High Cardinality, Medium size
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "Title",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Variables**: cardinality=9.3M, size=100, shard_size=1000, ratio=10, docs=100M, avg_key_len=65  
**Expected**: Streaming (high cardinality + long keys)

---

### Q6: High Cardinality, Large result set
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "Title",
        "size": 1000,
        "shard_size": 10000
      }
    }
  }
}'
```
**Variables**: cardinality=9.3M, size=1000, shard_size=10000, ratio=10, docs=100M  
**Expected**: Streaming (large result + high cardinality)

---

### Q7: Very High Cardinality, Medium size
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "UserID",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Variables**: cardinality=17.5M, size=100, shard_size=1000, ratio=10, docs=100M  
**Expected**: Streaming (very high cardinality)

---

### Q8: Very High Cardinality, Long keys
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "URL",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Variables**: cardinality=18.2M, size=100, shard_size=1000, ratio=10, docs=100M, avg_key_len=79  
**Expected**: Streaming (very high cardinality + long keys)

---

### Q9: Filtered Query - 10% selectivity
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "query": {
    "range": {
      "EventTime": {
        "gte": "2013-07-15T00:00:00",
        "lt": "2013-07-16T00:00:00"
      }
    }
  },
  "aggs": {
    "test": {
      "terms": {
        "field": "UserID",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Variables**: cardinality=2.8M (filtered), size=100, shard_size=1000, ratio=10, docs=10.5M (10.5%)  
**Expected**: Streaming (reduced working set + high cardinality)

---

### Q10: Filtered Query - 18% selectivity
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "query": {
    "term": {
      "RegionID": 229
    }
  },
  "aggs": {
    "test": {
      "terms": {
        "field": "UserID",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Variables**: cardinality=2.8M (filtered), size=100, shard_size=1000, ratio=10, docs=18.4M (18.4%)  
**Expected**: Streaming (reduced working set)

---

### Q11: Filtered Query - 67% selectivity (Low benefit)
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "query": {
    "term": {
      "JavaEnable": 1
    }
  },
  "aggs": {
    "test": {
      "terms": {
        "field": "UserID",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Variables**: cardinality=~12M (filtered), size=100, shard_size=1000, ratio=10, docs=67.2M (67%)  
**Expected**: Streaming (still high cardinality)

---

### Q12: Extreme - Very High Cardinality, High ratio
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "WatchID",
        "size": 100,
        "shard_size": 10000
      }
    }
  }
}'
```
**Variables**: cardinality=99.4M, size=100, shard_size=10000, ratio=100, docs=100M  
**Expected**: Streaming (extreme cardinality, nearly unique per doc)

---

## Expected Results Matrix

| Query | Field | Cardinality | size | shard_size | Ratio | Selectivity | Expected Winner | Key Factor |
|-------|-------|-------------|------|------------|-------|-------------|-----------------|------------|
| Q1 | RegionID | 9K | 10 | default | ~1 | 100% | Traditional | Low card + small result |
| Q2 | RegionID | 9K | 10 | 1000 | 100 | 100% | Traditional | Low card despite ratio |
| Q3 | SearchPhrase | 5.9M | 100 | 1000 | 10 | 100% | Streaming | Avoids ordinals |
| Q4 | SearchPhrase | 5.9M | 100 | 10000 | 100 | 100% | Streaming | High ratio |
| Q5 | Title | 9.3M | 100 | 1000 | 10 | 100% | Streaming | High card + long keys |
| Q6 | Title | 9.3M | 1000 | 10000 | 10 | 100% | Streaming | Large result set |
| Q7 | UserID | 17.5M | 100 | 1000 | 10 | 100% | Streaming | Very high card |
| Q8 | URL | 18.2M | 100 | 1000 | 10 | 100% | Streaming | Very high card + long keys |
| Q9 | UserID | 2.8M | 100 | 1000 | 10 | 10.5% | Streaming | Reduced working set |
| Q10 | UserID | 2.8M | 100 | 1000 | 10 | 18.4% | Streaming | Reduced working set |
| Q11 | UserID | ~12M | 100 | 1000 | 10 | 67% | Streaming | Still high card |
| Q12 | WatchID | 99.4M | 100 | 10000 | 100 | 100% | Streaming | Extreme cardinality |

## Variables for FlushModeResolver

Based on these queries, refine:

```java
// Cardinality thresholds
private static final long LOW_CARDINALITY_THRESHOLD = 10_000;
private static final long MEDIUM_CARDINALITY_THRESHOLD = 1_000_000;
private static final long HIGH_CARDINALITY_THRESHOLD = 10_000_000;

// Shard size ratio threshold
private static final double SHARD_SIZE_RATIO_THRESHOLD = 10.0;

// Key length impact (bytes)
private static final int LONG_KEY_THRESHOLD = 50;

// Query selectivity benefit threshold
private static final double SELECTIVITY_BENEFIT_THRESHOLD = 0.5; // 50%

// Decision logic
boolean useStreaming = 
    cardinality > LOW_CARDINALITY_THRESHOLD && (
        cardinality > HIGH_CARDINALITY_THRESHOLD ||
        (shardSize / size) > SHARD_SIZE_RATIO_THRESHOLD ||
        avgKeyLength > LONG_KEY_THRESHOLD ||
        querySelectivity < SELECTIVITY_BENEFIT_THRESHOLD
    );
```

## Metrics to Collect

For each query, record:
- `took` (ms)
- `_shards.total` and `_shards.successful`
- Memory usage (if available via profiling)
- Network bytes (estimate: buckets * (key_length + 16))

Compare streaming vs traditional mode and validate against predictions.
