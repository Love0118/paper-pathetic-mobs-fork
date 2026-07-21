package io.papermc.paper.optimization.network;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.util.Objects;

/**
 * Schedules an outbound write without waking a Netty event loop when a later,
 * normally scheduled flush is guaranteed to provide the wakeup.
 */
public final class PlayWriteDispatch {
    private PlayWriteDispatch() {
    }

    /**
     * @return {@code true} when the task used Netty's no-wakeup path
     */
    public static boolean execute(final EventLoop eventLoop, final Runnable task, final boolean deferWakeup) {
        Objects.requireNonNull(eventLoop, "eventLoop");
        Objects.requireNonNull(task, "task");

        if (deferWakeup && eventLoop instanceof SingleThreadEventExecutor executor) {
            executor.lazyExecute(task);
            return true;
        }

        eventLoop.execute(task);
        return false;
    }
}
