#!/bin/bash

HOST="172.31.53.13:9200"
INDEX="hits_split"
WARMUP_RUNS=2
BENCHMARK_RUNS=3

set_streaming() {
  local enabled=$1
  curl -s -X PUT "$HOST/_cluster/settings" -H 'Content-Type: application/json' -d"{
    \"persistent\": {
      \"stream.search.enabled\": $enabled
    }
  }" > /dev/null
  sleep 2
}

run_query() {
  local query=$1
  local runs=$2
  local total=0
  for i in $(seq 1 $runs); do
    took=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query" | jq -r '.took')
    total=$((total + took))
  done
  echo $((total / runs))
}

benchmark_query() {
  local query_id=$1
  local query=$2
  
  set_streaming false
  for i in $(seq 1 $WARMUP_RUNS); do
    curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query" > /dev/null
  done
  traditional_ms=$(run_query "$query" $BENCHMARK_RUNS)
  
  set_streaming true
  for i in $(seq 1 $WARMUP_RUNS); do
    curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query" > /dev/null
  done
  streaming_ms=$(run_query "$query" $BENCHMARK_RUNS)
  
  if [ $streaming_ms -lt $traditional_ms ]; then
    winner="Streaming"
    improvement=$(echo "scale=1; ($traditional_ms - $streaming_ms) * 100 / $traditional_ms" | bc)
  else
    winner="Traditional"
    improvement=$(echo "scale=1; ($streaming_ms - $traditional_ms) * 100 / $streaming_ms" | bc)
  fi
  
  echo "$query_id|$traditional_ms|$streaming_ms|$winner|${improvement}%"
}

# Query metadata: Field|Cardinality|size|shard_size|ratio|selectivity|key_len|expected_winner|reason
declare -A query_meta
query_meta["Q1"]="RegionID|9K|10|default|1|100%|5|Traditional|Low card, small result"
query_meta["Q2"]="RegionID|9K|100|1000|10|100%|5|?|Low card, medium result"
query_meta["Q3"]="RegionID|9K|1000|10000|10|100%|5|?|Low card, large result"
query_meta["Q4"]="CounterID|6K|100|1000|10|100%|8|?|Low card, medium result"
query_meta["Q5"]="CounterID|6K|1000|5000|5|100%|8|?|Low card, all buckets"
query_meta["Q6"]="AdvEngineID|19|10|100|10|100%|4|Traditional|Very low card"
query_meta["Q7"]="Age|6|6|50|8|100%|2|Traditional|Tiny card"
query_meta["Q8"]="RegionID|9K|100|default|1|10.5%|5|?|Low card, filtered"
query_meta["Q9"]="CounterID|6K|100|default|1|18.4%|8|?|Low card, filtered"
query_meta["Q10"]="RegionID|9K|500|5000|10|100%|5|?|Low card, mid-large result"

# Query definitions - REVISED for low-medium cardinality
declare -A queries
queries["Q1"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":10}}}}'
queries["Q2"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":100,"shard_size":1000}}}}'
queries["Q3"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":1000,"shard_size":10000}}}}'
queries["Q4"]='{"size":0,"aggs":{"test":{"terms":{"field":"CounterID","size":100,"shard_size":1000}}}}'
queries["Q5"]='{"size":0,"aggs":{"test":{"terms":{"field":"CounterID","size":1000,"shard_size":5000}}}}'
queries["Q6"]='{"size":0,"aggs":{"test":{"terms":{"field":"AdvEngineID","size":10,"shard_size":100}}}}'
queries["Q7"]='{"size":0,"aggs":{"test":{"terms":{"field":"Age","size":6,"shard_size":50}}}}'
queries["Q8"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"RegionID","size":100}}}}'
queries["Q9"]='{"size":0,"query":{"term":{"RegionID":229}},"aggs":{"test":{"terms":{"field":"CounterID","size":100}}}}'
queries["Q10"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":500,"shard_size":5000}}}}'

echo "======================================================================================================================"
echo "Streaming vs Traditional Benchmark Results (LOW-MEDIUM CARDINALITY FOCUS)"
echo "======================================================================================================================"
echo ""
printf "%-5s | %-12s | %-6s | %-4s | %-10s | %-5s | %-6s | %-7s | %-8s | %-10s | %-10s | %-7s | %-10s\n" \
  "Query" "Field" "Card" "size" "shard_size" "ratio" "select" "key_len" "Trad(ms)" "Stream(ms)" "Winner" "Improve" "Expected"
echo "------|--------------|--------|------|------------|-------|--------|---------|----------|------------|------------|---------|----------"

for query_id in Q1 Q2 Q3 Q4 Q5 Q6 Q7 Q8 Q9 Q10; do
  echo -n "Running $query_id... " >&2
  result=$(benchmark_query "$query_id" "${queries[$query_id]}")
  
  IFS='|' read -r field card size shard_size ratio select key_len expected reason <<< "${query_meta[$query_id]}"
  IFS='|' read -r qid trad_ms stream_ms winner improve <<< "$result"
  
  printf "%-5s | %-12s | %-6s | %-4s | %-10s | %-5s | %-6s | %-7s | %-8s | %-10s | %-10s | %-7s | %-10s\n" \
    "$qid" "$field" "$card" "$size" "$shard_size" "$ratio" "$select" "$key_len" "$trad_ms" "$stream_ms" "$winner" "$improve" "$expected"
  
  echo "Done ($reason)" >&2
done

set_streaming false

echo ""
echo "======================================================================================================================"
echo "Analysis"
echo "======================================================================================================================"
echo ""
echo "Key Insight: Streaming loses when cardinality >> size because it streams ALL buckets per segment,"
echo "while traditional does TopK filtering at segment level."
echo ""
echo "Streaming should win when:"
echo "  1. Cardinality is close to size (e.g., 6K cardinality, size=1000)"
echo "  2. Need to avoid global ordinal building overhead"
echo "  3. With concurrent segment search (eliminates cross-slice merge)"
echo ""
echo "Current results suggest streaming aggregations need TopK filtering at segment level to be competitive."
