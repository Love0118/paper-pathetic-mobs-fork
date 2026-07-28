package io.papermc.paper.optimization.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21FrameDecoder;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Normal
class NetworkZeroCopyDecodingTest {
    @Test
    void frameDecoderRetainsHeapDirectAndCompositeSlices() {
        final List<Supplier<ByteBuf>> inputs = List.of(
            () -> Unpooled.buffer(16),
            () -> Unpooled.directBuffer(16),
            () -> {
                final CompositeByteBuf composite = Unpooled.compositeBuffer();
                composite.addComponents(true, Unpooled.buffer(1), Unpooled.buffer(15));
                return composite;
            }
        );

        for (final Supplier<ByteBuf> supplier : inputs) {
            final ByteBuf input = supplier.get();
            VarInt.write(input, 4);
            input.writeBytes(new byte[] {10, 20, 30, 40});
            final int payloadIndex = input.readerIndex() + 1;
            final ByteBuf observer = input.retainedDuplicate();
            final ChannelHandlerContext context = activeContext();
            final ExposedFrameDecoder decoder = new ExposedFrameDecoder(true);
            final List<Object> output = new ArrayList<>();

            decoder.decodeFrame(context, input, output);
            assertEquals(1, output.size());
            final ByteBuf decoded = (ByteBuf)output.getFirst();
            decoded.setByte(0, 99);
            assertEquals(99, observer.getUnsignedByte(payloadIndex));
            assertEquals(4, decoded.readableBytes());

            decoded.release();
            observer.release();
            input.release();
            decoder.releaseHelper(context);
        }
    }

    @Test
    void disabledFrameOptimizationKeepsTheCopyPath() {
        final ByteBuf input = Unpooled.buffer();
        VarInt.write(input, 2);
        input.writeBytes(new byte[] {11, 12});
        final ByteBuf observer = input.retainedDuplicate();
        final ChannelHandlerContext context = activeContext();
        final ExposedFrameDecoder decoder = new ExposedFrameDecoder(false);
        final List<Object> output = new ArrayList<>();

        decoder.decodeFrame(context, input, output);
        final ByteBuf decoded = (ByteBuf)output.getFirst();
        decoded.setByte(0, 77);
        assertEquals(11, observer.getUnsignedByte(1));

        decoded.release();
        observer.release();
        input.release();
        decoder.releaseHelper(context);
    }

    @Test
    void frameDecoderHandlesMultipleVarIntBoundaries() {
        final int[] lengths = {1, 127, 128, 16_383, 16_384};
        final ByteBuf input = Unpooled.buffer();
        for (final int length : lengths) {
            VarInt.write(input, length);
            input.writeZero(length);
        }

        final EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder(null, true));
        assertTrue(channel.writeInbound(input));
        for (final int length : lengths) {
            final ByteBuf decoded = channel.readInbound();
            assertEquals(length, decoded.readableBytes());
            decoded.release();
        }
        channel.finishAndReleaseAll();
    }

    @Test
    void fragmentedAndBackToBackFramesSurviveCumulationOwnership() {
        final EmbeddedChannel channel = new EmbeddedChannel(new Varint21FrameDecoder(null, true));
        final ByteBuf firstFragment = Unpooled.buffer();
        VarInt.write(firstFragment, 4);
        firstFragment.writeBytes(new byte[] {1, 2});

        assertFalse(channel.writeInbound(firstFragment));

        final ByteBuf secondFragment = Unpooled.buffer();
        secondFragment.writeBytes(new byte[] {3, 4});
        VarInt.write(secondFragment, 3);
        secondFragment.writeBytes(new byte[] {5, 6, 7});
        assertTrue(channel.writeInbound(secondFragment));

        final ByteBuf firstFrame = channel.readInbound();
        final ByteBuf secondFrame = channel.readInbound();
        assertEquals(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4}), firstFrame);
        assertEquals(Unpooled.wrappedBuffer(new byte[] {5, 6, 7}), secondFrame);
        assertEquals(2, firstFrame.refCnt());
        assertEquals(2, secondFrame.refCnt());

        firstFrame.release();
        assertEquals(1, secondFrame.refCnt());
        secondFrame.release();
        assertEquals(0, secondFrame.refCnt());
        channel.finishAndReleaseAll();
    }

    @Test
    void uncompressedCompressionPayloadUsesRetainedSlice() {
        final ByteBuf input = Unpooled.directBuffer();
        VarInt.write(input, 0);
        input.writeBytes(new byte[] {1, 2, 3, 4});
        final int payloadIndex = input.readerIndex() + 1;
        final ByteBuf observer = input.retainedDuplicate();
        final EmbeddedChannel channel = new EmbeddedChannel(new CompressionDecoder(null, 256, true, true));

        assertTrue(channel.writeInbound(input));
        final ByteBuf decoded = channel.readInbound();
        decoded.setByte(0, 88);
        assertEquals(88, observer.getUnsignedByte(payloadIndex));

        decoded.release();
        observer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void compressedPayloadStillRoundTrips() {
        final byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte)(i * 31);
        }

        final EmbeddedChannel encoder = new EmbeddedChannel(new CompressionEncoder(null, 1, false));
        assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(payload)));
        final ByteBuf encoded = encoder.readOutbound();
        final EmbeddedChannel decoder = new EmbeddedChannel(new CompressionDecoder(null, 1, true, true));
        assertTrue(decoder.writeInbound(encoded));
        final ByteBuf decoded = decoder.readInbound();

        assertEquals(Unpooled.wrappedBuffer(payload), decoded);
        decoded.release();
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    private static ChannelHandlerContext activeContext() {
        final Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        final ChannelHandlerContext context = mock(ChannelHandlerContext.class);
        when(context.channel()).thenReturn(channel);
        return context;
    }

    private static final class ExposedFrameDecoder extends Varint21FrameDecoder {
        private ExposedFrameDecoder(final boolean zeroCopyFrames) {
            super(null, zeroCopyFrames);
        }

        private void decodeFrame(final ChannelHandlerContext context, final ByteBuf input, final List<Object> output) {
            super.decode(context, input, output);
        }

        private void releaseHelper(final ChannelHandlerContext context) {
            super.handlerRemoved0(context);
        }
    }
}
