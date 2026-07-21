package io.papermc.paper.optimization.pathfinding;

import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class PatheticMobPathfindingTest {
    @Test
    void onlyExactVanillaWalkEvaluatorFlatRequestsAreEligible() {
        final Node start = walkableNode(0, 64, 0);
        final Set<BlockPos> target = Set.of(new BlockPos(4, 64, 0));

        assertTrue(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.0F, 0));
        assertFalse(PatheticMobPathfinding.isEligible(new FlyNodeEvaluator(), start, target, 5.0F, 0));
        assertFalse(PatheticMobPathfinding.isEligible(new AmphibiousNodeEvaluator(false), start, target, 5.0F, 0));
        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.0F, 1));
        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, Set.of(new BlockPos(4, 65, 0)), 5.0F, 0));
    }

    @Test
    void maxRangeUsesVanillasStrictBoundary() {
        final Node start = walkableNode(0, 64, 0);
        final Set<BlockPos> target = Set.of(new BlockPos(5, 64, 0));

        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.0F, 0));
        assertTrue(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.01F, 0));
    }

    @Test
    void onlyTransitionFreeFlatPathTypesAreSupported() {
        final Set<PathType> supported = EnumSet.noneOf(PathType.class);
        for (final PathType pathType : PathType.values()) {
            if (PatheticNavigationPointProvider.isSupportedFlatType(pathType)) {
                supported.add(pathType);
            }
        }

        assertEquals(EnumSet.of(PathType.WALKABLE, PathType.DANGER_FIRE, PathType.DANGER_OTHER, PathType.WATER_BORDER), supported);
    }

    @Test
    void evaluationBudgetMarksOnlyAnOverrunAsExhausted() {
        final PatheticEvaluationBudget budget = new PatheticEvaluationBudget(1);

        assertTrue(budget.tryConsume());
        assertEquals(0, budget.remaining());
        assertFalse(budget.exhausted());
        assertFalse(budget.tryConsume());
        assertTrue(budget.exhausted());
    }

    private static Node walkableNode(final int x, final int y, final int z) {
        final Node node = new Node(x, y, z);
        node.type = PathType.WALKABLE;
        node.costMalus = 0.0F;
        return node;
    }
}
