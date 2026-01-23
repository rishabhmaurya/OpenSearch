#!/bin/bash

HOST="172.31.53.13:9200"
INDEX="hits_split"
WARMUP_RUNS=2
BENCHMARK_RUNS=3
RESULTS_FILE="benchmark-results.json"
TRAD_RESPONSES_DIR="trad_responses"
STREAM_RESPONSES_DIR="stream_responses"

mkdir -p "$TRAD_RESPONSES_DIR" "$STREAM_RESPONSES_DIR"

set_streaming() {
  local enabled=$1
  curl -s -X PUT "$HOST/_cluster/settings" -H 'Content-Type: application/json' -d"{
    \"persistent\": {
      \"stream.search.enabled\": $enabled
    }
  }" > /dev/null
  sleep 2
}

run_query_full() {
  local query=$1
  curl -s -X POST "$HOST/$INDEX/_search" -H 'Content-Type: application/json' -d"$query"
}

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

benchmark_traditional() {
  local query_id=$1
  local query=$2
  
  set_streaming false
  for i in $(seq 1 $WARMUP_RUNS); do
    run_query_full "$query" > /dev/null
  done
  
  trad_ms=$(run_query_latency "$query" $BENCHMARK_RUNS)
  if [ "$trad_ms" = "-1" ]; then
    echo "ERROR"
    return
  fi
  
  trad_response=$(run_query_full "$query")
  echo "$trad_response" > "$TRAD_RESPONSES_DIR/${query_id}.json"
  trad_success=$(echo "$trad_response" | jq -r '._shards.failed')
  
  if [ "$trad_success" != "0" ]; then
    echo "ERROR"
    return
  fi
  
  echo "$trad_ms"
}

benchmark_streaming() {
  local query_id=$1
  local query=$2
  
  # Fix shard_size to 10000 for streaming
  local stream_query=$(echo "$query" | jq '.aggs.test.terms.shard_size = 10000')
  
  set_streaming true
  for i in $(seq 1 $WARMUP_RUNS); do
    run_query_full "$stream_query" > /dev/null
  done
  
  stream_ms=$(run_query_latency "$stream_query" $BENCHMARK_RUNS)
  if [ "$stream_ms" = "-1" ]; then
    echo "ERROR"
    return
  fi
  
  stream_response=$(run_query_full "$stream_query")
  echo "$stream_response" > "$STREAM_RESPONSES_DIR/${query_id}.json"
  stream_success=$(echo "$stream_response" | jq -r '._shards.failed')
  
  if [ "$stream_success" != "0" ]; then
    echo "ERROR"
    return
  fi
  
  echo "$stream_ms"
}

compare_accuracy() {
  local query_id=$1
  local trad_file="$TRAD_RESPONSES_DIR/${query_id}.json"
  local stream_file="$STREAM_RESPONSES_DIR/${query_id}.json"
  
  local trad_total=$(jq '[.aggregations.test.buckets[].doc_count] | add' "$trad_file")
  local stream_total=$(jq '[.aggregations.test.buckets[].doc_count] | add' "$stream_file")
  
  if [ "$stream_total" -gt "$trad_total" ]; then
    local diff=$((stream_total - trad_total))
    local accuracy_gain=$(echo "scale=1; $diff * 100 / $trad_total" | bc)
    echo "Stream|+${accuracy_gain}"
  elif [ "$trad_total" -gt "$stream_total" ]; then
    local diff=$((trad_total - stream_total))
    local accuracy_gain=$(echo "scale=1; $diff * 100 / $stream_total" | bc)
    echo "Trad|+${accuracy_gain}"
  else
    echo "Same|0.0"
  fi
}

declare -A query_meta
query_meta["Q1"]="RegionID|9K|10|10|1|Trad"
query_meta["Q2"]="RegionID|9K|100|1000|10|Trad"
query_meta["Q3"]="RegionID|9K|100|10000|100|Stream"
query_meta["Q4"]="CounterID|6K|100|1000|10|Trad"
query_meta["Q5"]="CounterID|6K|100|10000|100|Stream"
query_meta["Q6"]="CounterID+1day|210|100|1000|10|Stream"
query_meta["Q7"]="CounterID+1day|210|100|5000|50|Stream"
query_meta["Q8"]="SearchPhrase+1day|389K|100|1000|10|Trad"
query_meta["Q9"]="Title+1day|726K|100|1000|10|Trad"
query_meta["Q10"]="UserID+1day|2.8M|100|1000|10|Trad"
query_meta["Q11"]="URL|18.2M|100|1000|10|Trad"
query_meta["Q12"]="WatchID|99.4M|100|10000|100|Trad"

declare -A queries
queries["Q1"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":10,"shard_size":10}}}}'
queries["Q2"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":100,"shard_size":1000}}}}'
queries["Q3"]='{"size":0,"aggs":{"test":{"terms":{"field":"RegionID","size":100,"shard_size":10000}}}}'
queries["Q4"]='{"size":0,"aggs":{"test":{"terms":{"field":"CounterID","size":100,"shard_size":1000}}}}'
queries["Q5"]='{"size":0,"aggs":{"test":{"terms":{"field":"CounterID","size":100,"shard_size":10000}}}}'
queries["Q6"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"CounterID","size":100,"shard_size":1000}}}}'
queries["Q7"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"CounterID","size":100,"shard_size":5000}}}}'
queries["Q8"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"SearchPhrase","size":100,"shard_size":1000}}}}'
queries["Q9"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"Title","size":100,"shard_size":1000}}}}'
queries["Q10"]='{"size":0,"query":{"range":{"EventTime":{"gte":"2013-07-15T00:00:00","lt":"2013-07-16T00:00:00"}}},"aggs":{"test":{"terms":{"field":"UserID","size":100,"shard_size":1000}}}}'
queries["Q11"]='{"size":0,"aggs":{"test":{"terms":{"field":"URL","size":100,"shard_size":1000}}}}'
queries["Q12"]='{"size":0,"aggs":{"test":{"terms":{"field":"WatchID","size":100,"shard_size":10000}}}}'

echo "========================================================================================================================"
echo "Phase 1: Running Traditional Queries"
echo "========================================================================================================================"
echo ""

declare -A trad_results
for query_id in Q1 Q2 Q3 Q4 Q5 Q6 Q7 Q8 Q9 Q10 Q11 Q12; do
  echo -n "Running $query_id (traditional)... " >&2
  result=$(benchmark_traditional "$query_id" "${queries[$query_id]}")
  if [ "$result" = "ERROR" ]; then
    echo "Failed" >&2
  else
    trad_results["$query_id"]="$result"
    echo "Done ($result ms)" >&2
  fi
done

echo ""
echo "========================================================================================================================"
echo "Phase 2: Running Streaming Queries"
echo "========================================================================================================================"
echo ""

declare -A stream_results
for query_id in Q1 Q2 Q3 Q4 Q5 Q6 Q7 Q8 Q9 Q10 Q11 Q12; do
  if [ -z "${trad_results[$query_id]}" ]; then
    continue
  fi
  echo -n "Running $query_id (streaming)... " >&2
  result=$(benchmark_streaming "$query_id" "${queries[$query_id]}")
  if [ "$result" = "ERROR" ]; then
    echo "Failed" >&2
  else
    stream_results["$query_id"]="$result"
    echo "Done ($result ms)" >&2
  fi
done

set_streaming false

echo ""
echo "========================================================================================================================"
echo "Streaming vs Traditional Benchmark Results"
echo "========================================================================================================================"
echo ""
printf "%-5s | %-17s | %-6s | %-4s | %-10s | %-5s | %-8s | %-10s | %-10s | %-7s | %-10s | %-10s | %-10s\n" \
  "Query" "Field" "Card" "size" "shard_size" "ratio" "Trad(ms)" "Stream(ms)" "Winner" "Improve" "MoreAccurate" "AccGain" "Expected"
echo "------|-------------------|--------|------|------------|-------|----------|------------|------------|---------|------------|----------|----------"

[ ! -f "$RESULTS_FILE" ] && echo "{}" > "$RESULTS_FILE"

for query_id in Q1 Q2 Q3 Q4 Q5 Q6 Q7 Q8 Q9 Q10 Q11 Q12; do
  if [ -z "${trad_results[$query_id]}" ] || [ -z "${stream_results[$query_id]}" ]; then
    continue
  fi
  
  trad_ms="${trad_results[$query_id]}"
  stream_ms="${stream_results[$query_id]}"
  
  accuracy_result=$(compare_accuracy "$query_id")
  IFS='|' read -r more_accurate accuracy_gain <<< "$accuracy_result"
  
  if [ $stream_ms -lt $trad_ms ]; then
    winner="Streaming"
    improvement=$(echo "scale=1; ($trad_ms - $stream_ms) * 100 / $trad_ms" | bc)
  else
    winner="Traditional"
    improvement=$(echo "scale=1; ($stream_ms - $trad_ms) * 100 / $stream_ms" | bc)
  fi
  
  jq ".\"$query_id\" = {
    \"trad_ms\": $trad_ms,
    \"stream_ms\": $stream_ms,
    \"winner\": \"$winner\",
    \"improvement\": \"${improvement}%\",
    \"more_accurate\": \"$more_accurate\",
    \"accuracy_gain\": \"${accuracy_gain}%\"
  }" "$RESULTS_FILE" > "${RESULTS_FILE}.tmp" && mv "${RESULTS_FILE}.tmp" "$RESULTS_FILE"
  
  IFS='|' read -r field card size shard_size ratio expected <<< "${query_meta[$query_id]}"
  
  printf "%-5s | %-17s | %-6s | %-4s | %-10s | %-5s | %-8s | %-10s | %-10s | %-7s | %-10s | %-10s | %-10s\n" \
    "$query_id" "$field" "$card" "$size" "$shard_size" "$ratio" "$trad_ms" "$stream_ms" "$winner" "${improvement}%" "$more_accurate" "${accuracy_gain}%" "$expected"
done

echo ""
echo "========================================================================================================================"
echo "Results saved to: $RESULTS_FILE"
echo "========================================================================================================================"
