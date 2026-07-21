package io.papermc.paper.optimization.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.flush.FlushConsolidationHandler;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Normal
class PlayFlushConsolidationTest {
    @Test
    void outsideReadFlushesAreDeferredWithoutReorderingWritesOrListeners() throws Exception {
        final FlushCounter counter = new FlushCounter();
        final EmbeddedChannel channel = channel(counter, true);
        final List<Integer> completed = new ArrayList<>();
        final FlushConsolidationHandler consolidator = channel.pipeline().get(FlushConsolidationHandler.class);
        final ChannelHandlerContext context = channel.pipeline().context(consolidator);

        for (int i = 0; i < 10; i++) {
            final int packet = i;
            final ChannelPromise promise = channel.newPromise();
            promise.addListener(ignored -> completed.add(packet));
            context.write(packet, promise);
        }
        for (int i = 0; i < 10; i++) {
            consolidator.flush(context);
        }

        assertEquals(0, counter.flushes);
        channel.runPendingTasks();
        assertEquals(1, counter.flushes);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), completed);
        for (int i = 0; i < 10; i++) {
            assertEquals(Integer.valueOf(i), channel.<Integer>readOutbound());
        }
        channel.finishAndReleaseAll();
    }

    @Test
    void upstreamModeFlushesImmediatelyOutsideReads() {
        final FlushCounter counter = new FlushCounter();
        final EmbeddedChannel channel = channel(counter, false);

        channel.pipeline().writeAndFlush(1);

        assertEquals(1, counter.flushes);
        assertEquals(Integer.valueOf(1), channel.<Integer>readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void replacingTheHandlerFlushesPendingWrites() {
        final FlushCounter counter = new FlushCounter();
        final EmbeddedChannel channel = channel(counter, true);

        channel.pipeline().writeAndFlush(1);
        assertEquals(0, counter.flushes);
        channel.pipeline().replace("consolidator", "consolidator", new FlushConsolidationHandler());

        assertEquals(1, counter.flushes);
        assertEquals(Integer.valueOf(1), channel.<Integer>readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void explicitFlushThresholdCannotDeferForever() throws Exception {
        final FlushCounter counter = new FlushCounter();
        final EmbeddedChannel channel = channel(counter, true);
        final FlushConsolidationHandler consolidator = channel.pipeline().get(FlushConsolidationHandler.class);
        final ChannelHandlerContext context = channel.pipeline().context(consolidator);

        for (int i = 0; i < FlushConsolidationHandler.DEFAULT_EXPLICIT_FLUSH_AFTER_FLUSHES; i++) {
            consolidator.flush(context);
        }

        assertEquals(1, counter.flushes);
        channel.runPendingTasks();
        assertEquals(1, counter.flushes);
        channel.finishAndReleaseAll();
    }

    @Test
    void closeFlushesPendingWrites() {
        final FlushCounter counter = new FlushCounter();
        final EmbeddedChannel channel = channel(counter, true);

        channel.pipeline().writeAndFlush(1);
        assertEquals(0, counter.flushes);
        channel.close();

        assertEquals(1, counter.flushes);
        assertEquals(Integer.valueOf(1), channel.<Integer>readOutbound());
        channel.finishAndReleaseAll();
    }

    private static EmbeddedChannel channel(final FlushCounter counter, final boolean consolidateOutsideReads) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("counter", counter);
        channel.pipeline().addLast(
            "consolidator",
            new FlushConsolidationHandler(FlushConsolidationHandler.DEFAULT_EXPLICIT_FLUSH_AFTER_FLUSHES, consolidateOutsideReads)
        );
        return channel;
    }

    private static final class FlushCounter extends ChannelOutboundHandlerAdapter {
        private int flushes;

        @Override
        public void flush(final ChannelHandlerContext context) {
            this.flushes++;
            context.flush();
        }
    }
}
