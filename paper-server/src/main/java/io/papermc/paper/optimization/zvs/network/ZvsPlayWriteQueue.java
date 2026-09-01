package io.papermc.paper.optimization.zvs.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class ZvsPlayWriteQueue {
    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger depth = new AtomicInteger();
    private final Executor executor;
    private final BooleanSupplier inEventLoop;
    private final int maxPacketsPerFlush;
    private final int maxEstimatedBytesPerFlush;
    private final int maxEffectBundlePackets;
    private final BatchWriter batchWriter;

    public ZvsPlayWriteQueue(
        final Executor executor,
        final BooleanSupplier inEventLoop,
        final int maxPacketsPerFlush,
        final int maxEstimatedBytesPerFlush
    ) {
        this(executor, inEventLoop, maxPacketsPerFlush, maxEstimatedBytesPerFlush, 1, null);
    }

    public ZvsPlayWriteQueue(
        final Executor executor,
        final BooleanSupplier inEventLoop,
        final int maxPacketsPerFlush,
        final int maxEstimatedBytesPerFlush,
        final int maxEffectBundlePackets,
        final BatchWriter batchWriter
    ) {
        this.executor = executor;
        this.inEventLoop = inEventLoop;
        this.maxPacketsPerFlush = Math.max(1, maxPacketsPerFlush);
        this.maxEstimatedBytesPerFlush = Math.max(1, maxEstimatedBytesPerFlush);
        this.maxEffectBundlePackets = Math.clamp(maxEffectBundlePackets, 1, 4_000);
        this.batchWriter = batchWriter;
    }

    public void enqueue(final Entry entry) {
        if (this.closed.get()) {
            entry.reject().run();
            return;
        }
        this.entries.add(entry);
        final int queueDepth = this.depth.incrementAndGet();
        final boolean newlyScheduled = this.scheduled.compareAndSet(false, true);
        ZvsNetworkMetrics.logicalPacket(entry.estimatedBytes(), queueDepth, newlyScheduled);
        if (newlyScheduled) {
            this.executor.execute(this::drain);
        }
    }

    public void drainNow() {
        if (!this.inEventLoop.getAsBoolean()) {
            throw new IllegalStateException("PLAY write queue can only drain on its event loop");
        }
        this.drain();
    }

    public boolean hasScheduledWrites() {
        return this.scheduled.get();
    }

    private void drain() {
        final List<Entry> batch = new ArrayList<>(Math.max(1, this.depth.get()));
        Entry entry;
        while ((entry = this.entries.poll()) != null) {
            this.depth.decrementAndGet();
            batch.add(entry);
        }

        int packetsSinceFlush = 0;
        int bytesSinceFlush = 0;
        for (int i = 0; i < batch.size();) {
            final Entry current = batch.get(i);
            int groupEnd = i + 1;
            int groupBytes = current.estimatedBytes();
            if (this.batchWriter != null && current.bundleCandidate() && !current.barrier()) {
                while (groupEnd < batch.size() && groupEnd - i < this.maxEffectBundlePackets) {
                    final Entry next = batch.get(groupEnd);
                    if (!next.bundleCandidate() || next.barrier()) {
                        break;
                    }
                    groupBytes += next.estimatedBytes();
                    groupEnd++;
                }
            }
            final int groupSize = groupEnd - i;
            packetsSinceFlush += groupSize;
            bytesSinceFlush += groupBytes;
            final boolean limitFlush = packetsSinceFlush >= this.maxPacketsPerFlush || bytesSinceFlush >= this.maxEstimatedBytesPerFlush;
            final boolean flush = current.barrier() || limitFlush || groupEnd == batch.size();
            if (groupSize > 1) {
                this.batchWriter.write(batch.subList(i, groupEnd), flush);
                ZvsNetworkMetrics.effectBundle(groupSize);
            } else {
                current.write().write(flush);
            }
            ZvsNetworkMetrics.physicalWrite();
            if (flush) {
                ZvsNetworkMetrics.flush(limitFlush);
                packetsSinceFlush = 0;
                bytesSinceFlush = 0;
            }
            i = groupEnd;
        }

        this.scheduled.set(false);
        if (!this.entries.isEmpty() && this.scheduled.compareAndSet(false, true)) {
            this.executor.execute(this::drain);
        }
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
    }

    @FunctionalInterface
    public interface Writer {
        void write(boolean flush);
    }

    @FunctionalInterface
    public interface BatchWriter {
        void write(List<Entry> entries, boolean flush);
    }

    public record Entry(Writer write, Runnable reject, boolean barrier, int estimatedBytes, Object payload, boolean bundleCandidate) {
        public Entry(final Writer write, final Runnable reject, final boolean barrier, final int estimatedBytes) {
            this(write, reject, barrier, estimatedBytes, null, false);
        }

        public Entry {
            if (estimatedBytes < 1) {
                throw new IllegalArgumentException("estimatedBytes must be positive");
            }
        }
    }
}
