package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.optimization.MobOptRuntimeMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * Paper integration for the Pathetic pathfinding engine.
 *
 * <p>Only exact vanilla {@link WalkNodeEvaluator} single-target, horizontal
 * searches are eligible. Accuracy zero targets the requested block; accuracy
 * one may finish on a horizontal Manhattan neighbour. Unsupported or
 * unsuccessful searches return {@code null}, allowing the existing Paper
 * pathfinder to continue with the already-prepared evaluator.</p>
 */
public final class PatheticMobPathfinding {
    private static final int MAX_FAST_DETOUR_OFFSET = 8;
    private static final int MAX_PATHETIC_WORK_UNITS = 100_000;

    private PatheticMobPathfinding() {
    }

    /**
     * Attempts the optimized path after the caller has prepared the evaluator and
     * obtained its vanilla start node.
     */
    @Nullable
    public static Path tryFindPath(
        final NodeEvaluator nodeEvaluator,
        final PathfindingContext pathfindingContext,
        final PathNavigationRegion region,
        final Mob mob,
        final Node start,
        final Set<BlockPos> targets,
        final float maxRange,
        final int accuracy,
        final int maxVisitedNodes,
        final float searchDepthMultiplier
    ) {
        if (!GlobalConfiguration.get().optimizations.patheticMobPathfinding.enabled
            || !isEligible(nodeEvaluator, start, targets, maxRange, accuracy)) {
            return null;
        }

        final BlockPos target = targets.iterator().next();

        final int maxPathLength = Math.max(1, (int) Math.ceil(maxRange));
        final int evaluationBudget = patheticEvaluationBudget(maxVisitedNodes, searchDepthMultiplier);
        if (evaluationBudget < 1) {
            return null;
        }
        MobOptRuntimeMetrics.pathfindingAttempt();
        return findGroundPath(
            region,
            mob,
            (WalkNodeEvaluator) nodeEvaluator,
            pathfindingContext,
            start,
            target,
            accuracy,
            maxRange,
            maxPathLength,
            evaluationBudget
        );
    }

    static boolean isEligible(
        final NodeEvaluator nodeEvaluator,
        final Node start,
        final Set<BlockPos> targets,
        final float maxRange,
        final int accuracy
    ) {
        if (nodeEvaluator.getClass() != WalkNodeEvaluator.class
            || targets.size() != 1
            || accuracy < 0
            || accuracy > 1
            || !PatheticNavigationPointProvider.isSupportedFlatType(start.type)
            || !Float.isFinite(start.costMalus)
            || start.costMalus < 0.0F
            || !Float.isFinite(maxRange)
            || maxRange <= 0.0F) {
            return false;
        }

        final BlockPos target = targets.iterator().next();
        return start.y == target.getY()
            && manhattanDistance(start, target) > accuracy
            && start.distanceTo(target) < maxRange + accuracy;
    }

    static int patheticEvaluationBudget(final int maxVisitedNodes, final float searchDepthMultiplier) {
        final float vanillaBudget = maxVisitedNodes * searchDepthMultiplier;
        if (!Float.isFinite(vanillaBudget) || vanillaBudget < 3.0F) {
            return 0;
        }

        final int vanillaExpansionBudget = Math.max(0, (int) vanillaBudget - 1);
        // A fast-path work unit is one previously unseen world position. Keep
        // probes inside vanilla's outer expansion ceiling and cap hostile
        // multipliers before falling through to the original Paper search.
        return Math.min(MAX_PATHETIC_WORK_UNITS, vanillaExpansionBudget);
    }

    @Nullable
    static Path findGroundPath(
        final PathNavigationRegion region,
        final Mob mob,
        final WalkNodeEvaluator nodeEvaluator,
        final PathfindingContext pathfindingContext,
        final Node start,
        final BlockPos target,
        final int accuracy,
        final float maxRange,
        final int maxPathLength,
        final int evaluationBudget
    ) {
        final PatheticNavigationPointProvider provider = new PatheticNavigationPointProvider();
        final PatheticEnvironmentContext context = new PatheticEnvironmentContext(
            region, mob, nodeEvaluator, pathfindingContext, start, evaluationBudget
        );
        final List<BlockPos> endpoints = targetCandidates(start, target, accuracy);
        for (final BlockPos endpoint : endpoints) {
            final Path directPath = findDirectGroundPath(provider, context, start, endpoint, target, maxRange);
            if (directPath != null) {
                recordResult(MobOptRuntimeMetrics.PathfindingResult.DIRECT, context);
                return directPath;
            }
            if (context.evaluationBudget().exhausted()) {
                recordResult(MobOptRuntimeMetrics.PathfindingResult.FALLBACK, context);
                return null;
            }
        }

        // Accuracy-one direct paths keep all five legal endpoints. The more
        // expensive dogleg probe is intentionally limited to the nearest one;
        // a miss is only an optimization miss and Paper remains authoritative.
        final BlockPos detourEndpoint = endpoints.getFirst();
        final Path detourPath = findOrthogonalDetourGroundPath(
            provider, context, start, detourEndpoint, target, maxRange, maxPathLength
        );
        if (detourPath != null) {
            recordResult(MobOptRuntimeMetrics.PathfindingResult.DETOUR, context);
            return detourPath;
        }
        if (context.evaluationBudget().exhausted()) {
            recordResult(MobOptRuntimeMetrics.PathfindingResult.FALLBACK, context);
            return null;
        }

        // Full Pathetic A* followed by vanilla fallback doubles the expensive
        // work on complex terrain and regresses high-entity workloads. The
        // optimization is deliberately bounded to direct and short dogleg
        // paths; complex requests continue immediately in Paper's prepared
        // PathFinder so plugin-visible behavior remains unchanged.
        recordResult(MobOptRuntimeMetrics.PathfindingResult.FALLBACK, context);
        return null;
    }

    private static void recordResult(
        final MobOptRuntimeMetrics.PathfindingResult result,
        final PatheticEnvironmentContext context
    ) {
        MobOptRuntimeMetrics.pathfindingResult(
            result,
            context.evaluationBudget().consumed(),
            context.evaluationBudget().exhausted()
        );
    }

    static List<BlockPos> targetCandidates(final Node start, final BlockPos target, final int accuracy) {
        final List<BlockPos> candidates = new ArrayList<>(accuracy == 0 ? 1 : 5);
        candidates.add(target);
        if (accuracy == 1) {
            candidates.add(target.offset(1, 0, 0));
            candidates.add(target.offset(-1, 0, 0));
            candidates.add(target.offset(0, 0, 1));
            candidates.add(target.offset(0, 0, -1));
        }
        candidates.sort(Comparator.comparingDouble(start::distanceTo));
        return candidates;
    }

    @Nullable
    private static Path findDirectGroundPath(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final Node start,
        final BlockPos endpoint,
        final BlockPos pathTarget,
        final float maxRange
    ) {
        final int deltaX = endpoint.getX() - start.x;
        final int deltaZ = endpoint.getZ() - start.z;
        final int steps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));
        if (steps == 0) {
            return null;
        }

        final List<Node> nodes = new ArrayList<>(boundedInitialCapacity(steps + 1));
        nodes.add(start);
        Node previous = start;
        for (int i = 1; i <= steps; i++) {
            final int x = start.x + Math.round((float) deltaX * (float) i / (float) steps);
            final int z = start.z + Math.round((float) deltaZ * (float) i / (float) steps);
            final PatheticNavigationPoint point = provider.pointAt(PathPosition.of(x, start.y, z), context);
            if (!isZeroCost(point)) {
                return null;
            }

            final boolean diagonal = previous.x != x && previous.z != z;
            if (diagonal && (!isSafeDiagonalSide(provider, context, previous.x, start.y, z)
                || !isSafeDiagonalSide(provider, context, x, start.y, previous.z)
                || point.pathType() == PathType.WALKABLE_DOOR)) {
                return null;
            }

            final Node node = nodeForPoint(x, start.y, z, point, previous);
            if (node.walkedDistance >= maxRange) {
                return null;
            }
            nodes.add(node);
            previous = node;
        }

        return new Path(nodes, pathTarget, true);
    }

    private static boolean isSafeDiagonalSide(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final int x,
        final int y,
        final int z
    ) {
        final PatheticNavigationPoint point = provider.pointAt(PathPosition.of(x, y, z), context);
        return isZeroCost(point) && point.pathType() != PathType.WALKABLE_DOOR;
    }

    @Nullable
    private static Path findOrthogonalDetourGroundPath(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final Node start,
        final BlockPos endpoint,
        final BlockPos pathTarget,
        final float maxRange,
        final int maxPathLength
    ) {
        final int deltaX = endpoint.getX() - start.x;
        final int deltaZ = endpoint.getZ() - start.z;
        if (deltaX == 0 && deltaZ == 0) {
            return null;
        }

        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return findZDetourPath(provider, context, start, endpoint, pathTarget, maxRange, maxPathLength);
        }

        return findXDetourPath(provider, context, start, endpoint, pathTarget, maxRange, maxPathLength);
    }

    @Nullable
    private static Path findZDetourPath(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final Node start,
        final BlockPos endpoint,
        final BlockPos pathTarget,
        final float maxRange,
        final int maxPathLength
    ) {
        Path edgePath = findWaypointGroundPath(
            provider,
            context,
            start,
            endpoint,
            pathTarget,
            maxRange,
            maxPathLength,
            new BlockPos(start.x, start.y, start.z + MAX_FAST_DETOUR_OFFSET),
            new BlockPos(endpoint.getX(), endpoint.getY(), start.z + MAX_FAST_DETOUR_OFFSET)
        );
        if (edgePath == null) {
            if (context.evaluationBudget().exhausted()) {
                return null;
            }
            edgePath = findWaypointGroundPath(
                provider,
                context,
                start,
                endpoint,
                pathTarget,
                maxRange,
                maxPathLength,
                new BlockPos(start.x, start.y, start.z - MAX_FAST_DETOUR_OFFSET),
                new BlockPos(endpoint.getX(), endpoint.getY(), start.z - MAX_FAST_DETOUR_OFFSET)
            );
            if (edgePath == null) {
                return null;
            }
        }

        for (int offset = 1; offset < MAX_FAST_DETOUR_OFFSET; offset++) {
            final Path positive = findWaypointGroundPath(
                provider,
                context,
                start,
                endpoint,
                pathTarget,
                maxRange,
                maxPathLength,
                new BlockPos(start.x, start.y, start.z + offset),
                new BlockPos(endpoint.getX(), endpoint.getY(), start.z + offset)
            );
            if (positive != null) {
                return positive;
            }
            if (context.evaluationBudget().exhausted()) {
                return edgePath;
            }

            final Path negative = findWaypointGroundPath(
                provider,
                context,
                start,
                endpoint,
                pathTarget,
                maxRange,
                maxPathLength,
                new BlockPos(start.x, start.y, start.z - offset),
                new BlockPos(endpoint.getX(), endpoint.getY(), start.z - offset)
            );
            if (negative != null) {
                return negative;
            }
            if (context.evaluationBudget().exhausted()) {
                return edgePath;
            }
        }
        return edgePath;
    }

    @Nullable
    private static Path findXDetourPath(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final Node start,
        final BlockPos endpoint,
        final BlockPos pathTarget,
        final float maxRange,
        final int maxPathLength
    ) {
        Path edgePath = findWaypointGroundPath(
            provider,
            context,
            start,
            endpoint,
            pathTarget,
            maxRange,
            maxPathLength,
            new BlockPos(start.x + MAX_FAST_DETOUR_OFFSET, start.y, start.z),
            new BlockPos(start.x + MAX_FAST_DETOUR_OFFSET, endpoint.getY(), endpoint.getZ())
        );
        if (edgePath == null) {
            if (context.evaluationBudget().exhausted()) {
                return null;
            }
            edgePath = findWaypointGroundPath(
                provider,
                context,
                start,
                endpoint,
                pathTarget,
                maxRange,
                maxPathLength,
                new BlockPos(start.x - MAX_FAST_DETOUR_OFFSET, start.y, start.z),
                new BlockPos(start.x - MAX_FAST_DETOUR_OFFSET, endpoint.getY(), endpoint.getZ())
            );
            if (edgePath == null) {
                return null;
            }
        }

        for (int offset = 1; offset < MAX_FAST_DETOUR_OFFSET; offset++) {
            final Path positive = findWaypointGroundPath(
                provider,
                context,
                start,
                endpoint,
                pathTarget,
                maxRange,
                maxPathLength,
                new BlockPos(start.x + offset, start.y, start.z),
                new BlockPos(start.x + offset, endpoint.getY(), endpoint.getZ())
            );
            if (positive != null) {
                return positive;
            }
            if (context.evaluationBudget().exhausted()) {
                return edgePath;
            }

            final Path negative = findWaypointGroundPath(
                provider,
                context,
                start,
                endpoint,
                pathTarget,
                maxRange,
                maxPathLength,
                new BlockPos(start.x - offset, start.y, start.z),
                new BlockPos(start.x - offset, endpoint.getY(), endpoint.getZ())
            );
            if (negative != null) {
                return negative;
            }
            if (context.evaluationBudget().exhausted()) {
                return edgePath;
            }
        }
        return edgePath;
    }

    @Nullable
    private static Path findWaypointGroundPath(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final Node start,
        final BlockPos endpoint,
        final BlockPos pathTarget,
        final float maxRange,
        final int maxPathLength,
        final BlockPos firstWaypoint,
        final BlockPos secondWaypoint
    ) {
        final List<Node> nodes = new ArrayList<>(boundedInitialCapacity(maxPathLength + 1));
        nodes.add(start);
        Node previous = appendClearSegment(provider, context, nodes, start, start.asBlockPos(), firstWaypoint, maxRange);
        if (previous == null) {
            return null;
        }
        previous = appendClearSegment(provider, context, nodes, previous, firstWaypoint, secondWaypoint, maxRange);
        if (previous == null) {
            return null;
        }
        previous = appendClearSegment(provider, context, nodes, previous, secondWaypoint, endpoint, maxRange);
        if (previous == null || nodes.size() - 1 > maxPathLength) {
            return null;
        }
        return new Path(nodes, pathTarget, true);
    }

    @Nullable
    private static Node appendClearSegment(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final List<Node> nodes,
        final Node previousNode,
        final BlockPos from,
        final BlockPos to,
        final float maxRange
    ) {
        final int deltaX = Integer.compare(to.getX(), from.getX());
        final int deltaZ = Integer.compare(to.getZ(), from.getZ());
        if (deltaX != 0 && deltaZ != 0) {
            return null;
        }

        Node previous = previousNode;
        final int steps = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        for (int i = 1; i <= steps; i++) {
            final int x = from.getX() + deltaX * i;
            final int z = from.getZ() + deltaZ * i;
            final PatheticNavigationPoint point = provider.pointAt(PathPosition.of(x, from.getY(), z), context);
            if (!isZeroCost(point)) {
                return null;
            }

            final Node node = nodeForPoint(x, from.getY(), z, point, previous);
            if (node.walkedDistance >= maxRange) {
                return null;
            }
            nodes.add(node);
            previous = node;
        }
        return previous;
    }

    static int manhattanDistance(final Node node, final BlockPos target) {
        return Math.abs(node.x - target.getX()) + Math.abs(node.y - target.getY()) + Math.abs(node.z - target.getZ());
    }

    private static boolean isZeroCost(final PatheticNavigationPoint point) {
        return point.isTraversable() && point.cost().value() <= 0.0D;
    }

    private static Node nodeForPoint(
        final int x,
        final int y,
        final int z,
        final PatheticNavigationPoint point,
        final Node previous
    ) {
        final Node node = new Node(x, y, z);
        node.type = point.pathType();
        node.costMalus = point.malus();
        node.cameFrom = previous;
        node.walkedDistance = previous.walkedDistance + previous.distanceTo(node);
        node.g = previous.g + previous.distanceTo(node) + Math.max(0.0F, node.costMalus);
        node.f = node.g;
        return node;
    }

    private static int boundedInitialCapacity(final int requested) {
        return Math.min(1024, Math.max(4, requested));
    }
}
