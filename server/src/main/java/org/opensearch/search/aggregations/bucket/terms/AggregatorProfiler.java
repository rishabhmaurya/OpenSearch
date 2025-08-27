/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.aggregations.bucket.terms;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.io.IOException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;

/**
 * Performance profiler for terms aggregators to compare StreamStringTermsAggregator vs GlobalOrdinalsStringTermsAggregator
 */
public class AggregatorProfiler {

    public enum Operation {
        BUILD_AGGREGATIONS("buildAggregations"),
        CONVERT_BUCKETS_AND_POP("convertBucketsAndPop"),
        BUCKET_COLLECTION("bucketCollection"),
        BUILD_RESULT("buildResult"),
        BUILD_PQ("buildPQ"),
        SEGMENT_ORDINAL_COMPUTATION("segmentOrdinalComputation"),
        GLOBAL_ORDINAL_COMPUTATION("globalOrdinalComputation"),
        BIGARRAY_OPERATIONS("bigArrayOperations"),
        ADVANCE_EXACT("advanceExact"),
        NEXT_ORD("nextOrd"),
        LOOKUP_ORD("lookupOrd"),
        SEND_BATCH("sendBatch"),
        SERIALIZATION("serialization"),
        DESERIALIZATION("deserialization"),
        SEND_CHANNEL("sendChannel"),
        RECEIVE_CHANNEL("receiveChannel"),
        PARTIAL_REDUCE("partialReduce"),
        FINAL_REDUCE("finalReduce"),
        QUERY_TASK("shardQueryPhase");

        private final String name;

        Operation(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private static final AggregatorProfiler INSTANCE = new AggregatorProfiler();
    private final Map<Operation, AtomicLong> timings = new ConcurrentHashMap<>();
    private final Map<Operation, AtomicLong> counts = new ConcurrentHashMap<>();
    private final Map<Operation, List<Long>> samples = new ConcurrentHashMap<>();
    private final AtomicLong segmentCount = new AtomicLong(0);
    private final AtomicLong totalDocuments = new AtomicLong(0);
    private final AtomicLong bigArraysMemoryUsage = new AtomicLong(0);
    private static final int MAX_SAMPLES = 1000;

    private AggregatorProfiler() {
        for (Operation op : Operation.values()) {
            timings.put(op, new AtomicLong(0));
            counts.put(op, new AtomicLong(0));
            if (shouldTrackPercentiles(op)) {
                samples.put(op, Collections.synchronizedList(new ArrayList<>()));
            }
        }
    }

    public static AggregatorProfiler getInstance() {
        return INSTANCE;
    }

    public void recordTime(Operation operation, long nanos) {
        timings.get(operation).addAndGet(nanos);
        counts.get(operation).incrementAndGet();

        if (shouldTrackPercentiles(operation)) {
            List<Long> operationSamples = samples.get(operation);
            synchronized (operationSamples) {
                if (operationSamples.size() >= MAX_SAMPLES) {
                    operationSamples.remove(0);
                }
                operationSamples.add(nanos);
            }
        }
    }

    private boolean shouldTrackPercentiles(Operation operation) {
        return operation == Operation.SEGMENT_ORDINAL_COMPUTATION ||
               operation == Operation.GLOBAL_ORDINAL_COMPUTATION ||
               operation == Operation.BUCKET_COLLECTION ||
               operation == Operation.BUILD_AGGREGATIONS ||
            operation == Operation.BUILD_PQ ||
            operation == Operation.BUILD_RESULT ||
            operation == Operation.SEND_BATCH ||
               operation == Operation.SERIALIZATION ||
               operation == Operation.DESERIALIZATION ||
               operation == Operation.SEND_CHANNEL ||
               operation == Operation.RECEIVE_CHANNEL ||
               operation == Operation.PARTIAL_REDUCE ||
               operation == Operation.FINAL_REDUCE ||
                operation == Operation.CONVERT_BUCKETS_AND_POP ||
                operation == Operation.QUERY_TASK;
    }

    public void incrementSegmentCount() {
        segmentCount.incrementAndGet();
    }

    public void addDocumentCount(long docs) {
        totalDocuments.addAndGet(docs);
    }

    public void recordBigArraysMemoryUsage(long memoryBytes) {
        bigArraysMemoryUsage.set(memoryBytes);
    }

    public ProfileResult getResult() {
        Map<String, Long> operationTimes = new ConcurrentHashMap<>();
        Map<String, Long> operationCounts = new ConcurrentHashMap<>();
        Map<String, PercentileStats> operationPercentiles = new ConcurrentHashMap<>();

        for (Operation op : Operation.values()) {
            operationTimes.put(op.getName(), timings.get(op).get());
            operationCounts.put(op.getName(), counts.get(op).get());
            if (shouldTrackPercentiles(op) && samples.containsKey(op)) {
                operationPercentiles.put(op.getName(), calculatePercentiles(samples.get(op)));
            }
        }

        return new ProfileResult(
            operationTimes,
            operationCounts,
            operationPercentiles,
            segmentCount.get(),
            totalDocuments.get(),
            bigArraysMemoryUsage.get()
        );
    }

    public ProfileResult getAndResetResult() {
        Map<String, Long> operationTimes = new ConcurrentHashMap<>();
        Map<String, Long> operationCounts = new ConcurrentHashMap<>();
        Map<String, PercentileStats> operationPercentiles = new ConcurrentHashMap<>();

        for (Operation op : Operation.values()) {
            operationTimes.put(op.getName(), timings.get(op).getAndSet(0));
            operationCounts.put(op.getName(), counts.get(op).getAndSet(0));
            if (shouldTrackPercentiles(op) && samples.containsKey(op)) {
                List<Long> operationSamples = samples.get(op);
                synchronized (operationSamples) {
                    operationPercentiles.put(op.getName(), calculatePercentiles(operationSamples));
                    operationSamples.clear();
                }
            }
        }

        return new ProfileResult(
            operationTimes,
            operationCounts,
            operationPercentiles,
            segmentCount.getAndSet(0),
            totalDocuments.getAndSet(0),
            bigArraysMemoryUsage.get()
        );
    }

    private PercentileStats calculatePercentiles(List<Long> samples) {
        if (samples.isEmpty()) {
            return new PercentileStats(0, 0, 0, 0, 0);
        }

        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int size = sorted.size();

        return new PercentileStats(
            sorted.get((int) (size * 0.50)),  // p50
            sorted.get((int) (size * 0.95)),  // p95
            sorted.get((int) (size * 0.99)),  // p99
            sorted.get(0),                    // min
            sorted.get(size - 1)              // max
        );
    }

    public static class PercentileStats implements Writeable {
        public final long p50;
        public final long p95;
        public final long p99;
        public final long min;
        public final long max;

        public PercentileStats(long p50, long p95, long p99, long min, long max) {
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.min = min;
            this.max = max;
        }

        public PercentileStats(StreamInput in) throws IOException {
            this.p50 = in.readLong();
            this.p95 = in.readLong();
            this.p99 = in.readLong();
            this.min = in.readLong();
            this.max = in.readLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeLong(p50);
            out.writeLong(p95);
            out.writeLong(p99);
            out.writeLong(min);
            out.writeLong(max);
        }
    }

    public static class ProfileResult implements Writeable {
        public final Map<String, Long> operationTimes;
        public final Map<String, Long> operationCounts;
        public final Map<String, PercentileStats> operationPercentiles;
        public final long segmentCount;
        public final long totalDocuments;
        public final long bigArraysMemoryUsage;

        public ProfileResult(Map<String, Long> operationTimes,
                           Map<String, Long> operationCounts, Map<String, PercentileStats> operationPercentiles, long segmentCount, long totalDocuments, long bigArraysMemoryUsage) {
            this.operationTimes = operationTimes;
            this.operationCounts = operationCounts;
            this.operationPercentiles = operationPercentiles;
            this.segmentCount = segmentCount;
            this.totalDocuments = totalDocuments;
            this.bigArraysMemoryUsage = bigArraysMemoryUsage;
        }

        public ProfileResult(StreamInput in) throws IOException {
            this.operationTimes = in.readMap(StreamInput::readString, StreamInput::readLong);
            this.operationCounts = in.readMap(StreamInput::readString, StreamInput::readLong);
            this.operationPercentiles = in.readMap(StreamInput::readString, PercentileStats::new);
            this.segmentCount = in.readLong();
            this.totalDocuments = in.readLong();
            this.bigArraysMemoryUsage = in.readLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeMap(operationTimes, StreamOutput::writeString, StreamOutput::writeLong);
            out.writeMap(operationCounts, StreamOutput::writeString, StreamOutput::writeLong);
            out.writeMap(operationPercentiles, StreamOutput::writeString, (o, v) -> v.writeTo(o));
            out.writeLong(segmentCount);
            out.writeLong(totalDocuments);
            out.writeLong(bigArraysMemoryUsage);
        }
    }
}
