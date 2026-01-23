#!/bin/bash

HOST="172.31.53.13:9200"
INDEX="hits_split"

echo "Analyzing dataset cardinalities for benchmark query selection..."
echo ""

# Get total docs
total=$(curl -s "$HOST/$INDEX/_count" | jq -r '.count')
echo "Total documents: $total"
echo ""

# Analyze all fields
fields=("RegionID" "CounterID" "UserID" "WatchID" "SearchPhrase" "Title" "URL" "RefererDomain" "URLDomain")

echo "=== Unfiltered Cardinalities ==="
for field in "${fields[@]}"; do
  card=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"{
    \"size\":0,
    \"aggs\":{\"card\":{\"cardinality\":{\"field\":\"$field\",\"precision_threshold\":40000}}}
  }" | jq -r '.aggregations.card.value')
  printf "%-20s: %10s\n" "$field" "$card"
done

echo ""
echo "=== Filtered Cardinalities (1-day filter) ==="
for field in "${fields[@]}"; do
  card=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"{
    \"size\":0,
    \"query\":{\"range\":{\"EventTime\":{\"gte\":\"2013-07-15T00:00:00\",\"lt\":\"2013-07-16T00:00:00\"}}},
    \"aggs\":{\"card\":{\"cardinality\":{\"field\":\"$field\",\"precision_threshold\":40000}}}
  }" | jq -r '.aggregations.card.value')
  printf "%-20s: %10s\n" "$field" "$card"
done

echo ""
echo "=== Filtered Cardinalities (RegionID=229) ==="
for field in "${fields[@]}"; do
  card=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"{
    \"size\":0,
    \"query\":{\"term\":{\"RegionID\":229}},
    \"aggs\":{\"card\":{\"cardinality\":{\"field\":\"$field\",\"precision_threshold\":40000}}}
  }" | jq -r '.aggregations.card.value')
  printf "%-20s: %10s\n" "$field" "$card"
done

echo ""
echo "=== Filtered Cardinalities (JavaEnable=1) ==="
for field in "${fields[@]}"; do
  card=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"{
    \"size\":0,
    \"query\":{\"term\":{\"JavaEnable\":1}},
    \"aggs\":{\"card\":{\"cardinality\":{\"field\":\"$field\",\"precision_threshold\":40000}}}
  }" | jq -r '.aggregations.card.value')
  printf "%-20s: %10s\n" "$field" "$card"
done

echo ""
echo "=== Query Selectivity ==="
day_count=$(curl -s -X POST "$HOST/$INDEX/_count" -H 'Content-Type: application/json' -d'{
  "query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}}
}' | jq -r '.count')
echo "1-day filter: $day_count docs ($(echo "scale=1; $day_count * 100 / $total" | bc)%)"

region_count=$(curl -s -X POST "$HOST/$INDEX/_count" -H 'Content-Type: application/json' -d'{
  "query":{"term":{"RegionID":229}}
}' | jq -r '.count')
echo "RegionID=229: $region_count docs ($(echo "scale=1; $region_count * 100 / $total" | bc)%)"

java_count=$(curl -s -X POST "$HOST/$INDEX/_count" -H 'Content-Type: application/json' -d'{
  "query":{"term":{"JavaEnable":1}}
}' | jq -r '.count')
echo "JavaEnable=1: $java_count docs ($(echo "scale=1; $java_count * 100 / $total" | bc)%)"

echo ""
echo "=== Recommendations ==="
echo "Fields in 100K-1M range (streaming should win):"
echo "  - Look for fields with cardinality between 100,000 and 1,000,000"
echo ""
echo "Fields >1M (traditional should win):"
echo "  - Look for fields with cardinality > 1,000,000"
