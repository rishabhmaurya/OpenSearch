/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.be.lucene;

import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards the fix for the "single-sided numeric range returns empty" bug: the Lucene secondary must
 * declare {@code POINT_RANGE} for integral/date types so the composite engine grants it that
 * capability, {@code LuceneDocumentInput} builds the value-free BKD at index time, and the planner
 * can delegate numeric range predicates to it. Without this declaration the field is never indexed
 * on the secondary and a delegated range query matches nothing.
 */
public class LuceneDataFormatNumericCapabilityTests extends OpenSearchTestCase {

    public void testNumericAndDateTypesAdvertisePointRange() {
        LuceneDataFormat format = new LuceneDataFormat();
        Map<String, Set<FieldTypeCapabilities.Capability>> byType = format.supportedFields()
            .stream()
            .collect(Collectors.toMap(FieldTypeCapabilities::fieldType, FieldTypeCapabilities::capabilities));

        for (String numeric : new String[] { "byte", "short", "integer", "long", "date" }) {
            assertTrue("Lucene secondary must support type " + numeric, byType.containsKey(numeric));
            assertTrue(
                numeric + " must advertise POINT_RANGE on the Lucene secondary",
                byType.get(numeric).contains(FieldTypeCapabilities.Capability.POINT_RANGE)
            );
        }
    }

    /** Floating-point is intentionally NOT claimed yet (factory encodes integral/date as long). */
    public void testFloatingPointNotClaimedYet() {
        LuceneDataFormat format = new LuceneDataFormat();
        Set<String> types = format.supportedFields()
            .stream()
            .map(FieldTypeCapabilities::fieldType)
            .collect(Collectors.toSet());
        assertFalse("double should not be claimed yet", types.contains("double"));
        assertFalse("float should not be claimed yet", types.contains("float"));
    }
}
