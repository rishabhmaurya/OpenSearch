/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.stream.join;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.fetch.subphase.FieldAndFormat;

import java.io.IOException;
import java.util.List;

/**
 * Represents a join condition in a search query.
 */
@ExperimentalApi
public class Join {
    private String index;
    private List<FieldAndFormat> fields;
    private JoinCondition condition;
    private QueryBuilder query;
    private String type = "inner";
    private String algorithm;

    public String getIndex() {
        return index;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public List<FieldAndFormat> getFields() {
        return fields;
    }

    public void setFields(List<FieldAndFormat> fields) {
        this.fields = fields;
    }

    public JoinCondition getCondition() {
        return condition;
    }

    public void setCondition(JoinCondition condition) {
        this.condition = condition;
    }

    public QueryBuilder getQuery() {
        return query;
    }

    public void setQuery(QueryBuilder query) {
        this.query = query;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    void parseCondition(XContentParser parser) throws IOException {
        JoinCondition condition = new JoinCondition();
        String currentFieldName = null;
        XContentParser.Token token;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                switch (currentFieldName) {
                    case "left_field":
                        condition.setLeftField(parser.text());
                        break;
                    case "right_field":
                        condition.setRightField(parser.text());
                        break;
                    case "comparator":
                        condition.setComparator(parser.text());
                        break;
                }
            }
        }

        setCondition(condition);
    }
}
