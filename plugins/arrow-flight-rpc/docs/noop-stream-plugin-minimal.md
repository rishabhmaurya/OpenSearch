# Minimal Noop Stream Plugin Implementation

## Purpose

Isolate node-to-node transport performance by creating a streaming action that:
- Coordinator broadcasts to data nodes (like aggregations)
- Data nodes stream back N batches via transport (netty4 or flight)
- Zero business logic - just serialization/transport overhead

## Implementation

### 1. Add to client-benchmark-noop-api-plugin

```java
// NoopStreamAction.java
public class NoopStreamAction extends ActionType<NoopStreamResponse> {
    public static final NoopStreamAction INSTANCE = new NoopStreamAction();
    public static final String NAME = "indices:data/read/noop_stream";

    private NoopStreamAction() {
        super(NAME, NoopStreamResponse::new);
    }
}

// NoopStreamRequest.java
public class NoopStreamRequest extends BroadcastRequest<NoopStreamRequest> {
    private int batchCount;
    private int batchSizeBytes;

    public NoopStreamRequest(StreamInput in) throws IOException {
        super(in);
        batchCount = in.readVInt();
        batchSizeBytes = in.readVInt();
    }

    public NoopStreamRequest(int batchCount, int batchSizeBytes) {
        super(new String[0]);  // All indices
        this.batchCount = batchCount;
        this.batchSizeBytes = batchSizeBytes;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(batchCount);
        out.writeVInt(batchSizeBytes);
    }

    public int getBatchCount() { return batchCount; }
    public int getBatchSizeBytes() { return batchSizeBytes; }
}

// NoopStreamResponse.java
public class NoopStreamResponse extends BroadcastResponse {
    private long totalBatches;
    private long totalBytes;

    public NoopStreamResponse(StreamInput in) throws IOException {
        super(in);
        totalBatches = in.readVLong();
        totalBytes = in.readVLong();
    }

    public NoopStreamResponse(int totalShards, int successfulShards, int failedShards,
                              List<DefaultShardOperationFailedException> shardFailures,
                              long totalBatches, long totalBytes) {
        super(totalShards, successfulShards, failedShards, shardFailures);
        this.totalBatches = totalBatches;
        this.totalBytes = totalBytes;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVLong(totalBatches);
        out.writeVLong(totalBytes);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("total_batches", totalBatches);
        builder.field("total_bytes", totalBytes);
        builder.endObject();
        return builder;
    }
}

// TransportNoopStreamAction.java
public class TransportNoopStreamAction extends TransportBroadcastAction<
    NoopStreamRequest,
    NoopStreamResponse,
    NoopStreamShardRequest,
    NoopStreamShardResponse> {

    @Inject
    public TransportNoopStreamAction(
        ClusterService clusterService,
        TransportService transportService,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            NoopStreamAction.NAME,
            clusterService,
            transportService,
            actionFilters,
            indexNameExpressionResolver,
            NoopStreamRequest::new,
            NoopStreamShardRequest::new,
            ThreadPool.Names.SEARCH
        );
    }

    @Override
    protected NoopStreamShardRequest newShardRequest(int numShards, ShardRouting shard, NoopStreamRequest request) {
        return new NoopStreamShardRequest(shard.shardId(), request);
    }

    @Override
    protected NoopStreamShardResponse shardOperation(NoopStreamShardRequest request, Task task) {
        // Simulate streaming: create dummy batches
        int batchCount = request.getBatchCount();
        int batchSize = request.getBatchSizeBytes();
        
        // Just allocate and discard - measures transport overhead only
        byte[] dummyData = new byte[batchSize];
        
        return new NoopStreamShardResponse(request.shardId(), batchCount, batchCount * batchSize);
    }

    @Override
    protected NoopStreamResponse newResponse(
        NoopStreamRequest request,
        int totalShards,
        int successfulShards,
        int failedShards,
        List<NoopStreamShardResponse> responses,
        List<DefaultShardOperationFailedException> shardFailures,
        ClusterState clusterState
    ) {
        long totalBatches = responses.stream().mapToLong(NoopStreamShardResponse::getBatchCount).sum();
        long totalBytes = responses.stream().mapToLong(NoopStreamShardResponse::getTotalBytes).sum();
        
        return new NoopStreamResponse(totalShards, successfulShards, failedShards, shardFailures, totalBatches, totalBytes);
    }
}

// RestNoopStreamAction.java
public class RestNoopStreamAction extends BaseRestHandler {
    @Override
    public String getName() {
        return "noop_stream_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(POST, "/_noop_stream"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        int batchCount = request.paramAsInt("batch_count", 100);
        int batchSize = request.paramAsInt("batch_size", 10240);  // 10KB default

        NoopStreamRequest noopRequest = new NoopStreamRequest(batchCount, batchSize);
        
        return channel -> client.execute(NoopStreamAction.INSTANCE, noopRequest, new RestToXContentListener<>(channel));
    }
}
```

### 2. Register in Plugin

```java
// In NullClientBenchmarkPlugin.java
@Override
public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
    return Arrays.asList(
        new ActionHandler<>(NoopBulkAction.INSTANCE, TransportNoopBulkAction.class),
        new ActionHandler<>(NoopSearchAction.INSTANCE, TransportNoopSearchAction.class),
        new ActionHandler<>(NoopStreamAction.INSTANCE, TransportNoopStreamAction.class)  // Add this
    );
}

@Override
public List<RestHandler> getRestHandlers(Settings settings, RestController restController, ...) {
    return Arrays.asList(
        new RestNoopBulkAction(),
        new RestNoopSearchAction(),
        new RestNoopStreamAction()  // Add this
    );
}
```

## Usage

### Build and Install
```bash
./gradlew :client:client-benchmark-noop-api-plugin:assemble
bin/opensearch-plugin install file:///path/to/noop-plugin.zip
```

### Test Endpoint
```bash
# Simple test
curl -X POST "localhost:9200/_noop_stream?batch_count=10&batch_size=1024"

# Response
{
  "total_batches": 20,      # 10 batches × 2 shards
  "total_bytes": 20480      # 10KB × 2 shards
}
```

### JMeter Configuration
```
HTTP Request
├── Method: POST
├── Path: /_noop_stream
└── Parameters:
    ├── batch_count: ${BATCHES}
    └── batch_size: ${SIZE}
```

## Why This Works

1. **Coordinator→Data**: Broadcast action hits all shards (2 shards = 2 data nodes)
2. **Transport Layer**: Shard responses use configured transport (netty4 or flight)
3. **No Business Logic**: Just byte array allocation, no computation
4. **Configurable Load**: Control batch count and size to simulate different workloads
5. **Thread Pool**: Uses SEARCH thread pool (same as real queries)

## Alternative: Use Existing Search

If plugin modification is too complex, use minimal search:

```bash
# Create 2-shard index
PUT /bench
{
  "settings": {
    "number_of_shards": 2,
    "number_of_replicas": 0,
    "refresh_interval": "-1"
  },
  "mappings": {
    "properties": {
      "id": { "type": "keyword" }
    }
  }
}

# Index 2 docs (one per shard)
POST /bench/_bulk
{"index":{"_id":"1","routing":"0"}}
{"id":"1"}
{"index":{"_id":"2","routing":"1"}}
{"id":"2"}

# Query that forces coordinator→data
POST /bench/_search
{
  "size": 0,
  "aggs": {
    "terms_agg": {
      "terms": {
        "field": "id",
        "size": 10000
      }
    }
  }
}
```

**Limitation**: Still executes aggregation logic (~5-10% overhead).
