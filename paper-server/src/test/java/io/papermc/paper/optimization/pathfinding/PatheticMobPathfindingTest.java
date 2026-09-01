package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.pathing.processing.Cost;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void blockedAndFluidStartsFallBackWhileSupportedHazardsStayIn2D() {
        final Set<BlockPos> target = Set.of(new BlockPos(4, 64, 0));
        final Node blocked = walkableNode(0, 64, 0);
        blocked.type = PathType.BLOCKED;
        final Node fluid = walkableNode(0, 64, 0);
        fluid.type = PathType.WATER;
        final Node hazard = walkableNode(0, 64, 0);
        hazard.type = PathType.DAMAGING;

        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), blocked, target, 8.0F, 0));
        assertFalse(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), fluid, target, 8.0F, 0));
        assertTrue(PatheticMobPathfinding.isEligible(new WalkNodeEvaluator(), hazard, target, 8.0F, 0));
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
    void blockedTwoDimensionalProgressIsReturnedAsAnUnreachedPartialPath() {
        final Node start = walkableNode(0, 64, 0);
        final Node progress = walkableNode(1, 64, 0);
        progress.cameFrom = start;
        progress.walkedDistance = 1.0F;

        final net.minecraft.world.level.pathfinder.Path partial = PatheticMobPathfinding.partialPath(
            List.of(start, progress), new BlockPos(4, 64, 0)
        );

        assertNotNull(partial);
        assertFalse(partial.canReach());
        assertEquals(progress.asBlockPos(), partial.getEndNode().asBlockPos());
    }

    @Test
    void successfulRoutesPopulateOneReverseFlowFieldAndInvalidateTogether() {
        final ZvsSharedPathCache cache = new ZvsSharedPathCache();
        final ZvsSharedPathCache.PathProfile profile = ZvsSharedPathCache.syntheticProfile(17);
        final Node first = walkableNode(0, 64, 0);
        final Node second = walkableNode(1, 64, 0);
        second.cameFrom = first;
        second.walkedDistance = 1.0F;
        final Node third = walkableNode(2, 64, 0);
        third.cameFrom = second;
        third.walkedDistance = 2.0F;
        final BlockPos target = new BlockPos(2, 64, 0);

        for (int request = 0; request < 4; request++) {
            assertNull(cache.findFlowField(profile, first, target, 0, 8.0F, 8));
        }
        cache.recordFlowField(
            profile, new net.minecraft.world.level.pathfinder.Path(List.of(first, second, third), target, true), 0, 8.0F
        );

        assertEquals(1, cache.sizeForTesting());
        final net.minecraft.world.level.pathfinder.Path suffix = cache.findFlowField(
            profile, walkableNode(1, 64, 0), target, 0, 8.0F, 8
        );
        assertNotNull(suffix);
        assertEquals(2, suffix.getNodeCount());
        assertEquals(target, suffix.getEndNode().asBlockPos());

        final long previousRevision = cache.revisionForTesting();
        cache.invalidate();
        assertEquals(previousRevision + 1, cache.revisionForTesting());
        assertEquals(0, cache.sizeForTesting());
        assertNull(cache.findFlowField(profile, walkableNode(1, 64, 0), target, 0, 8.0F, 8));
    }

    @Test
    void sectionInvalidationKeepsUnrelatedRoutesAndRejectsOverlappingRoutes() {
        final ZvsSharedPathCache cache = new ZvsSharedPathCache();
        final ZvsSharedPathCache.PathProfile profile = ZvsSharedPathCache.syntheticProfile(17);
        final Node first = walkableNode(0, 64, 0);
        final Node second = walkableNode(1, 64, 0);
        second.cameFrom = first;
        second.walkedDistance = 1.0F;
        final Node third = walkableNode(2, 64, 0);
        third.cameFrom = second;
        third.walkedDistance = 2.0F;
        final BlockPos target = new BlockPos(2, 64, 0);
        for (int request = 0; request < 4; request++) {
            assertNull(cache.findFlowField(profile, first, target, 0, 8.0F, 8));
        }
        cache.recordFlowField(
            profile, new net.minecraft.world.level.pathfinder.Path(List.of(first, second, third), target, true), 0, 8.0F
        );

        cache.invalidate(new BlockPos(200, 64, 200));
        assertNotNull(cache.findFlowField(profile, walkableNode(1, 64, 0), target, 0, 8.0F, 8));

        cache.invalidate(new BlockPos(1, 64, 0));
        assertNull(cache.findFlowField(profile, walkableNode(1, 64, 0), target, 0, 8.0F, 8));
    }

    @Test
    void sharedCellCacheReusesEvaluationUntilARelevantSectionChanges() {
        final ZvsSharedPathCache cache = new ZvsSharedPathCache();
        final ZvsSharedPathCache.SharedCellCache cells = cache.sharedCells(ZvsSharedPathCache.syntheticProfile(31), 16);
        final long position = BlockPos.asLong(1, 64, 1);
        final PatheticNavigationPoint point = new PatheticNavigationPoint(true, Cost.ZERO, PathType.WALKABLE, 0.0F);

        cells.record(position, point);
        assertSame(point, cells.find(position));

        cache.invalidate(new BlockPos(200, 64, 200));
        assertSame(point, cells.find(position));

        cache.invalidate(new BlockPos(1, 64, 1));
        assertNull(cells.find(position));
    }

    @Test
    void sharedCellCacheUsesABoundedGeneration() {
        final ZvsSharedPathCache cache = new ZvsSharedPathCache();
        final ZvsSharedPathCache.SharedCellCache cells = cache.sharedCells(ZvsSharedPathCache.syntheticProfile(32), 1);
        final PatheticNavigationPoint point = new PatheticNavigationPoint(true, Cost.ZERO, PathType.WALKABLE, 0.0F);
        final long first = BlockPos.asLong(1, 64, 1);
        final long second = BlockPos.asLong(2, 64, 2);

        cells.record(first, point);
        cells.record(second, point);

        assertNull(cells.find(first));
        assertSame(point, cells.find(second));
    }

    @Test
    void structuralProfileIsReusedAndInvalidatedByFloorOrMalusRevision() {
        final Mob mob = mock(Mob.class);
        final AtomicReference<Object> cached = new AtomicReference<>();
        final AtomicInteger revision = new AtomicInteger();
        when(mob.getType()).thenReturn(mock(EntityType.class));
        when(mob.getBbWidth()).thenReturn(0.6F);
        when(mob.getBbHeight()).thenReturn(1.95F);
        when(mob.zvsPathfindingMalusRevision()).thenAnswer(ignored -> revision.get());
        when(mob.zvsPathProfileCache()).thenAnswer(ignored -> cached.get());
        doAnswer(invocation -> {
            cached.set(invocation.getArgument(0));
            return null;
        }).when(mob).zvsPathProfileCache(any());
        final WalkNodeEvaluator evaluator = new WalkNodeEvaluator();

        final ZvsSharedPathCache.PathProfile first = ZvsSharedPathCache.profile(mob, evaluator, 64.0D);
        assertSame(first, ZvsSharedPathCache.profile(mob, evaluator, 64.0D));
        assertNotSame(first, ZvsSharedPathCache.profile(mob, evaluator, 64.5D));
        revision.incrementAndGet();
        assertNotSame(first, ZvsSharedPathCache.profile(mob, evaluator, 64.0D));
    }

    @Test
    void reverseFlowFieldBuildsAnOrderedSuffixAndRejectsCycles() {
        final BlockPos target = new BlockPos(2, 64, 0);
        final long startPosition = BlockPos.asLong(0, 64, 0);
        final long middlePosition = BlockPos.asLong(1, 64, 0);
        final long targetPosition = target.asLong();
        final ZvsReverseFlowFieldCache.FlowField field = new ZvsReverseFlowFieldCache.FlowField(Map.of(
            startPosition, new ZvsReverseFlowFieldCache.Cell(middlePosition, 2.0D, PathType.WALKABLE, 0.0F, false),
            middlePosition, new ZvsReverseFlowFieldCache.Cell(targetPosition, 1.0D, PathType.WALKABLE, 0.0F, false),
            targetPosition, new ZvsReverseFlowFieldCache.Cell(targetPosition, 0.0D, PathType.WALKABLE, 0.0F, true)
        ));

        final net.minecraft.world.level.pathfinder.Path path = field.toPath(walkableNode(0, 64, 0), target, 8.0F, 8);
        assertNotNull(path);
        assertEquals(3, path.getNodeCount());
        assertEquals(target, path.getEndNode().asBlockPos());
        assertTrue(path.canReach());

        final ZvsReverseFlowFieldCache.FlowField partial = new ZvsReverseFlowFieldCache.FlowField(Map.of(
            startPosition, new ZvsReverseFlowFieldCache.Cell(middlePosition, 1.0D, PathType.WALKABLE, 0.0F, false),
            middlePosition, new ZvsReverseFlowFieldCache.Cell(middlePosition, 0.0D, PathType.WALKABLE, 0.0F, false)
        ));
        final net.minecraft.world.level.pathfinder.Path partialPath = partial.toPath(
            walkableNode(0, 64, 0), target, 8.0F, 8
        );
        assertNotNull(partialPath);
        assertFalse(partialPath.canReach());

        final ZvsReverseFlowFieldCache.FlowField cycle = new ZvsReverseFlowFieldCache.FlowField(Map.of(
            startPosition, new ZvsReverseFlowFieldCache.Cell(middlePosition, 2.0D, PathType.WALKABLE, 0.0F, false),
            middlePosition, new ZvsReverseFlowFieldCache.Cell(startPosition, 1.0D, PathType.WALKABLE, 0.0F, false)
        ));
        assertNull(cycle.toPath(walkableNode(0, 64, 0), target, 8.0F, 8));
    }

    @Test
    void reverseFlowFieldSeparatesDemandAcrossCallerRangeLimits() {
        final ZvsSharedPathCache cache = new ZvsSharedPathCache();
        final ZvsSharedPathCache.PathProfile profile = ZvsSharedPathCache.syntheticProfile(41);
        final BlockPos target = new BlockPos(2, 64, 0);
        final Node start = walkableNode(0, 64, 0);
        for (int request = 0; request < 4; request++) {
            assertNull(cache.findFlowField(profile, start, target, 0, 12.0F, 12));
        }
        cache.recordFlowField(
            profile,
            new net.minecraft.world.level.pathfinder.Path(
                List.of(start, walkableNode(1, 64, 0), walkableNode(2, 64, 0)), target, true
            ),
            0,
            12.0F
        );

        assertNull(cache.findFlowField(profile, start, target, 0, 8.0F, 8));
        assertNotNull(cache.findFlowField(profile, start, target, 0, 12.0F, 12));
    }

    @Test
    void concurrentSuccessfulRoutesMergeIntoOneFieldWithoutWorldEvaluation() throws Exception {
        final BlockPos target = new BlockPos(4, 64, 0);
        final ZvsReverseFlowFieldCache cache = new ZvsReverseFlowFieldCache();
        final ZvsSharedPathCache.PathProfile profile = ZvsSharedPathCache.syntheticProfile(17);
        final Node start = walkableNode(0, 64, 0);
        final Node one = walkableNode(1, 64, 0);
        final Node two = walkableNode(2, 64, 0);
        final Node three = walkableNode(3, 64, 0);
        final Node end = walkableNode(4, 64, 0);
        final net.minecraft.world.level.pathfinder.Path route = new net.minecraft.world.level.pathfinder.Path(
            List.of(start, one, two, three, end), target, true
        );

        for (int request = 0; request < 4; request++) {
            assertNull(cache.find(profile, start, target, 0, 8.0F, 8, snapshot -> true));
        }
        CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> cache.record(profile, route, 0, 8.0F, PatheticMobPathfindingTest::snapshot)),
            CompletableFuture.runAsync(() -> cache.record(profile, route, 0, 8.0F, PatheticMobPathfindingTest::snapshot))
        ).get();

        assertEquals(1, cache.sizeForTesting());
        assertNotNull(cache.find(profile, walkableNode(1, 64, 0), target, 0, 8.0F, 8, snapshot -> true));
    }

    private static ZvsSharedPathCache.SectionSnapshot snapshot(final long[] sections) {
        return new ZvsSharedPathCache.SectionSnapshot(sections, new long[sections.length]);
    }

    @Test
    void seededStartReusesTheFloorEvaluationWithoutSpendingBudget() {
        final Node start = walkableNode(0, 64, 0);
        final PatheticNavigationPointProvider provider = new PatheticNavigationPointProvider();
        final PatheticEnvironmentContext context = testContext(1);
        provider.seed(start);

        final PatheticNavigationPoint point = provider.pointAt(PathPosition.of(0, 64, 0), context);

        assertTrue(point.isTraversable());
        assertEquals(1, context.evaluationBudget().remaining());
    }

    private static PatheticEnvironmentContext testContext(final int budget) {
        return new PatheticEnvironmentContext(
            mock(PathNavigationRegion.class),
            mock(Mob.class),
            new WalkNodeEvaluator(),
            mock(PathfindingContext.class),
            64.0D,
            new PatheticEvaluationBudget(budget)
        );
    }

    private static Node walkableNode(final int x, final int y, final int z) {
        final Node node = new Node(x, y, z);
        node.type = PathType.WALKABLE;
        node.costMalus = 0.0F;
        return node;
    }
}
