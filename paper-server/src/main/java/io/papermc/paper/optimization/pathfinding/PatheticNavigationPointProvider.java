package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.pathing.processing.Cost;
import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.PathType;

final class PatheticNavigationPointProvider implements NavigationPointProvider {
    private static final double FLOOR_EPSILON = 1.0E-5D;
    private static final PatheticNavigationPoint BUDGET_EXHAUSTED =
        new PatheticNavigationPoint(false, Cost.ZERO, PathType.BLOCKED, -1.0F);

    private final Long2ObjectOpenHashMap<PatheticNavigationPoint> cache = new Long2ObjectOpenHashMap<>(256);
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    @Override
    public NavigationPoint getNavigationPoint(final PathPosition pathPosition, final EnvironmentContext environmentContext) {
        return this.pointAt(pathPosition, (PatheticEnvironmentContext) environmentContext);
    }

    PatheticNavigationPoint pointAt(final PathPosition pathPosition, final PatheticEnvironmentContext context) {
        final int x = pathPosition.getFlooredX();
        final int y = pathPosition.getFlooredY();
        final int z = pathPosition.getFlooredZ();
        final long key = BlockPos.asLong(x, y, z);
        final PatheticNavigationPoint cached = this.cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!context.evaluationBudget().tryConsume()) {
            return BUDGET_EXHAUSTED;
        }

        final PatheticNavigationPoint point = this.evaluate(context, x, y, z);
        this.cache.put(key, point);
        return point;
    }

    private PatheticNavigationPoint evaluate(final PatheticEnvironmentContext context, final int x, final int y, final int z) {
        final PathType pathType = context.nodeEvaluator().getPathTypeOfMob(context.pathfindingContext(), x, y, z, context.mob());
        final float malus = context.mob().getPathfindingMalus(pathType);
        this.mutablePos.set(x, y, z);
        final double floorLevel = net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getFloorLevel(context.region(), this.mutablePos);
        final boolean traversable = isSupportedFlatType(pathType)
            && Math.abs(floorLevel - context.floorLevel()) <= FLOOR_EPSILON
            && Float.isFinite(malus)
            && malus >= 0.0F;
        return new PatheticNavigationPoint(traversable, traversable && malus > 0.0F ? Cost.of(malus) : Cost.ZERO, pathType, malus);
    }

    static boolean isSupportedFlatType(final PathType pathType) {
        return pathType == PathType.WALKABLE
            || pathType == PathType.DANGER_FIRE
            || pathType == PathType.DANGER_OTHER
            || pathType == PathType.WATER_BORDER;
    }
}
