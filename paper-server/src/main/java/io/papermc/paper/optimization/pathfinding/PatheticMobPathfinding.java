package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.pathing.INeighborStrategy;
import de.bsommerfeld.pathetic.api.pathing.Pathfinder;
import de.bsommerfeld.pathetic.api.pathing.configuration.PathfinderConfiguration;
import de.bsommerfeld.pathetic.api.pathing.result.PathfinderResult;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.api.wrapper.PathVector;
import de.bsommerfeld.pathetic.engine.factory.AStarPathfinderFactory;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.optimization.pathfinding.ZvsPathfindingMetrics.RejectionReason;
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
import org.jspecify.annotations.NullMarked;
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
@NullMarked
public final class PatheticMobPathfinding {
    private static final AStarPathfinderFactory FACTORY = new AStarPathfinderFactory();
    private static final List<PathVector> HORIZONTAL_CARDINAL_OFFSETS = List.of(
        PathVector.of(1.0D, 0.0D, 0.0D),
        PathVector.of(-1.0D, 0.0D, 0.0D),
        PathVector.of(0.0D, 0.0D, 1.0D),
        PathVector.of(0.0D, 0.0D, -1.0D)
    );
    private static final INeighborStrategy HORIZONTAL_CARDINAL = () -> HORIZONTAL_CARDINAL_OFFSETS;
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
        if (!GlobalConfiguration.get().optimizations.patheticMobPathfinding.enabled) {
            return null;
        }

        final RejectionReason rejectionReason = rejectionReason(
            nodeEvaluator, start, targets, maxRange, accuracy
        );
        if (rejectionReason != null) {
            ZvsPathfindingMetrics.reject(rejectionReason);
            return null;
        }

        final BlockPos target = targets.iterator().next();

        final int maxPathLength = Math.max(1, (int) Math.ceil(maxRange));
        final int evaluationBudget = patheticEvaluationBudget(maxVisitedNodes, searchDepthMultiplier);
        if (evaluationBudget < 1) {
            ZvsPathfindingMetrics.reject(RejectionReason.EVALUATION_BUDGET);
            return null;
        }
        ZvsPathfindingMetrics.attempt();
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
        return rejectionReason(nodeEvaluator, start, targets, maxRange, accuracy) == null;
    }

    @Nullable
    static RejectionReason rejectionReason(
        final NodeEvaluator nodeEvaluator,
        final Node start,
        final Set<BlockPos> targets,
        final float maxRange,
        final int accuracy
    ) {
        if (nodeEvaluator.getClass() != WalkNodeEvaluator.class) {
            return RejectionReason.EVALUATOR;
        }
        if (targets.size() != 1) {
            return RejectionReason.TARGET_COUNT;
        }
        if (accuracy < 0 || accuracy > 1) {
            return RejectionReason.ACCURACY;
        }
        if (!PatheticNavigationPointProvider.isSupportedFlatType(start.type)) {
            return RejectionReason.START_TYPE;
        }
        if (!Float.isFinite(start.costMalus) || start.costMalus < 0.0F) {
            return RejectionReason.START_MALUS;
        }
        if (!Float.isFinite(maxRange) || maxRange <= 0.0F) {
            return RejectionReason.RANGE;
        }

        final BlockPos target = targets.iterator().next();
        if (start.y != target.getY()) {
            return RejectionReason.VERTICAL;
        }
        if (manhattanDistance(start, target) <= accuracy) {
            return RejectionReason.ALREADY_REACHED;
        }
        if (start.distanceTo(target) >= maxRange + accuracy) {
            return RejectionReason.OUT_OF_RANGE;
        }
        return null;
    }

    static int patheticEvaluationBudget(final int maxVisitedNodes, final float searchDepthMultiplier) {
        final float vanillaBudget = maxVisitedNodes * searchDepthMultiplier;
        if (!Float.isFinite(vanillaBudget) || vanillaBudget < 3.0F) {
            return 0;
        }

        final int vanillaExpansionBudget = Math.max(0, (int) vanillaBudget - 1);
        // A Pathetic work unit is one previously unseen world position, while one
        // vanilla expansion can inspect several neighbours. Halving both sides
        // starves the A* phase after the direct/dogleg probes and can force the
        // complete vanilla search to run afterward. Keep the same outer vanilla
        // expansion ceiling, capped against hostile multipliers.
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
                recordResult(ZvsPathfindingMetrics.Result.DIRECT, context);
                return directPath;
            }
            if (context.evaluationBudget().exhausted()) {
                recordResult(ZvsPathfindingMetrics.Result.FALLBACK, context);
                return null;
            }
        }

        for (final BlockPos endpoint : endpoints) {
            final Path detourPath = findOrthogonalDetourGroundPath(
                provider, context, start, endpoint, target, maxRange, maxPathLength
            );
            if (detourPath != null) {
                recordResult(ZvsPathfindingMetrics.Result.DETOUR, context);
                return detourPath;
            }
            if (context.evaluationBudget().exhausted()) {
                recordResult(ZvsPathfindingMetrics.Result.FALLBACK, context);
                return null;
            }
        }

        int remainingAStarIterations = context.evaluationBudget().remaining();
        for (int index = 0; index < endpoints.size() && remainingAStarIterations > 0; index++) {
            final BlockPos endpoint = endpoints.get(index);
            final PatheticNavigationPoint endpointPoint = provider.pointAt(
                PathPosition.of(endpoint.getX(), endpoint.getY(), endpoint.getZ()), context
            );
            if (!endpointPoint.isTraversable()) {
                if (context.evaluationBudget().exhausted()) {
                    recordResult(ZvsPathfindingMetrics.Result.FALLBACK, context);
                    return null;
                }
                continue;
            }

            final int candidatesLeft = endpoints.size() - index;
            final int iterationShare = Math.max(1, remainingAStarIterations / candidatesLeft);
            final Path path = findAStarGroundPath(
                provider,
                context,
                start,
                endpoint,
                target,
                accuracy,
                maxRange,
                maxPathLength,
                iterationShare
            );
            if (path != null) {
                recordResult(ZvsPathfindingMetrics.Result.ASTAR, context);
                return path;
            }
            if (context.evaluationBudget().exhausted()) {
                recordResult(ZvsPathfindingMetrics.Result.FALLBACK, context);
                return null;
            }
            remainingAStarIterations -= iterationShare;
        }
        recordResult(ZvsPathfindingMetrics.Result.FALLBACK, context);
        return null;
    }

    private static void recordResult(
        final ZvsPathfindingMetrics.Result result,
        final PatheticEnvironmentContext context
    ) {
        ZvsPathfindingMetrics.result(
            result,
            context.evaluationBudget().consumed(),
            context.evaluationBudget().exhausted()
        );
    }

    @Nullable
    private static Path findAStarGroundPath(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final Node start,
        final BlockPos endpoint,
        final BlockPos target,
        final int accuracy,
        final float maxRange,
        final int maxPathLength,
        final int maxIterations
    ) {
        final PathfinderConfiguration configuration = PathfinderConfiguration.builder()
            .provider(provider)
            .async(false)
            .fallback(false)
            .maxIterations(maxIterations)
            .maxLength(maxPathLength)
            .neighborStrategy(HORIZONTAL_CARDINAL)
            .validationProcessors(List.of(evaluation -> {
                final PatheticNavigationPoint point = provider.pointAt(evaluation.getCurrentPathPosition(), context);
                return point.isTraversable();
            }))
            .costProcessor(List.of(evaluation -> {
                final PatheticNavigationPoint point = provider.pointAt(evaluation.getCurrentPathPosition(), context);
                return point.cost();
            }))
            .build();

        final Pathfinder pathfinder = FACTORY.createPathfinder(configuration);
        final PathPosition startPosition = PathPosition.of(start.x, start.y, start.z);
        final PathPosition endPosition = PathPosition.of(endpoint.getX(), endpoint.getY(), endpoint.getZ());
        final PathfinderResult result = pathfinder.findPath(startPosition, endPosition, context).resultBlocking();
        if (context.evaluationBudget().exhausted()
            || result == null
            || !result.successful()
            || result.getPath() == null
            || result.getPath().length() <= 1) {
            return null;
        }

        return toMinecraftPath(result.getPath(), provider, context, start, endpoint, target, accuracy, maxRange);
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
            final Path xDetour = findZDetourPath(provider, context, start, endpoint, pathTarget, maxRange, maxPathLength);
            return xDetour != null
                ? xDetour
                : findXDetourPath(provider, context, start, endpoint, pathTarget, maxRange, maxPathLength);
        }

        final Path zDetour = findXDetourPath(provider, context, start, endpoint, pathTarget, maxRange, maxPathLength);
        return zDetour != null
            ? zDetour
            : findZDetourPath(provider, context, start, endpoint, pathTarget, maxRange, maxPathLength);
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
        for (int offset = 1; offset <= MAX_FAST_DETOUR_OFFSET; offset++) {
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
        }
        return null;
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
        for (int offset = 1; offset <= MAX_FAST_DETOUR_OFFSET; offset++) {
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
        }
        return null;
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

    @Nullable
    private static Path toMinecraftPath(
        final de.bsommerfeld.pathetic.api.pathing.result.Path patheticPath,
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final Node start,
        final BlockPos endpoint,
        final BlockPos target,
        final int accuracy,
        final float maxRange
    ) {
        final List<Node> nodes = new ArrayList<>(boundedInitialCapacity(patheticPath.length()));
        nodes.add(start);
        Node previous = start;
        for (final PathPosition position : patheticPath) {
            final int x = position.getFlooredX();
            final int y = position.getFlooredY();
            final int z = position.getFlooredZ();
            if (previous.x == x && previous.y == y && previous.z == z) {
                continue;
            }
            if (y != previous.y || Math.abs(x - previous.x) + Math.abs(z - previous.z) != 1) {
                return null;
            }

            final PatheticNavigationPoint point = provider.pointAt(position, context);
            if (!point.isTraversable()) {
                return null;
            }
            final Node node = nodeForPoint(x, y, z, point, previous);
            if (node.walkedDistance >= maxRange) {
                return null;
            }
            nodes.add(node);
            previous = node;
        }

        if (nodes.size() <= 1
            || previous.x != endpoint.getX()
            || previous.y != endpoint.getY()
            || previous.z != endpoint.getZ()
            || manhattanDistance(previous, target) > accuracy) {
            return null;
        }
        return new Path(nodes, target, true);
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
