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

package org.opensearch.index.query;

import org.opensearch.common.annotation.PublicApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Context used for script fields
 *
 * @opensearch.api
 */
@PublicApi(since = "2.13.0")
public class DerivedFieldsContext {

    /**
     * Script field use in the script fields context
     *
     * @opensearch.api
     */
    @PublicApi(since = "2.13.0")
    public static class DerivedField {
        private final String name;
        private final DerivedFieldScript.LeafFactory script;
        private final boolean ignoreException;

        public DerivedField(String name, DerivedFieldScript.LeafFactory script, boolean ignoreException) {
            this.name = name;
            this.script = script;
            this.ignoreException = ignoreException;
        }

        public String name() {
            return name;
        }

        public DerivedFieldScript.LeafFactory script() {
            return this.script;
        }

        public boolean ignoreException() {
            return ignoreException;
        }
    }

    private List<DerivedField> fields = new ArrayList<>();

    public DerivedFieldsContext() {}

    public void add(DerivedField field) {
        this.fields.add(field);
    }

    public List<DerivedField> fields() {
        return this.fields;
    }
}
