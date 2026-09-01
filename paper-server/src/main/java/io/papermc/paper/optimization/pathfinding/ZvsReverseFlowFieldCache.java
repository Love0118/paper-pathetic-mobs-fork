package io.papermc.paper.optimization.pathfinding;

import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ZvsReverseFlowFieldCache {
    private static final int[] CARDINAL_X = {1, -1, 0, 0};
    private static final int[] CARDINAL_Z = {0, 0, 1, -1};
    private final LinkedHashMap<Key, FlowField> fields = new LinkedHashMap<>(16, 0.75F, true);
    private final HashMap<Key, Integer> demand = new HashMap<>();

    synchronized void invalidate() {
        this.fields.clear();
        this.demand.clear();
    }

    @Nullable
    Path findOrBuild(
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
        final GlobalConfiguration.Optimizations.PatheticMobPathfinding configuration =
            GlobalConfiguration.get().optimizations.patheticMobPathfinding;
        final int buildAfter = Math.max(0, configuration.reverseFlowFieldBuildAfterRequests);
        final int maximumCells = Math.max(0, configuration.reverseFlowFieldMaxCells);
        final int maximumFields = Math.max(0, configuration.reverseFlowFieldCacheEntries);
        if (buildAfter == 0 || maximumCells == 0 || maximumFields == 0) {
            return null;
        }

        final Key key = new Key(profile, target.asLong(), accuracy, Math.max(1, (int)Math.ceil(maxRange)));
        FlowField field;
        synchronized (this) {
            field = this.fields.get(key);
            if (field == null) {
                final int requests = this.demand.merge(key, 1, Integer::sum);
                if (requests < buildAfter) {
                    return null;
                }
            }
        }

        if (field == null) {
            field = build(provider, context, target, endpoints, maxRange, maximumCells);
            synchronized (this) {
                this.fields.put(key, field);
                this.demand.remove(key);
                trim(this.fields, maximumFields);
            }
            ZvsPathfindingMetrics.flowFieldBuild(field.cells().size());
        }
        return field.toPath(start, target, maxRange, maxPathLength);
    }

    private static FlowField build(
        final PatheticNavigationPointProvider provider,
        final PatheticEnvironmentContext context,
        final BlockPos target,
        final List<BlockPos> endpoints,
        final float maxRange,
        final int maximumCells
    ) {
        final HashMap<Long, Cell> cells = new HashMap<>(Math.min(maximumCells, 4_096));
        final PriorityQueue<State> frontier = new PriorityQueue<>(Comparator.comparingDouble(State::cost));
        for (final BlockPos endpoint : endpoints) {
            final PatheticNavigationPoint point = provider.pointAt(
                PathPosition.of(endpoint.getX(), endpoint.getY(), endpoint.getZ()), context
            );
            if (!point.isTraversable()) {
                continue;
            }
            final long position = endpoint.asLong();
            cells.put(position, new Cell(position, 0.0D, point.pathType(), point.malus()));
            frontier.add(new State(position, 0.0D));
        }

        final int initialEvaluations = context.evaluationBudget().consumed();
        final int buildEvaluationLimit = Math.max(1, context.evaluationBudget().remaining() / 2);
        final double maximumDistanceSquared = (double)maxRange * maxRange;
        while (!frontier.isEmpty()
            && cells.size() < maximumCells
            && context.evaluationBudget().consumed() - initialEvaluations < buildEvaluationLimit) {
            final State current = frontier.remove();
            final Cell currentCell = cells.get(current.position());
            if (currentCell == null || current.cost() != currentCell.cost()) {
                continue;
            }
            final int currentX = BlockPos.getX(current.position());
            final int currentY = BlockPos.getY(current.position());
            final int currentZ = BlockPos.getZ(current.position());
            for (int direction = 0; direction < CARDINAL_X.length && cells.size() < maximumCells; direction++) {
                final int x = currentX + CARDINAL_X[direction];
                final int z = currentZ + CARDINAL_Z[direction];
                final double dx = x - target.getX();
                final double dz = z - target.getZ();
                if (dx * dx + dz * dz >= maximumDistanceSquared) {
                    continue;
                }
                final long position = BlockPos.asLong(x, currentY, z);
                final PatheticNavigationPoint point = provider.pointAt(PathPosition.of(x, currentY, z), context);
                if (!point.isTraversable()) {
                    continue;
                }
                final double cost = current.cost() + 1.0D + Math.max(0.0F, point.malus());
                final Cell previous = cells.get(position);
                if (previous != null && previous.cost() <= cost) {
                    continue;
                }
                cells.put(position, new Cell(current.position(), cost, point.pathType(), point.malus()));
                frontier.add(new State(position, cost));
            }
        }
        return new FlowField(Map.copyOf(cells));
    }

    private static <K, V> void trim(final LinkedHashMap<K, V> map, final int maximumEntries) {
        final Iterator<Map.Entry<K, V>> iterator = map.entrySet().iterator();
        while (map.size() > maximumEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    record FlowField(Map<Long, Cell> cells) {
        @Nullable
        Path toPath(final Node start, final BlockPos target, final float maxRange, final int maxPathLength) {
            long position = start.asBlockPos().asLong();
            if (!this.cells.containsKey(position)) {
                return null;
            }
            final List<Node> nodes = new ArrayList<>(Math.min(1_024, maxPathLength + 1));
            nodes.add(start);
            Node previous = start;
            for (int steps = 0; steps < maxPathLength; steps++) {
                final Cell cell = this.cells.get(position);
                if (cell == null) {
                    return null;
                }
                if (cell.next() == position) {
                    return nodes.size() > 1 ? new Path(nodes, target, true) : null;
                }
                final long nextPosition = cell.next();
                final Cell next = this.cells.get(nextPosition);
                if (next == null) {
                    return null;
                }
                final Node node = new Node(BlockPos.getX(nextPosition), BlockPos.getY(nextPosition), BlockPos.getZ(nextPosition));
                node.type = next.pathType();
                node.costMalus = next.malus();
                node.cameFrom = previous;
                node.walkedDistance = previous.walkedDistance + previous.distanceTo(node);
                if (node.walkedDistance >= maxRange) {
                    return null;
                }
                node.g = previous.g + previous.distanceTo(node) + Math.max(0.0F, node.costMalus);
                node.f = node.g;
                nodes.add(node);
                previous = node;
                position = nextPosition;
            }
            return null;
        }
    }

    record Cell(long next, double cost, PathType pathType, float malus) {
    }

    private record Key(int profile, long target, int accuracy, int range) {
    }

    private record State(long position, double cost) {
    }
}
