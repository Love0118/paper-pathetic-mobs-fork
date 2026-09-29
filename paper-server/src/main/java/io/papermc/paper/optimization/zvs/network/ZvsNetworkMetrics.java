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
    private static final LongAdder CRITICAL_FLUSHES = new LongAdder();
    private static final LongAdder COUNT_LIMIT_FLUSHES = new LongAdder();
    private static final LongAdder BYTE_LIMIT_FLUSHES = new LongAdder();
    private static final LongAdder BATCH_END_FLUSHES = new LongAdder();
    private static final LongAdder ESTIMATED_BYTES = new LongAdder();
    private static final LongAdder PENDING_OUTBOUND_BYTES = new LongAdder();
    private static final LongAdder RETAINED_FRAME_BYTES = new LongAdder();
    private static final LongAdder COPIED_FRAME_BYTES = new LongAdder();
    private static final LongAdder IN_PLACE_PREFIXES = new LongAdder();
    private static final LongAdder COPIED_PREFIXES = new LongAdder();
    private static final LongAdder OPTIMIZED_CHUNK_RESENDS = new LongAdder();
    private static final LongAdder REPLACED_BLOCK_UPDATES = new LongAdder();
    private static final LongAdder COALESCED_EFFECT_PACKETS = new LongAdder();
    private static final LongAccumulator MAX_QUEUE_DEPTH = new LongAccumulator(Long::max, 0L);

    private ZvsNetworkMetrics() {
    }

    public static void configure(final boolean collectMetrics) {
        enabled = collectMetrics;
    }

    static void logicalPacket(final int estimatedBytes, final int queueDepth) {
        if (!enabled) {
            return;
        }
        LOGICAL_PACKETS.increment();
        ESTIMATED_BYTES.add(estimatedBytes);
        MAX_QUEUE_DEPTH.accumulate(queueDepth);
    }

    static void writeTask() {
        if (enabled) {
            WRITE_TASKS.increment();
        }
    }

    static void avoidedWriteTask() {
        if (enabled) {
            AVOIDED_WRITE_TASKS.increment();
        }
    }

    public static void channelWrite() {
        if (enabled) {
            CHANNEL_WRITES.increment();
        }
    }

    static void flush(final FlushReason reason) {
        if (!enabled) {
            return;
        }
        FLUSHES.increment();
        switch (reason) {
            case CRITICAL -> CRITICAL_FLUSHES.increment();
            case COUNT_LIMIT -> {
                LIMIT_FLUSHES.increment();
                COUNT_LIMIT_FLUSHES.increment();
            }
            case BYTE_LIMIT -> {
                LIMIT_FLUSHES.increment();
                BYTE_LIMIT_FLUSHES.increment();
            }
            case BATCH_END -> BATCH_END_FLUSHES.increment();
        }
    }

    static void pendingOutboundBytes(final long bytes) {
        if (enabled && bytes > 0L) {
            PENDING_OUTBOUND_BYTES.add(bytes);
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

    public static void optimizedChunkResend(final int replacedBlockUpdates) {
        if (enabled) {
            OPTIMIZED_CHUNK_RESENDS.increment();
            REPLACED_BLOCK_UPDATES.add(replacedBlockUpdates);
        }
    }

    static void coalescedEffectPackets(final int packetsRemoved) {
        if (enabled) {
            COALESCED_EFFECT_PACKETS.add(packetsRemoved);
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            enabled, LOGICAL_PACKETS.sum(), CHANNEL_WRITES.sum(), WRITE_TASKS.sum(), AVOIDED_WRITE_TASKS.sum(),
            FLUSHES.sum(), LIMIT_FLUSHES.sum(), ESTIMATED_BYTES.sum(), MAX_QUEUE_DEPTH.get(),
            RETAINED_FRAME_BYTES.sum(), COPIED_FRAME_BYTES.sum(), IN_PLACE_PREFIXES.sum(), COPIED_PREFIXES.sum(),
            OPTIMIZED_CHUNK_RESENDS.sum(), REPLACED_BLOCK_UPDATES.sum(), COALESCED_EFFECT_PACKETS.sum(),
            CRITICAL_FLUSHES.sum(), COUNT_LIMIT_FLUSHES.sum(), BYTE_LIMIT_FLUSHES.sum(), BATCH_END_FLUSHES.sum(),
            PENDING_OUTBOUND_BYTES.sum()
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
        long copiedPrefixes,
        long optimizedChunkResends,
        long replacedBlockUpdates,
        long coalescedEffectPackets,
        long criticalFlushes,
        long countLimitFlushes,
        long byteLimitFlushes,
        long batchEndFlushes,
        long pendingOutboundBytes
    ) {
    }

    enum FlushReason {
        CRITICAL,
        COUNT_LIMIT,
        BYTE_LIMIT,
        BATCH_END
    }
}
