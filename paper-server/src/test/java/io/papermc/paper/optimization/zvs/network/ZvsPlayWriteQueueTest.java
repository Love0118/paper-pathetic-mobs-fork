package io.papermc.paper.optimization.zvs.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
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
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(
            eventLoop, eventLoop::scheduleDelayed, eventLoop::inEventLoop, () -> writes.add("flush"),
            100, 25, ZvsPlayWriteQueue.Mode.SMART_EXECUTION, 25, 0
        );

        queue.enqueue(entry("one", false, 10, writes));
        queue.enqueue(entry("two", false, 10, writes));
        queue.enqueue(entry("three", false, 10, writes));
        queue.enqueue(entry("four", false, 10, writes));
        queue.requestTickDrain();
        eventLoop.runNext();

        assertEquals(List.of("one:false", "two:false", "three:false", "flush", "four:true"), writes);
    }

    @Test
    void rejectsPendingEntriesOnClose() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop, eventLoop::inEventLoop, 100, 1_000);
        queue.enqueue(new ZvsPlayWriteQueue.Entry((packet, flush) -> {
            writes.add("write");
            return 10L;
        }, () -> writes.add("reject"), false, 10));

        queue.close();

        assertEquals(List.of("reject"), writes);
    }

    @Test
    void reentrantEventLoopWriteStaysBehindTheWriteThatProducedIt() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(eventLoop, eventLoop::inEventLoop, 100, 10_000);

        queue.enqueue(new ZvsPlayWriteQueue.Entry((packet, flush) -> {
            writes.add("first:" + flush);
            queue.enqueue(entry("reentrant", false, 10, writes));
            return 10L;
        }, () -> writes.add("first:rejected"), false, 10));
        queue.requestTickDrain();
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

        assertEquals(List.of(), writes);
        queue.requestTickDrain();
        assertEquals(1, eventLoop.size());
        eventLoop.runNext();
        assertEquals(List.of("off-thread:false", "event-loop:true"), writes);
    }

    @Test
    void strictTickHoldsOrdinaryTrafficUntilTickDrain() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = configuredQueue(eventLoop, writes, ZvsPlayWriteQueue.Mode.STRICT_TICK, 25);

        queue.enqueue(entry("ordinary", false, 10, writes));
        assertEquals(0, eventLoop.size());

        queue.requestTickDrain();
        assertEquals(1, eventLoop.size());
        eventLoop.runNext();
        assertEquals(List.of("ordinary:true"), writes);
    }

    @Test
    void strictTickBarrierDrainsImmediatelyInFifoOrder() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = configuredQueue(eventLoop, writes, ZvsPlayWriteQueue.Mode.STRICT_TICK, 25);

        queue.enqueue(entry("ordinary", false, 10, writes));
        queue.enqueue(entry("barrier", true, 10, writes));
        assertEquals(1, eventLoop.size());
        eventLoop.runNext();

        assertEquals(List.of("ordinary:false", "barrier:true"), writes);
    }

    @Test
    void strictTickDoesNotUseTheSmartCountGuard() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(
            eventLoop, eventLoop::scheduleDelayed, eventLoop::inEventLoop, () -> writes.add("flush"),
            2, 10_000, ZvsPlayWriteQueue.Mode.STRICT_TICK, 25, 0
        );

        queue.enqueue(entry("one", false, 10, writes));
        queue.enqueue(entry("two", false, 10, writes));
        queue.enqueue(entry("three", false, 10, writes));
        assertEquals(0, eventLoop.size());

        queue.requestTickDrain();
        eventLoop.runNext();
        assertEquals(List.of("one:false", "two:true", "three:true"), writes);
    }

    @Test
    void intervalTrafficWaitsForTheConfiguredTimer() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<String> writes = new ArrayList<>();
        final ZvsPlayWriteQueue queue = configuredQueue(eventLoop, writes, ZvsPlayWriteQueue.Mode.INTERVAL, 37);

        queue.enqueue(entry("ordinary", false, 10, writes));
        queue.requestTickDrain();
        assertEquals(0, eventLoop.size());
        assertEquals(1, eventLoop.delayedSize());
        assertEquals(37, eventLoop.lastDelayMillis);

        eventLoop.runDelayed();
        assertEquals(0, eventLoop.size());
        assertEquals(List.of("ordinary:true"), writes);
    }

    @Test
    void invalidModeFallsBackToSmartExecution() {
        assertEquals(ZvsPlayWriteQueue.Mode.SMART_EXECUTION, ZvsPlayWriteQueue.parseMode("not-a-mode"));
        assertEquals(ZvsPlayWriteQueue.Mode.STRICT_TICK, ZvsPlayWriteQueue.parseMode("strict_tick"));
    }

    @Test
    void mergesDuplicateParticlePacketsBeforeEncoding() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<Packet<?>> packets = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(
            eventLoop, eventLoop::scheduleDelayed, eventLoop::inEventLoop, () -> {
            }, 100, 10_000, ZvsPlayWriteQueue.Mode.SMART_EXECUTION, 25, 100
        );
        final ClientboundLevelParticlesPacket first = particlePacket(3);
        final ClientboundLevelParticlesPacket second = particlePacket(4);

        queue.enqueue(effectEntry(first, packets));
        queue.enqueue(effectEntry(second, packets));
        queue.requestTickDrain();
        eventLoop.runNext();

        assertEquals(1, packets.size());
        assertEquals(7, ((ClientboundLevelParticlesPacket)packets.getFirst()).count());
    }

    private static ClientboundLevelParticlesPacket particlePacket(final int count) {
        return new ClientboundLevelParticlesPacket(
            ParticleTypes.FLAME, false, false, 1.0D, 2.0D, 3.0D, 0.1F, 0.2F, 0.3F, 0.05F, count
        );
    }

    @Test
    void batchesDirectedParticlesWithExactlyTheSameClientSpawnArguments() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<Packet<?>> packets = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(
            eventLoop, eventLoop::scheduleDelayed, eventLoop::inEventLoop, () -> {},
            100, 10_000, ZvsPlayWriteQueue.Mode.SMART_EXECUTION, 25, 100
        );
        final ClientboundLevelParticlesPacket directed = new ClientboundLevelParticlesPacket(
            ParticleTypes.FLAME, true, true, 1, 2, 3, -.3F, .7F, .2F, .05F, .1F, .15F,
            0, ClientboundLevelParticlesPacket.RandomizationType.DEFAULT
        );
        for (int i = 0; i < 12; i++) queue.enqueue(effectEntry(directed, packets));
        // Nearby effects and an ordinary randomized packet must not become part of this directed burst.
        queue.enqueue(effectEntry(particlePacket(3), packets));
        queue.requestTickDrain();
        eventLoop.runNext();
        assertEquals(2, packets.size());
        final ClientboundLevelParticlesPacket merged = (ClientboundLevelParticlesPacket)packets.getFirst();
        assertEquals(12, merged.count());
        assertEquals(ClientboundLevelParticlesPacket.RandomizationType.ALTERNATIVE, merged.randomizationType());
        assertEquals(directed.particle(), merged.particle());
        assertEquals(directed.overrideLimiter(), merged.overrideLimiter());
        assertEquals(directed.alwaysShow(), merged.alwaysShow());
        assertEquals(directed.x(), merged.x());
        assertEquals(directed.y(), merged.y());
        assertEquals(directed.z(), merged.z());
        assertEquals(0, merged.xDist());
        assertEquals(0, merged.yDist());
        assertEquals(0, merged.zDist());
        assertEquals(directed.xDist() * directed.xMaxSpeed(), merged.xMaxSpeed());
        assertEquals(directed.yDist() * directed.yMaxSpeed(), merged.yMaxSpeed());
        assertEquals(directed.zDist() * directed.zMaxSpeed(), merged.zMaxSpeed());
    }

    @Test
    void preservesAxisSpeedsAndRandomizationWhenCoalescing26_3Particles() {
        final TestEventLoop eventLoop = new TestEventLoop();
        final List<Packet<?>> packets = new ArrayList<>();
        final ZvsPlayWriteQueue queue = new ZvsPlayWriteQueue(
            eventLoop, eventLoop::scheduleDelayed, eventLoop::inEventLoop, () -> {},
            100, 10_000, ZvsPlayWriteQueue.Mode.SMART_EXECUTION, 25, 100
        );
        for (final ClientboundLevelParticlesPacket.RandomizationType mode : ClientboundLevelParticlesPacket.RandomizationType.values()) {
            for (int axis = 0; axis < 3; axis++) {
                final float x = axis == 0 ? 0.7F : 0.05F;
                final float y = axis == 1 ? 0.7F : 0.05F;
                final float z = axis == 2 ? 0.7F : 0.05F;
                for (int count : new int[] {3, 4}) {
                    queue.enqueue(effectEntry(new ClientboundLevelParticlesPacket(
                        ParticleTypes.FLAME, false, true, 1, 2, 3, .1F, .2F, .3F, x, y, z, count, mode
                    ), packets));
                }
            }
        }
        queue.requestTickDrain();
        eventLoop.runNext();
        assertEquals(9, packets.size(), "Different distribution modes and velocity axes must stay separate");
        for (int i = 0; i < packets.size(); i++) {
            final ClientboundLevelParticlesPacket packet = (ClientboundLevelParticlesPacket)packets.get(i);
            assertEquals(7, packet.count());
            assertEquals(i % 3 == 0 ? .7F : .05F, packet.xMaxSpeed());
            assertEquals(i % 3 == 1 ? .7F : .05F, packet.yMaxSpeed());
            assertEquals(i % 3 == 2 ? .7F : .05F, packet.zMaxSpeed());
            assertEquals(ClientboundLevelParticlesPacket.RandomizationType.values()[i / 3], packet.randomizationType());
        }
    }

    private static ZvsPlayWriteQueue.Entry effectEntry(final Packet<?> packet, final List<Packet<?>> packets) {
        return new ZvsPlayWriteQueue.Entry((written, flush) -> {
            packets.add(written);
            return 32L;
        }, () -> {
        }, false, 32, packet, true);
    }

    private static ZvsPlayWriteQueue configuredQueue(
        final TestEventLoop eventLoop,
        final List<String> writes,
        final ZvsPlayWriteQueue.Mode mode,
        final int intervalMillis
    ) {
        return new ZvsPlayWriteQueue(
            eventLoop, eventLoop::scheduleDelayed, eventLoop::inEventLoop, () -> writes.add("flush"),
            100, 10_000, mode, intervalMillis, 0
        );
    }

    private static ZvsPlayWriteQueue.Entry entry(final String name, final boolean barrier, final int bytes, final List<String> writes) {
        return new ZvsPlayWriteQueue.Entry((packet, flush) -> {
            writes.add(name + ":" + flush);
            return bytes;
        }, () -> writes.add(name + ":rejected"), barrier, bytes);
    }

    private static final class TestEventLoop implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final Queue<Runnable> delayedTasks = new ArrayDeque<>();
        private boolean inEventLoop;
        private int lastDelayMillis;

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

        int delayedSize() {
            return this.delayedTasks.size();
        }

        void scheduleDelayed(final Runnable command, final int delayMillis) {
            this.lastDelayMillis = delayMillis;
            this.delayedTasks.add(command);
        }

        void runNext() {
            this.runNow(this.tasks.remove());
        }

        void runDelayed() {
            this.runNow(this.delayedTasks.remove());
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
