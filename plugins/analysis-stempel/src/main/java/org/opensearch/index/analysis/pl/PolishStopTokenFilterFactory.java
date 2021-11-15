/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.analysis.pl;


import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.pl.PolishAnalyzer;
import org.apache.lucene.search.suggest.analyzing.SuggestStopFilter;
import org.opensearch.mod.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AbstractTokenFilterFactory;
import org.opensearch.index.analysis.Analysis;

import java.util.Map;
import java.util.Set;

import static java.util.Collections.singletonMap;

public class PolishStopTokenFilterFactory extends AbstractTokenFilterFactory {
    private static final Map<String, Set<?>> NAMED_STOP_WORDS = singletonMap("_polish_", PolishAnalyzer.getDefaultStopSet());

    private final CharArraySet stopWords;

    private final boolean ignoreCase;

    private final boolean removeTrailing;

    public PolishStopTokenFilterFactory(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(indexSettings, name, settings);
        this.ignoreCase = settings.getAsBoolean("ignore_case", false);
        this.removeTrailing = settings.getAsBoolean("remove_trailing", true);
        this.stopWords = Analysis.parseWords(env, settings, "stopwords",
                PolishAnalyzer.getDefaultStopSet(), NAMED_STOP_WORDS, ignoreCase);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        if (removeTrailing) {
            return new StopFilter(tokenStream, stopWords);
        } else {
            return new SuggestStopFilter(tokenStream, stopWords);
        }
    }

    public Set<?> stopWords() {
        return stopWords;
    }

    public boolean ignoreCase() {
        return ignoreCase;
    }

}
