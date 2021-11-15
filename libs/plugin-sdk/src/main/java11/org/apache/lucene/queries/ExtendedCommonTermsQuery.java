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

package org.apache.lucene.queries;

import org.apache.lucene.search.BooleanClause.Occur;

import java.util.regex.Pattern;

/**
 * Extended version of {@link CommonTermsQuery} that allows to pass in a
 * {@code minimumNumberShouldMatch} specification that uses the actual num of high frequent terms
 * to calculate the minimum matching terms.
 *
 * @deprecated Since max_optimization optimization landed in 7.0, normal MatchQuery
 *             will achieve the same result without any configuration.
 */
@Deprecated
public class ExtendedCommonTermsQuery extends CommonTermsQuery {

    public ExtendedCommonTermsQuery(Occur highFreqOccur, Occur lowFreqOccur, float maxTermFrequency) {
        super(highFreqOccur, lowFreqOccur, maxTermFrequency);
    }

    private String lowFreqMinNumShouldMatchSpec;
    private String highFreqMinNumShouldMatchSpec;

    @Override
    protected int calcLowFreqMinimumNumberShouldMatch(int numOptional) {
        return calcMinimumNumberShouldMatch(lowFreqMinNumShouldMatchSpec, numOptional);
    }

    protected int calcMinimumNumberShouldMatch(String spec, int numOptional) {
        if (spec == null) {
            return 0;
        }
        return calculateMinShouldMatch(numOptional, spec); // #RF-2
    }
    // #RF-2
    private static Pattern spaceAroundLessThanPattern = Pattern.compile("(\\s+<\\s*)|(\\s*<\\s+)");
    private static Pattern spacePattern = Pattern.compile(" ");
    private static Pattern lessThanPattern = Pattern.compile("<");

    public static int calculateMinShouldMatch(int optionalClauseCount, String spec) {
        int result = optionalClauseCount;
        spec = spec.trim();

        if (-1 < spec.indexOf("<")) {
            /* we have conditional spec(s) */
            spec = spaceAroundLessThanPattern.matcher(spec).replaceAll("<");
            for (String s : spacePattern.split(spec)) {
                String[] parts = lessThanPattern.split(s, 0);
                int upperBound = Integer.parseInt(parts[0]);
                if (optionalClauseCount <= upperBound) {
                    return result;
                } else {
                    result = calculateMinShouldMatch
                        (optionalClauseCount, parts[1]);
                }
            }
            return result;
        }

        /* otherwise, simple expression */

        if (-1 < spec.indexOf('%')) {
            /* percentage - assume the % was the last char.  If not, let Integer.parseInt fail. */
            spec = spec.substring(0, spec.length() - 1);
            int percent = Integer.parseInt(spec);
            float calc = (result * percent) * (1 / 100f);
            result = calc < 0 ? result + (int) calc : (int) calc;
        } else {
            int calc = Integer.parseInt(spec);
            result = calc < 0 ? result + calc : calc;
        }

        return result < 0 ? 0 : result;
    }
    @Override
    protected int calcHighFreqMinimumNumberShouldMatch(int numOptional) {
        return calcMinimumNumberShouldMatch(highFreqMinNumShouldMatchSpec, numOptional);
    }

    public void setHighFreqMinimumNumberShouldMatch(String spec) {
        this.highFreqMinNumShouldMatchSpec = spec;
    }

    public String getHighFreqMinimumNumberShouldMatchSpec() {
        return highFreqMinNumShouldMatchSpec;
    }

    public void setLowFreqMinimumNumberShouldMatch(String spec) {
        this.lowFreqMinNumShouldMatchSpec = spec;
    }

    public String getLowFreqMinimumNumberShouldMatchSpec() {
        return lowFreqMinNumShouldMatchSpec;
    }

    public float getMaxTermFrequency() {
        return this.maxTermFrequency;
    }

}
