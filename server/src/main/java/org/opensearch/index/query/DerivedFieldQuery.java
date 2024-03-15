/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.query;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * DerivedFieldQuery used for querying derived fields.
 */
public class DerivedFieldQuery extends Query {
    private final Query filter;
    private final DerivedFieldScript.LeafFactory derivedFieldScriptFactory;
    private final Analyzer indexAnalyzer;
    private final Function<Object, IndexableField> fieldFunction;
    private final QueryShardContext context;

    /**
     * @param query                     query to execute against memory index
     * @param derivedFieldScriptFactory derived field script's LeafFactory instance
     * @param indexAnalyzer             index analyzer to use while building memory index
     * @param fieldFunction             IndexableField generator
     * @param context                   QueryShardContext
     */
    public DerivedFieldQuery(Query query, DerivedFieldScript.LeafFactory derivedFieldScriptFactory,
                             Analyzer indexAnalyzer, Function<Object, IndexableField> fieldFunction,
                             QueryShardContext context) {
        this.filter = query;
        this.derivedFieldScriptFactory = derivedFieldScriptFactory;
        this.indexAnalyzer = indexAnalyzer;
        this.context = context;
        this.fieldFunction = fieldFunction;
        if (!context.documentMapper("").sourceMapper().enabled()) {
            throw new IllegalArgumentException(
                "DerivedFieldQuery error: unable to fetch fields from _source field: _source is disabled in the mappings "
                    + "for index ["
                    + context.index().getName()
                    + "]"
            );
        }
    }

    @Override
    public void visit(QueryVisitor visitor) {

    }

    @Override
    public Query rewrite(IndexSearcher indexSearcher) throws IOException {
        Query rewritten = indexSearcher.rewrite(filter);
        if (rewritten == filter) {
            return this;
        }
        return new DerivedFieldQuery(rewritten, derivedFieldScriptFactory, indexAnalyzer, fieldFunction, context);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        return new ConstantScoreWeight(this, boost) {
            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                DocIdSetIterator approximation = DocIdSetIterator.all(context.reader().maxDoc());
                DerivedFieldScript derivedFieldScript = derivedFieldScriptFactory.newInstance(context);
                TwoPhaseIterator twoPhase = new TwoPhaseIterator(approximation) {
                    @Override
                    public boolean matches() {
                        derivedFieldScript.setDocument(approximation.docID());
                        Object value = derivedFieldScript.execute();
                        // TODO: in case of errors from script, should it be ignored and treated as missing field
                        //  by using a configurable setting?
                        MemoryIndex memoryIndex = new MemoryIndex();
                        // TODO add support for multi-field with use of emit() once available
                        memoryIndex.addField(fieldFunction.apply(value), indexAnalyzer);
                        float score = memoryIndex.search(filter);
                        return score > 0.0f;
                    }

                    @Override
                    public float matchCost() {
                        // TODO: how can we compute this?
                        return 1000f;
                    }
                };
                return new ConstantScoreScorer(this, score(), scoreMode, twoPhase);
            }

            @Override
            public boolean isCacheable(LeafReaderContext ctx) {
                // TODO: Change this to true when we can assume that scripts are pure functions
                // ie. the return value is always the same given the same conditions and may not
                // depend on the current timestamp, other documents, etc.
                return false;
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (sameClassAs(o) == false) {
            return false;
        }
        DerivedFieldQuery other = (DerivedFieldQuery) o;
        return Objects.equals(this.filter, other.filter)
            && Objects.equals(this.derivedFieldScriptFactory, other.derivedFieldScriptFactory)
            && Objects.equals(this.fieldFunction, other.fieldFunction)
            && Objects.equals(this.indexAnalyzer, other.indexAnalyzer)
            && Objects.equals(this.context, other.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classHash(), filter, derivedFieldScriptFactory, fieldFunction, indexAnalyzer, context);
    }

    @Override
    public String toString(String f) {
        return "DerivedFieldQuery (filter query: [ " + filter.toString(f) + "])";
    }
}
