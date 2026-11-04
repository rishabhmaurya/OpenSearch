/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search.scan;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.sandbox.document.HalfFloatPoint;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.opensearch.arrow.search.ArrowBatches;
import org.opensearch.arrow.search.Column;
import org.opensearch.arrow.search.Column.ColumnType;
import org.opensearch.index.fielddata.IndexNumericFieldData;
import org.opensearch.index.fielddata.SortedNumericDoubleValues;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.query.QueryShardContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads the doc values of one segment into the Arrow vector of a column.
 * <p>
 * Numeric columns read raw Lucene doc values and decode them exactly as the field mappers encode them, using Lucene's bulk
 * read when a chunk has no missing values. Keyword and ip columns look each distinct ordinal of a chunk up once, in
 * ascending order. A list column writes every value of a document, in doc-values order, into the list's value vector.
 * Writers are created and used by one thread, as Lucene requires of doc-values iterators.
 * <p>
 * Values whose size varies (keyword values and list values) take their bytes from the {@link Allowance} of the pass, which
 * ends after the doc that overdraws it, so docs far larger than the pass was sized for cost about one batch of memory.
 */
final class ColumnWriters {

    /** Reads one segment's values of a column. */
    interface LeafColumnWriter {
        /**
         * Writes the values of {@code docs[0..count)}, which are increasing, into rows {@code [offset, offset + count)} of
         * {@code vector}; documents without a value become nulls. Values of varying size take their bytes from
         * {@code allowance}, and the writer stops after the doc that overdraws it, having possibly read the doc values of
         * later docs.
         *
         * @return how many docs, from the first, were written: {@code count}, or fewer, but at least one, if the allowance ran out
         */
        int write(int[] docs, int count, FieldVector vector, int offset, Allowance allowance) throws IOException;
    }

    /**
     * The bytes the values of a pass over buffered docs may still take, shared by the pass's columns. Only values whose size
     * varies take from it: the size of the other values is known when the pass is sized.
     */
    static final class Allowance {
        private long remaining;

        /** Starts a pass that may take {@code bytes}. */
        void reset(long bytes) {
            remaining = bytes;
        }

        /** @return the bytes left, negative once overdrawn */
        long remaining() {
            return remaining;
        }

        /** @return whether taking {@code bytes} overdrew the allowance, which ends the pass after the current doc */
        boolean take(long bytes) {
            remaining -= bytes;
            return remaining < 0;
        }
    }

    private ColumnWriters() {}

    /**
     * @param column      the column
     * @param leaf        the segment
     * @param context     the shard's query context
     * @param visible     whether field-level security lets the caller see the field
     * @param scratch     reusable buffers of the scan
     * @return a writer for the segment
     * @throws ArrowScanDataException if the shard maps the field to a type the column cannot hold, or without doc values
     */
    static LeafColumnWriter open(Column column, LeafReaderContext leaf, QueryShardContext context, boolean visible, Scratch scratch)
        throws IOException {
        if (visible == false) {
            return ColumnWriters::writeNulls;
        }
        final MappedFieldType fieldType = context.getMapperService().fieldType(column.name());
        if (fieldType == null) {
            return ColumnWriters::writeNulls;
        }
        final String mappingType = fieldType.typeName();
        if (Column.isSupported(mappingType) == false || Column.widen(union(column.mappingTypes(), mappingType)) != column.type()) {
            throw new ArrowScanDataException(
                "field ["
                    + column.name()
                    + "] is mapped as ["
                    + mappingType
                    + "] on this shard, which does not fit column type ["
                    + column.type()
                    + "]"
            );
        }
        if (fieldType.hasDocValues() == false) {
            throw new ArrowScanDataException("field [" + column.name() + "] has no doc values on this shard");
        }
        final String field = fieldType.name();
        return switch (mappingType) {
            case "keyword" -> new OrdinalWriter(column, DocValues.getSortedSet(leaf.reader(), field), false, scratch);
            case "ip" -> new OrdinalWriter(column, DocValues.getSortedSet(leaf.reader(), field), true, scratch);
            case "scaled_float" -> new FieldDataDoubleWriter(
                column,
                ((IndexNumericFieldData) context.getForField(fieldType)).load(leaf).getDoubleValues()
            );
            default -> new NumericWriter(
                column,
                DocValues.getSortedNumeric(leaf.reader(), field),
                decoder(mappingType, column.type()),
                scratch
            );
        };
    }

    private static Set<String> union(Set<String> types, String type) {
        if (types.contains(type)) {
            return types;
        }
        final Set<String> all = new HashSet<>(types);
        all.add(type);
        return all;
    }

    private static int writeNulls(int[] docs, int count, FieldVector vector, int offset, Allowance allowance) {
        if (vector instanceof ListVector == false) {
            ensureCapacity(vector, offset + count);
        }
        for (int i = 0; i < count; i++) {
            vector.setNull(offset + i);
        }
        return count;
    }

    /** Converts a raw doc value of a mapping type into the column's representation. */
    @FunctionalInterface
    private interface Decoder {
        long decode(long raw);
    }

    private static Decoder decoder(String mappingType, ColumnType columnType) {
        return switch (mappingType) {
            case "date" -> columnType == ColumnType.TIMESTAMP_NANOS ? ColumnWriters::millisToNanos : raw -> raw;
            case "date_nanos", "unsigned_long", "boolean" -> raw -> raw;
            case "byte", "short", "integer", "long" -> raw -> raw;
            // Floating point columns keep the decoded value's bits, written by the vector's width below.
            case "double" -> raw -> Double.doubleToRawLongBits(NumericUtils.sortableLongToDouble(raw));
            case "float" -> columnType == ColumnType.FLOAT64
                ? raw -> Double.doubleToRawLongBits(NumericUtils.sortableIntToFloat((int) raw))
                : raw -> Float.floatToRawIntBits(NumericUtils.sortableIntToFloat((int) raw));
            case "half_float" -> columnType == ColumnType.FLOAT64
                ? raw -> Double.doubleToRawLongBits(HalfFloatPoint.sortableShortToHalfFloat((short) raw))
                : raw -> Float.floatToRawIntBits(HalfFloatPoint.sortableShortToHalfFloat((short) raw));
            default -> throw new IllegalArgumentException("no numeric decoder for [" + mappingType + "]");
        };
    }

    private static long millisToNanos(long millis) {
        try {
            return Math.multiplyExact(millis, 1_000_000L);
        } catch (ArithmeticException e) {
            throw new ArrowScanDataException(
                "date ["
                    + millis
                    + "] is outside the range of a nanosecond timestamp column, which fields mapped as both date and "
                    + "date_nanos read into"
            );
        }
    }

    /**
     * Reusable buffers of a scan, so writing a chunk allocates nothing on the heap in the steady state. Batches of a scan
     * run one at a time, so the buffers are never used concurrently. Since passes stop at their allowance, the buffers stay
     * about as large as a batch.
     */
    static final class Scratch {
        long[] values = new long[0];
        int[] ords = new int[0];
        int[] valueCounts = new int[0];
        int[] sortedOrds = new int[0];
        int[] valueOffsets = new int[0];
        int[] valueLengths = new int[0];
        byte[] bytes = new byte[0];

        // Grown by copying, since callers keep filling arrays they asked for earlier.
        long[] values(int count) {
            return values = ArrayUtil.grow(values, count);
        }

        int[] ords(int count) {
            return ords = ArrayUtil.grow(ords, count);
        }

        int[] sortedOrds(int count) {
            return sortedOrds = ArrayUtil.grow(sortedOrds, count);
        }

        int[] valueCounts(int count) {
            return valueCounts = ArrayUtil.grow(valueCounts, count);
        }
    }

    /** Writes the values of numeric columns into fixed-width vectors. */
    private static final class NumericWriter implements LeafColumnWriter {
        private final Column column;
        private final SortedNumericDocValues values;
        private final NumericDocValues single;
        private final Decoder decoder;
        private final Scratch scratch;
        private final int width;

        NumericWriter(Column column, SortedNumericDocValues values, Decoder decoder, Scratch scratch) {
            this.column = column;
            this.values = values;
            this.single = DocValues.unwrapSingleton(values);
            this.decoder = decoder;
            this.scratch = scratch;
            this.width = switch (column.type()) {
                case INT8, BOOL -> 1;
                case INT16 -> 2;
                case INT32, FLOAT32 -> 4;
                default -> 8;
            };
        }

        @Override
        public int write(int[] docs, int count, FieldVector vector, int offset, Allowance allowance) throws IOException {
            if (count == 0) {
                return 0;
            }
            if (column.list()) {
                return writeLists(docs, count, (ListVector) vector, offset, allowance);
            }
            ensureCapacity(vector, offset + count);
            if (dense(docs, count)) {
                // Every doc of the chunk has a value: read them in bulk.
                final long[] raw = scratch.values(count);
                single.longValues(count, docs, raw, 0L);
                for (int i = 0; i < count; i++) {
                    put(vector, offset + i, decoder.decode(raw[i]));
                }
                return count;
            }
            for (int i = 0; i < count; i++) {
                final int doc = docs[i];
                if (single != null) {
                    if (single.advanceExact(doc)) {
                        put(vector, offset + i, decoder.decode(single.longValue()));
                    } else {
                        vector.setNull(offset + i);
                    }
                } else if (values.advanceExact(doc)) {
                    if (values.docValueCount() > 1) {
                        throw multiValued(column, values.docValueCount());
                    }
                    put(vector, offset + i, decoder.decode(values.nextValue()));
                } else {
                    vector.setNull(offset + i);
                }
            }
            return count;
        }

        private int writeLists(int[] docs, int count, ListVector list, int offset, Allowance allowance) throws IOException {
            final FieldVector items = list.getDataVector();
            if (dense(docs, count)) {
                // One value per doc, whose size the pass was sized with.
                final long[] raw = scratch.values(count);
                single.longValues(count, docs, raw, 0L);
                final int first = list.startNewValue(offset);
                ensureCapacity(items, first + count);
                for (int i = 0; i < count; i++) {
                    final int row = offset + i;
                    final int start = i == 0 ? first : list.startNewValue(row);
                    put(items, start, decoder.decode(raw[i]));
                    list.endValue(row, 1);
                }
                return count;
            }
            for (int i = 0; i < count; i++) {
                final int row = offset + i;
                if (values.advanceExact(docs[i]) == false) {
                    list.setNull(row);
                    continue;
                }
                final int valueCount = values.docValueCount();
                final int start = list.startNewValue(row);
                ensureCapacity(items, start + valueCount);
                for (int v = 0; v < valueCount; v++) {
                    put(items, start + v, decoder.decode(values.nextValue()));
                }
                list.endValue(row, valueCount);
                if (allowance.take((long) valueCount * width)) {
                    return i + 1;
                }
            }
            return count;
        }

        /** Whether every doc of the chunk has exactly one value, which Lucene can then read in bulk. */
        private boolean dense(int[] docs, int count) throws IOException {
            return single != null && single.advanceExact(docs[0]) && single.docIDRunEnd() > docs[count - 1];
        }

        private void put(FieldVector vector, int index, long value) {
            final ArrowBuf data = vector.getDataBuffer();
            switch (width) {
                case 1 -> {
                    if (column.type() == ColumnType.BOOL) {
                        if (value != 0) {
                            BitVectorHelper.setBit(data, index);
                        } else {
                            BitVectorHelper.unsetBit(data, index);
                        }
                    } else {
                        data.setByte(index, (byte) value);
                    }
                }
                case 2 -> data.setShort((long) index * 2, (short) value);
                case 4 -> data.setInt((long) index * 4, (int) value);
                default -> data.setLong((long) index * 8, value);
            }
            BitVectorHelper.setBit(vector.getValidityBuffer(), index);
        }
    }

    /** Writes the values of {@code scaled_float} columns, which only field data decodes. */
    private static final class FieldDataDoubleWriter implements LeafColumnWriter {
        private final Column column;
        private final SortedNumericDoubleValues values;

        FieldDataDoubleWriter(Column column, SortedNumericDoubleValues values) {
            this.column = column;
            this.values = values;
        }

        @Override
        public int write(int[] docs, int count, FieldVector vector, int offset, Allowance allowance) throws IOException {
            if (column.list()) {
                final ListVector list = (ListVector) vector;
                final FieldVector items = list.getDataVector();
                for (int i = 0; i < count; i++) {
                    final int row = offset + i;
                    if (values.advanceExact(docs[i]) == false) {
                        list.setNull(row);
                        continue;
                    }
                    final int valueCount = values.docValueCount();
                    final int start = list.startNewValue(row);
                    ensureCapacity(items, start + valueCount);
                    for (int v = 0; v < valueCount; v++) {
                        putDouble(items, start + v, values.nextValue());
                    }
                    list.endValue(row, valueCount);
                    if (allowance.take((long) valueCount * Double.BYTES)) {
                        return i + 1;
                    }
                }
                return count;
            }
            ensureCapacity(vector, offset + count);
            for (int i = 0; i < count; i++) {
                if (values.advanceExact(docs[i])) {
                    if (values.docValueCount() > 1) {
                        throw multiValued(column, values.docValueCount());
                    }
                    putDouble(vector, offset + i, values.nextValue());
                } else {
                    vector.setNull(offset + i);
                }
            }
            return count;
        }

        private static void putDouble(FieldVector vector, int index, double value) {
            vector.getDataBuffer().setDouble((long) index * Double.BYTES, value);
            BitVectorHelper.setBit(vector.getValidityBuffer(), index);
        }
    }

    /**
     * Keyword and ip values: gathers a chunk's ordinals, looks each distinct one up once in ascending order, which reads each
     * block of the terms dictionary once, then writes the values in row order. The values looked up are copied to the heap
     * while they fit the pass's allowance; the others, if any, are looked up again when they are written.
     */
    private static final class OrdinalWriter implements LeafColumnWriter {
        private static final int IP_BYTES = 16;

        private final Column column;
        private final SortedSetDocValues values;
        private final SortedDocValues single;
        private final boolean ip;
        private final Scratch scratch;

        OrdinalWriter(Column column, SortedSetDocValues values, boolean ip, Scratch scratch) {
            this.column = column;
            this.values = values;
            this.single = DocValues.unwrapSingleton(values);
            this.ip = ip;
            this.scratch = scratch;
        }

        @Override
        public int write(int[] docs, int count, FieldVector vector, int offset, Allowance allowance) throws IOException {
            if (column.list() == false) {
                ensureCapacity(vector, offset + count);
            }
            // What a list value takes besides its bytes: the offset of a keyword.
            final int itemOverhead = ip ? 0 : VarCharVector.OFFSET_WIDTH;
            final int minItemBytes = ip ? IP_BYTES : itemOverhead;
            // The ordinals of the docs in row order, and how many each doc has, or -1 if it has none. A list column stops
            // gathering once the values gathered overdraw the allowance even at their smallest.
            final int[] counts = scratch.valueCounts(count);
            int total = 0;
            int gathered = 0;
            int[] ords = scratch.ords(count);
            while (gathered < count) {
                final int doc = docs[gathered];
                if (single != null) {
                    if (single.advanceExact(doc)) {
                        ords = scratch.ords(total + 1);
                        ords[total++] = single.ordValue();
                        counts[gathered] = 1;
                    } else {
                        counts[gathered] = -1;
                    }
                } else if (values.advanceExact(doc)) {
                    final int valueCount = values.docValueCount();
                    if (valueCount > 1 && column.list() == false) {
                        throw multiValued(column, valueCount);
                    }
                    ords = scratch.ords(total + valueCount);
                    for (int v = 0; v < valueCount; v++) {
                        ords[total++] = (int) values.nextOrd();
                    }
                    counts[gathered] = valueCount;
                } else {
                    counts[gathered] = -1;
                }
                gathered++;
                if (column.list() && (long) total * minItemBytes > allowance.remaining()) {
                    break;
                }
            }
            final int[] sorted = scratch.sortedOrds(total);
            System.arraycopy(ords, 0, sorted, 0, total);
            Arrays.sort(sorted, 0, total);
            int distinct = 0;
            for (int i = 0; i < total; i++) {
                if (i == 0 || sorted[i] != sorted[distinct - 1]) {
                    sorted[distinct++] = sorted[i];
                }
            }
            final int copied = lookUp(sorted, distinct, allowance.remaining());

            int next = 0;
            if (column.list()) {
                final ListVector list = (ListVector) vector;
                final FieldVector items = list.getDataVector();
                for (int i = 0; i < gathered; i++) {
                    final int row = offset + i;
                    if (counts[i] < 0) {
                        list.setNull(row);
                        continue;
                    }
                    final int start = list.startNewValue(row);
                    if (ip) {
                        ensureCapacity(items, start + counts[i]);
                    }
                    long bytes = 0;
                    for (int v = 0; v < counts[i]; v++) {
                        bytes += itemOverhead + writeValue(items, start + v, ords[next++], sorted, distinct, copied);
                    }
                    list.endValue(row, counts[i]);
                    if (allowance.take(bytes)) {
                        return i + 1;
                    }
                }
                return gathered;
            }
            for (int i = 0; i < gathered; i++) {
                if (counts[i] < 0) {
                    vector.setNull(offset + i);
                } else {
                    final int bytes = writeValue(vector, offset + i, ords[next++], sorted, distinct, copied);
                    // An ip value has a fixed size.
                    if (ip == false && allowance.take(bytes)) {
                        return i + 1;
                    }
                }
            }
            return gathered;
        }

        /**
         * Writes the value of an ordinal, from the scratch buffer if it was copied there.
         *
         * @return the bytes of the value
         */
        private int writeValue(FieldVector vector, int index, int ord, int[] sorted, int distinct, int copied) throws IOException {
            final int slot = Arrays.binarySearch(sorted, 0, distinct, ord);
            final byte[] bytes;
            final int start;
            final int length;
            if (slot < copied) {
                bytes = scratch.bytes;
                start = scratch.valueOffsets[slot];
                length = scratch.valueLengths[slot];
            } else {
                final BytesRef value = lookupOrd(ord);
                bytes = value.bytes;
                start = value.offset;
                length = value.length;
            }
            if (ip) {
                vector.getDataBuffer().setBytes((long) index * IP_BYTES, bytes, start, IP_BYTES);
                BitVectorHelper.setBit(vector.getValidityBuffer(), index);
            } else {
                ((VarCharVector) vector).setSafe(index, bytes, start, length);
            }
            return length;
        }

        /**
         * Copies the values of the distinct ordinals, in ascending order, into the scratch byte buffer, recording where each
         * starts, until they take more than {@code maxBytes}.
         *
         * @return how many values, from the first, were copied
         */
        private int lookUp(int[] sorted, int distinct, long maxBytes) throws IOException {
            if (scratch.valueOffsets.length < distinct) {
                scratch.valueOffsets = new int[ArrayUtil.oversize(distinct, Integer.BYTES)];
                scratch.valueLengths = new int[scratch.valueOffsets.length];
            }
            int used = 0;
            for (int i = 0; i < distinct; i++) {
                if (used > maxBytes) {
                    return i;
                }
                final BytesRef value = lookupOrd(sorted[i]);
                scratch.bytes = ArrayUtil.grow(scratch.bytes, used + value.length);
                System.arraycopy(value.bytes, value.offset, scratch.bytes, used, value.length);
                scratch.valueOffsets[i] = used;
                scratch.valueLengths[i] = value.length;
                used += value.length;
            }
            return distinct;
        }

        private BytesRef lookupOrd(int ord) throws IOException {
            final BytesRef value = single != null ? single.lookupOrd(ord) : values.lookupOrd(ord);
            if (ip && value.length != IP_BYTES) {
                // Every copy of the shard reads the same values, e.g. those field masking replaced an address with.
                throw new ArrowScanDataException(
                    "field [" + column.name() + "] has values on this shard that are not ip addresses, such as masked values"
                );
            }
            return value;
        }
    }

    /**
     * Grows a scalar vector, or the value vector of a list, so indices below {@code rows} can be written directly to its
     * buffers. A variable-width vector grows its validity and offsets only: its data grows with the values written into it.
     */
    static void ensureCapacity(FieldVector vector, int rows) {
        if (vector instanceof VarCharVector varChar) {
            while (varChar.getValueCapacity() < rows) {
                varChar.reallocValidityAndOffsetBuffers();
            }
            return;
        }
        if (vector instanceof BaseFixedWidthVector == false) {
            throw new IllegalStateException("unexpected vector type " + vector.getClass().getName());
        }
        while (vector.getValueCapacity() < rows) {
            vector.reAlloc();
        }
    }

    /**
     * Sets the row count of a batch, which may drop rows written past it.
     */
    static void setRowCount(VectorSchemaRoot root, int rows) {
        ArrowBatches.setRowCount(root, rows);
    }

    private static ArrowScanDataException multiValued(Column column, int count) {
        return new ArrowScanDataException(
            "field ["
                + column.name()
                + "] has ["
                + count
                + "] values in a document; request it with format ["
                + Column.LIST_FORMAT
                + "] in [docvalue_fields] to read every value into a list"
        );
    }
}
