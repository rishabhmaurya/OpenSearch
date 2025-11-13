/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.flight.transport;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class VectorTransportResponseTest {
    
    private BufferAllocator allocator;
    private VectorSchemaRoot testRoot;
    
    @Before
    public void setUp() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        
        // Create a test VectorSchemaRoot with some data
        Field field = new Field("test_field", new FieldType(false, new ArrowType.Int(32, true), null), null);
        Schema schema = new Schema(Arrays.asList(field));
        testRoot = VectorSchemaRoot.create(schema, allocator);
        
        IntVector vector = (IntVector) testRoot.getVector("test_field");
        vector.allocateNew(3);
        vector.set(0, 100);
        vector.set(1, 200);
        vector.set(2, 300);
        vector.setValueCount(3);
        testRoot.setRowCount(3);
    }
    
    @After
    public void tearDown() {
        if (testRoot != null) {
            testRoot.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }
    
    @Test
    public void testVectorTransportResponseCreation() {
        VectorTransportResponse response = new VectorTransportResponse(testRoot);
        
        assertNotNull(response);
        assertEquals(testRoot, response.getVectorSchemaRoot());
        assertEquals(3, response.getVectorSchemaRoot().getRowCount());
    }
    
    @Test
    public void testVectorTransportResponseWriteToThrowsException() {
        VectorTransportResponse response = new VectorTransportResponse(testRoot);
        
        try {
            response.writeTo(null);
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("should not be serialized"));
        } catch (Exception e) {
            fail("Expected UnsupportedOperationException, got: " + e.getClass().getSimpleName());
        }
    }
    
    @Test
    public void testVectorTransportResponseStreamInputConstructorThrowsException() {
        try {
            new VectorTransportResponse((org.opensearch.core.common.io.stream.StreamInput) null);
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("should not be deserialized"));
        } catch (Exception e) {
            fail("Expected UnsupportedOperationException, got: " + e.getClass().getSimpleName());
        }
    }
    
    @Test
    public void testVectorTransportResponseHandlerMarkerInterface() {
        VectorTransportResponseHandlerExample handler = new VectorTransportResponseHandlerExample();
        
        assertTrue("Handler should implement VectorTransportResponseHandler", 
                  handler instanceof VectorTransportResponseHandler);
    }
    
    @Test
    public void testVectorTransportResponseHandlerReadThrowsException() {
        VectorTransportResponseHandlerExample handler = new VectorTransportResponseHandlerExample();
        
        try {
            handler.read(null);
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("should not use StreamInput"));
        } catch (Exception e) {
            fail("Expected UnsupportedOperationException, got: " + e.getClass().getSimpleName());
        }
    }
}