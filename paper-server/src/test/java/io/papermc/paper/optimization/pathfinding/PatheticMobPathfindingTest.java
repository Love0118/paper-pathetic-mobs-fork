package io.papermc.paper.optimization.pathfinding;

import java.util.EnumSet;
import java.util.List;
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
        assertTrue(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.0F, 1));
        assertFalse(PatheticMobPathfinding.isEligible(new FlyNodeEvaluator(), start, target, 5.0F, 0));
        assertFalse(PatheticMobPathfinding.isEligible(new AmphibiousNodeEvaluator(false), start, target, 5.0F, 0));
        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.0F, 2));
        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, Set.of(new BlockPos(4, 65, 0)), 5.0F, 0));
    }

    @Test
    void maxRangeUsesVanillasStrictBoundary() {
        final Node start = walkableNode(0, 64, 0);
        final Set<BlockPos> target = Set.of(new BlockPos(5, 64, 0));

        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.0F, 0));
        assertTrue(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.01F, 0));
        assertTrue(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, target, 5.0F, 1));
    }

    @Test
    void accuracyOneRequestsAlreadyAtTheGoalUseVanillaSemantics() {
        final Node start = walkableNode(0, 64, 0);
        final Set<BlockPos> adjacentTarget = Set.of(new BlockPos(1, 64, 0));

        assertTrue(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, adjacentTarget, 5.0F, 0));
        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), start, adjacentTarget, 5.0F, 1));
    }

    @Test
    void patheticWorkUsesVanillasWorldEvaluationBudget() {
        assertEquals(99, PatheticMobPathfinding.patheticEvaluationBudget(100, 1.0F));
        assertEquals(0, PatheticMobPathfinding.patheticEvaluationBudget(2, 1.0F));
        assertEquals(0, PatheticMobPathfinding.patheticEvaluationBudget(100, Float.NaN));
        assertEquals(100_000, PatheticMobPathfinding.patheticEvaluationBudget(Integer.MAX_VALUE, 1.0F));
    }

    @Test
    void onlyTransitionFreeFlatPathTypesAreSupported() {
        final Set<PathType> supported = EnumSet.noneOf(PathType.class);
        for (final PathType pathType : PathType.values()) {
            if (PatheticNavigationPointProvider.isSupportedFlatType(pathType)) {
                supported.add(pathType);
            }
        }

        assertEquals(
            EnumSet.of(
                PathType.WALKABLE,
                PathType.WALKABLE_DOOR,
                PathType.DANGER_FIRE,
                PathType.DAMAGE_FIRE,
                PathType.DANGER_OTHER,
                PathType.DAMAGE_OTHER,
                PathType.STICKY_HONEY,
                PathType.DAMAGE_CAUTIOUS,
                PathType.WATER_BORDER,
                PathType.RAIL,
                PathType.DOOR_OPEN
            ),
            supported
        );
    }

    @Test
    void accuracyOneCandidatesPreserveTheOriginalTargetRadius() {
        final Node start = walkableNode(0, 64, 0);
        final BlockPos target = new BlockPos(4, 64, 0);
        final List<BlockPos> candidates = PatheticMobPathfinding.targetCandidates(start, target, 1);

        assertEquals(5, candidates.size());
        assertEquals(new BlockPos(3, 64, 0), candidates.getFirst());
        assertTrue(candidates.contains(target));
        for (final BlockPos candidate : candidates) {
            final Node endpoint = walkableNode(candidate.getX(), candidate.getY(), candidate.getZ());
            assertTrue(PatheticMobPathfinding.manhattanDistance(endpoint, target) <= 1);
        }
    }

    @Test
    void evaluationBudgetMarksOnlyAnOverrunAsExhausted() {
        final PatheticEvaluationBudget budget = new PatheticEvaluationBudget(1);

        assertTrue(budget.tryConsume());
        assertEquals(0, budget.remaining());
        assertEquals(1, budget.consumed());
        assertFalse(budget.exhausted());
        assertFalse(budget.tryConsume());
        assertEquals(1, budget.consumed());
        assertTrue(budget.exhausted());
    }

    private static Node walkableNode(final int x, final int y, final int z) {
        final Node node = new Node(x, y, z);
        node.type = PathType.WALKABLE;
        node.costMalus = 0.0F;
        return node;
    }
}
