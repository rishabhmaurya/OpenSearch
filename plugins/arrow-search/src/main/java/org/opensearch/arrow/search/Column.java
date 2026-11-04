/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search;

import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A result column: a field and the Arrow type its doc values are read into.
 * <p>
 * A scalar column holds one value per row, and fails the search on a document with several values. A list column holds
 * every value of each document as an Arrow list, in doc-values order: numbers sorted, keyword and ip values sorted and
 * deduplicated, as {@code docvalue_fields} returns them.
 *
 * @param name          the field name
 * @param type          the type of the column's values
 * @param mappingTypes  the mapping types of the field across the target indices, exposed as field metadata
 * @param list          whether the column holds a list of values per row
 */
public record Column(String name, ColumnType type, Set<String> mappingTypes, boolean list) implements Writeable {

    /** Arrow field metadata key listing the OpenSearch mapping types behind a column, e.g. to tell {@code ip} from binary data. */
    public static final String MAPPING_TYPES_METADATA_KEY = "opensearch.type";

    /** The {@code docvalue_fields} format that reads a field into a list column. */
    public static final String LIST_FORMAT = "list";

    /** The name of the value field of list columns, as other Arrow libraries name it. */
    public static final String LIST_ITEM_NAME = "item";

    /**
     * The Arrow types columns are read into. Several mapping types can share one, so indices that map a field differently
     * but compatibly still produce one column.
     */
    public enum ColumnType {
        INT8(new ArrowType.Int(8, true)),
        INT16(new ArrowType.Int(16, true)),
        INT32(new ArrowType.Int(32, true)),
        INT64(new ArrowType.Int(64, true)),
        UINT64(new ArrowType.Int(64, false)),
        FLOAT32(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
        FLOAT64(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
        BOOL(ArrowType.Bool.INSTANCE),
        TIMESTAMP_MILLIS(new ArrowType.Timestamp(TimeUnit.MILLISECOND, "UTC")),
        TIMESTAMP_NANOS(new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
        UTF8(ArrowType.Utf8.INSTANCE),
        IP(new ArrowType.FixedSizeBinary(16));

        private final ArrowType arrowType;

        ColumnType(ArrowType arrowType) {
            this.arrowType = arrowType;
        }

        public ArrowType arrowType() {
            return arrowType;
        }
    }

    private static final Map<String, ColumnType> MAPPING_TYPES = Map.ofEntries(
        Map.entry("byte", ColumnType.INT8),
        Map.entry("short", ColumnType.INT16),
        Map.entry("integer", ColumnType.INT32),
        Map.entry("long", ColumnType.INT64),
        Map.entry("unsigned_long", ColumnType.UINT64),
        Map.entry("half_float", ColumnType.FLOAT32),
        Map.entry("float", ColumnType.FLOAT32),
        Map.entry("double", ColumnType.FLOAT64),
        Map.entry("scaled_float", ColumnType.FLOAT64),
        Map.entry("boolean", ColumnType.BOOL),
        Map.entry("date", ColumnType.TIMESTAMP_MILLIS),
        Map.entry("date_nanos", ColumnType.TIMESTAMP_NANOS),
        Map.entry("keyword", ColumnType.UTF8),
        Map.entry("ip", ColumnType.IP)
    );

    private static final Set<ColumnType> INTEGERS = Set.of(ColumnType.INT8, ColumnType.INT16, ColumnType.INT32, ColumnType.INT64);
    private static final Set<ColumnType> FLOATS = Set.of(ColumnType.FLOAT32, ColumnType.FLOAT64);
    private static final Set<ColumnType> TIMESTAMPS = Set.of(ColumnType.TIMESTAMP_MILLIS, ColumnType.TIMESTAMP_NANOS);

    public Column {
        mappingTypes = Set.copyOf(mappingTypes);
    }

    /**
     * A scalar column.
     */
    public Column(String name, ColumnType type, Set<String> mappingTypes) {
        this(name, type, mappingTypes, false);
    }

    /**
     * @param in the stream to read from
     * @throws IOException if reading fails
     */
    public Column(StreamInput in) throws IOException {
        this(in.readString(), in.readEnum(ColumnType.class), Set.copyOf(in.readStringList()), in.readBoolean());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        out.writeEnum(type);
        out.writeStringCollection(new TreeSet<>(mappingTypes));
        out.writeBoolean(list);
    }

    /**
     * @param mappingType a mapping type
     * @return whether doc values of {@code mappingType} can be read into a column
     */
    public static boolean isSupported(String mappingType) {
        return MAPPING_TYPES.containsKey(mappingType);
    }

    /**
     * @param mappingType a supported mapping type
     * @return the column type of a field with this mapping type in every index
     */
    public static ColumnType columnType(String mappingType) {
        final ColumnType type = MAPPING_TYPES.get(mappingType);
        if (type == null) {
            throw new IllegalArgumentException("mapping type [" + mappingType + "] is not supported by Arrow search");
        }
        return type;
    }

    /**
     * Picks the column type that holds every value of a field mapped with {@code mappingTypes} across indices.
     *
     * @param mappingTypes the supported mapping types of the field
     * @return the column type, or {@code null} when the types are incompatible
     */
    public static ColumnType widen(Set<String> mappingTypes) {
        ColumnType widest = null;
        for (String mappingType : mappingTypes) {
            final ColumnType type = columnType(mappingType);
            if (widest == null || widest == type) {
                widest = type;
            } else if (INTEGERS.contains(widest) && INTEGERS.contains(type)) {
                widest = ColumnType.INT64;
            } else if (FLOATS.contains(widest) && FLOATS.contains(type)) {
                widest = ColumnType.FLOAT64;
            } else if (TIMESTAMPS.contains(widest) && TIMESTAMPS.contains(type)) {
                widest = ColumnType.TIMESTAMP_NANOS;
            } else {
                return null;
            }
        }
        return widest;
    }

    /**
     * @return the nullable Arrow field of this column; a document without values reads as null
     */
    public Field toArrowField() {
        final Map<String, String> metadata = Map.of(MAPPING_TYPES_METADATA_KEY, String.join(",", new TreeSet<>(mappingTypes)));
        if (list) {
            final Field item = new Field(LIST_ITEM_NAME, FieldType.notNullable(type.arrowType()), null);
            return new Field(name, new FieldType(true, ArrowType.List.INSTANCE, null, metadata), List.of(item));
        }
        return new Field(name, new FieldType(true, type.arrowType(), null, metadata), null);
    }

    /**
     * @param columns the columns, in order
     * @return the Arrow schema of batches with these columns
     */
    public static Schema toSchema(List<Column> columns) {
        return new Schema(columns.stream().map(Column::toArrowField).toList());
    }
}
