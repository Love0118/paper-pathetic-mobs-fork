package io.papermc.paper.optimization.mud;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Normal
class MudRetainedFrameTest {
    @Test void fragmentedAndCoalescedFramesRetainValidPayloadsAfterInputRelease() {
        final EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder(null, true));
        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {3, 11})));
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {12, 13, 1, 42})));
        final ByteBuf first = channel.readInbound();
        final ByteBuf second = channel.readInbound();
        assertArrayEquals(new byte[] {11, 12, 13}, bytes(first));
        assertArrayEquals(new byte[] {42}, bytes(second));
        channel.finishAndReleaseAll();
        assertEquals(11, first.getByte(first.readerIndex()));
        first.release(); second.release();
    }

    @Test void compressedAndUncompressedRoundTripsMatchWithBothOptions() {
        for (final boolean enabled : new boolean[] {false, true}) {
            for (final int size : new int[] {1, 255, 256, 4096, 16384}) {
                final byte[] data = new byte[size];
                new java.util.Random(size).nextBytes(data);
                final EmbeddedChannel output = new EmbeddedChannel(new Varint21LengthFieldPrepender(enabled), new CompressionEncoder(null, 256, enabled));
                output.writeOutbound(Unpooled.wrappedBuffer(data));
                final ByteBuf encoded = output.readOutbound();
                final EmbeddedChannel input = new EmbeddedChannel(new Varint21FrameDecoder(null, enabled), new CompressionDecoder(null, 256, true, enabled));
                assertTrue(input.writeInbound(encoded));
                final ByteBuf decoded = input.readInbound();
                assertArrayEquals(data, bytes(decoded)); decoded.release();
                input.finishAndReleaseAll(); output.finishAndReleaseAll();
            }
        }
    }

    private static byte[] bytes(final ByteBuf buffer) {
        final byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }
}
