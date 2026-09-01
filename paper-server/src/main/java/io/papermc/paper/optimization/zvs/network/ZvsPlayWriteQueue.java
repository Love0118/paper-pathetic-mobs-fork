package io.papermc.paper.optimization.zvs.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.jspecify.annotations.Nullable;

/** FIFO PLAY write submission supporting smart, strict-tick, and interval draining. */
public final class ZvsPlayWriteQueue {
    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean taskScheduled = new AtomicBoolean();
    private final AtomicBoolean intervalScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger depth = new AtomicInteger();
    private final Executor executor;
    private final DelayedScheduler delayedScheduler;
    private final BooleanSupplier inEventLoop;
    private final Runnable flusher;
    private final int maxPacketsPerFlush;
    private final long maxBatchBytes;
    private final int maxCoalescedPackets;
    private final Mode mode;
    private final int intervalMillis;
    private boolean draining;

    public ZvsPlayWriteQueue(
        final Executor executor,
        final BooleanSupplier inEventLoop,
        final int maxPacketsPerFlush,
        final int maxEstimatedBytesPerFlush
    ) {
        this(executor, (task, ignoredDelay) -> executor.execute(task), inEventLoop, () -> {
        }, maxPacketsPerFlush, maxEstimatedBytesPerFlush, Mode.SMART_EXECUTION, 25, 0);
    }

    public ZvsPlayWriteQueue(
        final Executor executor,
        final DelayedScheduler delayedScheduler,
        final BooleanSupplier inEventLoop,
        final Runnable flusher,
        final int maxPacketsPerFlush,
        final long maxBatchBytes,
        final Mode mode,
        final int intervalMillis,
        final int maxCoalescedPackets
    ) {
        this.executor = executor;
        this.delayedScheduler = delayedScheduler;
        this.inEventLoop = inEventLoop;
        this.flusher = flusher;
        this.maxPacketsPerFlush = Math.max(1, maxPacketsPerFlush);
        this.maxBatchBytes = maxBatchBytes <= 0 ? Long.MAX_VALUE : maxBatchBytes;
        this.mode = mode;
        this.intervalMillis = Math.max(1, intervalMillis);
        this.maxCoalescedPackets = Math.max(0, maxCoalescedPackets);
    }

    public void enqueue(final Entry entry) {
        if (this.closed.get()) {
            entry.reject().run();
            return;
        }
        this.entries.add(entry);
        final int queueDepth = this.depth.incrementAndGet();
        final boolean first = this.active.compareAndSet(false, true);
        ZvsNetworkMetrics.logicalPacket(entry.estimatedBytes(), queueDepth);

        if (entry.barrier()) {
            this.requestImmediateDrain();
            return;
        }
        switch (this.mode) {
            // Keep the common server-tick burst together. Tick-end drains it;
            // the count guard bounds unusually large bursts.
            case SMART_EXECUTION -> {
                if (queueDepth >= this.maxPacketsPerFlush) {
                    this.requestImmediateDrain();
                }
            }
            case STRICT_TICK -> {
                // Only a semantic barrier or the normal tick-end flush drains it.
            }
            case INTERVAL -> {
                if (queueDepth >= this.maxPacketsPerFlush) {
                    this.requestImmediateDrain();
                    return;
                }
                if (first) {
                    this.requestIntervalDrain();
                }
            }
        }
    }

    /** Requests the tick-end drain used by smart and strict-tick modes. */
    public void requestTickDrain() {
        if (this.mode != Mode.INTERVAL && !this.entries.isEmpty()) {
            this.requestImmediateDrain();
        }
    }

    private void requestImmediateDrain() {
        if (this.inEventLoop.getAsBoolean()) {
            this.drainNow();
        } else {
            this.scheduleTask();
        }
    }

    private void scheduleTask() {
        if (!this.taskScheduled.compareAndSet(false, true)) {
            ZvsNetworkMetrics.avoidedWriteTask();
            return;
        }
        ZvsNetworkMetrics.writeTask();
        this.executor.execute(() -> {
            this.taskScheduled.set(false);
            this.drain();
        });
    }

    private void requestIntervalDrain() {
        if (!this.intervalScheduled.compareAndSet(false, true)) {
            return;
        }
        this.delayedScheduler.schedule(() -> {
            this.intervalScheduled.set(false);
            if (!this.closed.get() && !this.entries.isEmpty()) {
                this.requestImmediateDrain();
            }
        }, this.intervalMillis);
    }

    public void drainNow() {
        if (!this.inEventLoop.getAsBoolean()) {
            throw new IllegalStateException("PLAY write queue can only drain on its event loop");
        }
        this.drain();
    }

    public boolean hasScheduledWrites() {
        return this.active.get();
    }

    private void drain() {
        if (this.draining || this.closed.get()) {
            return;
        }
        this.draining = true;
        try {
            while (true) {
                this.drainBatch();
                this.active.set(false);
                if (this.entries.isEmpty()) {
                    return;
                }
                this.active.compareAndSet(false, true);
            }
        } finally {
            this.draining = false;
        }
    }

    private void drainBatch() {
        final List<Entry> batch = new ArrayList<>(Math.max(1, this.depth.get()));
        Entry entry;
        while ((entry = this.entries.poll()) != null) {
            this.depth.decrementAndGet();
            batch.add(entry);
        }

        final List<Entry> optimizedBatch = this.coalesceEffects(batch);
        int packetsSinceFlush = 0;
        long bytesSinceFlush = 0L;
        for (int index = 0; index < optimizedBatch.size(); index++) {
            final Entry current = optimizedBatch.get(index);
            packetsSinceFlush++;
            final boolean packetLimitFlush = packetsSinceFlush >= this.maxPacketsPerFlush;
            final boolean flush = current.barrier() || packetLimitFlush || index == optimizedBatch.size() - 1;
            final long pendingBytes = current.write().write(current.packet(), flush);
            ZvsNetworkMetrics.pendingOutboundBytes(pendingBytes);
            bytesSinceFlush = saturatingAdd(bytesSinceFlush, pendingBytes);
            if (flush) {
                ZvsNetworkMetrics.flush(
                    current.barrier()
                        ? ZvsNetworkMetrics.FlushReason.CRITICAL
                        : packetLimitFlush
                            ? ZvsNetworkMetrics.FlushReason.COUNT_LIMIT
                            : ZvsNetworkMetrics.FlushReason.BATCH_END
                );
                packetsSinceFlush = 0;
                bytesSinceFlush = 0L;
            } else if (bytesSinceFlush >= this.maxBatchBytes) {
                this.flusher.run();
                ZvsNetworkMetrics.flush(ZvsNetworkMetrics.FlushReason.BYTE_LIMIT);
                packetsSinceFlush = 0;
                bytesSinceFlush = 0L;
            }
        }
    }

    private List<Entry> coalesceEffects(final List<Entry> batch) {
        if (this.maxCoalescedPackets < 2 || batch.size() < 2) {
            return batch;
        }
        final List<Entry> result = new ArrayList<>(batch.size());
        int index = 0;
        while (index < batch.size()) {
            final Entry current = batch.get(index);
            if (!current.coalescible() || current.barrier()) {
                result.add(current);
                index++;
                continue;
            }
            final int runStart = index;
            while (index < batch.size()
                && batch.get(index).coalescible()
                && !batch.get(index).barrier()
                && index - runStart < this.maxCoalescedPackets) {
                index++;
            }
            this.coalesceRange(batch, runStart, index, result);
        }
        return result;
    }

    private void coalesceRange(final List<Entry> batch, final int start, final int end, final List<Entry> output) {
        final Map<Object, MergedEffect> merged = new LinkedHashMap<>();
        for (int index = start; index < end; index++) {
            final Entry entry = batch.get(index);
            final Object key = effectKey(entry.packet());
            if (key == null) {
                emitMergedEffects(merged, output);
                output.add(entry);
                continue;
            }
            final MergedEffect existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new MergedEffect(entry));
            } else {
                existing.packet = mergeEffect(existing.packet, entry.packet());
                existing.logicalPackets++;
            }
        }
        emitMergedEffects(merged, output);
    }

    private static void emitMergedEffects(final Map<Object, MergedEffect> merged, final List<Entry> output) {
        for (final MergedEffect effect : merged.values()) {
            final Entry first = effect.first;
            output.add(new Entry(
                first.write(), first.reject(), first.barrier(), first.estimatedBytes(), effect.packet, true
            ));
            if (effect.logicalPackets > 1) {
                ZvsNetworkMetrics.coalescedEffectPackets(effect.logicalPackets - 1);
            }
        }
        merged.clear();
    }

    private static @Nullable Object effectKey(final @Nullable Packet<?> packet) {
        if (packet instanceof ClientboundLevelParticlesPacket particles && particles.getCount() > 0) {
            return new ParticleKey(
                particles.getParticle(), particles.isOverrideLimiter(), particles.alwaysShow(),
                particles.getX(), particles.getY(), particles.getZ(), particles.getXDist(), particles.getYDist(),
                particles.getZDist(), particles.getMaxSpeed()
            );
        }
        if (packet instanceof ClientboundSoundPacket sound) {
            return new SoundKey(
                sound.getSound(), sound.getSource(), sound.getX(), sound.getY(), sound.getZ(), sound.getPitch()
            );
        }
        if (packet instanceof ClientboundSoundEntityPacket sound) {
            return new EntitySoundKey(sound.getSound(), sound.getSource(), sound.getId(), sound.getPitch());
        }
        return null;
    }

    private static Packet<?> mergeEffect(final Packet<?> first, final @Nullable Packet<?> second) {
        if (first instanceof ClientboundLevelParticlesPacket left && second instanceof ClientboundLevelParticlesPacket right) {
            final int mergedCount = left.getCount() > Integer.MAX_VALUE - right.getCount()
                ? Integer.MAX_VALUE
                : left.getCount() + right.getCount();
            return new ClientboundLevelParticlesPacket(
                left.getParticle(), left.isOverrideLimiter(), left.alwaysShow(), left.getX(), left.getY(), left.getZ(),
                left.getXDist(), left.getYDist(), left.getZDist(), left.getMaxSpeed(), mergedCount
            );
        }
        if (first instanceof ClientboundSoundPacket left && second instanceof ClientboundSoundPacket right) {
            return new ClientboundSoundPacket(
                left.getSound(), left.getSource(), left.getX(), left.getY(), left.getZ(),
                Math.max(left.getVolume(), right.getVolume()), left.getPitch(), left.getSeed()
            );
        }
        if (first instanceof ClientboundSoundEntityPacket left && second instanceof ClientboundSoundEntityPacket right) {
            return new ClientboundSoundEntityPacket(
                left.getSound(), left.getSource(), left.getId(), Math.max(left.getVolume(), right.getVolume()),
                left.getPitch(), left.getSeed()
            );
        }
        return first;
    }

    private static long saturatingAdd(final long first, final long second) {
        final long nonNegativeSecond = Math.max(0L, second);
        return first > Long.MAX_VALUE - nonNegativeSecond ? Long.MAX_VALUE : first + nonNegativeSecond;
    }

    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        Entry entry;
        while ((entry = this.entries.poll()) != null) {
            this.depth.decrementAndGet();
            entry.reject().run();
        }
        this.active.set(false);
    }

    public static Mode parseMode(final String configured) {
        if (configured == null) {
            return Mode.SMART_EXECUTION;
        }
        try {
            return Mode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ignored) {
            return Mode.SMART_EXECUTION;
        }
    }

    public enum Mode {
        SMART_EXECUTION,
        STRICT_TICK,
        INTERVAL
    }

    @FunctionalInterface
    public interface DelayedScheduler {
        void schedule(Runnable task, int delayMillis);
    }

    @FunctionalInterface
    public interface Writer {
        /** Writes one packet and returns the actual increase in Netty pending outbound bytes. */
        long write(@Nullable Packet<?> packet, boolean flush);
    }

    public record Entry(
        Writer write,
        Runnable reject,
        boolean barrier,
        int estimatedBytes,
        @Nullable Packet<?> packet,
        boolean coalescible
    ) {
        public Entry(final Writer write, final Runnable reject, final boolean barrier, final int estimatedBytes) {
            this(write, reject, barrier, estimatedBytes, null, false);
        }

        public Entry {
            if (estimatedBytes < 1) {
                throw new IllegalArgumentException("estimatedBytes must be positive");
            }
        }
    }

    private static final class MergedEffect {
        private final Entry first;
        private Packet<?> packet;
        private int logicalPackets = 1;

        private MergedEffect(final Entry first) {
            this.first = first;
            this.packet = java.util.Objects.requireNonNull(first.packet());
        }
    }

    private record ParticleKey(
        Object particle,
        boolean overrideLimiter,
        boolean alwaysShow,
        double x,
        double y,
        double z,
        float xDist,
        float yDist,
        float zDist,
        float speed
    ) {
    }

    private record SoundKey(Object sound, Object source, double x, double y, double z, float pitch) {
    }

    private record EntitySoundKey(Object sound, Object source, int entityId, float pitch) {
    }
}
