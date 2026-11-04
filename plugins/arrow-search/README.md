# arrow-search

Streams search results as [Apache Arrow](https://arrow.apache.org/) record batches read from doc values, through the
existing gRPC `SearchService/ServerStreamSearch` method. Clients filter in OpenSearch and receive columnar data they can
hand to Arrow-native engines (DataFusion, DuckDB, Polars, pandas, Spark) without decoding hits.

Phase one serves filtering use cases: matches are unscored and unordered.

## Design tenets

1. **Minimal client-facing change.** The existing `ServerStreamSearch` RPC and `SearchRequest` proto, selected by one
   request header. No new port, endpoint, TLS or authentication surface.
2. **Zero copy where the code owns the path.** Doc values are read straight into Arrow vectors, and from the coordinator's
   relay to the client's socket a batch moves by ownership transfer and gathering writes, copying only small headers.
   The node-to-node hop goes through Arrow Flight, which today copies each batch once on send (its gRPC message mixes a
   heap header with the body, so Netty copies the whole message into direct memory) and once on receive (into a new
   buffer).
3. **No marshalling of rows.** Rows travel as native Arrow buffers end to end: Arrow Flight between nodes, Arrow IPC
   messages to the client. Only control objects are serialized.
4. **Batched, never the whole result.** Results are produced and held one batch at a time; nothing scales with the result
   size.
5. **Off-heap.** Batches live in Arrow memory pools with limits and rejections, not on the heap.
6. **Waits on virtual threads.** Every wait for a consumer parks a virtual thread, never a platform thread.
7. **Reuse the search infrastructure.** The coordinator runs inside `TransportSearchAction` (resolution, routing,
   can-match, throttling, failover, stats, slow logs, workload group admission); each shard runs a regular query phase
   (rewrite, security hooks, timeouts, cancellation, limits); node-to-node streaming uses `arrow-flight-rpc`, as streaming
   aggregations do.
8. **Pause/resume, backpressure-driven batch production.** Like a streaming aggregation, each shard runs one query phase
   whose collector sends every batch as soon as it is full. Sending waits while the consumer is not ready, which pauses
   the scan with its scorer and doc-values iterators open; it resumes on the next match. The query is evaluated once per
   shard however many batches it produces, and demand flows back from the client to every data node.
9. **Parallel per-segment production.** Like a streaming aggregation under concurrent segment search, a shard's query
   phase splits its segments into slices collected in parallel, and each slice fills and sends its own batches with the
   same pause/resume backpressure. Parallelism within a shard follows the concurrent segment search settings (mode and
   slice count); the slices of a shard share its row limit, compute budget and memory limits.

## Requirements

- `transport-grpc` enabled (`aux.transport.types: [transport-grpc]`)
- the `arrow-base` and `arrow-flight-rpc` plugins, and the stream transport feature flag
  `opensearch.experimental.feature.transport.stream.enabled`
- this plugin, which extends `arrow-base` and `transport-grpc`

Calls use the gRPC port, interceptors and security of `transport-grpc`; use `secure-transport-grpc` in production. With the
security plugin, a call is authenticated by its gRPC interceptor (basic auth or JWT in the request metadata) and
authorized like any search: callers need the `indices:data/read/search*` and `indices:data/read/field_caps` privileges,
and document-level security, field-level security and field masking apply on every shard. The node-to-node Flight
listener must only be reachable by cluster members; with the security plugin, run it over TLS so each shard request
carries the caller's identity and the peer certificate is verified:

```yaml
aux.transport.types: [secure-transport-grpc]
plugins.security.ssl.aux.secure-transport-grpc.enabled: true      # plus pemkey/pemcert/pemtrustedcas_filepath
flight.ssl.enable: true
transport.stream.type.default: FLIGHT-SECURE
```


## Request

A regular protobuf `SearchRequest` sent to `ServerStreamSearch` with the request header `x-opensearch-format: arrow`.

| Option | Meaning |
|---|---|
| `docvalue_fields` | Required. Field names or wildcard patterns, in output column order. A field with format `list` becomes a list column; other formats are rejected. |
| `query`, `post_filter` | Any query; scoring is disabled. Alias filters and document- and field-level security apply. |
| `size` | Caps the total number of rows. Unset means all matches. |
| `terminate_after` | Caps the rows per shard. |
| `timeout` | Bounds the time each slice of a shard spends computing, not the time it waits for the client. |
| `routing`, `preference`, `allow_partial_search_results`, `max_concurrent_shard_requests`, `cancel_after_time_interval`, indices options | As in `_search`. |

Options that need scores, ordering, paging, fetching or aggregations (`sort`, `from`, `search_after`, `aggs`, `highlight`,
`fields`, `stored_fields`, `_source` other than `false`, `inner_hits`, `scroll`, `pit`, `slice`, `ext`, ...) are rejected
with `INVALID_ARGUMENT` rather than ignored, and so are search pipelines, including an index's default pipeline, and
remote cluster indices.

## Columns

Fields resolve through field capabilities across the target indices, so every shard produces the same schema. Since every
shard reads every column, as nulls where its index does not map the field, a search may select at most as many fields as
the largest `index.max_docvalue_fields_search` of its target indices; more fail with `INVALID_ARGUMENT`.

| Mapping types | Arrow type |
|---|---|
| `byte`, `short`, `integer`, `long` | `Int8`/`Int16`/`Int32`/`Int64`; mixed widths widen to `Int64` |
| `unsigned_long` | `UInt64` |
| `half_float`, `float` | `Float32`; mixed with `double` or `scaled_float`, `Float64` |
| `double`, `scaled_float` | `Float64` |
| `boolean` | `Bool` |
| `date` | `Timestamp(ms, UTC)`; mixed with `date_nanos`, `Timestamp(ns, UTC)` |
| `date_nanos` | `Timestamp(ns, UTC)` |
| `keyword` | `Utf8` |
| `ip` | `FixedSizeBinary(16)`, IPv4 as IPv4-mapped IPv6 |

Field metadata `opensearch.type` lists the mapping types behind each column. A document without a value reads as null.

A scalar column fails the search on a document with several values. A list column (`format: "list"`) is
`List<item: T>` and holds every value, in doc-values order: numbers sorted, keyword and ip values sorted and deduplicated,
as `docvalue_fields` returns them in `_search`. Nested and object fields are not supported.

An explicitly named field must be mapped in some target index, with doc values and compatible types everywhere it is
mapped; a pattern skips fields that do not qualify. Columns resolve with the caller's permissions, so a field hidden by
field-level security counts as not mapped, as in field capabilities, and a pattern skips it; a masked field returns its
masked values, except an `ip` field, whose masked values are not addresses and fail the search with `INVALID_ARGUMENT`.

## Response

Every gRPC message is a valid `SearchResponse`:

1. one message per Arrow IPC message, carrying it in field `100` (`bytes`): first the schema, then record batches;
   concatenated, they form an Arrow IPC stream;
2. a final regular `SearchResponse` with `took`, `_shards`, `timed_out`, `terminated_early` and `hits.total` set to the
   number of rows (relation `gte` when `size`, `terminate_after` or `timeout` cut the results).

Rows of different shards interleave; no order is promised. A failure after rows were sent ends the call with an error
status, and the client must discard what it received. So must a client whose call was reset (`CANCELLED`) because it
stopped reading, which can happen after the search ended (see Backpressure).

## Execution

- **Coordinator.** Resolves the columns, sends the schema, then runs the shard phase through `TransportSearchAction`. It
  opens one Flight stream per shard copy, a bounded number at a time, preferring the node with the fewest open streams,
  and relays each batch to the client by ownership transfer.
- **Failover.** A shard copy that fails before any of its rows reached the client is retried on the next copy, as in
  `_search`. A shard that relayed rows is never retried, so its failure fails the search. Deterministic errors (bad
  query, a multi-valued value in a scalar column, a type conflict) fail the search at once.
- **Data node.** Each shard stream is one query phase on a virtual thread. Under concurrent segment search (mode `auto`
  or `all`, the default `auto` included, with or without aggregations), the query phase splits the shard's segments into
  up to `search.concurrent.max_slice_count` slices and collects them in parallel, each slice on its own virtual thread
  rather than the `index_searcher` pool; with mode `none`, one slice collects every segment. Each slice's collector reads
  doc values in chunks straight into Arrow vectors, cuts batches to their byte budget (the first batch is small and later
  ones grow, so rows arrive early), and sends each batch through Flight. A full send pauses that slice, which then holds
  no compute slot. The slices of a shard share its row limit (`size`, `terminate_after`) and stop together once it is
  reached, the `timeout` budget of any slice is spent, or a slice fails. As in `_search`, matches that exactly fill the
  limit are not `terminated_early`: a slice that reaches the limit scores on in its segment for a match past it, holding
  its compute slot and within its budget. If it stops first, or segments that may match are left, the shard reports
  `terminated_early`, not `timed_out`, since it streamed every row it may.
- **Backpressure.** A client that stops reading stops every relay, Flight stops requesting batches, and each shard's scan
  pauses inside its send. A client that stays idle longer than `search.arrow.client_idle_timeout` fails the search with
  `DEADLINE_EXCEEDED`; everything the search holds on every node is released at once, except the batches gRPC already
  queued for the call, which the client receives first if it reads again. Those batches, like the last batches of a
  search that succeeded, stay charged to the coordinator's memory until the client has read them, each as a batch of the
  larger of `batch_size` and `max_row_size`; the rest of the search's reservation returns when the search ends. A client
  that has not read them within another `client_idle_timeout` has its call reset (`CANCELLED`), which releases them; only
  a batch the transport already began writing to the connection stays, still charged, until the client reads it or the
  connection closes. A reset needs the call as gRPC created it: if a gRPC interceptor of another plugin wraps calls, the
  node logs a warning once, and unread batches stay, still charged, until the client reads them or its connection closes.

## Settings

| Setting | Default | |
|---|---|---|
| `search.arrow.batch_size` | `1mb` | Size batches grow to (at most `3mb`, below gRPC's default 4MB message limit). |
| `search.arrow.first_batch_size` | `64kb` | First batch of each shard; doubles with each batch. |
| `search.arrow.batch_rows` | `65536` | Maximum rows per batch. |
| `search.arrow.max_row_size` | `1mb` | Larger rows fail the search. A row larger than `batch_size` is sent as a batch of its own, so the coordinator sizes each shard stream for batches of this size; raising it lowers how many shard streams a search opens at once. Keep it below gRPC's 4MB default client message limit. |
| `search.arrow.max_buffered_bytes_per_request` | `64mb` | Batch memory one search buffers on its coordinator; bounds its open shard streams, each counted as six batches of the larger of `batch_size` and `max_row_size` (6mb by default, so ten streams). A search reserves no more than its target shards need. |
| `search.arrow.coordinator.max_buffered_bytes` | `512mb` | Batch memory of all searches on a coordinator, including batches their clients have not read yet; beyond it searches are rejected. |
| `search.arrow.client_idle_timeout` | `60s` | How long a client may stop reading, while the search runs and again once it ended. |
| `search.arrow.cancel_after_time_interval` | `-1` | Cancels searches running longer, unless the request sets its own interval. |
| `search.arrow.shard.max_open_scans` | 4 × processors | Shard scans open on a data node; more wait, then are rejected and retried elsewhere. |
| `search.arrow.shard.admission_wait` | `5s` | How long a scan waits for an open slot. |
| `search.arrow.shard.max_concurrent_batches` | half the search pool | Scan slices computing at once on a data node. |
| `search.arrow.shard.max_buffered_bytes` | `512mb` | Batch memory of all scans on a data node (static). |

How many slices a scan collects in parallel follows the concurrent segment search settings of the index:
`search.concurrent_segment_search.mode` and `search.concurrent.max_slice_count` (default: half the processors, at most 4).

## Limitations

- No scoring, sorting or aggregations; system and search-throttled indices are rejected.
- A shard's last batch waits while the slice that filled the row limit scores on for a match past it, as `_search`
  does for `terminate_after`; with a selective, costly query this delays the end of the stream, up to the `timeout`.
- A segment is never split across slices: intra-segment partitioning (`partition_strategy: balanced`) only applies when
  the query opts in, which scan queries such as `match_all`, `term` and `range` do not, so a shard dominated by one
  large segment is collected at about one thread's speed. Letting scans opt in is a follow-up.
- Each slice holds up to one batch being filled and one being sent, so a scan's data-node memory grows with its slice
  count; `search.arrow.shard.max_buffered_bytes` bounds the total. Rows much larger than the rows before them still cost
  about one batch: reading stops at the doc whose values overdraw the batch's budget.
- A paused scan keeps its shard's reader open, like a scroll; a closing shard aborts it once it resumes or its wait for
  the coordinator times out.
- Arrow search tasks are not tracked for resource use, since their lifetime follows the client and their CPU runs on
  virtual threads, which per-thread accounting does not measure. Search backpressure does not cancel them, workload
  management neither counts their CPU and heap toward their workload group's usage, on which its rejections are based,
  nor cancels them, and query insights reports no CPU or memory for them. The node bounds them with the scan and compute
  limits above, which every workload group shares.
- A shard's query-phase stats (`query_time_in_millis`, `query_current`) and its query slow log measure the whole scan,
  including the time it waits for the client; the `timeout` budget does not count that time.
- Payload field `100` is not yet declared in `opensearch-protobufs`.
