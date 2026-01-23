# Streaming Aggregation Benchmarking Guide

This guide provides queries to benchmark streaming vs traditional aggregations using ClickBench data to refine cost estimates in `FlushModeResolver.java`.

## Variables for Cost Estimation

| Variable | Description | How to Estimate |
|----------|-------------|-----------------|
| `shard_size` | Number of buckets per shard | Query parameter |
| `size` | Final number of buckets | Query parameter |
| `avg_key_length` | Average term length in bytes | Field stats API |
| `total_docs` | Total documents in index | `_count` API |
| `total_cardinality` | Unique values in field | Cardinality agg |
| `query_matching_docs` | Docs matching query | Query with `track_total_hits` |
| `query_matching_cardinality` | Unique values in result set | Cardinality on filtered data |

## ClickBench Dataset Overview

**Index**: `hits_split`  
**Endpoint**: `172.31.53.13:9200/hits_split/_search`

### Key Fields for Testing

```bash
# Get field statistics
curl -X GET "172.31.53.13:9200/hits_split/_field_caps?fields=*&pretty"

# Get document count
curl -X GET "172.31.53.13:9200/hits_split/_count?pretty"
```

## Benchmark Query Templates

### 1. Low Cardinality Terms (Baseline)

**Variables**: `total_cardinality ~100`, `avg_key_length ~10`

```json
POST 172.31.53.13:9200/hits_split/_search
{
  "size": 0,
  "aggs": {
    "top_regions": {
      "terms": {
        "field": "RegionID",
        "size": 10,
        "shard_size": 100
      }
    }
  }
}
```

**Expected**: Traditional wins (overhead not justified)

---

### 2. Medium Cardinality with Size Variation

**Variables**: `total_cardinality ~10K`, vary `size` and `shard_size`

```json
POST 172.31.53.13:9200/hits_split/_search
{
  "size": 0,
  "aggs": {
    "top_urls": {
      "terms": {
        "field": "URL.keyword",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}
```

**Variations to test**:
- `size=10, shard_size=100`
- `size=100, shard_size=1000`
- `size=100, shard_size=10000`
- `size=1000, shard_size=10000`

---

### 3. High Cardinality Terms

**Variables**: `total_cardinality >100K`, `avg_key_length ~50`

```json
POST 172.31.53.13:9200/hits_split/_search
{
  "size": 0,
  "aggs": {
    "unique_urls": {
      "terms": {
        "field": "URL.keyword",
        "size": 1000,
        "shard_size": 10000
      }
    }
  }
}
```

**Expected**: Streaming wins when `shard_size >> size` and memory pressure exists

---

### 4. Cardinality Aggregation (Pure Win)

**Variables**: All cardinalities

```json
POST 172.31.53.13:9200/hits_split/_search
{
  "size": 0,
  "aggs": {
    "unique_users": {
      "cardinality": {
        "field": "UserID"
      }
    },
    "unique_urls": {
      "cardinality": {
        "field": "URL.keyword"
      }
    }
  }
}
```

**Expected**: Streaming always wins (50-60% network reduction)

---

### 5. Terms with Cardinality Sub-Agg

**Variables**: Parent cardinality ~1K, child cardinality ~100K

```json
POST 172.31.53.13:9200/hits_split/_search
{
  "size": 0,
  "aggs": {
    "by_region": {
      "terms": {
        "field": "RegionID",
        "size": 100
      },
      "aggs": {
        "unique_users": {
          "cardinality": {
            "field": "UserID"
          }
        }
      }
    }
  }
}
```

**Expected**: Streaming wins (eliminates global ordinals + HLL streaming)

---

### 6. Terms with Stats/Percentiles

**Variables**: Vary parent cardinality

```json
POST 172.31.53.13:9200/hits_split/_search
{
  "size": 0,
  "aggs": {
    "by_url": {
      "terms": {
        "field": "URL.keyword",
        "size": 100,
        "shard_size": 1000
      },
      "aggs": {
        "response_time_stats": {
          "percentiles": {
            "field": "EventTime",
            "percents": [50, 95, 99]
          }
        }
      }
    }
  }
}
```

**Expected**: Streaming wins for medium-high cardinality (sketch streaming)

---

### 7. Date Histogram with Terms Sub-Agg

**Variables**: Date buckets ~30, terms cardinality ~10K

```json
POST 172.31.53.13:9200/hits_split/_search
{
  "size": 0,
  "aggs": {
    "daily": {
      "date_histogram": {
        "field": "EventTime",
        "calendar_interval": "day"
      },
      "aggs": {
        "top_urls": {
          "terms": {
            "field": "URL.keyword",
            "size": 100
          }
        }
      }
    }
  }
}
```

**Expected**: Streaming wins (parent provides natural partitioning)

---

### 8. Filtered Aggregation (Query Selectivity)

**Variables**: Test different `query_matching_docs` ratios

```json
POST 172.31.53.13:9200/hits_split/_search
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
    "top_urls": {
      "terms": {
        "field": "URL.keyword",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}
```

**Variations**:
- 1 day (high selectivity, ~1% docs)
- 7 days (medium selectivity, ~10% docs)
- 30 days (low selectivity, ~50% docs)
- No filter (100% docs)

---

## Pre-Query Statistics Collection

Run these before benchmarking to gather variables:

```bash
# 1. Total document count
curl -X GET "172.31.53.13:9200/hits_split/_count?pretty"

# 2. Field cardinality estimates
curl -X POST "172.31.53.13:9200/hits_split/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "url_cardinality": {"cardinality": {"field": "URL.keyword"}},
    "user_cardinality": {"cardinality": {"field": "UserID"}},
    "region_cardinality": {"cardinality": {"field": "RegionID"}}
  }
}'

# 3. Average key length (sample)
curl -X POST "172.31.53.13:9200/hits_split/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "size": 100,
  "_source": ["URL.keyword"],
  "script_fields": {
    "url_length": {
      "script": "doc[\"URL.keyword\"].value.length()"
    }
  }
}'

# 4. Query matching docs
curl -X POST "172.31.53.13:9200/hits_split/_count?pretty" -H 'Content-Type: application/json' -d'
{
  "query": {
    "range": {
      "EventTime": {
        "gte": "2013-07-01",
        "lte": "2013-07-07"
      }
    }
  }
}'
```

## Cost Model Variables to Refine

Based on benchmarks, refine these in `FlushModeResolver.java`:

```java
// Network cost factors
private static final double STREAMING_OVERHEAD_PER_BUCKET = 1.5; // bytes per repeated bucket
private static final double TRADITIONAL_OVERHEAD_PER_BUCKET = 1.0;

// Memory cost factors
private static final long GLOBAL_ORDINAL_COST_PER_UNIQUE_TERM = 8; // bytes
private static final long BUCKET_STATE_COST = 64; // bytes per bucket

// Coordinator cost factors
private static final double COORDINATOR_MERGE_COST_PER_BUCKET = 0.1; // ms
private static final double STREAMING_COORDINATOR_MULTIPLIER = 2.0;

// Cardinality thresholds
private static final long LOW_CARDINALITY_THRESHOLD = 10_000;
private static final long HIGH_CARDINALITY_THRESHOLD = 100_000;

// Shard size ratio threshold
private static final double SHARD_SIZE_RATIO_THRESHOLD = 10.0; // shard_size/size
```

## Benchmark Execution Script

```bash
#!/bin/bash

HOST="172.31.53.13:9200"
INDEX="hits_split"

# Function to run query and measure
run_benchmark() {
  local query_file=$1
  local mode=$2  # "streaming" or "traditional"
  
  echo "Running $query_file with $mode mode..."
  
  # Run query 5 times, discard first (warmup)
  for i in {1..5}; do
    response=$(curl -s -X POST "$HOST/$INDEX/_search?preference=_local&search_pipeline=$mode" \
      -H 'Content-Type: application/json' \
      -d @"$query_file" \
      -w "\nTime: %{time_total}s\n")
    
    if [ $i -gt 1 ]; then
      echo "$response" | grep "took\|Time:"
    fi
  done
}

# Test each query
for query in queries/*.json; do
  echo "=== Testing $query ==="
  run_benchmark "$query" "traditional"
  run_benchmark "$query" "streaming"
  echo ""
done
```

## Expected Results Matrix

| Query Type | Cardinality | shard_size/size | Expected Winner | Key Factor |
|------------|-------------|-----------------|-----------------|------------|
| Simple Terms | <10K | <5 | Traditional | Overhead not justified |
| Simple Terms | 10K-100K | 5-10 | Streaming | Memory savings |
| Simple Terms | >100K | >10 | Streaming | Global ordinal cost |
| Cardinality | Any | N/A | Streaming | Network reduction |
| Terms + Cardinality | >10K | Any | Streaming | HLL streaming |
| Terms + Percentiles | >50K | >5 | Streaming | Sketch streaming |
| Date Histogram + Terms | Any | Any | Streaming | Natural partitioning |
| Filtered (1% docs) | >10K | >5 | Streaming | Reduced data volume |

## Metrics to Collect

For each query, record:

```json
{
  "query_id": "terms_medium_cardinality",
  "mode": "streaming",
  "took_ms": 245,
  "memory_used_mb": 120,
  "network_bytes": 15728640,
  "coordinator_cpu_ms": 45,
  "data_node_cpu_ms": 180,
  "variables": {
    "total_docs": 8873898,
    "total_cardinality": 45231,
    "query_matching_docs": 887389,
    "query_matching_cardinality": 12456,
    "size": 100,
    "shard_size": 1000,
    "avg_key_length": 48
  }
}
```

## Analysis Steps

1. **Collect baseline metrics** for all queries with both modes
2. **Calculate cost ratios**: `streaming_cost / traditional_cost`
3. **Identify breakeven points** for each variable
4. **Refine thresholds** in `FlushModeResolver.java`
5. **Validate** with additional queries at boundary conditions
