#!/bin/bash

# Q11 Investigation: URL field with 18.2M cardinality
# Traditional: 1360ms, Streaming: 50452ms (97.3% slower)

HOST="172.31.53.13:9200"
INDEX="hits_split"

echo "=========================================="
echo "Q11 Manual Profiling"
echo "=========================================="
echo ""

# Enable traditional mode
echo "1. Setting traditional mode..."
curl -X PUT "$HOST/_cluster/settings" -H 'Content-Type: application/json' -d'{
  "persistent": {
    "stream.search.enabled": false
  }
}'
echo ""
sleep 2

# Run with profile
echo ""
echo "2. Running Q11 with TRADITIONAL mode + profile..."
echo ""
curl -X POST "$HOST/$INDEX/_search?pretty" -H 'Content-Type: application/json' -d'{
  "size": 0,
  "profile": true,
  "aggs": {
    "test": {
      "terms": {
        "field": "URL",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}' | tee q11_traditional_profile.json

echo ""
echo "=========================================="
echo ""

# Enable streaming mode
echo "3. Setting streaming mode..."
curl -X PUT "$HOST/_cluster/settings" -H 'Content-Type: application/json' -d'{
  "persistent": {
    "stream.search.enabled": true
  }
}'
echo ""
sleep 2

# Run with profile
echo ""
echo "4. Running Q11 with STREAMING mode + profile..."
echo ""
curl -X POST "$HOST/$INDEX/_search?pretty" -H 'Content-Type: application/json' -d'{
  "size": 0,
  "profile": true,
  "aggs": {
    "test": {
      "terms": {
        "field": "URL",
        "size": 100,
        "shard_size": 1000
      }
    }
  }
}' | tee q11_streaming_profile.json

echo ""
echo "=========================================="
echo ""

# Disable streaming
echo "5. Disabling streaming mode..."
curl -X PUT "$HOST/_cluster/settings" -H 'Content-Type: application/json' -d'{
  "persistent": {
    "stream.search.enabled": false
  }
}'
echo ""

echo ""
echo "=========================================="
echo "Profile results saved to:"
echo "  - q11_traditional_profile.json"
echo "  - q11_streaming_profile.json"
echo "=========================================="
echo ""
echo "To analyze:"
echo "  jq '.profile.shards[0].aggregations[0]' q11_traditional_profile.json"
echo "  jq '.profile.shards[0].aggregations[0]' q11_streaming_profile.json"
echo ""
echo "To compare timing breakdown:"
echo "  jq '.profile.shards[0].aggregations[0].breakdown' q11_traditional_profile.json"
echo "  jq '.profile.shards[0].aggregations[0].breakdown' q11_streaming_profile.json"
