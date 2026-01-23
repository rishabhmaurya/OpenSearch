#!/bin/bash

HOST="172.31.53.13:9200"
INDEX="hits_split"
WARMUP_RUNS=2
BENCHMARK_RUNS=3

set_streaming() {
  curl -s -X PUT "$HOST/_cluster/settings" -H 'Content-Type: application/json' -d"{
    \"persistent\": {\"stream.search.enabled\": $1}
  }" > /dev/null
  sleep 2
}

benchmark_query() {
  local query_id=$1
  local query=$2
  
  # Traditional mode
  set_streaming false
  for i in $(seq 1 $WARMUP_RUNS); do
    curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query" > /dev/null
  done
  
  local trad_total=0
  local trad_response=""
  for i in $(seq 1 $BENCHMARK_RUNS); do
    trad_response=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query")
    local took=$(echo "$trad_response" | jq -r '.took')
    trad_total=$((trad_total + took))
  done
  local trad_ms=$((trad_total / BENCHMARK_RUNS))
  local trad_keys=$(echo "$trad_response" | jq -r '.aggregations.test.buckets[].key' | sort)
  
  # Streaming mode
  set_streaming true
  for i in $(seq 1 $WARMUP_RUNS); do
    curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query" > /dev/null
  done
  
  local stream_total=0
  local stream_response=""
  for i in $(seq 1 $BENCHMARK_RUNS); do
    stream_response=$(curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query")
    local took=$(echo "$stream_response" | jq -r '.took')
    stream_total=$((stream_total + took))
  done
  local stream_ms=$((stream_total / BENCHMARK_RUNS))
  local stream_keys=$(echo "$stream_response" | jq -r '.aggregations.test.buckets[].key' | sort)
  
  # Calculate accuracy
  local total_keys=$(echo "$trad_keys" | wc -l | tr -d ' ')
  local matching_keys=$(comm -12 <(echo "$trad_keys") <(echo "$stream_keys") | wc -l | tr -d ' ')
  local accuracy=$(echo "scale=1; $matching_keys * 100 / $total_keys" | bc)
  
  # Calculate winner
  if [ $stream_ms -lt $trad_ms ]; then
    local winner="Streaming"
    local improvement=$(echo "scale=1; ($trad_ms - $stream_ms) * 100 / $trad_ms" | bc)
  else
    local winner="Traditional"
    local improvement=$(echo "scale=1; ($stream_ms - $trad_ms) * 100 / $stream_ms" | bc)
  fi
  
  echo "$query_id|$trad_ms|$stream_ms|$winner|${improvement}|${accuracy}"
}

# Query metadata
declare -A query_meta
query_meta["Q1"]="RegionID|9K|10|default|1|Traditional"
query_meta["Q2"]="RegionID|9K|10|1000|100|Traditional"
query_meta["Q3"]="SearchPhrase|5.9M|100|1000|10|Streaming"
query_meta["Q4"]="SearchPhrase|5.9M|100|10000|100|Streaming"
query_meta["Q5"]="Title|9.3M|100|1000|10|Streaming"
query_meta["Q6"]="Title|9.3M|1000|10000|10|Streaming"
query_meta["Q7"]="UserID|17.5M|100|1000|10|Streaming"
query_meta["Q8"]="URL|18.2M|100|1000|10|Streaming"
query_meta["Q9"]="UserID|2.8M|100|1000|10|Streaming"
query_meta["Q10"]="UserID|2.8M|100|1000|10|Streaming"
query_meta["Q11"]="UserID|~12M|100|1000|10|Streaming"
query_meta["Q12"]="WatchID|99.4M|100|10000|100|Streaming"

declare -A queries
queries["Q1"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":10}}}}'
queries["Q2"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":10,"shard_size":1000}}}}'
queries["Q3"]='{"size":0,"aggs":{"test":{"terms":{"field":"SearchPhrase","size":100,"shard_size":1000}}}}'
queries["Q4"]='{"size":0,"aggs":{"test":{"terms":{"field":"SearchPhrase","size":100,"shard_size":10000}}}}'
queries["Q5"]='{"size":0,"aggs":{"test":{"terms":{"field":"Title","size":100,"shard_size":1000}}}}'
queries["Q6"]='{"size":0,"aggs":{"test":{"terms":{"field":"Title","size":1000,"shard_size":10000}}}}'
queries["Q7"]='{"size":0,"aggs":{"test":{"terms":{"field":"UserID","size":100,"shard_size":1000}}}}'
queries["Q8"]='{"size":0,"aggs":{"test":{"terms":{"field":"URL","size":100,"shard_size":1000}}}}'
queries["Q9"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"UserID","size":100,"shard_size":1000}}}}'
queries["Q10"]='{"size":0,"query":{"term":{"RegionID":229}},"aggs":{"test":{"terms":{"field":"UserID","size":100,"shard_size":1000}}}}'
queries["Q11"]='{"size":0,"query":{"term":{"JavaEnable":1}},"aggs":{"test":{"terms":{"field":"UserID","size":100,"shard_size":1000}}}}'
queries["Q12"]='{"size":0,"aggs":{"test":{"terms":{"field":"WatchID","size":100,"shard_size":10000}}}}'

echo "=============================================================================================================="
echo "Streaming vs Traditional Benchmark Results"
echo "=============================================================================================================="
echo ""
printf "%-5s | %-12s | %-6s | %-4s | %-10s | %-5s | %-8s | %-10s | %-10s | %-8s | %-8s | %-10s\n" \
  "Query" "Field" "Card" "size" "shard_size" "ratio" "Trad(ms)" "Stream(ms)" "Winner" "Improve%" "Accuracy%" "Expected"
echo "------|--------------|--------|------|------------|-------|----------|------------|------------|----------|-----------|----------"

for query_id in Q1 Q2 Q3 Q4 Q5 Q6 Q7 Q8 Q9 Q10 Q11 Q12; do
  echo -n "Running $query_id... " >&2
  result=$(benchmark_query "$query_id" "${queries[$query_id]}")
  echo "Done" >&2
  
  IFS='|' read -r field card size shard_size ratio expected <<< "${query_meta[$query_id]}"
  IFS='|' read -r qid trad_ms stream_ms winner improve accuracy <<< "$result"
  
  printf "%-5s | %-12s | %-6s | %-4s | %-10s | %-5s | %-8s | %-10s | %-10s | %-8s | %-8s | %-10s\n" \
    "$qid" "$field" "$card" "$size" "$shard_size" "$ratio" "$trad_ms" "$stream_ms" "$winner" "$improve" "$accuracy" "$expected"
done

set_streaming false

echo ""
echo "=============================================================================================================="
echo "Benchmark Complete"
echo "=============================================================================================================="
