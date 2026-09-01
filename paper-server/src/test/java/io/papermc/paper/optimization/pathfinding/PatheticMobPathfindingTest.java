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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                PathType.FIRE_IN_NEIGHBOR,
                PathType.FIRE,
                PathType.DAMAGING_IN_NEIGHBOR,
                PathType.DAMAGING,
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

    @Test
    void eligibilityReportsFallbackReasons() {
        final Node start = walkableNode(0, 64, 0);
        final Set<BlockPos> target = Set.of(new BlockPos(4, 64, 0));

        assertEquals(
            ZvsPathfindingMetrics.RejectionReason.EVALUATOR,
            PatheticMobPathfinding.rejectionReason(new FlyNodeEvaluator(), start, target, 5.0F, 0)
        );
        assertEquals(
            ZvsPathfindingMetrics.RejectionReason.VERTICAL,
            PatheticMobPathfinding.rejectionReason(
                new WalkNodeEvaluator(), start, Set.of(new BlockPos(4, 65, 0)), 5.0F, 0
            )
        );
        assertEquals(
            ZvsPathfindingMetrics.RejectionReason.OUT_OF_RANGE,
            PatheticMobPathfinding.rejectionReason(new WalkNodeEvaluator(), start, target, 4.0F, 0)
        );
    }

    @Test
    void metricsExposeAttemptsResultsAndRejections() {
        ZvsPathfindingMetrics.resetForTesting();
        ZvsPathfindingMetrics.attempt();
        ZvsPathfindingMetrics.reject(ZvsPathfindingMetrics.RejectionReason.VERTICAL);
        ZvsPathfindingMetrics.result(ZvsPathfindingMetrics.Result.DIRECT, 7, false);

        final ZvsPathfindingMetrics.Snapshot snapshot = ZvsPathfindingMetrics.snapshot();
        assertEquals(1L, snapshot.attempts());
        assertEquals(7L, snapshot.evaluations());
        assertEquals(1L, snapshot.results().get(ZvsPathfindingMetrics.Result.DIRECT));
        assertEquals(1L, snapshot.rejections().get(ZvsPathfindingMetrics.RejectionReason.VERTICAL));
    }

    @Test
    void handledSearchWithoutAPathMustNotFallThroughToVanilla() {
        final PatheticMobPathfinding.SearchResult notHandled =
            PatheticMobPathfinding.SearchResult.NOT_HANDLED;
        final PatheticMobPathfinding.SearchResult handledWithoutPath =
            PatheticMobPathfinding.SearchResult.handled(null);

        assertFalse(notHandled.handled());
        assertTrue(handledWithoutPath.handled());
        assertNull(handledWithoutPath.path());
    }

    @Test
    void sharedRouteCacheReusesSuffixesAndInvalidatesTogether() {
        final ZvsSharedPathCache cache = new ZvsSharedPathCache();
        final Node first = walkableNode(0, 64, 0);
        final Node second = walkableNode(1, 64, 0);
        second.cameFrom = first;
        second.walkedDistance = 1.0F;
        final Node third = walkableNode(2, 64, 0);
        third.cameFrom = second;
        third.walkedDistance = 2.0F;
        final BlockPos target = new BlockPos(2, 64, 0);

        cache.record(17, new net.minecraft.world.level.pathfinder.Path(List.of(first, second, third), target, true), 0);

        assertEquals(2, cache.sizeForTesting());
        final net.minecraft.world.level.pathfinder.Path suffix = cache.find(17, walkableNode(1, 64, 0), target, 0, 8.0F);
        assertNotNull(suffix);
        assertEquals(2, suffix.getNodeCount());
        assertEquals(target, suffix.getEndNode().asBlockPos());

        final long previousRevision = cache.revision();
        cache.invalidate();
        assertEquals(previousRevision + 1, cache.revision());
        assertEquals(0, cache.sizeForTesting());
        assertNull(cache.find(17, walkableNode(1, 64, 0), target, 0, 8.0F));
    }

    private static Node walkableNode(final int x, final int y, final int z) {
        final Node node = new Node(x, y, z);
        node.type = PathType.WALKABLE;
        node.costMalus = 0.0F;
        return node;
    }
}
