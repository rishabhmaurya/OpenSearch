/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.KeywordField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.opensearch.common.Nullable;
import org.opensearch.common.geo.ShapeRelation;
import org.opensearch.common.lucene.Lucene;
import org.opensearch.common.time.DateMathParser;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.query.DerivedFieldQuery;
import org.opensearch.index.query.DerivedFieldScript;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.script.Script;
import org.opensearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A field mapper for derived fields
 *
 * @opensearch.internal
 */
public class DerivedFieldMapper extends ParametrizedFieldMapper {

    public static final String CONTENT_TYPE = "derived_field";

    /**
     * Default parameters for the boolean field mapper
     *
     * @opensearch.internal
     */
    public static class Defaults {
        public static final FieldType FIELD_TYPE = new FieldType();

        static {
            FIELD_TYPE.setOmitNorms(true);
            FIELD_TYPE.setStored(false);
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.setIndexOptions(IndexOptions.NONE);
            FIELD_TYPE.freeze();
        }
    }

    private static DerivedFieldMapper toType(FieldMapper in) {
        return (DerivedFieldMapper) in;
    }

    /**
     * Builder for this field mapper
     *
     * @opensearch.internal
     */
    public static class Builder extends ParametrizedFieldMapper.Builder {
        // TODO: The type of parameter may change here if the actual underlying FieldType object is needed
        private final Parameter<String> type = Parameter.stringParam(
            "type",
            false,
            m -> toType(m).type,
            "text"
        );

        private final Parameter<Script> script = new Parameter<>(
            "script",
            false,
            () -> null,
            (n, c, o) -> o == null ? null : Script.parse(o),
            m -> toType(m).script
        ).setSerializerCheck((id, ic, value) -> value != null);

        public Builder(String name) {
            super(name);
        }


        @Override
        protected List<Parameter<?>> getParameters() {
            return Arrays.asList(type, script);
        }

        @Override
        public DerivedFieldMapper build(BuilderContext context) {
            FieldMapper typeFieldMapper;
            Function<Object, IndexableField> fieldFunction;
            switch (type.getValue()) {
                // TODO: add logic all supported type in derived fields
                // TODO: should we support mapping settings exposed by a given field type from derived fields too?
                // for example, support `format` for date type?
                case KeywordFieldMapper.CONTENT_TYPE:
                    FieldType dummyFieldType = new FieldType();
                    dummyFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
                    KeywordFieldMapper.Builder keywordBuilder = new KeywordFieldMapper.Builder(name());
                    KeywordFieldMapper.KeywordFieldType keywordFieldType = keywordBuilder.buildFieldType(context, dummyFieldType);
                    keywordFieldType.setIndexAnalyzer(Lucene.KEYWORD_ANALYZER);
                    typeFieldMapper = new KeywordFieldMapper(
                        name(), dummyFieldType, keywordFieldType,
                        keywordBuilder.multiFieldsBuilder.build(this, context),
                        keywordBuilder.copyTo.build(),
                        keywordBuilder
                    );
                    fieldFunction = o -> new KeywordField(name(), (String) o, Field.Store.NO);
                    break;
                case "long":
                    // ignoreMalformed?
                    NumberFieldMapper.Builder longBuilder = new NumberFieldMapper.Builder(name, NumberFieldMapper.NumberType.LONG, false, false);
                    typeFieldMapper = longBuilder.build(context);
                    fieldFunction = o -> new LongField(name(), Long.parseLong(o.toString()), Field.Store.NO);
                    break;
                case "double":
                    // ignoreMalformed?
                    NumberFieldMapper.Builder doubleBuilder = new NumberFieldMapper.Builder(name, NumberFieldMapper.NumberType.DOUBLE, false, false);
                    typeFieldMapper = doubleBuilder.build(context);
                    fieldFunction = o -> new DoubleField(name(), Double.parseDouble(o.toString()), Field.Store.NO);
                    break;
                default:
                    throw new IllegalArgumentException("Field [" + name() + "] of type [" + type + "] isn't supported " +
                        "in Derived field context.");

            }
            MappedFieldType ft = new DerivedFieldType(buildFullName(context), type.getValue(), script.getValue(), typeFieldMapper, fieldFunction);
            return new DerivedFieldMapper(name, ft, multiFieldsBuilder.build(this, context), copyTo.build(), this);
        }
    }


    public static final TypeParser PARSER = new TypeParser((n, c) -> new Builder(n));

    /**
     * Field type for derived field mapper
     *
     * @opensearch.internal
     */
    public static final class DerivedFieldType extends MappedFieldType {
        private final String type;

        private final Script script;

        FieldMapper typeFieldMapper;

        private final Function<Object, IndexableField> fieldFunction;

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
            this.fieldFunction = fieldFunction;
        }

        public DerivedFieldType(String name, String type, Script script, FieldMapper typeFieldMapper, Function<Object, IndexableField> fieldFunction) {
            this(name, type, script, false, false, false, Collections.emptyMap(), typeFieldMapper, fieldFunction);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public ValueFetcher valueFetcher(QueryShardContext context, SearchLookup searchLookup, String format) {
            if (format != null) {
                throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
            }

            // TODO Return to this during Query implementation. The derived fields don't typically exist in _source but
            //  we may want fetch the field name from source if a 'script' is not provided.
            return new SourceValueFetcher(name(), context) {
                @Override
                protected Object parseSourceValue(Object value) {
                    return value;
                }
            };
        }

        @Override
        public Query termQuery(Object value, @Nullable QueryShardContext context) {
            Query query = typeFieldMapper.mappedFieldType.termQuery(value, context);
            DerivedFieldScript.Factory factory = context.compile(script, DerivedFieldScript.CONTEXT);
            DerivedFieldScript.LeafFactory derivedFieldScript = factory.newFactory(script.getParams(), context.lookup());
            return new DerivedFieldQuery(query, derivedFieldScript, typeFieldMapper.mappedFieldType.indexAnalyzer(), fieldFunction, context);
        }

        @Override
        public Query prefixQuery(
            String value,
            @Nullable MultiTermQuery.RewriteMethod method,
            boolean caseInsensitive,
            QueryShardContext context
        ) {
            Query query = typeFieldMapper.mappedFieldType.prefixQuery(value, method, caseInsensitive, context);
            DerivedFieldScript.Factory factory = context.compile(script, DerivedFieldScript.CONTEXT);
            DerivedFieldScript.LeafFactory derivedFieldScript = factory.newFactory(script.getParams(), context.lookup());
            return new DerivedFieldQuery(query, derivedFieldScript, typeFieldMapper.mappedFieldType.indexAnalyzer(), fieldFunction, context);
        }

        @Override
        public Query wildcardQuery(
            String value,
            @Nullable MultiTermQuery.RewriteMethod method,
            boolean caseInsensitive,
            QueryShardContext context
        ) {
            Query query = typeFieldMapper.mappedFieldType.wildcardQuery(value, method, caseInsensitive, context);
            DerivedFieldScript.Factory factory = context.compile(script, DerivedFieldScript.CONTEXT);
            DerivedFieldScript.LeafFactory derivedFieldScript = factory.newFactory(script.getParams(), context.lookup());
            return new DerivedFieldQuery(query, derivedFieldScript, typeFieldMapper.mappedFieldType.indexAnalyzer(), fieldFunction, context);
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
            DerivedFieldScript.Factory factory = context.compile(script, DerivedFieldScript.CONTEXT);
            DerivedFieldScript.LeafFactory derivedFieldScript = factory.newFactory(script.getParams(), context.lookup());
            return new DerivedFieldQuery(query, derivedFieldScript, typeFieldMapper.mappedFieldType.indexAnalyzer(), fieldFunction, context);
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
            DerivedFieldScript.Factory factory = context.compile(script, DerivedFieldScript.CONTEXT);
            DerivedFieldScript.LeafFactory derivedFieldScript = factory.newFactory(script.getParams(), context.lookup());
            return new DerivedFieldQuery(query, derivedFieldScript, typeFieldMapper.mappedFieldType.indexAnalyzer(), fieldFunction, context);
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


    private final String type;

    private final Script script;

    protected DerivedFieldMapper(
        String simpleName,
        MappedFieldType mappedFieldType,
        MultiFields multiFields,
        CopyTo copyTo,
        Builder builder
    ) {
        super(simpleName, mappedFieldType, multiFields, copyTo);
        this.type = builder.type.getValue();
        this.script = builder.script.getValue();
    }

    @Override
    public DerivedFieldType fieldType() {
        return (DerivedFieldType) super.fieldType();
    }

    @Override
    protected void parseCreateField(ParseContext context) throws IOException {
        // Leaving this empty as the parsing should be handled via the Builder when root object is parsed.
        // The context would not contain anything in this case since the DerivedFieldMapper is not indexed or stored.
        throw new UnsupportedOperationException("should not be invoked");
    }

    @Override
    public ParametrizedFieldMapper.Builder getMergeBuilder() {
        return new Builder(simpleName()).init(this);
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        getMergeBuilder().toXContent(builder, includeDefaults);
        multiFields.toXContent(builder, params);
        copyTo.toXContent(builder, params);
    }

    public String getType() {
        return type;
    }

    public Script getScript() {
        return script;
    }
}
