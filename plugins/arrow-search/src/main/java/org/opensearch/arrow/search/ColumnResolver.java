/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search;

import org.opensearch.action.fieldcaps.FieldCapabilities;
import org.opensearch.action.fieldcaps.FieldCapabilitiesResponse;
import org.opensearch.common.regex.Regex;
import org.opensearch.index.IndexSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves the requested {@code docvalue_fields} into columns from field capabilities merged across the target indices,
 * so that every shard produces the same schema.
 * <p>
 * An explicitly named field must be mapped in at least one index, with compatible supported types and doc values wherever
 * it is mapped; indices without the field produce nulls. A wildcard pattern expands, in name order, to the matching fields
 * that meet these conditions and skips the others. Since every shard produces every column, the columns are bounded like the
 * doc value fields a search of one index may read.
 * <p>
 * A field inside a nested field does not qualify: its values are indexed on hidden child documents, while a scan reads the
 * top-level documents, so the column would only hold nulls.
 */
public final class ColumnResolver {

    /** Field capabilities type reported for indices that do not map a field. */
    private static final String UNMAPPED = "unmapped";
    /** Field capabilities type reported for the path of a nested field. */
    private static final String NESTED = "nested";

    private ColumnResolver() {}

    /**
     * A requested field name or pattern.
     *
     * @param pattern a field name or wildcard pattern
     * @param list    whether the matching fields read into list columns
     */
    public record RequestedField(String pattern, boolean list) {
    }

    /**
     * @param requested  field names and patterns, in output order; a field matched by several keeps the first
     * @param fieldCaps  field capabilities of the target indices, including unmapped entries
     * @param maxColumns the most columns allowed: the largest {@code index.max_docvalue_fields_search} of the target indices
     * @return the columns, without duplicates, in the order the fields were first requested
     * @throws IllegalArgumentException if a named field is unusable, or no column or more than {@code maxColumns} result
     */
    public static List<Column> resolve(List<RequestedField> requested, FieldCapabilitiesResponse fieldCaps, int maxColumns) {
        final Map<String, Map<String, FieldCapabilities>> capabilities = fieldCaps.get();
        final Map<String, Column> columns = new LinkedHashMap<>();
        for (RequestedField request : requested) {
            final String pattern = request.pattern();
            if (Regex.isSimpleMatchPattern(pattern)) {
                for (String field : new TreeSet<>(capabilities.keySet())) {
                    if (columns.containsKey(field) == false
                        && Regex.simpleMatch(pattern, field)
                        && nestedParent(field, capabilities) == null) {
                        final Column column = toColumn(field, capabilities.get(field), request.list(), false);
                        if (column != null) {
                            columns.put(field, column);
                        }
                    }
                }
            } else if (columns.containsKey(pattern) == false) {
                final Map<String, FieldCapabilities> byType = capabilities.get(pattern);
                if (byType == null || byType.keySet().stream().allMatch(UNMAPPED::equals)) {
                    throw new IllegalArgumentException("field [" + pattern + "] is not mapped in any target index");
                }
                final String nested = nestedParent(pattern, capabilities);
                if (nested != null) {
                    throw new IllegalArgumentException(
                        "field [" + pattern + "] is inside the nested field [" + nested + "], which Arrow search does not support"
                    );
                }
                columns.put(pattern, toColumn(pattern, byType, request.list(), true));
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                "[docvalue_fields] " + requested.stream().map(RequestedField::pattern).toList() + " selected no field with doc values"
            );
        }
        if (columns.size() > maxColumns) {
            throw new IllegalArgumentException(
                "[docvalue_fields] select ["
                    + columns.size()
                    + "] fields across the target indices, more than the ["
                    + maxColumns
                    + "] allowed by ["
                    + IndexSettings.MAX_DOCVALUE_FIELDS_SEARCH_SETTING.getKey()
                    + "]; every shard reads every selected field"
            );
        }
        return new ArrayList<>(columns.values());
    }

    /**
     * @return the closest enclosing path of {@code field} that some target index maps as a nested field, or {@code null};
     *         field capabilities report the enclosing paths of every field they return
     */
    private static String nestedParent(String field, Map<String, Map<String, FieldCapabilities>> capabilities) {
        for (int dot = field.lastIndexOf('.'); dot > 0; dot = field.lastIndexOf('.', dot - 1)) {
            final String parent = field.substring(0, dot);
            final Map<String, FieldCapabilities> byType = capabilities.get(parent);
            if (byType != null && byType.containsKey(NESTED)) {
                return parent;
            }
        }
        return null;
    }

    private static Column toColumn(String field, Map<String, FieldCapabilities> byType, boolean list, boolean strict) {
        final Set<String> mappingTypes = new HashSet<>();
        for (Map.Entry<String, FieldCapabilities> entry : byType.entrySet()) {
            final String type = entry.getKey();
            if (UNMAPPED.equals(type)) {
                continue;
            }
            if (Column.isSupported(type) == false) {
                return reject(strict, "field [" + field + "] of type [" + type + "] is not supported by Arrow search");
            }
            // Field capabilities report doc values as aggregatable for every supported type.
            if (entry.getValue().isAggregatable() == false) {
                return reject(strict, "field [" + field + "] has no doc values in at least one target index");
            }
            mappingTypes.add(type);
        }
        if (mappingTypes.isEmpty()) {
            return null;
        }
        final Column.ColumnType type = Column.widen(mappingTypes);
        if (type == null) {
            return reject(
                strict,
                "field [" + field + "] is mapped to incompatible types " + new TreeSet<>(mappingTypes) + " across target indices"
            );
        }
        return new Column(field, type, mappingTypes, list);
    }

    private static Column reject(boolean strict, String message) {
        if (strict) {
            throw new IllegalArgumentException(message);
        }
        return null;
    }
}
