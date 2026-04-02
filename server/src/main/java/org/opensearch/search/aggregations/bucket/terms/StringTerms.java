/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.aggregations.bucket.terms;

import org.apache.lucene.util.BytesRef;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of the {@link TermsAggregator} when the field is a String.
 *
 * @opensearch.internal
 */
public class StringTerms extends InternalMappedTerms<StringTerms, StringTerms.Bucket> {
    public static final String NAME = "sterms";

    /** Transient flag — true if bucket keys are FSST-compressed (set by aggregator). */
    public void setFsstCompressedKeys(boolean compressed) {
        this.fsstCompressedKeys = compressed;
    }

    public boolean isFsstCompressedKeys() {
        return fsstCompressedKeys;
    }

    /**
     * Bucket for string terms
     *
     * @opensearch.internal
     */
    public static class Bucket extends InternalTerms.Bucket<Bucket> {
        BytesRef termBytes;

        public Bucket(
            BytesRef term,
            long docCount,
            InternalAggregations aggregations,
            boolean showDocCountError,
            long docCountError,
            DocValueFormat format
        ) {
            super(docCount, aggregations, showDocCountError, docCountError, format);
            this.termBytes = term;
        }

        /**
         * Read from a stream.
         */
        public Bucket(StreamInput in, DocValueFormat format, boolean showDocCountError) throws IOException {
            super(in, format, showDocCountError);
            termBytes = in.readBytesRef();
        }

        @Override
        protected void writeTermTo(StreamOutput out) throws IOException {
            out.writeBytesRef(termBytes);
        }

        @Override
        public Object getKey() {
            return getKeyAsString();
        }

        // this method is needed for scripted numeric aggs
        @Override
        public Number getKeyAsNumber() {
            /*
             * If the term is a long greater than 2^52 then parsing as a double would lose accuracy. Therefore, we first parse as a long and
             * if this fails then we attempt to parse the term as a double.
             */
            try {
                return Long.parseLong(termBytes.utf8ToString());
            } catch (final NumberFormatException ignored) {
                return Double.parseDouble(termBytes.utf8ToString());
            }
        }

        @Override
        public String getKeyAsString() {
            return format.format(termBytes).toString();
        }

        @Override
        public int compareKey(Bucket other) {
            return termBytes.compareTo(other.termBytes);
        }

        @Override
        protected final XContentBuilder keyToXContent(XContentBuilder builder) throws IOException {
            return builder.field(CommonFields.KEY.getPreferredName(), getKeyAsString());
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj) && Objects.equals(termBytes, ((Bucket) obj).termBytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), termBytes);
        }
    }

    public StringTerms(
        String name,
        BucketOrder reduceOrder,
        BucketOrder order,
        Map<String, Object> metadata,
        DocValueFormat format,
        int shardSize,
        boolean showTermDocCountError,
        long otherDocCount,
        List<Bucket> buckets,
        long docCountError,
        TermsAggregator.BucketCountThresholds bucketCountThresholds
    ) {
        super(
            name,
            reduceOrder,
            order,
            metadata,
            format,
            shardSize,
            showTermDocCountError,
            otherDocCount,
            buckets,
            docCountError,
            bucketCountThresholds
        );
    }

    /**
     * Read from a stream.
     */
    public StringTerms(StreamInput in) throws IOException {
        super(in, Bucket::new);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public StringTerms create(List<Bucket> buckets) {
        return new StringTerms(
            name,
            reduceOrder,
            order,
            metadata,
            format,
            shardSize,
            showTermDocCountError,
            otherDocCount,
            buckets,
            docCountError,
            bucketCountThresholds
        );
    }

    @Override
    public Bucket createBucket(InternalAggregations aggregations, Bucket prototype) {
        return new Bucket(
            prototype.termBytes,
            prototype.docCount,
            aggregations,
            prototype.showDocCountError,
            prototype.docCountError,
            prototype.format
        );
    }

    @Override
    Bucket createBucket(long docCount, InternalAggregations aggs, long docCountError, StringTerms.Bucket prototype) {
        return new Bucket(prototype.termBytes, docCount, aggs, prototype.showDocCountError, docCountError, format);
    }

    @Override
    protected StringTerms create(String name, List<Bucket> buckets, BucketOrder reduceOrder, long docCountError, long otherDocCount) {
        StringTerms created = new StringTerms(
            name,
            reduceOrder,
            order,
            getMetadata(),
            format,
            shardSize,
            showTermDocCountError,
            otherDocCount,
            buckets,
            docCountError,
            bucketCountThresholds
        );
        created.fsstCompressedKeys = this.fsstCompressedKeys;
        return created;
    }

    @Override
    protected Bucket[] createBucketsArray(int size) {
        return new Bucket[size];
    }

    /** System property pointing to base path for per-field FSST symbol tables. */
    public static final String FSST_SYMBOL_TABLE_PATH_PROP = "opensearch.fsst.basePath";

    /** Cached decompressors per field name — loaded once, reused across reduce calls. */
    private static final java.util.concurrent.ConcurrentHashMap<String, org.apache.lucene.codecs.lucene90.fsst.FSSTDecompressor>
        DECOMPRESSOR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static org.apache.lucene.codecs.lucene90.fsst.FSSTDecompressor getDecompressor(String name) {
        return DECOMPRESSOR_CACHE.computeIfAbsent(name, n -> {
            String basePath = System.getProperty(FSST_SYMBOL_TABLE_PATH_PROP);
            if (basePath == null) return null;
            // Try exact name first, then scan for any .fsst file
            java.nio.file.Path tablePath = java.nio.file.Path.of(basePath, n + ".fsst");
            if (!java.nio.file.Files.exists(tablePath)) {
                try (var files = java.nio.file.Files.list(java.nio.file.Path.of(basePath))) {
                    tablePath = files.filter(p -> p.toString().endsWith(".fsst")).findFirst().orElse(null);
                } catch (java.io.IOException e) {
                    return null;
                }
            }
            if (tablePath == null || !java.nio.file.Files.exists(tablePath)) return null;
            try {
                return new org.apache.lucene.codecs.lucene90.fsst.FSSTDecompressor(
                    org.apache.lucene.codecs.lucene90.fsst.FSSTSymbolTable.load(tablePath));
            } catch (java.io.IOException e) {
                return null;
            }
        });
    }

    private static final org.apache.logging.log4j.Logger logger =
        org.apache.logging.log4j.LogManager.getLogger(StringTerms.class);

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, InternalAggregation.ReduceContext reduceContext) {
        boolean anyCompressed = aggregations.stream()
            .filter(a -> a instanceof StringTerms)
            .anyMatch(a -> ((StringTerms) a).fsstCompressedKeys);

        logger.info("FSST reduce: anyCompressed={}, aggCount={}", anyCompressed, aggregations.size());

        InternalAggregation result = super.reduce(aggregations, reduceContext);

        if (anyCompressed && reduceContext.isFinalReduce() && result instanceof StringTerms st) {
            org.apache.lucene.codecs.lucene90.fsst.FSSTDecompressor decompressor = getDecompressor(st.getName());
            logger.info("FSST reduce: decompressor={}, name={}, buckets={}",
                decompressor != null ? "loaded" : "null", st.getName(), st.getBuckets().size());
            if (decompressor != null) {
                for (Bucket b : st.getBuckets()) {
                    try {
                        byte[] buf = new byte[b.termBytes.length * 8];
                        int len = decompressor.decompress(b.termBytes.bytes, b.termBytes.offset, b.termBytes.length, buf);
                        b.termBytes = new BytesRef(java.util.Arrays.copyOf(buf, len));
                    } catch (Exception e) {
                        logger.error("FSST decompress failed for bucket key len={}: {}", b.termBytes.length, e.toString());
                    }
                }
            }
        }
        return result;
    }
}
