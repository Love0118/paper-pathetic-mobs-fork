package io.papermc.paper.optimization.pathfinding;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
    private final Map<PathProfile, SharedCellCache> sharedCells = new HashMap<>();
    private int sharedCellCount;
    private long revision;

    public void invalidate() {
        synchronized (this) {
            this.revision++;
            this.sectionRevisions.clear();
            this.clearSharedCells();
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

    synchronized SharedCellCache sharedCells(
        final PathProfile profile,
        final int maxEntries,
        final boolean metricsEnabled
    ) {
        final SharedCellCache cache = this.sharedCells.computeIfAbsent(profile, ignored -> new SharedCellCache());
        cache.maxEntries = Math.max(1, maxEntries);
        cache.metricsEnabled = metricsEnabled;
        return cache;
    }

    synchronized SharedCellCache sharedCells(final PathProfile profile, final int maxEntries) {
        return this.sharedCells(profile, maxEntries, true);
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

    private static long sectionFor(final long position) {
        return SectionPos.asLong(
            SectionPos.blockToSectionCoord(BlockPos.getX(position)),
            SectionPos.blockToSectionCoord(BlockPos.getY(position)),
            SectionPos.blockToSectionCoord(BlockPos.getZ(position))
        );
    }

    private void clearSharedCells() {
        for (final SharedCellCache cache : this.sharedCells.values()) {
            cache.points.clear();
        }
        this.sharedCellCount = 0;
    }

    final class SharedCellCache {
        private final Long2ObjectOpenHashMap<CachedPoint> points = new Long2ObjectOpenHashMap<>();
        private int maxEntries = 1;
        private boolean metricsEnabled;

        @Nullable
        PatheticNavigationPoint find(final long position) {
            synchronized (ZvsSharedPathCache.this) {
                final CachedPoint cached = this.points.get(position);
                if (cached == null) {
                    ZvsPathfindingMetrics.sharedCell(this.metricsEnabled, false);
                    return null;
                }
                if (ZvsSharedPathCache.this.sectionRevisions.get(cached.section()) != cached.revision()) {
                    this.points.remove(position);
                    ZvsSharedPathCache.this.sharedCellCount--;
                    ZvsPathfindingMetrics.sharedCell(this.metricsEnabled, false);
                    return null;
                }
                ZvsPathfindingMetrics.sharedCell(this.metricsEnabled, true);
                return cached.point();
            }
        }

        void record(final long position, final PatheticNavigationPoint point) {
            synchronized (ZvsSharedPathCache.this) {
                if (this.points.containsKey(position)) {
                    return;
                }
                if (ZvsSharedPathCache.this.sharedCellCount >= this.maxEntries) {
                    ZvsSharedPathCache.this.clearSharedCells();
                }
                final long section = sectionFor(position);
                this.points.put(position, new CachedPoint(
                    point, section, ZvsSharedPathCache.this.sectionRevisions.get(section)
                ));
                ZvsSharedPathCache.this.sharedCellCount++;
            }
        }
    }

    private record CachedPoint(PatheticNavigationPoint point, long section, long revision) {
    }

    static PathProfile profile(final Mob mob, final WalkNodeEvaluator evaluator, final double floorLevel) {
        final int widthBits = Float.floatToIntBits(mob.getBbWidth());
        final int heightBits = Float.floatToIntBits(mob.getBbHeight());
        final long floorLevelBits = Double.doubleToLongBits(floorLevel);
        final int malusRevision = mob.zvsPathfindingMalusRevision();
        if (!(mob.getControlledVehicle() instanceof Mob)) {
            final Object cachedObject = mob.zvsPathProfileCache();
            if (cachedObject instanceof ProfileCache cached
                && cached.matches(
                    widthBits, heightBits, floorLevelBits, malusRevision,
                    evaluator.canPassDoors(), evaluator.canOpenDoors(), evaluator.canFloat(), evaluator.canWalkOverFences()
                )) {
                return cached.profile();
            }
        }

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
        final PathProfile profile = new PathProfile(
            mob.getType(),
            widthBits,
            heightBits,
            floorLevelBits,
            evaluator.canPassDoors(),
            evaluator.canOpenDoors(),
            evaluator.canFloat(),
            evaluator.canWalkOverFences(),
            malusBits
        );
        if (!(mob.getControlledVehicle() instanceof Mob)) {
            mob.zvsPathProfileCache(new ProfileCache(
                widthBits, heightBits, floorLevelBits, malusRevision,
                evaluator.canPassDoors(), evaluator.canOpenDoors(), evaluator.canFloat(), evaluator.canWalkOverFences(),
                profile
            ));
        }
        return profile;
    }

    static PathProfile syntheticProfile(final int identity) {
        return new PathProfile(identity, 0, 0, 0L, false, false, false, false, new int[0]);
    }

    record SectionSnapshot(long[] sections, long[] revisions) {
    }

    private record ProfileCache(
        int widthBits,
        int heightBits,
        long floorLevelBits,
        int malusRevision,
        boolean canPassDoors,
        boolean canOpenDoors,
        boolean canFloat,
        boolean canWalkOverFences,
        PathProfile profile
    ) {
        boolean matches(
            final int width,
            final int height,
            final long floor,
            final int revision,
            final boolean passDoors,
            final boolean openDoors,
            final boolean floating,
            final boolean fences
        ) {
            return this.widthBits == width
                && this.heightBits == height
                && this.floorLevelBits == floor
                && this.malusRevision == revision
                && this.canPassDoors == passDoors
                && this.canOpenDoors == openDoors
                && this.canFloat == floating
                && this.canWalkOverFences == fences;
        }
    }

    /** Collision-safe equality key. Hash collisions only cause a normal map probe. */
    static final class PathProfile {
        private final Object mobType;
        private final int widthBits;
        private final int heightBits;
        private final long floorLevelBits;
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
            final long floorLevelBits,
            final boolean canPassDoors,
            final boolean canOpenDoors,
            final boolean canFloat,
            final boolean canWalkOverFences,
            final int[] malusBits
        ) {
            this.mobType = mobType;
            this.widthBits = widthBits;
            this.heightBits = heightBits;
            this.floorLevelBits = floorLevelBits;
            this.canPassDoors = canPassDoors;
            this.canOpenDoors = canOpenDoors;
            this.canFloat = canFloat;
            this.canWalkOverFences = canWalkOverFences;
            this.malusBits = malusBits.clone();
            int hash = System.identityHashCode(mobType);
            hash = 31 * hash + widthBits;
            hash = 31 * hash + heightBits;
            hash = 31 * hash + Long.hashCode(floorLevelBits);
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
                && this.floorLevelBits == profile.floorLevelBits
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
