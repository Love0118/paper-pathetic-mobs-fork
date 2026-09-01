package io.papermc.paper.optimization.zvs.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Normal
class ZvsPlayWriteQueueTest {
    @Test
    void preservesOrderAndUsesOneScheduledTaskForBurst() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop, eventLoop::inEventLoop, 1_024, 32_768);

        queue.enqueue(entry("one", false, 10, writes));
        queue.enqueue(entry("two", false, 10, writes));
        queue.enqueue(entry("critical", true, 10, writes));
        queue.enqueue(entry("three", false, 10, writes));

        assertEquals(1, eventLoop.size());
        eventLoop.runNext();
        assertEquals(List.of("one:false", "two:false", "critical:true", "three:true"), writes);
    }

    @Test
    void flushesAtPacketAndByteLimits() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop, eventLoop::inEventLoop, 2, 25);

        queue.enqueue(entry("one", false, 10, writes));
        queue.enqueue(entry("two", false, 10, writes));
        queue.enqueue(entry("three", false, 30, writes));
        eventLoop.runNext();

        assertEquals(List.of("one:false", "two:true", "three:true"), writes);
    }

    @Test
    void rejectsPendingEntriesOnClose() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop, eventLoop::inEventLoop, 100, 1_000);
        queue.enqueue(new ZvsPlayWriteQueue.Entry(flush -> writes.add("write"), () -> writes.add("reject"), false, 10));

        queue.close();
        eventLoop.runNext();

        assertEquals(List.of("reject"), writes);
    }

    @Test
    void reentrantEventLoopWriteStaysBehindTheWriteThatProducedIt() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop, eventLoop::inEventLoop, 100, 10_000);

        queue.enqueue(new ZvsPlayWriteQueue.Entry(flush -> {
            writes.add("first:" + flush);
            queue.enqueue(entry("reentrant", false, 10, writes));
        }, () -> writes.add("first:rejected"), false, 10));
        eventLoop.runNext();

        assertEquals(List.of("first:true", "reentrant:true"), writes);
    }

    @Test
    void eventLoopWriteCannotOvertakeAnAlreadyQueuedWrite() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop, eventLoop::inEventLoop, 1_024, 32_768);

        queue.enqueue(entry("off-thread", false, 10, writes));
        eventLoop.runNow(() -> queue.enqueue(entry("event-loop", false, 10, writes)));

        assertEquals(List.of("off-thread:false", "event-loop:true"), writes);
        assertEquals(1, eventLoop.size());
        eventLoop.runNext();
        assertEquals(List.of("off-thread:false", "event-loop:true"), writes);
    }

    private static ZvsPlayWriteQueue.Entry entry(final String name, final boolean barrier, final int bytes, final List<String> writes) {
        return new ZvsPlayWriteQueue.Entry(flush -> writes.add(name + ":" + flush), () -> writes.add(name + ":rejected"), barrier, bytes);
    }

    private static final class TestEventLoop implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean inEventLoop;

        @Override
        public void execute(final Runnable command) {
            this.tasks.add(command);
        }

        boolean inEventLoop() {
            return this.inEventLoop;
        }

        int size() {
            return this.tasks.size();
        }

        void runNext() {
            this.runNow(this.tasks.remove());
        }

        void runNow(final Runnable command) {
            this.inEventLoop = true;
            try {
                command.run();
            } finally {
                this.inEventLoop = false;
            }
        }
    }
}
