package io.papermc.paper.optimization.zvs.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
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
class ZvsNetworkFramePrefixTest {
    @Test
    void prefixesExclusiveReservedBuffersInPlaceAcrossVarIntBoundaries() {
        for (final int length : new int[] {1, 127, 128, 16_383, 16_384}) {
            final ByteBuf payload = Unpooled.buffer(length + 3).setIndex(3, 3).writeZero(length);
            final EmbeddedChannel channel = new EmbeddedChannel(new Varint21LengthFieldPrepender(true));

            assertTrue(channel.writeOutbound(payload));
            final ByteBuf framed = channel.readOutbound();
            assertSame(payload, framed);
            assertEquals(length, VarInt.read(framed.duplicate()));
            assertEquals(length + VarInt.getByteSize(length), framed.readableBytes());
            framed.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void copiesSharedCompositeAndHeadroomlessBuffers() {
        final CompositeByteBuf composite = Unpooled.compositeBuffer();
        composite.addComponents(true, Unpooled.wrappedBuffer(new byte[] {1}), Unpooled.wrappedBuffer(new byte[] {2, 3}));
        final ByteBuf observer = composite.retainedDuplicate();
        final EmbeddedChannel channel = new EmbeddedChannel(new Varint21LengthFieldPrepender(true));

        assertTrue(channel.writeOutbound(composite));
        final ByteBuf framed = channel.readOutbound();
        assertNotSame(composite, framed);
        assertEquals(3, VarInt.read(framed));
        final byte[] data = new byte[3];
        framed.readBytes(data);
        assertArrayEquals(new byte[] {1, 2, 3}, data);
        assertArrayEquals(new byte[] {1, 2, 3}, bytes(observer));

        framed.release();
        observer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void compressionEncoderReservesUsablePrefixSpace() {
        final byte[] data = new byte[64];
        java.util.Arrays.fill(data, (byte)9);
        final EmbeddedChannel channel = new EmbeddedChannel(
            new Varint21LengthFieldPrepender(true),
            new CompressionEncoder(null, 256, true)
        );

        assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(data)));
        final ByteBuf framed = channel.readOutbound();
        final int envelopeLength = VarInt.read(framed);
        assertEquals(framed.readableBytes(), envelopeLength);
        assertEquals(0, VarInt.read(framed));
        assertArrayEquals(data, bytes(framed));
        framed.release();
        channel.finishAndReleaseAll();
    }

    private static byte[] bytes(final ByteBuf buffer) {
        final byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }
}
