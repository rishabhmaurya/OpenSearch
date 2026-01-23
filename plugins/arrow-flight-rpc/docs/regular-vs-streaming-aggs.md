OpenSearch Composable Engine and Streaming

What we'll cover today

* The Problem: Why OpenSearch's tightly coupled query engine limits composability
* The Vision: Building a modular, composable query engine
* The Analysis: Understanding OpenSearch's aggregation bottlenecks
* The Breakthrough: Streaming aggregations and performance gains
* Building Infrastructure: Arrow format and streaming transport components
* The Future: Completing the composable query engine



The Problem: Why OpenSearch's tightly coupled query engine is hitting limits

Let me start with a story that many of you probably recognize. You're running OpenSearch in production, and performance is becoming a bottleneck. As data volumes grow and query complexity increases, you're facing rising infrastructure costs and slower response times. Your infrastructure team wants to experiment with Arrow format for better analytics performance. Your platform team wants to try gRPC for more efficient transport. Your data team wants to integrate with custom storage backends. All these needs stem from the same pressure: optimizing cost and performance at scale.

Each team has legitimate technical needs, but suddenly you realize how hard it is to swap out core engine components in OpenSearch. This isn't a failure of OpenSearch - OpenSearch has fantastic plugin extensibility for features and functionality. The challenge is with the core query engine components that are tightly coupled together. Want to replace the in-memory format? You're rewriting the aggregation framework. Want to experiment with a new transport protocol? You're touching networking code throughout the engine. Want to integrate with a new storage layer? You're fighting against tight coupling with Lucene.

Why does this happen? Let's look at the technical reality behind these extensibility challenges:

Missing Columnar In-Memory Format: Various aggregators assume their own row-based data representation. Want to plug in a columnar format like Arrow for vectorized processing? You can't just swap it in - you need to rewrite the aggregation and data processing components to work with columnar data.

Hardcoded Transport Layer: The transport RPC and wire format are deeply embedded in the engine. Want to experiment with Arrow Flight for streaming analytics? You're rewriting core networking code throughout the system.

Inflexible Serialization: JSON and custom binary protocols (StreamInput/StreamOutput) are baked into every aggregation. Want to use Arrow's efficient binary format? Every component needs modification to handle the new serialization.

Missing Abstraction Layers: There's no clean separation between storage retrieval, query planning, and execution. Want to plug in columnar storage or a new query optimizer? You're touching multiple tightly coupled layers.

Tightly Coupled Query Language: The DSL and language frontend are tightly coupled to Lucene QueryBuilders. Want to support alternative query engines or custom query optimizations? You're rebuilding the entire query translation layer.

The Vision: Building a modular, composable query engine

But what if we could change this? This is the vision for a composable query engine - democratizing query processing through pluggable, interchangeable components. Instead of rewriting engine internals every time you want to swap a component, you could just plug in new formats, protocols, and processing components. Your infrastructure team could experiment with Arrow format without touching aggregation code. Your platform team could try gRPC transport without rewriting networking throughout the system. When core engine components have well-defined interfaces and standard formats, we transform OpenSearch from having tightly coupled engine internals into a composable analytics platform where innovation happens at the component level, not the engine level.

The Analysis: Understanding OpenSearch's aggregation bottlenecks

To understand why we need modular, streaming operators, let's first look at OpenSearch's current architecture and then examine two specific aggregation patterns where this approach breaks down.

OpenSearch's Distributed Architecture

OpenSearch uses a 2-tiered architecture with coordinator and data nodes. Each index is divided into shards, distributed across multiple data nodes for parallel processing and fault tolerance. Both data and coordinator nodes share the same OpenSearch Java codebase.

With a typical 10-100 data nodes to 1 coordinator ratio, data nodes do the CPU-intensive and memory-intensive work of processing individual shards, while coordinators handle memory intensive operation of merging results. This architecture works well for most queries, but both coordinators and data nodes become bottlenecks when processing large result sets from dozens of shards - especially for high-cardinality aggregations.

Key Terms:

* Operators: Building blocks of query processing - aggregations, joins, and data processing components
* Shards: Index pieces distributed across nodes for parallel processing (each shard is a Lucene index)
* Lucene Segments: Units within a shard containing a subset of documents and their inverted indexes (can merge and grow over time)
* Global Ordinals: Integer representations of string values for memory-efficient high-cardinality processing across entire shard
* Segment Ordinals: Integer representations of string values scoped to individual segments, precomputed and immutable

Now let's examine two aggregation patterns where this architecture hits limits:

Pattern 1: Term Aggregations

Term aggregations group documents by field values - like finding the most popular URLs in web logs. Let's examine what happens with a real high-cardinality example from the ClickBench dataset: 100 million documents, 18 million unique URLs, query matches 47,000 documents across 13,000 unique URLs, requesting top 1000.

Current Approach - Per Shard Processing:

1. Search Lucene Index: Find matching documents, return DocIDs
2. Extract Global Ordinals: Convert field values to integer representations (built at index time or search time)
3. Collect & Aggregate by Ordinals: Build aggregation state using ordinal arrays
4. Find TopN: Add buckets to priority queue from collected ordinals and metrics arrays to find TopN based on metrics
5. Materialize TopN Keys: Convert only TopN ordinals back to actual string values

Coordinator Processing:

1. Merge Shard Results: Combine TopN results from all shards
2. Final TopN Selection: Re-sort and select globally top N results

Accuracy Limitation: This approach assumes uniform distribution of data across shards. Since each shard only sends its TopN results, the globally accurate TopN might be missed if high-frequency terms are concentrated in specific shards. The final results are approximate, not exact.

Performance Reality:

The numbers tell the story:

* Total query time: 500ms
* TopN selection: 320ms (64% of total time!) - This is our bottleneck
* Collection: 140ms, Materialization: 34ms, Coordinator: 5ms

The Streaming Solution:

Streaming aggregations process results per Lucene segment rather than per shard, flushing results immediately as each segment is processed:

* Segment Ordinals: Precomputed and immutable (free to use)
* Memory Efficiency: Segment-level processing with immediate flushing - 10x reduction in data node memory usage
* No TopN Computation: Eliminate expensive priority queue building entirely

Streaming Performance:

* Total time: 430ms (16% improvement)
* Collection: 50ms (72% faster) - faster because it uses segment-level ordinals vs global ordinals
* Key materialization: 360ms (new bottleneck - repeated per segment)
* Coordinator: 10ms

With Concurrent Segment Search:
The gains are even better when combined with OpenSearch's concurrent segment search feature, which processes segments in parallel. Streaming aggregations naturally complement this parallelism, delivering significantly higher performance improvements as segments can stream results concurrently.

Streaming Trade-offs:
While streaming provides significant benefits, it comes with important trade-offs:

* Repeated Key Materialization: Since we send results per segment, keys must be materialized multiple times per shard, adding latency overhead
* Increased Network Bandwidth: Repeated buckets sent across multiple batches increase network utilization
* Higher Coordinator Memory: The coordinator handles all unique keys from all shards, not just TopN from each shard
* Memory Budget Constraints: Streaming queries can only execute if they fit within the coordinator's memory budget
* Hash-based Fallback: For memory-constrained scenarios, we can stream hashes instead of materialized keys, then materialize hashes to actual values as an additional step

This reveals a critical need for adaptive query planning - the system must decide whether to use streaming based on estimated memory requirements, cardinality, and available resources. This query planning capability is part of our scope but not yet implemented.

Pattern 2: Distinct/Cardinality Aggregations - The Perfect Streaming Case

Distinct count aggregations count unique values in a field - like counting unique visitors to a website. These are actually perfect for streaming because of how HyperLogLog works.

Current Approach:
Each shard builds a complete HyperLogLog++ sketch, then sends the entire sketch to the coordinator once processing is complete.

Streaming Opportunity:
HyperLogLog sketches can be updated incrementally. As new unique values are discovered during processing, only the new hash values need to be streamed to the coordinator. The coordinator maintains the running sketch and only receives updates when genuinely new unique values are found.

Why This Works:

* Incremental Updates: HyperLogLog sketches naturally support incremental updates
* Memory Efficiency: No need to buffer complete sketches on data nodes

Distinct aggregations demonstrate the ideal streaming pattern - they naturally decompose into partial-to-final operations with minimal overhead, making them perfect candidates for our composable operator architecture.

Building the Infrastructure Components

Streaming Transport (Implemented)

We've implemented streaming transport using Arrow Flight RPC Java that enables partial result emission and reduces memory and CPU pressure on data nodes. For streaming aggregations, data nodes can now send partial aggregation results incrementally instead of building complete aggregation state in memory. This dramatically reduces both memory usage and CPU overhead on data nodes, while coordinators handle the incremental merging of these partial results.

This streaming capability is the foundation that will eventually enable more sophisticated distributed algorithms and composable processing components, but today it's primarily solving the data node resource bottleneck problem for high-cardinality aggregations.

Arrow Columnar In-Memory Format (Available, Not Yet Utilized)

We've integrated Apache Arrow as our standard in-memory format. Arrow provides a columnar layout that's perfect for analytical workloads, with several key advantages:

Standardized Memory Layout: Arrow gives us nullability vectors, fixed-width types, and variable-size data all in a consistent format that every operator can understand.
Zero-Copy Operations: All vectors and buffers are reference counted. A single buffer can be referenced by multiple vectors, and we get copy-on-write semantics automatically.
Lazy Materialization: Arrow's lazy vectors are incredibly powerful for cardinality reduction operations like joins and filters. Depending on selectivity, we can avoid materialization entirely or scope it to just the surviving rows.
Efficient Serialization: Arrow's binary format eliminates the multiple conversion overhead we mentioned earlier. Instead of POJO → JSON → Binary conversions, Arrow data can be serialized and deserialized with minimal overhead, and even shared between processes with zero-copy when possible.

While Arrow is available in our infrastructure, we're not yet fully leveraging its capabilities across all operators - that's our next step.

Block-based Retrieval and Columnar Execution (Next Logical Step)

The next breakthrough involves two complementary transformations that will enable true vectorized execution:

1. Block-based Document Retrieval

Today's aggregation framework processes documents one-by-one through the aggregation tree. We need to modify the aggregation framework and interfaces to process batches of DocIDs instead of individual documents. This transformation is already underway in the Lucene community with block-based collector APIs that will provide native support for batch processing at the Lucene collector level.

Benefits of Block-based Retrieval:

* Better Cache Utilization: Sequential access patterns improve CPU cache efficiency
* Reduced Function Call Overhead: Batch operations eliminate per-document method calls
* Vectorized Processing: Process batches of documents together, enabling SIMD operations on data arrays

2. Columnar Aggregation Processing

After collecting DocIDs in batches, the entire aggregation pipeline needs columnar transformation:

Current Row-based Processing:

* Extract Global Ordinals → Collect & Aggregate by Ordinals → Find TopN → Merge Shard Results
* Uses InternalAggregation row-based structures
* Requires serialization between wire format and Java POJOs
* Individual bucket objects created and processed

Target Columnar Processing:

* Columnar Bucket Structures: Replace InternalBucket POJOs with Arrow columnar vectors
* Zero-Copy Wire Format: Use Arrow's binary format directly, eliminating serialization overhead
* Vectorized TopN: Use Arrow's compute functions for efficient sorting and selection
* Columnar Coordinator Merge: Process entire columns of buckets instead of individual objects

This columnar transformation will create a foundation where any operator can benefit from vectorized optimizations, while Arrow's standardized format ensures composability across different processing components.

The Future: Completing the composable query engine

We've built the foundation with streaming transport and Arrow format, but several key components remain to complete our vision of a fully composable query engine:

Query Planning and Optimization (Future)

Adaptive Query Planning: Intelligent decision-making about when to use streaming vs traditional approaches based on cardinality estimates, memory budgets, and cluster resources.
Cost-Based Optimization: Query planners that can choose between different execution strategies (streaming, traditional, hybrid) based on data characteristics and resource availability.
Dynamic Execution: Runtime adaptation when initial estimates prove incorrect, allowing queries to switch execution modes mid-flight.

Advanced Operators (Future)

Streaming Search: Enable low-latency search with immediate result streaming for both scored and unscored queries, dramatically reducing time-to-first-byte compared to traditional scroll APIs.
Streaming Joins: Extend the partial-to-final pattern to distributed joins, enabling incremental join processing across shards and indices.

Storage Integration (Future)

Columnar Storage Adapters: Direct integration with columnar storage formats like Parquet, enabling zero-copy data access.
Tiered Storage Optimization: Smart data placement and retrieval strategies that optimize for different storage tiers and access patterns.
