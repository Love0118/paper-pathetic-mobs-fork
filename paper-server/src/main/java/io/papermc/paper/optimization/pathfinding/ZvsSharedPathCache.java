package io.papermc.paper.optimization.pathfinding;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Per-level reverse-flow cache for successful managed two-dimensional routes.
 *
 * <p>Successful paths are merged into one next-hop field per fixed objective.
 * This replaces the former exact-start suffix cache plus synchronous Dijkstra
 * build: a request never performs extra world evaluation just to populate a
 * cache, and the same next-hop data is not stored twice.</p>
 */
@NullMarked
public final class ZvsSharedPathCache {
    private final ZvsReverseFlowFieldCache flowFields = new ZvsReverseFlowFieldCache();
    private final Long2LongOpenHashMap sectionRevisions = new Long2LongOpenHashMap();
    private long revision;

    public void invalidate() {
        synchronized (this) {
            this.revision++;
            this.sectionRevisions.clear();
        }
        this.flowFields.invalidate();
        ZvsPathfindingMetrics.invalidation();
    }

    public synchronized void invalidate(final BlockPos changedPosition) {
        this.revision++;
        final LongOpenHashSet affectedSections = new LongOpenHashSet(8);
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            final int sectionX = SectionPos.blockToSectionCoord(changedPosition.getX() + offsetX);
            for (int offsetY = -2; offsetY <= 2; offsetY++) {
                final int sectionY = SectionPos.blockToSectionCoord(changedPosition.getY() + offsetY);
                for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                    affectedSections.add(SectionPos.asLong(
                        sectionX,
                        sectionY,
                        SectionPos.blockToSectionCoord(changedPosition.getZ() + offsetZ)
                    ));
                }
            }
        }
        for (final long section : affectedSections) {
            this.sectionRevisions.addTo(section, 1L);
        }
        ZvsPathfindingMetrics.invalidation();
    }

    @Nullable
    Path findFlowField(
        final PathProfile profile,
        final Node start,
        final BlockPos target,
        final int accuracy,
        final float maxRange,
        final int maxPathLength
    ) {
        return this.flowFields.find(
            profile, start, target, accuracy, maxRange, maxPathLength, this::isCurrent
        );
    }

    void recordFlowField(
        final PathProfile profile,
        final Path path,
        final int accuracy,
        final float maxRange
    ) {
        this.flowFields.record(profile, path, accuracy, maxRange, this::snapshot);
    }

    synchronized int sizeForTesting() {
        return this.flowFields.sizeForTesting();
    }

    synchronized long revisionForTesting() {
        return this.revision;
    }

    private synchronized SectionSnapshot snapshot(final long[] sections) {
        final long[] revisions = new long[sections.length];
        for (int index = 0; index < sections.length; index++) {
            revisions[index] = this.sectionRevisions.get(sections[index]);
        }
        return new SectionSnapshot(sections, revisions);
    }

    private synchronized boolean isCurrent(final SectionSnapshot snapshot) {
        for (int index = 0; index < snapshot.sections().length; index++) {
            if (this.sectionRevisions.get(snapshot.sections()[index]) != snapshot.revisions()[index]) {
                return false;
            }
        }
        return true;
    }

    static PathProfile profile(final Mob mob, final WalkNodeEvaluator evaluator) {
        int supportedCount = 0;
        for (final PathType pathType : PathType.values()) {
            if (PatheticNavigationPointProvider.isSupportedFlatType(pathType)) {
                supportedCount++;
            }
        }
        final int[] malusBits = new int[supportedCount];
        int index = 0;
        for (final PathType pathType : PathType.values()) {
            if (PatheticNavigationPointProvider.isSupportedFlatType(pathType)) {
                malusBits[index++] = Float.floatToIntBits(mob.getPathfindingMalus(pathType));
            }
        }
        return new PathProfile(
            mob.getType(),
            Float.floatToIntBits(mob.getBbWidth()),
            Float.floatToIntBits(mob.getBbHeight()),
            evaluator.canPassDoors(),
            evaluator.canOpenDoors(),
            evaluator.canFloat(),
            evaluator.canWalkOverFences(),
            malusBits
        );
    }

    static PathProfile syntheticProfile(final int identity) {
        return new PathProfile(identity, 0, 0, false, false, false, false, new int[0]);
    }

    record SectionSnapshot(long[] sections, long[] revisions) {
    }

    /** Collision-safe equality key. Hash collisions only cause a normal map probe. */
    static final class PathProfile {
        private final Object mobType;
        private final int widthBits;
        private final int heightBits;
        private final boolean canPassDoors;
        private final boolean canOpenDoors;
        private final boolean canFloat;
        private final boolean canWalkOverFences;
        private final int[] malusBits;
        private final int hashCode;

        private PathProfile(
            final Object mobType,
            final int widthBits,
            final int heightBits,
            final boolean canPassDoors,
            final boolean canOpenDoors,
            final boolean canFloat,
            final boolean canWalkOverFences,
            final int[] malusBits
        ) {
            this.mobType = mobType;
            this.widthBits = widthBits;
            this.heightBits = heightBits;
            this.canPassDoors = canPassDoors;
            this.canOpenDoors = canOpenDoors;
            this.canFloat = canFloat;
            this.canWalkOverFences = canWalkOverFences;
            this.malusBits = malusBits.clone();
            int hash = System.identityHashCode(mobType);
            hash = 31 * hash + widthBits;
            hash = 31 * hash + heightBits;
            hash = 31 * hash + Boolean.hashCode(canPassDoors);
            hash = 31 * hash + Boolean.hashCode(canOpenDoors);
            hash = 31 * hash + Boolean.hashCode(canFloat);
            hash = 31 * hash + Boolean.hashCode(canWalkOverFences);
            this.hashCode = 31 * hash + Arrays.hashCode(this.malusBits);
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PathProfile profile)) {
                return false;
            }
            return this.mobType == profile.mobType
                && this.widthBits == profile.widthBits
                && this.heightBits == profile.heightBits
                && this.canPassDoors == profile.canPassDoors
                && this.canOpenDoors == profile.canOpenDoors
                && this.canFloat == profile.canFloat
                && this.canWalkOverFences == profile.canWalkOverFences
                && Arrays.equals(this.malusBits, profile.malusBits);
        }

        @Override
        public int hashCode() {
            return this.hashCode;
        }
    }
}
