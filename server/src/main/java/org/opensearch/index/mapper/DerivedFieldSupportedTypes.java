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
import org.opensearch.common.lucene.Lucene;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum DerivedFieldSupportedTypes {
    KEYWORD (
        "keyword",
        (name, context) -> {
            FieldType dummyFieldType = new FieldType();
            dummyFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
            KeywordFieldMapper.Builder keywordBuilder = new KeywordFieldMapper.Builder(name);
            KeywordFieldMapper.KeywordFieldType keywordFieldType = keywordBuilder.buildFieldType(context, dummyFieldType);
            keywordFieldType.setIndexAnalyzer(Lucene.KEYWORD_ANALYZER);
            return new KeywordFieldMapper(
                name, dummyFieldType, keywordFieldType,
                keywordBuilder.multiFieldsBuilder.build(keywordBuilder, context),
                keywordBuilder.copyTo.build(),
                keywordBuilder
            );
        },
        name -> o -> new KeywordField(name, (String) o, Field.Store.NO)
    ),
    LONG (
        "long",
        (name, context) -> {
            NumberFieldMapper.Builder longBuilder = new NumberFieldMapper.Builder(name, NumberFieldMapper.NumberType.LONG, false, false);
            return longBuilder.build(context);
        },
        name -> o -> new LongField(name, Long.parseLong(o.toString()), Field.Store.NO)
    ),
    DOUBLE (
        "double",
        (name, context) -> {
            NumberFieldMapper.Builder doubleBuilder = new NumberFieldMapper.Builder(name, NumberFieldMapper.NumberType.DOUBLE, false, false);
            return doubleBuilder.build(context);
        },
        name -> o -> new DoubleField(name, Double.parseDouble(o.toString()), Field.Store.NO)
    );
    // TODO: add logic all supported type in derived fields
    // TODO: should we support mapping settings exposed by a given field type from derived fields too?
    // for example, support `format` for date type?
    final String name;
    private final BiFunction<String, Mapper.BuilderContext, FieldMapper> builder;

    private final Function<String, Function<Object, IndexableField>> indexableFieldBuilder;

    DerivedFieldSupportedTypes(String name, BiFunction<String, Mapper.BuilderContext, FieldMapper> builder,
                               Function<String, Function<Object, IndexableField>> indexableFieldBuilder){
        this.name = name;
        this.builder = builder;
        this.indexableFieldBuilder = indexableFieldBuilder;
    }

    public String getName() {
        return name;
    }
    private FieldMapper getFieldMapper(String name, Mapper.BuilderContext context) {
        return builder.apply(name, context);
    }

    private Function<Object, IndexableField> getIndexableFieldGenerator(String name) {
        return indexableFieldBuilder.apply(name);
    }
    private static final Map<String, DerivedFieldSupportedTypes> enumMap = Arrays.stream(DerivedFieldSupportedTypes.values())
        .collect(Collectors.toMap(DerivedFieldSupportedTypes::getName, enumValue -> enumValue));

    public static FieldMapper getFieldMapperFromType(String type, String name, Mapper.BuilderContext context) {
        if (!enumMap.containsKey(type)) {
            throw new IllegalArgumentException("Type [" + type + "] isn't supported in Derived field context.");
        }
        return enumMap.get(type).getFieldMapper(name, context);
    }

    public static Function<Object, IndexableField> getIndexableFieldGeneratorType(String type, String name) {
        if (!enumMap.containsKey(type)) {
            throw new IllegalArgumentException("Type [" + type + "] isn't supported in Derived field context.");
        }
        return enumMap.get(type).getIndexableFieldGenerator(name);
    }
}
