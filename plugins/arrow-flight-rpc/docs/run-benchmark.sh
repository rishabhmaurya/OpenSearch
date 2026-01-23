#!/bin/bash

HOST="172.31.53.13:9200"
INDEX="hits_split"
WARMUP_RUNS=2
BENCHMARK_RUNS=3

# Function to enable/disable streaming
set_streaming() {
  local enabled=$1
  curl -s -X PUT "$HOST/_cluster/settings" -H 'Content-Type: application/json' -d"{
    \"persistent\": {
      \"stream.search.enabled\": $enabled
    }
  }" > /dev/null
  sleep 2
}

# Function to run query and get average latency
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

# Function to benchmark a query
benchmark_query() {
  local query_id=$1
  local query=$2
  
  # Warmup with streaming disabled
  set_streaming false
  for i in $(seq 1 $WARMUP_RUNS); do
    curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query" > /dev/null
  done
  
  # Benchmark traditional
  traditional_ms=$(run_query "$query" $BENCHMARK_RUNS)
  
  # Warmup with streaming enabled
  set_streaming true
  for i in $(seq 1 $WARMUP_RUNS); do
    curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query" > /dev/null
  done
  
  # Benchmark streaming
  streaming_ms=$(run_query "$query" $BENCHMARK_RUNS)
  
  # Calculate winner and improvement
  if [ $streaming_ms -lt $traditional_ms ]; then
    winner="Streaming"
    improvement=$(echo "scale=1; ($traditional_ms - $streaming_ms) * 100 / $traditional_ms" | bc)
  else
    winner="Traditional"
    improvement=$(echo "scale=1; ($streaming_ms - $traditional_ms) * 100 / $streaming_ms" | bc)
  fi
  
  echo "$query_id|$traditional_ms|$streaming_ms|$winner|${improvement}%"
}

# Query metadata: Field|Cardinality|size|shard_size|ratio|selectivity|avg_key_len|expected_winner
declare -A query_meta
query_meta["Q1"]="RegionID|9K|10|default|1|100%|5|Traditional"
query_meta["Q2"]="RegionID|9K|10|1000|100|100%|5|Traditional"
query_meta["Q3"]="SearchPhrase|5.9M|100|1000|10|100%|1|Streaming"
query_meta["Q4"]="SearchPhrase|5.9M|100|10000|100|100%|1|Streaming"
query_meta["Q5"]="Title|9.3M|100|1000|10|100%|65|Streaming"
query_meta["Q6"]="Title|9.3M|1000|10000|10|100%|65|Streaming"
query_meta["Q7"]="UserID|17.5M|100|1000|10|100%|8|Streaming"
query_meta["Q8"]="URL|18.2M|100|1000|10|100%|79|Streaming"
query_meta["Q9"]="UserID|2.8M|100|1000|10|10.5%|8|Streaming"
query_meta["Q10"]="UserID|2.8M|100|1000|10|18.4%|8|Streaming"
query_meta["Q11"]="UserID|~12M|100|1000|10|67%|8|Streaming"
query_meta["Q12"]="WatchID|99.4M|100|10000|100|100%|8|Streaming"

# Query definitions
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

# Print header
echo "=============================================================================================================="
echo "Streaming vs Traditional Benchmark Results"
echo "=============================================================================================================="
echo ""
printf "%-5s | %-12s | %-6s | %-4s | %-10s | %-5s | %-6s | %-7s | %-8s | %-10s | %-10s | %-7s | %-10s\n" \
  "Query" "Field" "Card" "size" "shard_size" "ratio" "select" "key_len" "Trad(ms)" "Stream(ms)" "Winner" "Improve" "Expected"
echo "------|--------------|--------|------|------------|-------|--------|---------|----------|------------|------------|---------|----------"

# Run benchmarks
for query_id in Q1 Q2 Q3 Q4 Q5 Q6 Q7 Q8 Q9 Q10 Q11 Q12; do
  echo -n "Running $query_id... " >&2
  result=$(benchmark_query "$query_id" "${queries[$query_id]}")
  
  # Parse metadata
  IFS='|' read -r field card size shard_size ratio select key_len expected <<< "${query_meta[$query_id]}"
  
  # Parse result
  IFS='|' read -r qid trad_ms stream_ms winner improve <<< "$result"
  
  # Print formatted row
  printf "%-5s | %-12s | %-6s | %-4s | %-10s | %-5s | %-6s | %-7s | %-8s | %-10s | %-10s | %-7s | %-10s\n" \
    "$qid" "$field" "$card" "$size" "$shard_size" "$ratio" "$select" "$key_len" "$trad_ms" "$stream_ms" "$winner" "$improve" "$expected"
  
  echo "Done" >&2
done

# Reset to default
set_streaming false

echo ""
echo "=============================================================================================================="
echo "Benchmark Complete"
echo "=============================================================================================================="
echo ""
echo "Legend:"
echo "  Card = Cardinality, select = Query selectivity, key_len = Avg key length (bytes)"
echo "  Trad = Traditional mode, Stream = Streaming mode, Improve = Performance improvement %"
echo "  Expected = Predicted winner based on cost model"
