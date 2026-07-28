package io.papermc.paper.optimization.network;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Normal
class PlayWriteDispatchTest {
    @Test
    void lazyWritesRemainAheadOfTheNormalWakeupTask() throws Exception {
        final DefaultEventLoop eventLoop = new DefaultEventLoop();
        try {
            final List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            final CountDownLatch completed = new CountDownLatch(1);

            assertTrue(PlayWriteDispatch.execute(eventLoop, () -> order.add(1), true));
            assertTrue(PlayWriteDispatch.execute(eventLoop, () -> order.add(2), true));
            eventLoop.execute(() -> {
                order.add(3);
                completed.countDown();
            });

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3), order);
        } finally {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void disabledModeUsesNormalExecute() {
        final EventLoop eventLoop = mock(EventLoop.class);
        final Runnable task = () -> {
        };

        assertFalse(PlayWriteDispatch.execute(eventLoop, task, false));
        verify(eventLoop).execute(task);
    }

    @Test
    void unsupportedEventLoopFallsBackToNormalExecute() {
        final EventLoop eventLoop = mock(EventLoop.class);
        final Runnable task = () -> {
        };

        assertFalse(PlayWriteDispatch.execute(eventLoop, task, true));
        verify(eventLoop).execute(task);
    }
}
