package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.optimization.pathfinding.ZvsPathfindingMetrics.RejectionReason;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
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
 * ineligible searches leave the existing Paper pathfinder in control. Once an
 * eligible request starts evaluating the world, its result is authoritative,
 * including a bounded no-path result; it must never trigger a second vanilla
 * search in the same call.</p>
 */
@NullMarked
public final class PatheticMobPathfinding {
    private static final int[] CARDINAL_X = {1, -1, 0, 0};
    private static final int[] CARDINAL_Z = {0, 0, 1, -1};
    private static final int MAX_FAST_DETOUR_OFFSET = 8;
    private static final int MAX_PATHETIC_WORK_UNITS = 100_000;

    private PatheticMobPathfinding() {
    }

    /**
     * Attempts the optimized path after the caller has prepared the evaluator and
     * obtained its vanilla start node.
     */
    public static SearchResult tryFindPath(
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
        final GlobalConfiguration.Optimizations.PatheticMobPathfinding configuration =
            GlobalConfiguration.get().optimizations.patheticMobPathfinding;
        if (!configuration.enabled) {
            return SearchResult.NOT_HANDLED;
        }
        if (!mob.entityTags().contains(configuration.markerTag)) {
            ZvsPathfindingMetrics.reject(RejectionReason.MARKER_TAG);
            return SearchResult.NOT_HANDLED;
        }

        final RejectionReason rejectionReason = rejectionReason(
            nodeEvaluator, start, targets, maxRange, accuracy
        );
        if (rejectionReason != null) {
            ZvsPathfindingMetrics.reject(rejectionReason);
            return SearchResult.NOT_HANDLED;
        }

        final BlockPos target = targets.iterator().next();

        final int maxPathLength = Math.max(1, (int) Math.ceil(maxRange));
        final int evaluationBudget = patheticEvaluationBudget(maxVisitedNodes, searchDepthMultiplier);
        if (evaluationBudget < 1) {
            ZvsPathfindingMetrics.reject(RejectionReason.EVALUATION_BUDGET);
            return SearchResult.NOT_HANDLED;
        }
        ZvsPathfindingMetrics.attempt();
        final WalkNodeEvaluator walkNodeEvaluator = (WalkNodeEvaluator) nodeEvaluator;
        final ZvsSharedPathCache.PathProfile pathProfile = ZvsSharedPathCache.profile(mob, walkNodeEvaluator);
        final ZvsSharedPathCache sharedPathCache = mob.level().zvs2DPathCache;
        final Path path = findGroundPath(
            region,
            mob,
            walkNodeEvaluator,
            pathProfile,
            pathfindingContext,
            start,
            target,
            accuracy,
            maxRange,
            maxPathLength,
            evaluationBudget
        );
        return SearchResult.handled(path);
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
        final ZvsSharedPathCache.PathProfile pathProfile,
        final PathfindingContext pathfindingContext,
        final Node start,
        final BlockPos target,
        final int accuracy,
        final float maxRange,
        final int maxPathLength,
        final int evaluationBudget
    ) {
        final PatheticEnvironmentContext context = new PatheticEnvironmentContext(
            region, mob, nodeEvaluator, pathfindingContext, start, evaluationBudget
        );
        final PatheticNavigationPointProvider provider = new PatheticNavigationPointProvider();
        provider.seed(start);
        final List<BlockPos> endpoints = targetCandidates(start, target, accuracy);
        final ZvsSharedPathCache sharedPathCache = mob.level().zvs2DPathCache;
        final Path flowFieldPath = sharedPathCache.findFlowField(
            pathProfile, start, target, accuracy, maxRange, maxPathLength
        );
        if (flowFieldPath != null) {
            recordResult(ZvsPathfindingMetrics.Result.FLOW_FIELD, context);
            return flowFieldPath;
        }
        Path bestPartial = null;
        for (final BlockPos endpoint : endpoints) {
            final Path directPath = findDirectGroundPath(provider, context, start, endpoint, target, maxRange);
            if (directPath != null) {
                if (directPath.canReach()) {
                    recordResult(ZvsPathfindingMetrics.Result.DIRECT, context);
                    sharedPathCache.recordFlowField(pathProfile, directPath, accuracy, maxRange);
                    return directPath;
                }
                bestPartial = closerPartial(bestPartial, directPath, target);
            }
            if (context.evaluationBudget().exhausted()) {
                break;
            }
        }

        for (final BlockPos endpoint : context.evaluationBudget().exhausted() ? List.<BlockPos>of() : endpoints) {
            final Path detourPath = findOrthogonalDetourGroundPath(
                provider, context, start, endpoint, target, maxRange, maxPathLength
            );
            if (detourPath != null) {
                recordResult(ZvsPathfindingMetrics.Result.DETOUR, context);
                sharedPathCache.recordFlowField(pathProfile, detourPath, accuracy, maxRange);
                return detourPath;
            }
            if (context.evaluationBudget().exhausted()) {
                break;
            }
        }

        final Path aStarPath = findAStarGroundPath(
            provider, context, start, target, accuracy, maxRange, maxPathLength,
            context.evaluationBudget().remaining()
        );
        final Path path = aStarPath != null && aStarPath.canReach()
            ? aStarPath
            : closerPartial(bestPartial, aStarPath, target);
        if (path != null) {
            final ZvsPathfindingMetrics.Result result = path.canReach()
                ? ZvsPathfindingMetrics.Result.ASTAR
                : ZvsPathfindingMetrics.Result.PARTIAL;
            recordResult(result, context);
            if (path.canReach()) {
                sharedPathCache.recordFlowField(pathProfile, path, accuracy, maxRange);
            }
            return path;
        }
        recordResult(ZvsPathfindingMetrics.Result.NO_PATH, context);
        return null;
    }

    @Nullable
    private static Path closerPartial(
        final @Nullable Path first,
        final @Nullable Path second,
        final BlockPos target
    ) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return manhattanDistance(second.getEndNode(), target) < manhattanDistance(first.getEndNode(), target)
            ? second
            : first;
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
        final BlockPos target,
        final int accuracy,
        final float maxRange,
        final int maxPathLength,
        final int maxIterations
    ) {
        if (maxIterations <= 0) {
            return null;
        }
        final PriorityQueue<AStarNode> frontier = new PriorityQueue<>(
            Comparator.comparingDouble(AStarNode::score).thenComparingInt(AStarNode::heuristic)
        );
        final HashMap<Long, AStarNode> bestByPosition = new HashMap<>();
        final int startHeuristic = horizontalHeuristic(start.x, start.z, target, accuracy);
        final AStarNode startNode = new AStarNode(
            start.asBlockPos().asLong(), start.x, start.y, start.z, 0.0D, startHeuristic, 0, null,
            new PatheticNavigationPoint(true, de.bsommerfeld.pathetic.api.pathing.processing.Cost.ZERO, start.type, start.costMalus)
        );
        frontier.add(startNode);
        bestByPosition.put(startNode.position(), startNode);
        AStarNode best = startNode;
        AStarNode reached = null;
        int iterations = 0;

        search:
        while (!frontier.isEmpty() && iterations++ < maxIterations) {
            final AStarNode current = frontier.remove();
            if (bestByPosition.get(current.position()) != current) {
                continue;
            }
            if (current.heuristic() == 0) {
                reached = current;
                break;
            }
            if (current.steps() >= maxPathLength) {
                continue;
            }
            for (int direction = 0; direction < CARDINAL_X.length; direction++) {
                final int x = current.x() + CARDINAL_X[direction];
                final int z = current.z() + CARDINAL_Z[direction];
                final int steps = current.steps() + 1;
                if (steps >= maxRange) {
                    continue;
                }
                final PatheticNavigationPoint point = provider.pointAt(
                    PathPosition.of(x, current.y(), z), context
                );
                if (!point.isTraversable()) {
                    if (context.evaluationBudget().exhausted()) {
                        break search;
                    }
                    continue;
                }
                final double cost = current.cost() + 1.0D + Math.max(0.0F, point.malus());
                final long position = BlockPos.asLong(x, current.y(), z);
                final AStarNode previous = bestByPosition.get(position);
                if (previous != null && previous.cost() <= cost) {
                    continue;
                }
                final int heuristic = horizontalHeuristic(x, z, target, accuracy);
                final AStarNode candidate = new AStarNode(
                    position, x, current.y(), z, cost, heuristic, steps, current, point
                );
                bestByPosition.put(position, candidate);
                frontier.add(candidate);
                if (heuristic < best.heuristic()
                    || heuristic == best.heuristic() && cost < best.cost()) {
                    best = candidate;
                }
            }
        }

        final AStarNode result = reached != null ? reached : best;
        if (result == startNode) {
            return null;
        }
        final List<AStarNode> reversed = new ArrayList<>(result.steps() + 1);
        for (AStarNode node = result; node != null; node = node.parent()) {
            reversed.add(node);
        }
        Collections.reverse(reversed);
        final List<Node> nodes = new ArrayList<>(reversed.size());
        nodes.add(start);
        Node previous = start;
        for (int index = 1; index < reversed.size(); index++) {
            final AStarNode pathNode = reversed.get(index);
            final Node node = nodeForPoint(
                pathNode.x(), pathNode.y(), pathNode.z(), pathNode.point(), previous
            );
            nodes.add(node);
            previous = node;
        }
        return new Path(nodes, target, reached != null);
    }

    private static int horizontalHeuristic(
        final int x,
        final int z,
        final BlockPos target,
        final int accuracy
    ) {
        return Math.max(0, Math.abs(x - target.getX()) + Math.abs(z - target.getZ()) - accuracy);
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
                return partialPath(nodes, pathTarget);
            }

            final boolean diagonal = previous.x != x && previous.z != z;
            if (diagonal && (!isSafeDiagonalSide(provider, context, previous.x, start.y, z)
                || !isSafeDiagonalSide(provider, context, x, start.y, previous.z)
                || point.pathType() == PathType.WALKABLE_DOOR)) {
                return partialPath(nodes, pathTarget);
            }

            final Node node = nodeForPoint(x, start.y, z, point, previous);
            if (node.walkedDistance >= maxRange) {
                return partialPath(nodes, pathTarget);
            }
            nodes.add(node);
            previous = node;
        }

        return new Path(nodes, pathTarget, true);
    }

    @Nullable
    static Path partialPath(final List<Node> nodes, final BlockPos target) {
        return nodes.size() > 1 ? new Path(List.copyOf(nodes), target, false) : null;
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

    private record AStarNode(
        long position,
        int x,
        int y,
        int z,
        double cost,
        int heuristic,
        int steps,
        @Nullable AStarNode parent,
        PatheticNavigationPoint point
    ) {
        double score() {
            return this.cost + this.heuristic;
        }
    }

    /**
     * Distinguishes requests rejected before any 2D world evaluation from
     * requests fully owned by the 2D engine. A handled search never falls
     * through to vanilla, including when no path was found.
     */
    public record SearchResult(boolean handled, @Nullable Path path) {
        static final SearchResult NOT_HANDLED = new SearchResult(false, null);

        static SearchResult handled(@Nullable final Path path) {
            return new SearchResult(true, path);
        }
    }
}
