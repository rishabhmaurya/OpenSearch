/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.index.LeafReaderContext;
import org.opensearch.index.query.DerivedFieldScript;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.script.Script;
import org.opensearch.search.lookup.SourceLookup;

import java.io.IOException;
import java.util.List;

/**
 * ValueFetcher used by Derived Fields.
 */
public final class DerivedFieldValueFetcher implements ValueFetcher {
    private DerivedFieldScript derivedFieldScript;
    private final DerivedFieldScript.LeafFactory derivedFieldScriptFactory;

    DerivedFieldValueFetcher(QueryShardContext context, Script script) {
        if (!context.documentMapper("").sourceMapper().enabled()) {
            throw new IllegalArgumentException(
                "DerivedFieldQuery error: unable to fetch fields from _source field: _source is disabled in the mappings "
                    + "for index ["
                    + context.index().getName()
                    + "]"
            );
        }
        DerivedFieldScript.Factory factory = context.compile(script, DerivedFieldScript.CONTEXT);
        derivedFieldScriptFactory = factory.newFactory(script.getParams(), context.lookup());
    }

    @Override
    public List<Object> fetchValues(SourceLookup lookup) {
        derivedFieldScript.setDocument(lookup.docId());
        // TODO: remove List.of() when derivedFieldScript.execute() returns list of objects.
        return List.of(derivedFieldScript.execute());
    }

    public void setNextReader(LeafReaderContext context) {
        try {
            derivedFieldScript = derivedFieldScriptFactory.newInstance(context);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
