/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.opensearch.common.Nullable;
import org.opensearch.common.geo.ShapeRelation;
import org.opensearch.common.time.DateMathParser;
import org.opensearch.index.query.DerivedFieldQuery;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.script.Script;
import org.opensearch.search.lookup.SearchLookup;

import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;


/**
 * MappedFieldType for {@link DerivedFieldMapper}
 * Contains logic to different type of queries on derived fields
 * @opensearch.internal
 */
public final class DerivedFieldType extends MappedFieldType {
    private final String type;

    private final Script script;

    FieldMapper typeFieldMapper;

    private final Function<Object, IndexableField> indexableFieldGenerator;

    public DerivedFieldType(
        String name,
        String type,
        Script script,
        boolean isIndexed,
        boolean isStored,
        boolean hasDocValues,
        Map<String, String> meta,
        FieldMapper typeFieldMapper,
        Function<Object, IndexableField> fieldFunction
    ) {
        super(name, isIndexed, isStored, hasDocValues, typeFieldMapper.fieldType().getTextSearchInfo(), meta);
        this.type = type;
        this.script = script;
        this.typeFieldMapper = typeFieldMapper;
        this.indexableFieldGenerator = fieldFunction;
    }

    public DerivedFieldType(String name, String type, Script script, FieldMapper typeFieldMapper, Function<Object, IndexableField> fieldFunction) {
        this(name, type, script, false, false, false, Collections.emptyMap(), typeFieldMapper, fieldFunction);
    }

    @Override
    public String typeName() {
        return DerivedFieldMapper.CONTENT_TYPE;
    }

    public String getType() {
        return type;
    }

    @Override
    public DerivedFieldValueFetcher valueFetcher(QueryShardContext context, SearchLookup searchLookup, String format) {
        if (format != null) {
            throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
        }
        return new DerivedFieldValueFetcher(context, script);
    }

    @Override
    public Query termQuery(Object value, @Nullable QueryShardContext context) {
        Query query = typeFieldMapper.mappedFieldType.termQuery(value, context);
        DerivedFieldValueFetcher valueFetcher =  new DerivedFieldValueFetcher(context, script);
        return new DerivedFieldQuery(query, valueFetcher, context.lookup(), indexableFieldGenerator, typeFieldMapper.mappedFieldType.indexAnalyzer());
    }

    @Override
    public Query prefixQuery(
        String value,
        @Nullable MultiTermQuery.RewriteMethod method,
        boolean caseInsensitive,
        QueryShardContext context
    ) {
        Query query = typeFieldMapper.mappedFieldType.prefixQuery(value, method, caseInsensitive, context);
        DerivedFieldValueFetcher valueFetcher =  new DerivedFieldValueFetcher(context, script);
        return new DerivedFieldQuery(query, valueFetcher, context.lookup(), indexableFieldGenerator, typeFieldMapper.mappedFieldType.indexAnalyzer());
    }

    @Override
    public Query wildcardQuery(
        String value,
        @Nullable MultiTermQuery.RewriteMethod method,
        boolean caseInsensitive,
        QueryShardContext context
    ) {
        Query query = typeFieldMapper.mappedFieldType.wildcardQuery(value, method, caseInsensitive, context);
        DerivedFieldValueFetcher valueFetcher =  new DerivedFieldValueFetcher(context, script);
        return new DerivedFieldQuery(query, valueFetcher, context.lookup(), indexableFieldGenerator, typeFieldMapper.mappedFieldType.indexAnalyzer());
    }

    @Override
    public Query regexpQuery(
        String value,
        int syntaxFlags,
        int matchFlags,
        int maxDeterminizedStates,
        @Nullable MultiTermQuery.RewriteMethod method,
        QueryShardContext context
    ) {
        Query query = typeFieldMapper.mappedFieldType.regexpQuery(value, syntaxFlags, matchFlags,
            maxDeterminizedStates, method, context);
        DerivedFieldValueFetcher valueFetcher =  new DerivedFieldValueFetcher(context, script);
        return new DerivedFieldQuery(query, valueFetcher, context.lookup(), indexableFieldGenerator, typeFieldMapper.mappedFieldType.indexAnalyzer());
    }

    // TODO: Override all types of queries which can be supported by all types supported within derived fields.
    @Override
    public Query rangeQuery(
        Object lowerTerm,
        Object upperTerm,
        boolean includeLower,
        boolean includeUpper,
        ShapeRelation relation,
        ZoneId timeZone,
        DateMathParser parser,
        QueryShardContext context
    ) {
        Query query = typeFieldMapper.mappedFieldType.rangeQuery(lowerTerm, upperTerm, includeLower, includeUpper,
            relation, timeZone, parser, context);
        DerivedFieldValueFetcher valueFetcher =  new DerivedFieldValueFetcher(context, script);
        return new DerivedFieldQuery(query, valueFetcher, context.lookup(), indexableFieldGenerator, typeFieldMapper.mappedFieldType.indexAnalyzer());
    }

    @Override
    public Query existsQuery(QueryShardContext context) {
        throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] does not support exist queries");
    }

    @Override
    public boolean isAggregatable() {
        return false;
    }
}

