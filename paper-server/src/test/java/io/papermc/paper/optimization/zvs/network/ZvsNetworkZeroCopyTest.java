package io.papermc.paper.optimization.zvs.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21FrameDecoder;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ZvsNetworkZeroCopyTest {
    @Test
    void completeFrameSharesStorageOnlyWhenEnabled() {
        assertFrameStorageSharing(true, 77);
        assertFrameStorageSharing(false, 11);
    }

    @Test
    void compressionPassthroughRetainsPayloadStorage() {
        final ByteBuf encoded = Unpooled.directBuffer();
        VarInt.write(encoded, 0);
        encoded.writeBytes(new byte[] {1, 2, 3});
        final ByteBuf observer = encoded.retainedDuplicate();
        final EmbeddedChannel channel = new EmbeddedChannel(new CompressionDecoder(null, 256, true, true));

        assertTrue(channel.writeInbound(encoded));
        final ByteBuf decoded = channel.readInbound();
        decoded.setByte(0, 99);
        assertEquals(99, observer.getUnsignedByte(1));
        decoded.release();
        observer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void compressedPacketsStillRoundTrip() {
        final byte[] payload = new byte[4_096];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte)(index * 17);
        }
        final EmbeddedChannel encoder = new EmbeddedChannel(new CompressionEncoder(null, 1, false));
        assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(payload)));
        final ByteBuf compressed = encoder.readOutbound();
        final EmbeddedChannel decoder = new EmbeddedChannel(new CompressionDecoder(null, 1, true, true));
        assertTrue(decoder.writeInbound(compressed));
        final ByteBuf decoded = decoder.readInbound();
        assertEquals(Unpooled.wrappedBuffer(payload), decoded);
        decoded.release();
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    private static void assertFrameStorageSharing(final boolean zeroCopy, final int expectedObserverValue) {
        final ByteBuf frame = Unpooled.buffer();
        VarInt.write(frame, 2);
        frame.writeBytes(new byte[] {11, 12});
        final ByteBuf observer = frame.retainedDuplicate();
        final EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder(null, zeroCopy));

        assertTrue(channel.writeInbound(frame));
        final ByteBuf decoded = channel.readInbound();
        decoded.setByte(0, 77);
        assertEquals(expectedObserverValue, observer.getUnsignedByte(1));
        decoded.release();
        observer.release();
        channel.finishAndReleaseAll();
    }
}
