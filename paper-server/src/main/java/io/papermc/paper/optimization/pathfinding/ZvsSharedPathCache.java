package io.papermc.paper.optimization.pathfinding;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Per-level cache of immutable successful 2D route suffixes.
 *
 * <p>A single path to a fixed objective populates entries for each cell on the
 * path, so mobs joining the route from different starts reuse the same tail.
 * The owning level invalidates the complete cache whenever a block state
 * changes. Broad invalidation deliberately favors correctness over hit rate.</p>
 */
@NullMarked
public final class ZvsSharedPathCache {
    private final LinkedHashMap<Key, CachedPath> routes = new LinkedHashMap<>(256, 0.75F, true);
    private final ZvsReverseFlowFieldCache flowFields = new ZvsReverseFlowFieldCache();
    private long revision;

    public synchronized void invalidate() {
        this.revision++;
        if (!this.routes.isEmpty()) {
            this.routes.clear();
        }
        this.flowFields.invalidate();
        ZvsPathfindingMetrics.invalidation();
    }

    public synchronized long revision() {
        return this.revision;
    }

    @Nullable
    Path find(
        final int profile,
        final Node start,
        final BlockPos target,
        final int accuracy,
        final float maxRange
    ) {
        final int maximumEntries = maximumEntries();
        if (maximumEntries <= 0) {
            return null;
        }
        final CachedPath cached;
        synchronized (this) {
            cached = this.routes.get(new Key(start.asBlockPos().asLong(), target.asLong(), accuracy, profile));
        }
        return cached == null ? null : cached.toPath(start, maxRange);
    }

    void record(final int profile, final Path path, final int accuracy) {
        if (!path.canReach() || path.getNodeCount() < 2) {
            return;
        }
        final int maximumEntries = maximumEntries();
        final int maximumNodes = Math.max(
            0,
            GlobalConfiguration.get().optimizations.patheticMobPathfinding.sharedRouteMaxPathNodes
        );
        if (maximumEntries <= 0 || maximumNodes < 2 || path.getNodeCount() > maximumNodes) {
            return;
        }

        final CachedNode[] nodes = new CachedNode[path.getNodeCount()];
        for (int index = 0; index < nodes.length; index++) {
            final Node node = path.getNode(index);
            nodes[index] = new CachedNode(node.x, node.y, node.z, node.type, node.costMalus);
        }

        synchronized (this) {
            for (int index = 0; index < nodes.length - 1; index++) {
                final CachedNode start = nodes[index];
                this.routes.put(
                    new Key(BlockPos.asLong(start.x(), start.y(), start.z()), path.getTarget().asLong(), accuracy, profile),
                    new CachedPath(nodes, index, path.getTarget())
                );
            }
            this.trimTo(maximumEntries);
        }
    }

    @Nullable
    Path findOrBuildFlowField(
        final int profile,
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final Node start,
        final BlockPos target,
        final List<BlockPos> endpoints,
        final int accuracy,
        final float maxRange,
        final int maxPathLength
    ) {
        return this.flowFields.findOrBuild(
            profile, provider, context, start, target, endpoints, accuracy, maxRange, maxPathLength
        );
    }

    synchronized int sizeForTesting() {
        return this.routes.size();
    }

    private void trimTo(final int maximumEntries) {
        final Iterator<Map.Entry<Key, CachedPath>> iterator = this.routes.entrySet().iterator();
        while (this.routes.size() > maximumEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    static int profile(final Mob mob, final WalkNodeEvaluator evaluator) {
        int hash = System.identityHashCode(mob.getType());
        hash = 31 * hash + Float.floatToIntBits(mob.getBbWidth());
        hash = 31 * hash + Float.floatToIntBits(mob.getBbHeight());
        hash = 31 * hash + Boolean.hashCode(evaluator.canPassDoors());
        hash = 31 * hash + Boolean.hashCode(evaluator.canOpenDoors());
        hash = 31 * hash + Boolean.hashCode(evaluator.canFloat());
        hash = 31 * hash + Boolean.hashCode(evaluator.canWalkOverFences());
        for (final PathType pathType : PathType.values()) {
            if (PatheticNavigationPointProvider.isSupportedFlatType(pathType)) {
                hash = 31 * hash + Float.floatToIntBits(mob.getPathfindingMalus(pathType));
            }
        }
        return hash;
    }

    private static int maximumEntries() {
        return Math.max(0, GlobalConfiguration.get().optimizations.patheticMobPathfinding.sharedRouteCacheEntries);
    }

    private record Key(long start, long target, int accuracy, int profile) {
    }

    private record CachedNode(int x, int y, int z, PathType type, float malus) {
    }

    private record CachedPath(CachedNode[] nodes, int offset, BlockPos target) {
        @Nullable
        Path toPath(final Node start, final float maxRange) {
            if (this.offset >= this.nodes.length
                || this.nodes[this.offset].x() != start.x
                || this.nodes[this.offset].y() != start.y
                || this.nodes[this.offset].z() != start.z) {
                return null;
            }

            final List<Node> rebuilt = new ArrayList<>(this.nodes.length - this.offset);
            rebuilt.add(start);
            Node previous = start;
            for (int index = this.offset + 1; index < this.nodes.length; index++) {
                final CachedNode cached = this.nodes[index];
                final Node node = new Node(cached.x(), cached.y(), cached.z());
                node.type = cached.type();
                node.costMalus = cached.malus();
                node.cameFrom = previous;
                node.walkedDistance = previous.walkedDistance + previous.distanceTo(node);
                if (node.walkedDistance >= maxRange) {
                    return null;
                }
                node.g = previous.g + previous.distanceTo(node) + Math.max(0.0F, node.costMalus);
                node.f = node.g;
                rebuilt.add(node);
                previous = node;
            }
            return new Path(rebuilt, this.target, true);
        }
    }
}
