## Streaming vs Non-streaming (default) aggregation

**Bucket Collection (`collect`):**
- collects matching docs ordinals
- happens per lucene segment on data node.
- Main bottleneck on data node.
- Similar performance for both streaming and non-stream aggregation
- Ordinals are collected into long BigArray:
- - Streaming aggs: per segment ordinals; max-heap: max ord per segment
- - Non-Streaming aggs: global ordinals; max-heap: max ord per index

**BuildAggregation (`ba`)**
- builds buckets from collect results by looking up ordinal value.
- Second-biggest bottleneck on data node.

collect per leaf
buildAgg
sBuildAgg
reduce
topN
send


1. non-concurrent seg search

A. Non-streaming
N - segments
collectxN + buildAgg + topN + send

B. Streaming

sCollectxN + N x sBuildAgg + N x send

`collectxN + buildAgg + topN + send > sCollectxN + N x sBuildAgg + N x send`

buildAgg + topN > N x sBuildAgg

conclusion: number of global ordinal lookup in non-streaming vs local ordinal looks in stream

// mid point algo - key based flushing heuristic

2. Concurrent seg search

P - partition
[N/P] segment per partition

A. Non-streaming

Per partition
collect x [N/P] + buildAgg (ordinal->docCount + lookup ord) + reduce(p paritions) + topN + send

Overall
collect x N + buildAgg x P + reduce(p paritions) + topN x P + send

B. Streaming

Per partition
sCollect x [N/P] + sBuildAgg x [N/P] + send x [N/P]

Overall
sCollect x [N] + sBuildAgg x N + send x N

Scatter and gather after collect while buildAgg across partitions; applicable for both

Assuming all CPU is available -
`buildAgg + reduce(p paritions) + topN >  sBuildAgg x [N/P]`

Throughput
`buildAgg x P + reduce(p paritions) + topN > sBuildAgg x N`









