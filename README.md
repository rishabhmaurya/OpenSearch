# Arrow streaming search: benchmark data

Data behind the charts of opensearch-project/OpenSearch#23542. Every table is the raw result of one run of the
memory-instrumented benchmark (2026-11-03, final build).

**Cluster.** 3 x r5.2xlarge on AWS EC2 (8 vCPU, 64 GB): one coordinating node and two data nodes, 32 GB heap each
(pre-touched). Indices of 2 primaries, no replicas, warm page cache. Benchmark client on a c4.2xlarge.

**Data.** `big5` (58M log documents) and `clickbench` (100M web-analytics rows).

| case | index | columns | query |
|---|---|---|---|
| big5-wide | big5 | `@timestamp`, `metrics.size`, `metrics.tmin`, `process.name`, `agent.name`, `log.file.path` | all |
| click-narrow | clickbench | `CounterID`, `EventDate`, `UserID` | all |
| click-wide | clickbench | `CounterID`, `EventDate`, `UserID`, `RegionID`, `ResolutionWidth`, `SearchPhrase`, `URL` | all |
| click-filter | clickbench | `CounterID`, `UserID`, `URL` | `CounterID` < 100000 |

**Methods.** Every method reads the same columns from doc values on the server (`docvalue_fields`, `_source: false`,
`stored_fields: _none_`, 10k-hit pages for scroll and PIT).
- `arrow-stream`: one `ServerStreamSearch` call; the client decodes each batch and drops it.
- `arrow`: the same call; the client keeps every batch and builds one pyarrow Table.
- `sliced-scroll`: 8 parallel slices of a scroll, one process each, counting hits.
- `scroll`: one scroll, counting hits. `scroll-table`: one scroll whose pages are decoded into a pyarrow Table.
- `pit`: point in time with `search_after` on `_shard_doc` and `track_total_hits: true`, counting hits.

**Memory measures.** Heap allocated: the sum of heap growth between node-stats samples taken every 100 ms. Arrow native
peak: the nodes' Arrow memory pools at their highest sample. RssAnon growth: anonymous resident memory over the run's
start (the heap is pre-touched, so this is native memory). Client RSS growth: peak RSS of the client process over its
start. Coordinator and data-node values are written c/d.


## Like-for-like, 5M rows (median of 2)

| case | method | time | rows/s | received | coord CPU | data CPU | client CPU | heap alloc c/d | young GCs c/d | Arrow native peak c/d | RssAnon growth c/d | client RSS growth |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| big5-wide | arrow | 0.68 s | 7334610 | 407 MB | 0.7 s | 3.9 s | 1.4 s | 48/128 MB | 0/0 | 13/20 MB | 10.0/0.0 MB | 426 MB |
| big5-wide | arrow-stream | 0.67 s | 7453038 | 407 MB | 0.8 s | 4.0 s | 1.3 s | 16/16 MB | 0/0 | 13/20 MB | 0.0/0.0 MB | 40 MB |
| big5-wide | sliced-scroll | 11.78 s | 424967 | 1241 MB | 28.5 s | 63.2 s | 2.2 s | 11320/14536 MB | 0/1 | 0/0 MB | 0.0/1.0 MB | 1 MB |
| big5-wide | scroll | 39.78 s | 125790 | 1231 MB | 26.1 s | 50.2 s | 1.8 s | 11344/19184 MB | 1/1 | 0/0 MB | 0.0/0.0 MB | 11 MB |
| big5-wide | scroll-table | 216.81 s | 23067 | 1231 MB | 28.8 s | 53.5 s | 179.8 s | 12104/20208 MB | 0/1 | 0/0 MB | 0.0/0.0 MB | 2661 MB |
| big5-wide | pit | 177.99 s | 28094 | 1232 MB | 27.6 s | 497.5 s | 2.0 s | 13200/19672 MB | 0/1 | 0/0 MB | 2.5/16.0 MB | 10 MB |
| click-narrow | arrow | 0.19 s | 26554943 | 102 MB | 0.2 s | 1.4 s | 0.3 s | 24/0 MB | 0/0 | 12/15 MB | 3.0/0.0 MB | 130 MB |
| click-narrow | arrow-stream | 0.17 s | 28679349 | 102 MB | 0.2 s | 1.4 s | 0.3 s | 0/0 MB | 0/0 | 13/19 MB | 0.0/0.0 MB | 36 MB |
| click-narrow | sliced-scroll | 8.00 s | 625036 | 772 MB | 16.5 s | 57.7 s | 1.3 s | 6264/11176 MB | 0/0 | 0/0 MB | 0.0/0.0 MB | 1 MB |
| click-narrow | scroll | 25.64 s | 194981 | 766 MB | 15.7 s | 38.2 s | 0.9 s | 7272/15568 MB | 0/0 | 0/0 MB | 0.0/0.0 MB | 7 MB |
| click-narrow | scroll-table | 80.63 s | 62040 | 766 MB | 16.4 s | 39.5 s | 56.2 s | 7648/16136 MB | 0/1 | 0/0 MB | 0.0/0.0 MB | 1138 MB |
| click-narrow | pit | 122.97 s | 40659 | 768 MB | 16.9 s | 975.5 s | 0.9 s | 7792/16272 MB | 0/0 | 0/0 MB | 0.0/0.5 MB | 7 MB |
| click-wide | arrow | 2.03 s | 2469057 | 671 MB | 0.9 s | 24.9 s | 1.8 s | 96/232 MB | 0/0 | 4/11 MB | 0.0/0.0 MB | 682 MB |
| click-wide | arrow-stream | 2.02 s | 2473632 | 672 MB | 1.0 s | 25.1 s | 1.6 s | 40/128 MB | 0/0 | 4/11 MB | 0.0/0.0 MB | 38 MB |
| click-wide | sliced-scroll | 21.54 s | 232181 | 2176 MB | 34.8 s | 138.3 s | 3.8 s | 16312/19648 MB | 1/1 | 0/0 MB | 0.0/0.0 MB | 1 MB |
| click-wide | scroll | 86.36 s | 57899 | 2163 MB | 33.8 s | 113.4 s | 3.4 s | 16544/24720 MB | 0/1 | 0/0 MB | 0.0/0.0 MB | 28 MB |
| click-wide | scroll-table | 292.77 s | 17080 | 2163 MB | 37.2 s | 117.5 s | 210.4 s | 16960/25576 MB | 1/1 | 0/0 MB | 0.0/0.0 MB | 5183 MB |
| click-wide | pit | 203.73 s | 24541 | 2003 MB | 34.4 s | 1041.0 s | 3.1 s | 17648/23928 MB | 0/1 | 0/0 MB | 0.0/0.0 MB | 34 MB |
| click-filter | arrow | 0.73 s | 6866236 | 398 MB | 0.6 s | 8.0 s | 1.4 s | 64/184 MB | 0/0 | 13/15 MB | 5.5/0.0 MB | 436 MB |
| click-filter | arrow-stream | 0.66 s | 7624419 | 398 MB | 0.6 s | 7.9 s | 1.3 s | 8/0 MB | 0/0 | 12/13 MB | 0.5/0.0 MB | 51 MB |
| click-filter | sliced-scroll | 9.93 s | 503575 | 1062 MB | 18.2 s | 66.5 s | 1.9 s | 6712/11400 MB | 1/1 | 0/0 MB | 0.0/2.0 MB | 1 MB |
| click-filter | scroll | 43.04 s | 116162 | 1062 MB | 16.9 s | 57.2 s | 1.6 s | 8000/16608 MB | 0/1 | 0/0 MB | 0.0/0.0 MB | 11 MB |
| click-filter | scroll-table | 107.07 s | 46714 | 1062 MB | 17.9 s | 58.5 s | 65.8 s | 8280/17104 MB | 0/0 | 0/0 MB | 0.0/0.0 MB | 2091 MB |
| click-filter | pit | 90.26 s | 55397 | 1058 MB | 18.6 s | 366.2 s | 1.5 s | 8496/16768 MB | 0/1 | 0/0 MB | 0.0/0.0 MB | 11 MB |

## Whole index, Arrow streaming consumer

| case | attempt | rows | time | rows/s | received | heap alloc c/d | young GCs | Arrow native peak c/d | RssAnon growth c/d |
|---|---|---|---|---|---|---|---|---|---|
| big5-narrow | 0 | 58.0M | 1.5 s | 37.7M | 0.9 GB | 48/80 MB | 0 | 13/14 MB | 0/0 MB |
| big5-wide | 0 | 58.0M | 16.9 s | 3.4M | 4.7 GB | 320/576 MB | 0 | 13/20 MB | 0/10 MB |
| big5-range | 0 | 4.8M | 0.5 s | 10.2M | 0.1 GB | 16/16 MB | 0 | 0/9 MB | 0/16 MB |
| click-narrow | 0 | 100.0M | 3.3 s | 30.2M | 2.0 GB | 80/176 MB | 0 | 13/18 MB | 0/7 MB |
| click-wide | 0 | 100.0M | 47.5 s | 2.1M | 13.3 GB | 896/2560 MB | 1 | 3/15 MB | 6/7 MB |
| click-filter | 0 | 35.4M | 8.8 s | 4.0M | 3.1 GB | 128/512 MB | 0 | 13/14 MB | 0/0 MB |
| big5-narrow | 1 | 58.0M | 1.8 s | 32.9M | 0.9 GB | 48/80 MB | 0 | 13/15 MB | 0/0 MB |
| big5-wide | 1 | 58.0M | 16.9 s | 3.4M | 4.7 GB | 320/560 MB | 0 | 13/19 MB | 0/1 MB |
| big5-range | 1 | 4.8M | 0.3 s | 13.9M | 0.1 GB | 0/16 MB | 0 | 4/9 MB | 0/3 MB |
| click-narrow | 1 | 100.0M | 3.3 s | 30.1M | 2.0 GB | 80/144 MB | 0 | 13/20 MB | 0/0 MB |
| click-wide | 1 | 100.0M | 47.9 s | 2.1M | 13.3 GB | 848/2608 MB | 0 | 2/15 MB | 1/8 MB |
| click-filter | 1 | 35.4M | 8.7 s | 4.1M | 3.1 GB | 160/544 MB | 0 | 13/14 MB | 0/0 MB |

## Concurrent 5M-row click-wide exports

| method | exports | ran / rejected | wall | total rows/s | heap alloc c/d | young GCs c/d | live heap after GC c/d | Arrow native peak c/d | RssAnon growth c/d |
|---|---|---|---|---|---|---|---|---|---|
| arrow-stream | 1 | 1 / 0 | 2.1 s | 2.42M | 48/112 MB | 0/0 | -/- MB | 6/12 MB | 10/0 MB |
| arrow-stream | 2 | 2 / 0 | 4.8 s | 2.10M | 80/352 MB | 0/0 | -/- MB | 2/13 MB | 1/0 MB |
| arrow-stream | 4 | 4 / 0 | 9.7 s | 2.05M | 208/640 MB | 0/1 | -/101 MB | 2/16 MB | 2/15 MB |
| arrow-stream | 8 | 8 / 0 | 20.0 s | 2.00M | 432/1456 MB | 0/0 | -/- MB | 2/23 MB | 7/41 MB |
| arrow-stream | 16 | 16 / 0 | 40.4 s | 1.98M | 832/2816 MB | 0/0 | -/- MB | 2/37 MB | 4/60 MB |
| arrow-stream | 32 | 32 / 0 | 81.6 s | 1.96M | 1600/5760 MB | 1/0 | 83/- MB | 2/57 MB | 3/70 MB |
| scroll | 1 | 1 / 0 | 89.5 s | 0.06M | 17040/24080 MB | 1/2 | 130/120 MB | 0/0 MB | 49/26 MB |
| scroll | 4 | 4 / 0 | 112.9 s | 0.18M | 63712/93840 MB | 3/4 | 225/148 MB | 0/0 MB | 16/2 MB |
| scroll | 8 | 8 / 0 | 154.8 s | 0.26M | 122752/184560 MB | 7/10 | 289/191 MB | 0/0 MB | 2/2 MB |
| scroll | 16 | 16 / 0 | 238.8 s | 0.33M | 241584/364960 MB | 13/20 | 364/227 MB | 0/0 MB | 2/1 MB |
| arrow-stream, caps 64mb / 16mb | 1 | 1 / 0 | 2.0 s | 2.49M | 160/240 MB | 0/0 | -/- MB | 1/13 MB | 8/0 MB |
| arrow-stream, caps 64mb / 16mb | 8 | 5 / 3 | 12.8 s | 1.96M | 304/896 MB | 0/0 | -/- MB | 2/18 MB | 1/11 MB |

## Stalled client, click-wide whole index

- stops reading for 30 s: completed, 99997497 rows, Arrow native peak c/d 11/15 MB, RssAnon growth c/d 16/4 MB
- stops reading for 75 s: DEADLINE_EXCEEDED, 0 rows, Arrow native peak c/d 6/12 MB, RssAnon growth c/d 0/1 MB

## opensearch-java client, -Xmx128m, Arrow allocator limit 64 MB

| case | mode | rows | time | rows/s | client CPU | heap peak | heap allocated | GCs | GC time | Arrow peak |
|---|---|---|---|---|---|---|---|---|---|---|
| click-narrow | arrow | 100.0M | 3.3 s | 29.89M | 7.8 s | 33 MB | 45 MB | 2 | 4 ms | 2 MB |
| click-narrow | arrow | 5.0M | 0.2 s | 22.30M | 0.9 s | 14 MB | 2 MB | 0 | 0 ms | 3 MB |
| click-narrow | scroll | 5.0M | 38.5 s | 0.13M | 14.7 s | 145 MB | 9168 MB | 330 | 1023 ms | 0 MB |
| click-wide | arrow | 100.0M | 47.7 s | 2.09M | 16.7 s | 35 MB | 349 MB | 16 | 31 ms | 2 MB |
| click-wide | arrow | 5.0M | 2.1 s | 2.43M | 4.3 s | 28 MB | 16 MB | 0 | 0 ms | 3 MB |
| click-wide | scroll | 5.0M | 132.9 s | 0.04M | 39.0 s | 200 MB | 21111 MB | 770 | 3930 ms | 0 MB |
| big5-wide | arrow | 58.0M | 17.1 s | 3.40M | 11.3 s | 34 MB | 110 MB | 5 | 10 ms | 4 MB |
| big5-wide | arrow | 5.0M | 0.7 s | 6.78M | 3.2 s | 22 MB | 10 MB | 0 | 0 ms | 3 MB |
| big5-wide | scroll | 5.0M | 64.7 s | 0.08M | 26.4 s | 192 MB | 15481 MB | 543 | 2637 ms | 0 MB |
| click-filter | arrow | 35.4M | 8.7 s | 4.08M | 8.2 s | 34 MB | 66 MB | 3 | 6 ms | 3 MB |
| click-filter | arrow | 5.0M | 0.7 s | 7.19M | 3.0 s | 21 MB | 9 MB | 0 | 0 ms | 4 MB |
| click-filter | scroll | 5.0M | 65.2 s | 0.08M | 18.1 s | 137 MB | 10899 MB | 393 | 1084 ms | 0 MB |
