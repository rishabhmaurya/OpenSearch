/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.transport.grpc.stream;

import org.opensearch.test.OpenSearchTestCase;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ZeroCopyBufferChainTests extends OpenSearchTestCase {

    /**
     * Fails when a grpc-java upgrade renames the internals zero-copy writes depend on, instead of silently copying.
     */
    public void testGrpcInternalsAreAvailable() {
        assertTrue(ZeroCopyBufferChain.isAvailable());
    }

    public void testCompositeIsDirectSoNettyDoesNotCopyIt() {
        ByteBuffer body = ByteBuffer.allocateDirect(4).put(new byte[] { 5, 6, 7, 8 }).flip();
        List<ByteBuffer> segments = List.of(
            ByteBuffer.wrap(new byte[] { 1, 2 }),
            ByteBuffer.wrap(new byte[] { 3, 4 }),
            body,
            ByteBuffer.wrap(new byte[0]),
            ByteBuffer.wrap(new byte[] { 9 })
        );
        ZeroCopyBufferChain.ReleasingCompositeByteBuf buffer = new ZeroCopyBufferChain.ReleasingCompositeByteBuf(segments);
        try {
            assertTrue(buffer.isDirect());
            // The two leading heap segments share one copy; the direct body is wrapped, not copied.
            assertEquals(3, buffer.numComponents());
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }, bytes);
            assertEquals(0, body.position());
        } finally {
            AtomicBoolean released = new AtomicBoolean();
            buffer.arm(() -> released.set(true));
            buffer.release();
            assertTrue(released.get());
        }
    }

    public void testDeclinesStreamsOtherThanTheGrpcBufferChain() {
        AtomicBoolean released = new AtomicBoolean();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertFalse(ZeroCopyBufferChain.tryAppend(out, List.of(ByteBuffer.wrap(new byte[] { 1 })), () -> released.set(true)));
        assertEquals(0, out.size());
        assertFalse(released.get());
    }
}
