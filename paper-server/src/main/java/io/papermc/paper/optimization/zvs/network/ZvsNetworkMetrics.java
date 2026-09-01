package io.papermc.paper.optimization.zvs.network;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Optional counters. Disabled mode is one predictable volatile branch. */
public final class ZvsNetworkMetrics {
    private static volatile boolean enabled;
    private static final LongAdder LOGICAL_PACKETS = new LongAdder();
    private static final LongAdder CHANNEL_WRITES = new LongAdder();
    private static final LongAdder WRITE_TASKS = new LongAdder();
    private static final LongAdder AVOIDED_WRITE_TASKS = new LongAdder();
    private static final LongAdder FLUSHES = new LongAdder();
    private static final LongAdder LIMIT_FLUSHES = new LongAdder();
    private static final LongAdder ESTIMATED_BYTES = new LongAdder();
    private static final LongAdder RETAINED_FRAME_BYTES = new LongAdder();
    private static final LongAdder COPIED_FRAME_BYTES = new LongAdder();
    private static final LongAdder IN_PLACE_PREFIXES = new LongAdder();
    private static final LongAdder COPIED_PREFIXES = new LongAdder();
    private static final LongAccumulator MAX_QUEUE_DEPTH = new LongAccumulator(Long::max, 0L);

    private ZvsNetworkMetrics() {
    }

    public static void configure(final boolean collectMetrics) {
        enabled = collectMetrics;
    }

    static void logicalPacket(final int estimatedBytes, final int queueDepth, final boolean scheduled) {
        if (!enabled) {
            return;
        }
        LOGICAL_PACKETS.increment();
        ESTIMATED_BYTES.add(estimatedBytes);
        MAX_QUEUE_DEPTH.accumulate(queueDepth);
        if (scheduled) {
            WRITE_TASKS.increment();
        } else {
            AVOIDED_WRITE_TASKS.increment();
        }
    }

    public static void channelWrite() {
        if (enabled) {
            CHANNEL_WRITES.increment();
        }
    }

    static void flush(final boolean limit) {
        if (!enabled) {
            return;
        }
        FLUSHES.increment();
        if (limit) {
            LIMIT_FLUSHES.increment();
        }
    }

    public static void retainedFrame(final int bytes) {
        if (enabled) {
            RETAINED_FRAME_BYTES.add(bytes);
        }
    }

    public static void copiedFrame(final int bytes) {
        if (enabled) {
            COPIED_FRAME_BYTES.add(bytes);
        }
    }

    public static void framePrefix(final boolean inPlace) {
        if (!enabled) {
            return;
        }
        if (inPlace) {
            IN_PLACE_PREFIXES.increment();
        } else {
            COPIED_PREFIXES.increment();
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            enabled, LOGICAL_PACKETS.sum(), CHANNEL_WRITES.sum(), WRITE_TASKS.sum(), AVOIDED_WRITE_TASKS.sum(),
            FLUSHES.sum(), LIMIT_FLUSHES.sum(), ESTIMATED_BYTES.sum(), MAX_QUEUE_DEPTH.get(),
            RETAINED_FRAME_BYTES.sum(), COPIED_FRAME_BYTES.sum(), IN_PLACE_PREFIXES.sum(), COPIED_PREFIXES.sum()
        );
    }

    public record Snapshot(
        boolean enabled,
        long logicalPackets,
        long channelWrites,
        long writeTasks,
        long avoidedWriteTasks,
        long flushes,
        long limitFlushes,
        long estimatedBytes,
        long maxQueueDepth,
        long retainedFrameBytes,
        long copiedFrameBytes,
        long inPlacePrefixes,
        long copiedPrefixes
    ) {
    }
}
