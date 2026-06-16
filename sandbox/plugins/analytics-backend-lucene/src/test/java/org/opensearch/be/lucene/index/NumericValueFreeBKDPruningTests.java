/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.be.lucene.index;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.lucene90.Lucene90PointsWriter;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.bkd.BKDReader;
import org.opensearch.be.lucene.LucenePlugin;
import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;
import org.opensearch.index.mapper.MappedFieldType;

import java.util.BitSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end verification that a numeric field added through the real
 * {@link LuceneDocumentInput}/{@link org.opensearch.be.lucene.LuceneFieldFactoryRegistry} path is
 * indexed as a <b>value-free BKD</b> and prunes a range query correctly: the raw BKD result is a
 * super-set of the true matches (no match dropped), exact after a residual re-check, and the
 * underlying reader is confirmed value-free. This is the "create docs, verify pruning" check for
 * the OpenSearch indexing path (the parts that don't need the Rust/FFM scan engine).
 */
public class NumericValueFreeBKDPruningTests extends LucenePluginBaseTests {

    private MappedFieldType mockLongField(String name) {
        MappedFieldType ft = mock(MappedFieldType.class);
        when(ft.typeName()).thenReturn("long");
        when(ft.name()).thenReturn(name);
        when(ft.getCapabilityMap()).thenReturn(
            Map.of(LucenePlugin.DATA_FORMAT, Set.of(FieldTypeCapabilities.Capability.POINT_RANGE))
        );
        // No text search info -> getFieldType() returns null, which the numeric factory ignores.
        when(ft.getTextSearchInfo()).thenReturn(null);
        return ft;
    }

    public void testNumericFieldIndexesValueFreeAndPrunes() throws Exception {
        final String field = "EventTime";
        final int n = 2000;
        long[] valuesByDoc = new long[n];
        MappedFieldType longField = mockLongField(field);

        try (Directory dir = newDirectory()) {
            IndexWriterConfig iwc = new IndexWriterConfig().setCodec(Codec.forName("Lucene104"));
            iwc.setMergePolicy(NoMergePolicy.INSTANCE);
            iwc.setRAMBufferSizeMB(256.0);
            iwc.setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH);
            iwc.setUseCompoundFile(false);

            try (IndexWriter w = new IndexWriter(dir, iwc)) {
                for (int i = 0; i < n; i++) {
                    long v = random().nextInt(1000);
                    valuesByDoc[i] = v;
                    // Drive the REAL document-building path: registry -> NumericPointFieldFactory.
                    LuceneDocumentInput input = new LuceneDocumentInput();
                    input.addField(longField, v);
                    Document doc = input.getFinalInput();
                    assertNotNull("numeric factory should have added a point field", doc.getField(field));
                    w.addDocument(doc);
                }
                w.commit();
            }

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                assertEquals("expected a single segment", 1, reader.leaves().size());
                LeafReaderContext ctx = reader.leaves().get(0);
                PointValues pv = ctx.reader().getPointValues(field);
                assertNotNull("point values must exist for the numeric field", pv);
                assertTrue("field must be indexed as a value-free BKD", isDocIdsOnly(pv));

                for (int iter = 0; iter < 40; iter++) {
                    int a = random().nextInt(1000);
                    int b = random().nextInt(1000);
                    long qMin = Math.min(a, b);
                    long qMax = Math.max(a, b);

                    BitSet truth = new BitSet();
                    for (int d = 0; d < n; d++) {
                        if (valuesByDoc[d] >= qMin && valuesByDoc[d] <= qMax) {
                            truth.set(d);
                        }
                    }

                    BitSet raw = new BitSet();
                    pv.intersect(rangeVisitor(raw, qMin, qMax, valuesByDoc, false));
                    for (int d = truth.nextSetBit(0); d >= 0; d = truth.nextSetBit(d + 1)) {
                        assertTrue("value-free BKD dropped a true match docID=" + d, raw.get(d));
                    }

                    BitSet exact = new BitSet();
                    pv.intersect(rangeVisitor(exact, qMin, qMax, valuesByDoc, true));
                    assertEquals("residual-checked result must equal truth", truth, exact);
                }
            }
        }
    }

    private static boolean isDocIdsOnly(PointValues pv) {
        if (pv instanceof org.apache.lucene.tests.index.AssertingLeafReader.AssertingPointValues a) {
            pv = a.getWrapped();
        }
        return pv instanceof BKDReader bkd && bkd.isDocIdsOnly();
    }

    private IntersectVisitor rangeVisitor(BitSet hits, long min, long max, long[] valuesByDoc, boolean residual) {
        byte[] qMin = new byte[Long.BYTES];
        byte[] qMax = new byte[Long.BYTES];
        NumericUtils.longToSortableBytes(min, qMin, 0);
        NumericUtils.longToSortableBytes(max, qMax, 0);
        return new IntersectVisitor() {
            @Override
            public void visit(int docID) {
                if (residual) {
                    long v = valuesByDoc[docID];
                    if (v < min || v > max) {
                        return;
                    }
                }
                hits.set(docID);
            }

            @Override
            public void visit(int docID, byte[] packedValue) {
                if (java.util.Arrays.compareUnsigned(packedValue, 0, Long.BYTES, qMin, 0, Long.BYTES) >= 0
                    && java.util.Arrays.compareUnsigned(packedValue, 0, Long.BYTES, qMax, 0, Long.BYTES) <= 0) {
                    hits.set(docID);
                }
            }

            @Override
            public Relation compare(byte[] minPacked, byte[] maxPacked) {
                if (java.util.Arrays.compareUnsigned(maxPacked, 0, Long.BYTES, qMin, 0, Long.BYTES) < 0
                    || java.util.Arrays.compareUnsigned(minPacked, 0, Long.BYTES, qMax, 0, Long.BYTES) > 0) {
                    return Relation.CELL_OUTSIDE_QUERY;
                } else if (java.util.Arrays.compareUnsigned(minPacked, 0, Long.BYTES, qMin, 0, Long.BYTES) < 0
                    || java.util.Arrays.compareUnsigned(maxPacked, 0, Long.BYTES, qMax, 0, Long.BYTES) > 0) {
                        return Relation.CELL_CROSSES_QUERY;
                    } else {
                        return Relation.CELL_INSIDE_QUERY;
                    }
            }
        };
    }
}
