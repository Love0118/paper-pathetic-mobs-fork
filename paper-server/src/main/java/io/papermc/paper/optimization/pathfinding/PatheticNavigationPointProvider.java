package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.pathing.processing.Cost;
import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import org.jspecify.annotations.NullMarked;

@NullMarked
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
        final PathType commonFloorType = this.commonFloorPathType(context, x, y, z);
        final PathType pathType = commonFloorType != null
            ? commonFloorType
            : context.nodeEvaluator().getPathTypeOfMob(context.pathfindingContext(), x, y, z, context.mob());
        final float malus = context.mob().getPathfindingMalus(pathType);
        final double floorLevel = commonFloorType != null ? y : this.floorLevel(context, pathType, x, y, z);
        final boolean traversable = isSupportedFlatType(pathType)
            && Math.abs(floorLevel - context.floorLevel()) <= FLOOR_EPSILON
            && Float.isFinite(malus)
            && malus >= 0.0F;
        return new PatheticNavigationPoint(traversable, traversable && malus > 0.0F ? Cost.of(malus) : Cost.ZERO, pathType, malus);
    }

    private PathType commonFloorPathType(final PatheticEnvironmentContext context, final int x, final int y, final int z) {
        final float width = context.mob().getBbWidth();
        final float height = context.mob().getBbHeight();
        if (!Float.isFinite(width) || !Float.isFinite(height) || width >= 1.0F || height >= 2.0F) {
            return null;
        }

        this.mutablePos.set(x, y - 1, z);
        if (!isCommonFullFloor(context.region().getBlockState(this.mutablePos))) {
            return null;
        }

        final int entityHeight = Math.max(1, net.minecraft.util.Mth.floor(height + 1.0F));
        for (int offsetY = 0; offsetY < entityHeight; offsetY++) {
            this.mutablePos.set(x, y + offsetY, z);
            if (!context.region().getBlockState(this.mutablePos).isAir()) {
                return null;
            }
        }

        return net.minecraft.world.level.pathfinder.WalkNodeEvaluator.checkNeighbourBlocks(
            context.pathfindingContext(), x, y, z, PathType.WALKABLE
        );
    }

    static boolean isSupportedFlatType(final PathType pathType) {
        return pathType == PathType.WALKABLE
            || pathType == PathType.WALKABLE_DOOR
            || pathType == PathType.FIRE_IN_NEIGHBOR
            || pathType == PathType.FIRE
            || pathType == PathType.DAMAGING_IN_NEIGHBOR
            || pathType == PathType.DAMAGING
            || pathType == PathType.STICKY_HONEY
            || pathType == PathType.DAMAGE_CAUTIOUS
            || pathType == PathType.WATER_BORDER
            || pathType == PathType.RAIL
            || pathType == PathType.DOOR_OPEN;
    }

    private double floorLevel(
        final PatheticEnvironmentContext context,
        final PathType pathType,
        final int x,
        final int y,
        final int z
    ) {
        this.mutablePos.set(x, y - 1, z);
        final BlockState floor = context.region().getBlockState(this.mutablePos);
        if (pathType == PathType.WALKABLE && isCommonFullFloor(floor)) {
            return y;
        }

        this.mutablePos.set(x, y, z);
        return net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getFloorLevel(context.region(), this.mutablePos);
    }

    private static boolean isCommonFullFloor(final BlockState state) {
        final Block block = state.getBlock();
        return block == Blocks.STONE
            || block == Blocks.GRASS_BLOCK
            || block == Blocks.DIRT
            || block == Blocks.COBBLESTONE
            || block == Blocks.DEEPSLATE
            || block == Blocks.SAND
            || block == Blocks.GRAVEL
            || block == Blocks.NETHERRACK;
    }
}
