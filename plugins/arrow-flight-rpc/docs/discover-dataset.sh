#!/bin/bash

# Dataset Discovery Script for Streaming Aggregation Benchmarking
# This script analyzes the ClickBench dataset to identify suitable fields and queries

HOST="172.31.53.13:9200"
INDEX="hits_split"

echo "=========================================="
echo "ClickBench Dataset Discovery"
echo "=========================================="
echo ""

# 1. Total document count
echo "1. Total Documents:"
curl -s -X GET "$HOST/$INDEX/_count" | jq '.count'
echo ""

# 2. Get all fields
echo "2. Available Fields:"
curl -s -X GET "$HOST/$INDEX/_mapping" | jq -r '.hits_split.mappings.properties | keys[]'
echo ""

# 3. Field cardinalities - numeric fields
echo "3. Numeric Field Cardinalities:"
for field in "RegionID" "CounterID" "UserID" "WatchID" "JavaEnable" "GoodEvent" "CounterClass" "AdvEngineID" "Age" "FlashMajor" "FlashMinor" "NetMajor" "NetMinor"; do
  card=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"{
    \"size\": 0,
    \"aggs\": {
      \"card\": {
        \"cardinality\": {
          \"field\": \"$field\",
          \"precision_threshold\": 40000
        }
      }
    }
  }" | jq -r '.aggregations.card.value')
  printf "  %-20s: %s\n" "$field" "$card"
done
echo ""

# 4. Text field cardinalities (non-.keyword)
echo "4. Text Field Cardinalities:"
for field in "URL" "Referer" "URLDomain" "RefererDomain" "Title" "SearchPhrase"; do
  card=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"{
    \"size\": 0,
    \"aggs\": {
      \"card\": {
        \"cardinality\": {
          \"field\": \"$field\",
          \"precision_threshold\": 40000
        }
      }
    }
  }" | jq -r '.aggregations.card.value')
  printf "  %-25s: %s\n" "$field" "$card"
done
echo ""

# 5. Sample average key lengths for text fields
echo "5. Average Key Lengths (sample of 100 docs):"
for field in "URL" "Referer" "URLDomain" "RefererDomain" "Title" "SearchPhrase"; do
  result=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"{
    \"size\": 100,
    \"_source\": [\"$field\"],
    \"query\": {\"exists\": {\"field\": \"$field\"}}
  }" | jq -r '[.hits.hits[]._source.'"$field"' | select(. != null) | length] | if length > 0 then (add / length | floor) else 0 end')
  printf "  %-25s: %s bytes\n" "$field" "$result"
done
echo ""

# 6. Date range analysis
echo "6. Date Range:"
curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": {
    "min_date": {"min": {"field": "EventTime"}},
    "max_date": {"max": {"field": "EventTime"}}
  }
}' | jq -r '
  "  Min: " + (.aggregations.min_date.value_as_string // (.aggregations.min_date.value | tostring)) + "\n" +
  "  Max: " + (.aggregations.max_date.value_as_string // (.aggregations.max_date.value | tostring))
'
echo ""

# 7. Sample queries with different selectivity
echo "7. Query Selectivity Analysis:"

# 7a. Single region (high selectivity)
region_count=$(curl -s -X POST "$HOST/$INDEX/_count" -H 'Content-Type: application/json' -d'
{
  "query": {"term": {"RegionID": 229}}
}' | jq -r '.count')
total_count=$(curl -s -X GET "$HOST/$INDEX/_count" | jq -r '.count')
selectivity=$(echo "scale=2; $region_count * 100 / $total_count" | bc)
echo "  RegionID=229: $region_count docs (${selectivity}% selectivity)"

# 7b. Date range - 1 day
day_count=$(curl -s -X POST "$HOST/$INDEX/_count" -H 'Content-Type: application/json' -d'
{
  "query": {
    "range": {
      "EventTime": {
        "gte": "2013-07-15T00:00:00",
        "lt": "2013-07-16T00:00:00"
      }
    }
  }
}' | jq -r '.count')
selectivity=$(echo "scale=2; $day_count * 100 / $total_count" | bc)
echo "  1 day (2013-07-15): $day_count docs (${selectivity}% selectivity)"

# 7c. Date range - 7 days
week_count=$(curl -s -X POST "$HOST/$INDEX/_count" -H 'Content-Type: application/json' -d'
{
  "query": {
    "range": {
      "EventTime": {
        "gte": "2013-07-15T00:00:00",
        "lt": "2013-07-22T00:00:00"
      }
    }
  }
}' | jq -r '.count')
selectivity=$(echo "scale=2; $week_count * 100 / $total_count" | bc)
echo "  7 days (2013-07-15 to 22): $week_count docs (${selectivity}% selectivity)"

# 7d. JavaEnable filter
java_count=$(curl -s -X POST "$HOST/$INDEX/_count" -H 'Content-Type: application/json' -d'
{
  "query": {"term": {"JavaEnable": 1}}
}' | jq -r '.count')
selectivity=$(echo "scale=2; $java_count * 100 / $total_count" | bc)
echo "  JavaEnable=1: $java_count docs (${selectivity}% selectivity)"

echo ""

# 8. Cardinality under different filters
echo "8. Cardinality Under Filters:"

# UserID cardinality with region filter
user_card_region=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "query": {"term": {"RegionID": 229}},
  "aggs": {
    "user_card": {"cardinality": {"field": "UserID", "precision_threshold": 40000}}
  }
}' | jq -r '.aggregations.user_card.value')
echo "  UserID (RegionID=229): $user_card_region"

# UserID cardinality with date filter
user_card_date=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d'
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
    "user_card": {"cardinality": {"field": "UserID", "precision_threshold": 40000}}
  }
}' | jq -r '.aggregations.user_card.value')
echo "  UserID (1 day): $user_card_date"

echo ""

# 9. Summary and recommendations
echo "=========================================="
echo "SUMMARY & RECOMMENDATIONS"
echo "=========================================="
echo ""
echo "Based on the analysis above, suitable fields for benchmarking:"
echo ""
echo "LOW CARDINALITY (<10K):"
echo "  - JavaEnable (2), GoodEvent (1), CounterClass (2)"
echo "  - RegionID (9K), CounterID (6K)"
echo "  - Use for: Baseline tests where traditional should win"
echo ""
echo "MEDIUM CARDINALITY (10K-1M):"
echo "  - Fields with 10K-100K unique values"
echo "  - Use for: Testing shard_size/size ratio effects"
echo ""
echo "HIGH CARDINALITY (>1M):"
echo "  - UserID (17M), WatchID (99M)"
echo "  - Use for: Testing where streaming should win"
echo ""
echo "QUERY FILTERS for selectivity testing:"
echo "  - Medium selectivity (~10%): 1-day date range"
echo "  - Medium selectivity (~18%): RegionID=229"
echo "  - Medium selectivity (~28%): 7-day date range"
echo "  - High selectivity (~67%): JavaEnable=1"
echo ""
