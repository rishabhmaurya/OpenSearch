/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
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
open module opensearch.common {
    exports org.opensearch.mod;
    exports org.opensearch.mod.common;
    exports org.opensearch.mod.common.breaker;
    exports org.opensearch.mod.common.breaker.fromindices.breaker;
    exports org.opensearch.mod.common.bytes;
    exports org.opensearch.mod.common.collect;
    exports org.opensearch.mod.common.component;
    exports org.opensearch.mod.common.compress;
    exports org.opensearch.mod.common.geo;
    exports org.opensearch.mod.common.hash;
    exports org.opensearch.mod.common.inject;
    exports org.opensearch.mod.common.inject.assistedinject;
    exports org.opensearch.mod.common.inject.binder;
    exports org.opensearch.mod.common.inject.internal;
    exports org.opensearch.mod.common.inject.matcher;
    exports org.opensearch.mod.common.inject.multibindings;
    exports org.opensearch.mod.common.inject.name;
    exports org.opensearch.mod.common.inject.spi;
    exports org.opensearch.mod.common.inject.util;

    exports org.opensearch.mod.common.io;
    exports org.opensearch.mod.common.io.stream;
    exports org.opensearch.mod.common.lease;
    exports org.opensearch.mod.common.logging;
    exports org.opensearch.mod.common.recycler;
    exports org.opensearch.mod.common.regex;
    exports org.opensearch.mod.common.settings;
    exports org.opensearch.mod.common.text;
    exports org.opensearch.mod.common.time;
    exports org.opensearch.mod.common.unit;
    exports org.opensearch.mod.common.util;
    exports org.opensearch.mod.common.util.concurrent;
    exports org.opensearch.mod.common.util.iterable;
    exports org.opensearch.mod.common.util.set;
    exports org.opensearch.mod.common.xcontent;

    exports org.opensearch.mod.monitor.jvm;
    exports org.opensearch.mod.node;
    exports org.opensearch.mod.rest;

    // at least one automatic module needs to be required to make all automatic modules visible
    //requires ALL-SYSTEM;
    //requires opensearch.core;
    requires opensearch.cli;
    requires opensearch.geo;

    //requires opensearch.x.content;
}


