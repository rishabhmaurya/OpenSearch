/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.be.lucene;

import org.apache.lucene.codecs.lucene90.Lucene90PointsWriter;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.IndexableFieldType;
import org.apache.lucene.util.NumericUtils;
import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.index.mapper.MappedFieldType;

/**
 * Builds a single-dimension, 8-byte Lucene point field over an integral/temporal numeric value,
 * using the opt-in <b>value-free ("doc-ids only") BKD</b> leaf format. The point's
 * {@link FieldType} carries the {@link Lucene90PointsWriter#DOC_IDS_ONLY_ATTRIBUTE_KEY} attribute so
 * the points codec stores only matching doc-ids per leaf and omits the packed values (the parquet
 * primary remains the authoritative value store; a delegated range query re-checks exactly).
 *
 * <p>This is what lets a numeric column participate in Lucene filter delegation at sub-page
 * granularity — the navigable BKD tree answers {@code col >/</between} as a doc-id super-set even
 * though the rows are physically scattered in parquet (where row-order zone-maps cannot prune).
 *
 * <p>Scope: integral types (byte/short/integer/long) and date, all encoded as a sortable 8-byte
 * long. Floating-point types are intentionally not handled here yet.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public final class NumericPointFieldFactory {

    /** Number of bytes per dimension for the long-encoded point. */
    static final int BYTES = Long.BYTES;

    /** Shared, frozen value-free point {@link FieldType} (single dim, 8 bytes, doc-ids-only). */
    static final FieldType DOC_IDS_ONLY_LONG_POINT;

    static {
        FieldType ft = new FieldType();
        ft.setDimensions(1, BYTES);
        ft.putAttribute(Lucene90PointsWriter.DOC_IDS_ONLY_ATTRIBUTE_KEY, "true");
        ft.freeze();
        DOC_IDS_ONLY_LONG_POINT = ft;
    }

    private NumericPointFieldFactory() {}

    /** The {@link LuceneFieldFactory} singleton for integral/date numeric fields. */
    static final LuceneFieldFactory INSTANCE =
        (document, fieldType, value, luceneFieldType) -> {
            long encoded = toSortableLong(value, fieldType);
            byte[] packed = new byte[BYTES];
            NumericUtils.longToSortableBytes(encoded, packed, 0);
            document.add(new Field(fieldType.name(), packed, (IndexableFieldType) DOC_IDS_ONLY_LONG_POINT));
            // Co-write the raw (unflipped) value as SortedNumericDocValues under the same field.
            // The value-free BKD leaves store no values, so they cannot be merged on their own;
            // these doc-values merge natively and are the source from which Lucene90PointsWriter
            // rebuilds the value-free BKD for the merged segment. The leaves stay value-free; this
            // is merge-survival metadata, not per-leaf storage. (Interim until the BKD is rebuilt
            // from the parquet primary post-merge.)
            document.add(new SortedNumericDocValuesField(fieldType.name(), encoded));
        };

    /**
     * Encodes a numeric/temporal value into a sortable long whose unsigned-byte ordering matches
     * numeric ordering, so BKD range navigation is correct. Integral values map directly; the
     * {@code longToSortableBytes} flip happens at pack time.
     */
    static long toSortableLong(Object value, MappedFieldType fieldType) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException(
            "NumericPointFieldFactory expected a Number for field \""
                + fieldType.name()
                + "\" but got "
                + (value == null ? "null" : value.getClass().getName())
        );
    }
}
