/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.regex.Regex;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.script.Script;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.opensearch.index.mapper.FieldMapper.IGNORE_MALFORMED_SETTING;

/**
 * Accepts definition of DerivedField from search request in both forms: map parsed from SearchRequest and {@link DerivedField} defined using client.
 * The object is initialized per search request and is responsible to resolve {@link DerivedFieldType} given a field name.
 * It uses {@link FieldTypeInference} to infer field type for a nested field within DerivedField of {@link DerivedFieldSupportedTypes#OBJECT} type.
 */
public class DefaultDerivedFieldResolver implements DerivedFieldResolver {
    private final QueryShardContext queryShardContext;
    private final Map<String, DerivedFieldType> derivedFieldTypeMap = new ConcurrentHashMap<>();
    private final FieldTypeInference typeInference;
    private static final Logger logger = LogManager.getLogger(DefaultDerivedFieldResolver.class);

    public DefaultDerivedFieldResolver(
        QueryShardContext queryShardContext,
        Map<String, Object> derivedFieldsObject,
        List<DerivedField> derivedFields
    ) {
        this(
            queryShardContext,
            derivedFieldsObject,
            derivedFields,
            new FieldTypeInference(
                queryShardContext.index().getName(),
                queryShardContext.getMapperService(),
                queryShardContext.getIndexReader()
            )
        );
    }

    public DefaultDerivedFieldResolver(
        QueryShardContext queryShardContext,
        Map<String, Object> derivedFieldsObject,
        List<DerivedField> derivedFields,
        FieldTypeInference typeInference
    ) {
        this.queryShardContext = queryShardContext;
        initDerivedFieldTypes(derivedFieldsObject, derivedFields);
        this.typeInference = typeInference;
    }

    @Override
    public Set<String> resolvePattern(String pattern) {
        Set<String> derivedFields = new HashSet<>();
        if (queryShardContext != null && queryShardContext.getMapperService() != null) {
            for (MappedFieldType fieldType : queryShardContext.getMapperService().fieldTypes()) {
                if (Regex.simpleMatch(pattern, fieldType.name()) && fieldType instanceof DerivedFieldType) {
                    derivedFields.add(fieldType.name());
                }
            }
        }
        for (String fieldName : derivedFieldTypeMap.keySet()) {
            if (Regex.simpleMatch(pattern, fieldName)) {
                derivedFields.add(fieldName);
            }
        }
        return derivedFields;
    }

    /**
     * Resolves the fieldName. The search request definitions are given precedence over derived fields definitions in the index mapping.
     * It caches the response for previously resolved field names
     * @param fieldName name of the field. It also accepts nested derived field
     * @return DerivedFieldType if resolved successfully, a null otherwise.
     */
    @Override
    public DerivedFieldType resolve(String fieldName) {
        return Optional.ofNullable(resolveUsingSearchDefinitions(fieldName)).orElseGet(() -> resolveUsingMappings(fieldName));
    }

    private DerivedFieldType resolveUsingSearchDefinitions(String fieldName) {
        if (derivedFieldTypeMap.containsKey(fieldName)) {
            return derivedFieldTypeMap.get(fieldName);
        }
        DerivedFieldType resolvedNestedType = resolveNestedField(fieldName);
        if (resolvedNestedType != null) {
            derivedFieldTypeMap.put(fieldName, resolvedNestedType);
        }
        return resolvedNestedType;
    }

    private DerivedFieldType resolveNestedField(String fieldName) {
        DerivedFieldType parentDerivedField = (DerivedFieldType) getParentDerivedField(fieldName);
        if (parentDerivedField != null) {
            try {
                Script script = parentDerivedField.derivedField.getScript();
                String nestedType = explicitTypeFromParent(
                    parentDerivedField.derivedField,
                    fieldName.substring(fieldName.indexOf(".") + 1)
                );
                if (nestedType == null) {
                    Mapper inferredFieldMapper = typeInference.infer(
                        getValueFetcher(fieldName, script, parentDerivedField.derivedField.getIgnoreMalformed())
                    );
                    if (inferredFieldMapper != null) {
                        nestedType = inferredFieldMapper.typeName();
                    }
                }
                if (nestedType != null) {
                    DerivedField derivedField = new DerivedField(fieldName, nestedType, script);
                    if (parentDerivedField.derivedField.getProperties() != null) {
                        derivedField.setProperties(parentDerivedField.derivedField.getProperties());
                    }
                    if (parentDerivedField.derivedField.getSourceIndexedField() != null) {
                        derivedField.setSourceIndexedField(parentDerivedField.derivedField.getSourceIndexedField());
                    }
                    if (parentDerivedField.derivedField.getFormat() != null) {
                        derivedField.setFormat(parentDerivedField.derivedField.getFormat());
                    }
                    if (parentDerivedField.derivedField.getIgnoreMalformed()) {
                        derivedField.setIgnoreMalformed(parentDerivedField.derivedField.getIgnoreMalformed());
                    }
                    return getDerivedFieldType(derivedField);
                } else {
                    logger.warn(
                        "Field type cannot be inferred. Ensure the field {} is not rare across entire index or provide explicit mapping using [properties] under parent object [{}] ",
                        fieldName,
                        parentDerivedField.derivedField.getName()
                    );
                }
            } catch (IOException e) {
                logger.warn(e.getMessage());
            }
        }
        return null;
    }

    private MappedFieldType getParentDerivedField(String fieldName) {
        if (fieldName.contains(".")) {
            return resolve(fieldName.split("\\.")[0]);
        }
        return null;
    }

    private static String explicitTypeFromParent(DerivedField parentDerivedField, String subField) {
        if (parentDerivedField == null
            || parentDerivedField.getProperties() == null
            || parentDerivedField.getProperties().isEmpty()
            || subField == null
            || subField.isEmpty()) {
            return null;
        }
        return parentDerivedField.getProperties().get(subField);
    }

    ValueFetcher getValueFetcher(String fieldName, Script script, boolean ignoreMalformed) {
        String subFieldName = fieldName.substring(fieldName.indexOf(".") + 1);
        return new ObjectDerivedFieldType.ObjectDerivedFieldValueFetcher(
            subFieldName,
            DerivedFieldType.getDerivedFieldLeafFactory(script, queryShardContext, queryShardContext.lookup()),
            o -> o, // raw object returned will be used to infer the type without modifying it
            ignoreMalformed
        );
    }

    private void initDerivedFieldTypes(Map<String, Object> derivedFieldsObject, List<DerivedField> derivedFields) {
        if (derivedFieldsObject != null && !derivedFieldsObject.isEmpty()) {
            Map<String, Object> derivedFieldObject = new HashMap<>();
            derivedFieldObject.put(DerivedFieldMapper.CONTENT_TYPE, derivedFieldsObject);
            derivedFieldTypeMap.putAll(getAllDerivedFieldTypeFromObject(derivedFieldObject));
        }
        if (derivedFields != null) {
            for (DerivedField derivedField : derivedFields) {
                derivedFieldTypeMap.put(derivedField.getName(), getDerivedFieldType(derivedField));
            }
        }
    }

    private Map<String, DerivedFieldType> getAllDerivedFieldTypeFromObject(Map<String, Object> derivedFieldObject) {
        Map<String, DerivedFieldType> derivedFieldTypes = new HashMap<>();
        DocumentMapper documentMapper = queryShardContext.getMapperService()
            .documentMapperParser()
            .parse(DerivedFieldMapper.CONTENT_TYPE, derivedFieldObject);
        if (documentMapper != null && documentMapper.mappers() != null) {
            for (Mapper mapper : documentMapper.mappers()) {
                if (mapper instanceof DerivedFieldMapper) {
                    DerivedFieldType derivedFieldType = ((DerivedFieldMapper) mapper).fieldType();
                    derivedFieldTypes.put(derivedFieldType.name(), derivedFieldType);
                }
            }
        }
        return derivedFieldTypes;
    }

    private DerivedFieldType getDerivedFieldType(DerivedField derivedField) {
        Mapper.BuilderContext builderContext = new Mapper.BuilderContext(
            queryShardContext.getMapperService().getIndexSettings().getSettings(),
            new ContentPath(1)
        );
        DerivedFieldMapper.Builder builder = new DerivedFieldMapper.Builder(
            derivedField,
            queryShardContext.getMapperService().getIndexAnalyzers(),
            null,
            IGNORE_MALFORMED_SETTING.getDefault(queryShardContext.getIndexSettings().getSettings())
        );
        return builder.build(builderContext).fieldType();
    }

    private DerivedFieldType resolveUsingMappings(String name) {
        if (queryShardContext != null && queryShardContext.getMapperService() != null) {
            MappedFieldType mappedFieldType = queryShardContext.getMapperService().fieldType(name);
            if (mappedFieldType instanceof DerivedFieldType) {
                return (DerivedFieldType) mappedFieldType;
            }
        }
        return null;
    }
}
