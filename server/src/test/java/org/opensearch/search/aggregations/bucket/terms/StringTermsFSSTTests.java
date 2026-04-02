/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.aggregations.bucket.terms;

import org.apache.lucene.util.BytesRef;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.NamedWriteableAwareStreamInput;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.SearchModule;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.test.InternalAggregationTestCase;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tests FSST compressed keys wire serialization and coordinator decompression in StringTerms.
 */
public class StringTermsFSSTTests extends OpenSearchTestCase {

    private NamedWriteableRegistry registry;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        SearchModule searchModule = new SearchModule(org.opensearch.common.settings.Settings.EMPTY, Collections.emptyList());
        registry = new NamedWriteableRegistry(searchModule.getNamedWriteables());
    }

    public void testFSSTFlagTrueRoundTrip() throws IOException {
        StringTerms original = createStringTerms(List.of("alpha", "beta"), true);
        StringTerms deserialized = serializeDeserialize(original);

        assertTrue("fsstCompressedKeys should survive wire round-trip", deserialized.isFsstCompressedKeys());
        assertEquals(2, deserialized.getBuckets().size());
    }

    public void testFSSTFlagFalseRoundTrip() throws IOException {
        StringTerms original = createStringTerms(List.of("x", "y"), false);
        StringTerms deserialized = serializeDeserialize(original);

        assertFalse(deserialized.isFsstCompressedKeys());
        assertEquals(2, deserialized.getBuckets().size());
    }

    public void testReduceDecompressesFSSTKeys() throws Exception {
        Path tempDir = createTempDir();
        writeIdentitySymbolTable(tempDir.resolve("test_field.fsst"));

        String oldBasePath = System.getProperty("opensearch.fsst.basePath");
        try {
            System.setProperty("opensearch.fsst.basePath", tempDir.toString());

            StringTerms shard1 = createStringTerms(List.of("hello", "world"), true);
            InternalAggregation reduced = shard1.reduce(
                List.of(shard1),
                InternalAggregationTestCase.emptyReduceContextBuilder().forFinalReduction()
            );

            StringTerms result = (StringTerms) reduced;
            Set<String> keys = result.getBuckets().stream()
                .map(StringTerms.Bucket::getKeyAsString).collect(Collectors.toSet());
            assertTrue("Expected 'hello' in " + keys, keys.contains("hello"));
            assertTrue("Expected 'world' in " + keys, keys.contains("world"));
        } finally {
            if (oldBasePath != null) System.setProperty("opensearch.fsst.basePath", oldBasePath);
            else System.clearProperty("opensearch.fsst.basePath");
        }
    }

    public void testReduceSkipsDecompressionWhenFlagFalse() throws Exception {
        StringTerms shard1 = createStringTerms(List.of("abc", "def"), false);
        InternalAggregation reduced = shard1.reduce(
            List.of(shard1),
            InternalAggregationTestCase.emptyReduceContextBuilder().forFinalReduction()
        );
        StringTerms result = (StringTerms) reduced;
        Set<String> keys = result.getBuckets().stream()
            .map(StringTerms.Bucket::getKeyAsString).collect(Collectors.toSet());
        assertTrue(keys.contains("abc"));
        assertTrue(keys.contains("def"));
    }

    private StringTerms serializeDeserialize(StringTerms original) throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        return new StringTerms(new NamedWriteableAwareStreamInput(out.bytes().streamInput(), registry));
    }

    private StringTerms createStringTerms(List<String> keys, boolean fsstCompressed) {
        List<StringTerms.Bucket> buckets = new ArrayList<>();
        for (String key : keys) {
            buckets.add(new StringTerms.Bucket(
                new BytesRef(key), 1, InternalAggregations.EMPTY, false, 0, DocValueFormat.RAW
            ));
        }
        StringTerms terms = new StringTerms(
            "test_field", BucketOrder.key(true), BucketOrder.key(true),
            Collections.emptyMap(), DocValueFormat.RAW, 10, false, 0,
            buckets, 0, new TermsAggregator.BucketCountThresholds(1, 0, 10, 10)
        );
        terms.setFsstCompressedKeys(fsstCompressed);
        return terms;
    }

    private void writeIdentitySymbolTable(Path path) throws IOException {
        byte[] data = new byte[255 + 255 * 8];
        for (int i = 0; i < 255; i++) data[i] = 1;
        for (int i = 0; i < 255; i++) data[255 + i * 8] = (byte) i;
        Files.write(path, data);
    }
}
