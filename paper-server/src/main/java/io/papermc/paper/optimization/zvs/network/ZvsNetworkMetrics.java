package io.papermc.paper.optimization.zvs.network;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

public final class ZvsNetworkMetrics {
    private static final LongAdder LOGICAL_PACKETS = new LongAdder();
    private static final LongAdder PHYSICAL_WRITES = new LongAdder();
    private static final LongAdder WRITE_TASKS = new LongAdder();
    private static final LongAdder AVOIDED_WRITE_TASKS = new LongAdder();
    private static final LongAdder FLUSHES = new LongAdder();
    private static final LongAdder LIMIT_FLUSHES = new LongAdder();
    private static final LongAdder ESTIMATED_BYTES = new LongAdder();
    private static final LongAdder BUNDLED_LOGICAL_PACKETS = new LongAdder();
    private static final LongAdder EFFECT_BUNDLES = new LongAdder();
    private static final LongAdder RETAINED_FRAME_BYTES = new LongAdder();
    private static final LongAdder COPIED_FRAME_BYTES = new LongAdder();
    private static final LongAdder IN_PLACE_PREFIXES = new LongAdder();
    private static final LongAdder COPIED_PREFIXES = new LongAdder();
    private static final LongAccumulator MAX_QUEUE_DEPTH = new LongAccumulator(Long::max, 0L);

    private ZvsNetworkMetrics() {
    }

    static void logicalPacket(final int estimatedBytes, final int queueDepth, final boolean scheduled) {
        LOGICAL_PACKETS.increment();
        ESTIMATED_BYTES.add(estimatedBytes);
        MAX_QUEUE_DEPTH.accumulate(queueDepth);
        if (scheduled) {
            WRITE_TASKS.increment();
        } else {
            AVOIDED_WRITE_TASKS.increment();
        }
    }

    static void physicalWrite() {
        PHYSICAL_WRITES.increment();
    }

    static void effectBundle(final int logicalPackets) {
        BUNDLED_LOGICAL_PACKETS.add(logicalPackets);
        EFFECT_BUNDLES.increment();
    }

    static void flush(final boolean limit) {
        FLUSHES.increment();
        if (limit) {
            LIMIT_FLUSHES.increment();
        }
    }

    public static void retainedFrame(final int bytes) {
        RETAINED_FRAME_BYTES.add(bytes);
    }

    public static void copiedFrame(final int bytes) {
        COPIED_FRAME_BYTES.add(bytes);
    }

    public static void framePrefix(final boolean inPlace) {
        if (inPlace) {
            IN_PLACE_PREFIXES.increment();
        } else {
            COPIED_PREFIXES.increment();
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            LOGICAL_PACKETS.sum(), PHYSICAL_WRITES.sum(), WRITE_TASKS.sum(), AVOIDED_WRITE_TASKS.sum(),
            FLUSHES.sum(), LIMIT_FLUSHES.sum(), ESTIMATED_BYTES.sum(), MAX_QUEUE_DEPTH.get(),
            BUNDLED_LOGICAL_PACKETS.sum(), EFFECT_BUNDLES.sum(), RETAINED_FRAME_BYTES.sum(), COPIED_FRAME_BYTES.sum(),
            IN_PLACE_PREFIXES.sum(), COPIED_PREFIXES.sum()
        );
    }

    public record Snapshot(
        long logicalPackets,
        long physicalWrites,
        long writeTasks,
        long avoidedWriteTasks,
        long flushes,
        long limitFlushes,
        long estimatedBytes,
        long maxQueueDepth,
        long bundledLogicalPackets,
        long effectBundles,
        long retainedFrameBytes,
        long copiedFrameBytes,
        long inPlacePrefixes,
        long copiedPrefixes
    ) {
    }
}
