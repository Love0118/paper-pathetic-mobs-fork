package io.papermc.paper.optimization.zvs.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** FIFO PLAY write submission with one event-loop task and bounded flushes per burst. */
public final class ZvsPlayWriteQueue {
    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger depth = new AtomicInteger();
    private final Executor executor;
    private final BooleanSupplier inEventLoop;
    private final int maxPacketsPerFlush;
    private final int maxEstimatedBytesPerFlush;
    private boolean draining;

    public ZvsPlayWriteQueue(
        final Executor executor,
        final BooleanSupplier inEventLoop,
        final int maxPacketsPerFlush,
        final int maxEstimatedBytesPerFlush
    ) {
        this.executor = executor;
        this.inEventLoop = inEventLoop;
        this.maxPacketsPerFlush = Math.max(1, maxPacketsPerFlush);
        this.maxEstimatedBytesPerFlush = maxEstimatedBytesPerFlush <= 0
            ? Integer.MAX_VALUE
            : maxEstimatedBytesPerFlush;
    }

    public void enqueue(final Entry entry) {
        if (this.closed.get()) {
            entry.reject().run();
            return;
        }
        this.entries.add(entry);
        final int queueDepth = this.depth.incrementAndGet();
        final boolean newlyScheduled = this.scheduled.compareAndSet(false, true);
        final boolean onEventLoop = this.inEventLoop.getAsBoolean();
        ZvsNetworkMetrics.logicalPacket(entry.estimatedBytes(), queueDepth, newlyScheduled && !onEventLoop);
        if (onEventLoop) {
            this.drainNow();
        } else if (newlyScheduled) {
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
        if (this.draining) {
            return;
        }
        this.draining = true;
        try {
            while (true) {
                this.drainBatch();
                this.scheduled.set(false);
                if (this.entries.isEmpty()) {
                    return;
                }
                this.scheduled.compareAndSet(false, true);
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

        int packetsSinceFlush = 0;
        int bytesSinceFlush = 0;
        for (int index = 0; index < batch.size(); index++) {
            final Entry current = batch.get(index);
            packetsSinceFlush++;
            bytesSinceFlush = saturatingAdd(bytesSinceFlush, current.estimatedBytes());
            final boolean limitFlush = packetsSinceFlush >= this.maxPacketsPerFlush
                || bytesSinceFlush >= this.maxEstimatedBytesPerFlush;
            final boolean flush = current.barrier() || limitFlush || index == batch.size() - 1;
            current.write().write(flush);
            if (flush) {
                ZvsNetworkMetrics.flush(limitFlush);
                packetsSinceFlush = 0;
                bytesSinceFlush = 0;
            }
        }
    }

    private static int saturatingAdd(final int first, final int second) {
        return first > Integer.MAX_VALUE - second ? Integer.MAX_VALUE : first + second;
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

    public record Entry(Writer write, Runnable reject, boolean barrier, int estimatedBytes) {
        public Entry {
            if (estimatedBytes < 1) {
                throw new IllegalArgumentException("estimatedBytes must be positive");
            }
        }
    }
}
