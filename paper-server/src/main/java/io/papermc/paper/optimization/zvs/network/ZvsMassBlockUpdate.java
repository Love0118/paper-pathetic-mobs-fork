package io.papermc.paper.optimization.zvs.network;

import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

/** Decision helpers for replacing a dense block-change broadcast with one chunk snapshot. */
public final class ZvsMassBlockUpdate {
    private static final int SECTION_PACKET_OVERHEAD = 12;
    private static final int MAX_PACKED_CHANGE_BYTES = 7;

    private ZvsMassBlockUpdate() {
    }

    public static int countChanges(final @Nullable ShortSet[] sections) {
        if (sections == null) {
            return 0;
        }
        int total = 0;
        for (final ShortSet section : sections) {
            if (section != null) {
                total = total > Integer.MAX_VALUE - section.size() ? Integer.MAX_VALUE : total + section.size();
            }
        }
        return total;
    }

    public static int countChangedSections(final @Nullable ShortSet[] sections) {
        if (sections == null) {
            return 0;
        }
        int total = 0;
        for (final ShortSet section : sections) {
            if (section != null && !section.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    /** Conservative upper bound for section-update packet payloads. */
    public static long estimateIncrementalBytes(final int changedBlocks, final int changedSections) {
        return saturatingAdd(
            saturatingMultiply(Math.max(0, changedBlocks), MAX_PACKED_CHANGE_BYTES),
            saturatingMultiply(Math.max(0, changedSections), SECTION_PACKET_OVERHEAD)
        );
    }

    public static long estimateChunkSectionBytes(final LevelChunkSection[] sections) {
        long total = 0L;
        for (final LevelChunkSection section : sections) {
            total = saturatingAdd(total, section.getSerializedSize());
        }
        return total;
    }

    public static boolean shouldResend(
        final boolean enabled,
        final int threshold,
        final int changedBlocks,
        final long estimatedIncrementalBytes,
        final long estimatedChunkSectionBytes,
        final int chunkSafetyBytes
    ) {
        if (!enabled || changedBlocks < Math.max(1, threshold)) {
            return false;
        }
        final long conservativeChunkBytes = saturatingAdd(
            Math.max(0L, estimatedChunkSectionBytes), Math.max(0, chunkSafetyBytes)
        );
        return estimatedIncrementalBytes >= conservativeChunkBytes;
    }

    private static long saturatingMultiply(final int value, final int multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : (long)value * multiplier;
    }

    private static long saturatingAdd(final long first, final long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
