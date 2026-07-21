package io.papermc.paper.optimization.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Arrays;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class NetworkFramePrefixTest {
    @Test
    void exclusiveRootBufferUsesReservedHeadroomAtVarIntBoundaries() {
        for (final int length : new int[] {1, 127, 128, 16_383, 16_384}) {
            final ByteBuf body = Unpooled.buffer(length + 3);
            body.setIndex(3, 3);
            body.writeZero(length);
            final EmbeddedChannel channel = new EmbeddedChannel(new Varint21LengthFieldPrepender(true));

            assertTrue(channel.writeOutbound(body));
            final ByteBuf framed = channel.readOutbound();
            assertSame(body, framed);
            assertEquals(length, VarInt.read(framed.duplicate()));
            assertEquals(length + VarInt.getByteSize(length), framed.readableBytes());

            framed.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void missingHeadroomUsesCopyFallback() {
        final ByteBuf body = Unpooled.wrappedBuffer(new byte[] {4, 5, 6});
        final EmbeddedChannel channel = new EmbeddedChannel(new Varint21LengthFieldPrepender(true));

        assertTrue(channel.writeOutbound(body));
        final ByteBuf framed = channel.readOutbound();
        assertNotSame(body, framed);
        assertEquals(3, VarInt.read(framed));
        final byte[] payload = new byte[3];
        framed.readBytes(payload);
        assertArrayEquals(new byte[] {4, 5, 6}, payload);

        framed.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void singleComponentCompositeWithRetainedObserverUsesCopyFallback() {
        final byte[] prefix = {91, 92, 93};
        final byte[] payload = {4, 5, 6};
        final ByteBuf component = Unpooled.buffer(prefix.length + payload.length);
        component.writeBytes(prefix);
        component.writeBytes(payload);
        final ByteBuf observer = component.retainedDuplicate();
        final CompositeByteBuf body = Unpooled.compositeBuffer(1);
        body.addComponent(true, component);
        body.readerIndex(prefix.length);
        final EmbeddedChannel channel = new EmbeddedChannel(new Varint21LengthFieldPrepender(true));

        assertTrue(channel.writeOutbound(body));
        final ByteBuf framed = channel.readOutbound();
        assertNotSame(body, framed);
        final byte[] observedPrefix = new byte[prefix.length];
        observer.getBytes(0, observedPrefix);
        assertArrayEquals(prefix, observedPrefix);
        assertEquals(payload.length, VarInt.read(framed));
        final byte[] decodedPayload = new byte[payload.length];
        framed.readBytes(decodedPayload);
        assertArrayEquals(payload, decodedPayload);

        framed.release();
        observer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void compressionOutputCanBePrefixedWithoutAnotherBodyCopy() {
        final byte[] payload = new byte[64];
        Arrays.fill(payload, (byte)7);
        final EmbeddedChannel channel = new EmbeddedChannel(
            new Varint21LengthFieldPrepender(true),
            new CompressionEncoder(null, 256, true)
        );

        assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(payload)));
        final ByteBuf framed = channel.readOutbound();
        final int compressedEnvelopeLength = VarInt.read(framed);
        assertEquals(framed.readableBytes(), compressedEnvelopeLength);
        assertEquals(0, VarInt.read(framed));
        final byte[] decodedPayload = new byte[payload.length];
        framed.readBytes(decodedPayload);
        assertArrayEquals(payload, decodedPayload);

        framed.release();
        channel.finishAndReleaseAll();
    }
}
