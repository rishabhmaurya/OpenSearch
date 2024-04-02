/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.opensearch.common.Nullable;
import org.opensearch.common.geo.ShapeRelation;
import org.opensearch.common.time.DateMathParser;
import org.opensearch.common.unit.Fuzziness;
import org.opensearch.index.query.DerivedFieldQuery;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.script.DerivedFieldScript;
import org.opensearch.script.Script;
import org.opensearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MappedFieldType for Derived Fields
 * Contains logic to execute different type of queries on a derived field of given type.
 * @opensearch.internal
 */
public final class DerivedFieldType extends MappedFieldType {
    private final String type;

    private final Script script;

    FieldMapper typeFieldMapper;

    final Function<Object, IndexableField> indexableFieldGenerator;

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

    public DerivedFieldType(
        String name,
        String type,
        Script script,
        FieldMapper typeFieldMapper,
        Function<Object, IndexableField> fieldFunction
    ) {
        this(name, type, script, false, false, false, Collections.emptyMap(), typeFieldMapper, fieldFunction);
    }

    @Override
    public String typeName() {
        return "derived";
    }

    public String getType() {
        return type;
    }

    @Override
    public DerivedFieldValueFetcher valueFetcher(QueryShardContext context, SearchLookup searchLookup, String format) {
        if (format != null) {
            throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
        }
        return new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
    }

    @Override
    public Query termQuery(Object value, QueryShardContext context) {
        Query query = typeFieldMapper.mappedFieldType.termQuery(value, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query termQueryCaseInsensitive(Object value, @Nullable QueryShardContext context) {
        Query query = typeFieldMapper.mappedFieldType.termQueryCaseInsensitive(value, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query termsQuery(List<?> values, @Nullable QueryShardContext context) {
        Query query = typeFieldMapper.mappedFieldType.termsQuery(values, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

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
        Query query = typeFieldMapper.mappedFieldType.rangeQuery(
            lowerTerm,
            upperTerm,
            includeLower,
            includeUpper,
            relation,
            timeZone,
            parser,
            context
        );
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query fuzzyQuery(
        Object value,
        Fuzziness fuzziness,
        int prefixLength,
        int maxExpansions,
        boolean transpositions,
        QueryShardContext context
    ) {
        Query query = typeFieldMapper.mappedFieldType.fuzzyQuery(value, fuzziness, prefixLength, maxExpansions, transpositions, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query fuzzyQuery(
        Object value,
        Fuzziness fuzziness,
        int prefixLength,
        int maxExpansions,
        boolean transpositions,
        @Nullable MultiTermQuery.RewriteMethod method,
        QueryShardContext context
    ) {
        Query query = typeFieldMapper.mappedFieldType.fuzzyQuery(
            value,
            fuzziness,
            prefixLength,
            maxExpansions,
            transpositions,
            method,
            context
        );
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query prefixQuery(
        String value,
        @Nullable MultiTermQuery.RewriteMethod method,
        boolean caseInsensitive,
        QueryShardContext context
    ) {
        Query query = typeFieldMapper.mappedFieldType.prefixQuery(value, method, caseInsensitive, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query wildcardQuery(
        String value,
        @Nullable MultiTermQuery.RewriteMethod method,
        boolean caseInsensitive,
        QueryShardContext context
    ) {
        Query query = typeFieldMapper.mappedFieldType.wildcardQuery(value, method, caseInsensitive, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query normalizedWildcardQuery(String value, @Nullable MultiTermQuery.RewriteMethod method, QueryShardContext context) {
        Query query = typeFieldMapper.mappedFieldType.normalizedWildcardQuery(value, method, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
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
        Query query = typeFieldMapper.mappedFieldType.regexpQuery(value, syntaxFlags, matchFlags, maxDeterminizedStates, method, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query phraseQuery(TokenStream stream, int slop, boolean enablePositionIncrements, QueryShardContext context) throws IOException {
        Query query = typeFieldMapper.mappedFieldType.phraseQuery(stream, slop, enablePositionIncrements, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query multiPhraseQuery(TokenStream stream, int slop, boolean enablePositionIncrements, QueryShardContext context)
        throws IOException {
        Query query = typeFieldMapper.mappedFieldType.multiPhraseQuery(stream, slop, enablePositionIncrements, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query phrasePrefixQuery(TokenStream stream, int slop, int maxExpansions, QueryShardContext context) throws IOException {
        Query query = typeFieldMapper.mappedFieldType.phrasePrefixQuery(stream, slop, maxExpansions, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public SpanQuery spanPrefixQuery(String value, SpanMultiTermQueryWrapper.SpanRewriteMethod method, QueryShardContext context) {
        throw new IllegalArgumentException(
            "Can only use span prefix queries on text fields - not on [" + name() + "] which is of type [" + typeName() + "]"
        );
    }

    @Override
    public Query distanceFeatureQuery(Object origin, String pivot, float boost, QueryShardContext context) {
        Query query = typeFieldMapper.mappedFieldType.distanceFeatureQuery(origin, pivot, boost, context);
        DerivedFieldValueFetcher valueFetcher = new DerivedFieldValueFetcher(getDerivedFieldLeafFactory(context));
        return new DerivedFieldQuery(
            query,
            valueFetcher,
            context.lookup(),
            indexableFieldGenerator,
            typeFieldMapper.mappedFieldType.indexAnalyzer()
        );
    }

    @Override
    public Query existsQuery(QueryShardContext context) {
        throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] does not support exist queries");
    }

    @Override
    public boolean isAggregatable() {
        return false;
    }

    private DerivedFieldScript.LeafFactory getDerivedFieldLeafFactory(QueryShardContext context) {
        if (!context.documentMapper("").sourceMapper().enabled()) {
            throw new IllegalArgumentException(
                "DerivedFieldQuery error: unable to fetch fields from _source field: _source is disabled in the mappings "
                    + "for index ["
                    + context.index().getName()
                    + "]"
            );
        }
        DerivedFieldScript.Factory factory = context.compile(script, DerivedFieldScript.CONTEXT);
        return factory.newFactory(script.getParams(), context.lookup());
    }
}
