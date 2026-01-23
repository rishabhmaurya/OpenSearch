# Terms Aggregation Benchmarking Guide

Simple guide to benchmark streaming vs traditional terms aggregations using ClickBench data.

## Variables to Test

| Variable | Description | How to Control |
|----------|-------------|----------------|
| `size` | Final buckets returned | Query param |
| `shard_size` | Buckets per shard | Query param |
| `cardinality` | Unique values | Choose different fields |
| `avg_key_length` | Term length | Choose different fields |

## Test Queries

### Query 1: Low Cardinality (Baseline)
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
**Expected cardinality**: ~100  
**Expected winner**: Traditional (overhead not justified)

---

### Query 2: Medium Cardinality, Low size
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "URL.keyword",
        "size": 10,
        "shard_size": 100
      }
    }
  }
}'
```
**Expected cardinality**: ~10K  
**Expected winner**: Traditional (small result set)

---

### Query 3: Medium Cardinality, High shard_size
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "URL.keyword",
        "size": 100,
        "shard_size": 10000
      }
    }
  }
}'
```
**Expected cardinality**: ~10K  
**Expected winner**: Streaming (high shard_size/size ratio = 100)

---

### Query 4: High Cardinality, Medium size
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "test": {
      "terms": {
        "field": "URL.keyword",
        "size": 1000,
        "shard_size": 10000
      }
    }
  }
}'
```
**Expected cardinality**: ~100K  
**Expected winner**: Streaming (avoids global ordinals)

---

### Query 5: With Query Filter (10% selectivity)
```bash
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "query": {
    "range": {
      "EventTime": {
        "gte": "2013-07-01",
        "lte": "2013-07-07"
      }
    }
  },
  "aggs": {
    "test": {
      "terms": {
        "field": "URL.keyword",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Expected winner**: Streaming (reduced working set)

---

### Query 6: With Query Filter (1% selectivity)
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
        "field": "URL.keyword",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}'
```
**Expected winner**: Streaming (very selective query)

---

## Quick Stats Collection

```bash
# Document count
curl -X GET "172.31.53.13:9200/hits_split/_count"

# Field cardinalities
curl -X POST "172.31.53.13:9200/hits_split/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "url_card": {"cardinality": {"field": "URL.keyword"}},
    "user_card": {"cardinality": {"field": "UserID"}},
    "region_card": {"cardinality": {"field": "RegionID"}}
  }
}'
```

## Test Matrix

| Query | Field | size | shard_size | Ratio | Expected Winner |
|-------|-------|------|------------|-------|-----------------|
| Q1 | RegionID | 10 | default | 1 | Traditional |
| Q2 | URL | 10 | 100 | 10 | Traditional |
| Q3 | URL | 100 | 10000 | 100 | Streaming |
| Q4 | URL | 1000 | 10000 | 10 | Streaming |
| Q5 | URL (filtered) | 100 | 1000 | 10 | Streaming |
| Q6 | URL (highly filtered) | 100 | 1000 | 10 | Streaming |

## Variables to Refine in FlushModeResolver.java

```java
// Thresholds to tune based on results
private static final long LOW_CARDINALITY_THRESHOLD = 10_000;
private static final long HIGH_CARDINALITY_THRESHOLD = 100_000;
private static final double SHARD_SIZE_RATIO_THRESHOLD = 10.0; // shard_size/size
private static final double QUERY_SELECTIVITY_THRESHOLD = 0.5; // % docs matched

// Cost multipliers to calibrate
private static final double STREAMING_NETWORK_MULTIPLIER = 1.5;
private static final double GLOBAL_ORDINAL_COST_PER_TERM = 8.0; // bytes
private static final double COORDINATOR_OVERHEAD_MULTIPLIER = 2.0;
```

## Metrics to Record

For each query run:
- `took` (ms) from response
- Memory usage (if available)
- Network bytes transferred
- Winner (streaming vs traditional)

Compare against predictions from FlushModeResolver to refine thresholds.
