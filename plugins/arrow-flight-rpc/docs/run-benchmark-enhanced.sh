#!/bin/bash

HOST="172.31.53.13:9200"
INDEX="hits_split"
WARMUP_RUNS=2
BENCHMARK_RUNS=3
RESULTS_FILE="benchmark-results.json"

set_streaming() {
  local enabled=$1
  curl -s -X PUT "$HOST/_cluster/settings" -H 'Content-Type: application/json' -d"{
    \"persistent\": {
      \"stream.search.enabled\": $enabled
    }
  }" > /dev/null
  sleep 2
}

# Run query once and return full response
run_query_full() {
  local query=$1
  curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query"
}

# Run query multiple times for latency
run_query_latency() {
  local query=$1
  local runs=$2
  local total=0
  for i in $(seq 1 $runs); do
    response=$(run_query_full "$query")
    took=$(echo "$response" | jq -r '.took // -1')
    if [ "$took" = "-1" ]; then
      echo "-1"
      return
    fi
    total=$((total + took))
  done
  echo $((total / runs))
}

# Compare top buckets for accuracy based on doc counts
compare_accuracy() {
  local trad_response=$1
  local stream_response=$2
  
  # Get total doc counts for each mode
  local trad_total=$(echo "$trad_response" | jq '[.aggregations.test.buckets[].doc_count] | add')
  local stream_total=$(echo "$stream_response" | jq '[.aggregations.test.buckets[].doc_count] | add')
  
  # Determine which has higher doc count (more accurate)
  if [ "$stream_total" -gt "$trad_total" ]; then
    local more_accurate="Stream"
    local diff=$((stream_total - trad_total))
    local accuracy_gain=$(echo "scale=1; $diff * 100 / $trad_total" | bc)
    echo "Stream|+${accuracy_gain}"
  elif [ "$trad_total" -gt "$stream_total" ]; then
    local more_accurate="Trad"
    local diff=$((trad_total - stream_total))
    local accuracy_gain=$(echo "scale=1; $diff * 100 / $stream_total" | bc)
    echo "Trad|+${accuracy_gain}"
  else
    echo "Same|0.0"
  fi
}

benchmark_query() {
  local query_id=$1
  local query=$2
  
  # Check if already benchmarked
  if [ -f "$RESULTS_FILE" ] && jq -e ".\"$query_id\"" "$RESULTS_FILE" > /dev/null 2>&1; then
    echo "SKIP"
    return
  fi
  
  # Warmup traditional
  set_streaming false
  for i in $(seq 1 $WARMUP_RUNS); do
    run_query_full "$query" > /dev/null
  done
  
  # Benchmark traditional
  trad_ms=$(run_query_latency "$query" $BENCHMARK_RUNS)
  if [ "$trad_ms" = "-1" ]; then
    echo "ERROR|Traditional query failed"
    return
  fi
  
  # Get traditional results for accuracy comparison
  trad_response=$(run_query_full "$query")
  trad_success=$(echo "$trad_response" | jq -r '._shards.failed')
  
  # Warmup streaming
  set_streaming true
  for i in $(seq 1 $WARMUP_RUNS); do
    run_query_full "$query" > /dev/null
  done
  
  # Benchmark streaming
  stream_ms=$(run_query_latency "$query" $BENCHMARK_RUNS)
  if [ "$stream_ms" = "-1" ]; then
    echo "ERROR|Streaming query failed"
    return
  fi
  
  # Get streaming results for accuracy comparison
  stream_response=$(run_query_full "$query")
  stream_success=$(echo "$stream_response" | jq -r '._shards.failed')
  
  # Check for failures
  if [ "$trad_success" != "0" ] || [ "$stream_success" != "0" ]; then
    echo "ERROR|Shard failures detected"
    return
  fi
  
  # Calculate accuracy (which mode has higher total doc count)
  accuracy_result=$(compare_accuracy "$trad_response" "$stream_response")
  IFS='|' read -r more_accurate accuracy_gain <<< "$accuracy_result"
  
  # Calculate winner
  if [ $stream_ms -lt $trad_ms ]; then
    winner="Streaming"
    improvement=$(echo "scale=1; ($trad_ms - $stream_ms) * 100 / $trad_ms" | bc)
  else
    winner="Traditional"
    improvement=$(echo "scale=1; ($stream_ms - $trad_ms) * 100 / $stream_ms" | bc)
  fi
  
  # Save to results file
  if [ ! -f "$RESULTS_FILE" ]; then
    echo "{}" > "$RESULTS_FILE"
  fi
  
  jq ".\"$query_id\" = {
    \"trad_ms\": $trad_ms,
    \"stream_ms\": $stream_ms,
    \"winner\": \"$winner\",
    \"improvement\": \"${improvement}%\",
    \"more_accurate\": \"$more_accurate\",
    \"accuracy_gain\": \"${accuracy_gain}%\"
  }" "$RESULTS_FILE" > "${RESULTS_FILE}.tmp" && mv "${RESULTS_FILE}.tmp" "$RESULTS_FILE"
  
  echo "$query_id|$trad_ms|$stream_ms|$winner|${improvement}%|$more_accurate|${accuracy_gain}%"
}

# Query metadata
declare -A query_meta
query_meta["Q1"]="RegionID|9K|10|default|1|100%|5|Either"
query_meta["Q2"]="RegionID|9K|10|1000|100|100%|5|Either"
query_meta["Q3"]="CounterID|~100K|100|1000|10|100%|8|Streaming"
query_meta["Q4"]="CounterID|~100K|100|5000|50|100%|8|Streaming"
query_meta["Q5"]="UserID|2.8M|100|1000|10|10.5%|8|Traditional"
query_meta["Q6"]="UserID|3.2M|100|1000|10|18.4%|8|Traditional"
query_meta["Q7"]="SearchPhrase|~500K|100|1000|10|10.5%|1|Streaming"
query_meta["Q8"]="Title|~800K|100|1000|10|10.5%|65|Streaming"
query_meta["Q9"]="UserID|~12M|100|1000|10|67%|8|Traditional"
query_meta["Q10"]="SearchPhrase|5.9M|100|1000|10|100%|1|Traditional"
query_meta["Q11"]="URL|18.2M|100|1000|10|100%|79|Traditional"
query_meta["Q12"]="WatchID|99.4M|100|10000|100|100%|8|Traditional"

declare -A queries
queries["Q1"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":10}}}}'
queries["Q2"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":10,"shard_size":1000}}}}'
queries["Q3"]='{"size":0,"aggs":{"test":{"terms":{"field":"CounterID","size":100,"shard_size":1000}}}}'
queries["Q4"]='{"size":0,"aggs":{"test":{"terms":{"field":"CounterID","size":100,"shard_size":5000}}}}'
queries["Q5"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"UserID","size":100,"shard_size":1000}}}}'
queries["Q6"]='{"size":0,"query":{"term":{"RegionID":229}},"aggs":{"test":{"terms":{"field":"UserID","size":100,"shard_size":1000}}}}'
queries["Q7"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"SearchPhrase","size":100,"shard_size":1000}}}}'
queries["Q8"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"Title","size":100,"shard_size":1000}}}}'
queries["Q9"]='{"size":0,"query":{"term":{"JavaEnable":1}},"aggs":{"test":{"terms":{"field":"UserID","size":100,"shard_size":1000}}}}'
queries["Q10"]='{"size":0,"aggs":{"test":{"terms":{"field":"SearchPhrase","size":100,"shard_size":1000}}}}'
queries["Q11"]='{"size":0,"aggs":{"test":{"terms":{"field":"URL","size":100,"shard_size":1000}}}}'
queries["Q12"]='{"size":0,"aggs":{"test":{"terms":{"field":"WatchID","size":100,"shard_size":10000}}}}'

echo "========================================================================================================================"
echo "Streaming vs Traditional Benchmark Results"
echo "========================================================================================================================"
echo ""
printf "%-5s | %-12s | %-6s | %-4s | %-10s | %-5s | %-8s | %-10s | %-10s | %-7s | %-10s | %-10s | %-10s\n" \
  "Query" "Field" "Card" "size" "shard_size" "ratio" "Trad(ms)" "Stream(ms)" "Winner" "Improve" "MoreAccurate" "AccGain" "Expected"
echo "------|--------------|--------|------|------------|-------|----------|------------|------------|---------|------------|----------|----------"

for query_id in Q1 Q2 Q3 Q4 Q5 Q6 Q7 Q8 Q9 Q10 Q11 Q12; do
  echo -n "Running $query_id... " >&2
  result=$(benchmark_query "$query_id" "${queries[$query_id]}")
  
  if [ "$result" = "SKIP" ]; then
    echo "Skipped (already benchmarked)" >&2
    # Load from results file
    trad_ms=$(jq -r ".\"$query_id\".trad_ms" "$RESULTS_FILE")
    stream_ms=$(jq -r ".\"$query_id\".stream_ms" "$RESULTS_FILE")
    winner=$(jq -r ".\"$query_id\".winner" "$RESULTS_FILE")
    improve=$(jq -r ".\"$query_id\".improvement" "$RESULTS_FILE")
    more_accurate=$(jq -r ".\"$query_id\".more_accurate" "$RESULTS_FILE")
    accuracy_gain=$(jq -r ".\"$query_id\".accuracy_gain" "$RESULTS_FILE")
    result="$query_id|$trad_ms|$stream_ms|$winner|$improve|$more_accurate|$accuracy_gain"
  elif [[ "$result" == ERROR* ]]; then
    echo "Failed: $result" >&2
    continue
  else
    echo "Done" >&2
  fi
  
  IFS='|' read -r field card size shard_size ratio select key_len expected <<< "${query_meta[$query_id]}"
  IFS='|' read -r qid trad_ms stream_ms winner improve more_accurate accuracy_gain <<< "$result"
  
  printf "%-5s | %-12s | %-6s | %-4s | %-10s | %-5s | %-8s | %-10s | %-10s | %-7s | %-10s | %-10s | %-10s\n" \
    "$qid" "$field" "$card" "$size" "$shard_size" "$ratio" "$trad_ms" "$stream_ms" "$winner" "$improve" "$more_accurate" "$accuracy_gain" "$expected"
done

set_streaming false

echo ""
echo "========================================================================================================================"
echo "Results saved to: $RESULTS_FILE"
echo "========================================================================================================================"
