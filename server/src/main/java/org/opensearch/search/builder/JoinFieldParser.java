/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.builder;

import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.search.fetch.subphase.FieldAndFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.opensearch.index.query.AbstractQueryBuilder.parseInnerQueryBuilder;

public class JoinFieldParser {

    public static Join parse(XContentParser parser) throws IOException {
        Join join = new Join();

        String currentFieldName = null;
        XContentParser.Token token;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                parseJoinField(join, currentFieldName, parser);
            } else if (token == XContentParser.Token.START_OBJECT) {
                if ("condition".equals(currentFieldName)) {
                    join.parseCondition(parser);
                } else if ("query".equals(currentFieldName)) {
                    join.setQuery(parseInnerQueryBuilder(parser));
                } else {
                    throw new IllegalArgumentException("Unexpected field in join: " + currentFieldName);
                }
            } else if (token == XContentParser.Token.START_ARRAY) {
                if ("fields".equals(currentFieldName)) {
                    List<FieldAndFormat> fetchFields = new ArrayList<>();
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        fetchFields.add(FieldAndFormat.fromXContent(parser));
                    }
                    join.setFields(fetchFields);
                } else {
                    parser.skipChildren();
                }
            }
        }

        return join;
    }

    private static void parseJoinField(Join join, String currentFieldName, XContentParser parser) throws IOException {
        switch (currentFieldName) {
            case "index":
                join.setIndex(parser.text());
                break;
            case "type":
                join.setType(parser.text());
                break;
            case "algorithm":
                join.setAlgorithm(parser.text());
                break;
        }
    }


}
