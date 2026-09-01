package io.papermc.paper.optimization.zvs.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Normal
class ZvsPlayWriteQueueTest {
    @Test
    void preservesOrderAndUsesOneScheduledTaskForBurst() {
        final Queue<Runnable> eventLoop = new ArrayDeque<>();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop::add, () -> true, 1_024, 32_768);

        queue.enqueue(entry("one", false, 10, writes));
        queue.enqueue(entry("two", false, 10, writes));
        queue.enqueue(entry("critical", true, 10, writes));
        queue.enqueue(entry("three", false, 10, writes));

        assertEquals(1, eventLoop.size());
        eventLoop.remove().run();
        assertEquals(List.of("one:false", "two:false", "critical:true", "three:true"), writes);
    }

    @Test
    void flushesAtPacketAndByteLimits() {
        final Queue<Runnable> eventLoop = new ArrayDeque<>();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop::add, () -> true, 2, 25);

        queue.enqueue(entry("one", false, 10, writes));
        queue.enqueue(entry("two", false, 10, writes));
        queue.enqueue(entry("three", false, 30, writes));
        eventLoop.remove().run();

        assertEquals(List.of("one:false", "two:true", "three:true"), writes);
    }

    @Test
    void rejectsPendingEntriesOnClose() {
        final Queue<Runnable> eventLoop = new ArrayDeque<>();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop::add, () -> true, 100, 1_000);
        queue.enqueue(new ZvsPlayWriteQueue.Entry(flush -> writes.add("write"), () -> writes.add("reject"), false, 10));

        queue.close();
        eventLoop.remove().run();

        assertEquals(List.of("reject"), writes);
    }

    @Test
    void bundlesOnlyConsecutiveCandidatesAndKeepsListenerOrder() {
        final Queue<Runnable> eventLoop = new ArrayDeque<>();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(
            eventLoop::add,
            () -> true,
            100,
            10_000,
            4_000,
            (entries, flush) -> {
                writes.add("bundle:" + flush);
                entries.forEach(entry -> writes.add((String)entry.payload()));
            }
        );

        queue.enqueue(bundleEntry("particle", writes));
        queue.enqueue(bundleEntry("sound", writes));
        queue.enqueue(entry("state", false, 10, writes));
        queue.enqueue(bundleEntry("particle-after-state", writes));
        eventLoop.remove().run();

        assertEquals(List.of("bundle:false", "particle", "sound", "state:false", "particle-after-state:true"), writes);
    }

    private static ZvsPlayWriteQueue.Entry entry(final String name, final boolean barrier, final int bytes, final List<String> writes) {
        return new ZvsPlayWriteQueue.Entry(flush -> writes.add(name + ":" + flush), () -> writes.add(name + ":rejected"), barrier, bytes);
    }

    private static ZvsPlayWriteQueue.Entry bundleEntry(final String name, final List<String> writes) {
        return new ZvsPlayWriteQueue.Entry(
            flush -> writes.add(name + ":" + flush), () -> writes.add(name + ":rejected"), false, 10, name, true
        );
    }
}
