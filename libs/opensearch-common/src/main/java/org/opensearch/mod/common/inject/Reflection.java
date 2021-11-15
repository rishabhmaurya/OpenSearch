/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.mod.common.inject;

import java.lang.reflect.Constructor;

/**
 * Abstraction for Java's reflection APIs. This interface exists to provide a single place where
 * runtime reflection can be substituted for another mechanism such as CGLib or compile-time code
 * generation.
 *
 * @author jessewilson@google.com (Jesse Wilson)
 */
class Reflection {

    /**
     * A placeholder. This enables us to continue processing and gather more
     * errors but blows up if you actually try to use it.
     */
    static class InvalidConstructor {
        InvalidConstructor() {
            throw new AssertionError();
        }
    }

    @SuppressWarnings("unchecked")
    static <T> Constructor<T> invalidConstructor() {
        try {
            return (Constructor<T>) InvalidConstructor.class.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
