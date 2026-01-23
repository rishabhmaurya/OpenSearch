#!/bin/bash

HOST="172.31.53.13:9200"
INDEX="hits_split"

echo "Calculating total buckets across segments for each query..."
echo ""

# Get shard count
shard_count=$(curl -s "$HOST/$INDEX/_settings" | jq -r '.[].settings.index.number_of_shards')
echo "Index has $shard_count shards"
echo ""

# Function to estimate total buckets
estimate_buckets() {
  local field=$1
  local filter=$2
  local cardinality=$3
  
  # Rough estimate: cardinality * segments_per_shard * shard_count
  # Assuming ~10 segments per shard on average
  local segments_per_shard=10
  local total_buckets=$((cardinality * segments_per_shard * shard_count))
  echo $total_buckets
}

echo "=== Estimated Total Buckets (cardinality × segments × shards) ==="
echo ""

# Q1-Q2: RegionID (9K)
echo "Q1-Q2: RegionID (9K cardinality)"
buckets=$(estimate_buckets "RegionID" "none" 9000)
echo "  Estimated total buckets: $buckets ($(echo "scale=1; $buckets / 1000000" | bc)M)"
echo "  Verdict: $([ $buckets -lt 1000000 ] && echo 'Streaming might win' || echo 'Traditional wins')"
echo ""

# Q3-Q4: CounterID (~100K)
echo "Q3-Q4: CounterID (~100K cardinality)"
buckets=$(estimate_buckets "CounterID" "none" 100000)
echo "  Estimated total buckets: $buckets ($(echo "scale=1; $buckets / 1000000" | bc)M)"
echo "  Verdict: $([ $buckets -lt 1000000 ] && echo 'Streaming might win' || echo 'Traditional wins')"
echo ""

# Q5: UserID with 1-day filter (2.8M)
echo "Q5: UserID with 1-day filter (2.8M cardinality)"
buckets=$(estimate_buckets "UserID" "1day" 2800000)
echo "  Estimated total buckets: $buckets ($(echo "scale=1; $buckets / 1000000" | bc)M)"
echo "  Verdict: Traditional wins (way over 1M)"
echo ""

# Q7: SearchPhrase with 1-day filter (~500K)
echo "Q7: SearchPhrase with 1-day filter (~500K cardinality)"
buckets=$(estimate_buckets "SearchPhrase" "1day" 500000)
echo "  Estimated total buckets: $buckets ($(echo "scale=1; $buckets / 1000000" | bc)M)"
echo "  Verdict: $([ $buckets -lt 1000000 ] && echo 'Streaming might win' || echo 'Traditional wins')"
echo ""

echo "=== Recommendations ==="
echo ""
echo "For streaming to win (total buckets < 1M):"
echo "  Required: cardinality × segments × shards < 1,000,000"
echo "  With $shard_count shards and ~10 segments/shard: cardinality < $(echo "1000000 / ($shard_count * 10)" | bc)"
echo ""
echo "Suggested queries for streaming to have a chance:"
echo "  1. Very low cardinality fields (< 10K) with high shard_size ratio (100+)"
echo "  2. Medium cardinality (50K-100K) with VERY high shard_size ratio (1000+)"
echo "  3. Focus on Q3, Q4, Q12 pattern - increase shard_size dramatically"
