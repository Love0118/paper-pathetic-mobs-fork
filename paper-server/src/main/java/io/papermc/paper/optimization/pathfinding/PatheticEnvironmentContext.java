package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.NullMarked;

@NullMarked
record PatheticEnvironmentContext(
    PathNavigationRegion region,
    Mob mob,
    WalkNodeEvaluator nodeEvaluator,
    PathfindingContext pathfindingContext,
    double floorLevel,
    PatheticEvaluationBudget evaluationBudget,
    boolean metricsEnabled
) implements EnvironmentContext {
    PatheticEnvironmentContext(
        final PathNavigationRegion region,
        final Mob mob,
        final WalkNodeEvaluator nodeEvaluator,
        final PathfindingContext pathfindingContext,
        final double floorLevel,
        final PatheticEvaluationBudget evaluationBudget
    ) {
        this(region, mob, nodeEvaluator, pathfindingContext, floorLevel, evaluationBudget, true);
    }

    PatheticEnvironmentContext(
        final PathNavigationRegion region,
        final Mob mob,
        final WalkNodeEvaluator nodeEvaluator,
        final PathfindingContext pathfindingContext,
        final Node start,
        final int evaluationBudget
    ) {
        this(
            region,
            mob,
            nodeEvaluator,
            pathfindingContext,
            WalkNodeEvaluator.getFloorLevel(region, start.asBlockPos()),
            new PatheticEvaluationBudget(evaluationBudget),
            true
        );
    }
}
