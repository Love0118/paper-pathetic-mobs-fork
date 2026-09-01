package io.papermc.paper.optimization.pathfinding;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ZvsReverseFlowFieldCache {
    private final LinkedHashMap<Key, FlowField> fields = new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<Key, Integer> demand = new LinkedHashMap<>(32, 0.75F, true);

    synchronized void invalidate() {
        this.fields.clear();
        this.demand.clear();
    }

    @Nullable
    Path find(
        final ZvsSharedPathCache.PathProfile profile,
        final Node start,
        final BlockPos target,
        final int accuracy,
        final float maxRange,
        final int maxPathLength,
        final Predicate<ZvsSharedPathCache.SectionSnapshot> snapshotValidator
    ) {
        final Limits limits = limits();
        if (!limits.enabled()) {
            return null;
        }

        final Key key = key(profile, target, accuracy, maxRange);
        final FlowField field;
        synchronized (this) {
            field = this.fields.get(key);
            if (field == null) {
                this.demand.merge(key, 1, Integer::sum);
                trim(this.demand, Math.max(16, limits.maximumFields() * 4));
                return null;
            }
        }
        if (!field.isCurrent(snapshotValidator)) {
            synchronized (this) {
                if (this.fields.remove(key, field)) {
                    this.demand.put(key, 1);
                }
            }
            return null;
        }
        return field.toPath(start, target, maxRange, maxPathLength);
    }

    void record(
        final ZvsSharedPathCache.PathProfile profile,
        final Path path,
        final int accuracy,
        final float maxRange,
        final Function<long[], ZvsSharedPathCache.SectionSnapshot> snapshotFactory
    ) {
        if (!path.canReach() || path.getNodeCount() < 2) {
            return;
        }
        final Limits limits = limits();
        if (!limits.enabled()) {
            return;
        }

        final Key key = key(profile, path.getTarget(), accuracy, maxRange);
        synchronized (this) {
            final FlowField existing = this.fields.get(key);
            if (existing == null && this.demand.getOrDefault(key, 0) < limits.buildAfter()) {
                return;
            }

            final Map<Long, Cell> cells = existing == null
                ? new HashMap<>(Math.min(limits.maximumCells(), path.getNodeCount() * 2))
                : new HashMap<>(existing.cells());
            double suffixCost = 0.0D;
            long next = path.getNode(path.getNodeCount() - 1).asBlockPos().asLong();
            for (int index = path.getNodeCount() - 1; index >= 0; index--) {
                final Node node = path.getNode(index);
                final long position = node.asBlockPos().asLong();
                final Cell previous = cells.get(position);
                if (previous != null || cells.size() < limits.maximumCells()) {
                    if (previous == null || suffixCost < previous.cost()) {
                        cells.put(position, new Cell(next, suffixCost, node.type, node.costMalus));
                    }
                }
                next = position;
                if (index > 0) {
                    final Node prior = path.getNode(index - 1);
                    suffixCost += prior.distanceTo(node) + Math.max(0.0F, node.costMalus);
                }
            }
            if (cells.size() < 2) {
                return;
            }
            final FlowField merged = new FlowField(Map.copyOf(cells)).withSnapshot(snapshotFactory.apply(sections(cells)));
            this.fields.put(key, merged);
            this.demand.remove(key);
            trim(this.fields, limits.maximumFields());
            ZvsPathfindingMetrics.flowFieldBuild(cells.size());
        }
    }

    synchronized int sizeForTesting() {
        return this.fields.size();
    }

    private static Key key(
        final ZvsSharedPathCache.PathProfile profile,
        final BlockPos target,
        final int accuracy,
        final float maxRange
    ) {
        return new Key(profile, target.asLong(), accuracy, Math.max(1, (int)Math.ceil(maxRange)));
    }

    private static Limits limits() {
        final GlobalConfiguration.Optimizations.PatheticMobPathfinding configuration =
            GlobalConfiguration.get().optimizations.patheticMobPathfinding;
        return new Limits(
            Math.max(0, configuration.reverseFlowFieldBuildAfterRequests),
            Math.max(0, configuration.reverseFlowFieldMaxCells),
            Math.max(0, configuration.reverseFlowFieldCacheEntries)
        );
    }

    private static <K, V> void trim(final LinkedHashMap<K, V> map, final int maximumEntries) {
        final Iterator<Map.Entry<K, V>> iterator = map.entrySet().iterator();
        while (map.size() > maximumEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static long[] sections(final Map<Long, Cell> cells) {
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet sections =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (final long position : cells.keySet()) {
            sections.add(SectionPos.asLong(
                SectionPos.blockToSectionCoord(BlockPos.getX(position)),
                SectionPos.blockToSectionCoord(BlockPos.getY(position)),
                SectionPos.blockToSectionCoord(BlockPos.getZ(position))
            ));
        }
        return sections.toLongArray();
    }

    record FlowField(
        Map<Long, Cell> cells,
        ZvsSharedPathCache.@Nullable SectionSnapshot snapshot
    ) {
        FlowField(final Map<Long, Cell> cells) {
            this(cells, null);
        }

        FlowField withSnapshot(final ZvsSharedPathCache.SectionSnapshot snapshot) {
            return new FlowField(this.cells, snapshot);
        }

        boolean isCurrent(final Predicate<ZvsSharedPathCache.SectionSnapshot> snapshotValidator) {
            return this.snapshot == null || snapshotValidator.test(this.snapshot);
        }

        @Nullable
        Path toPath(final Node start, final BlockPos target, final float maxRange, final int maxPathLength) {
            long position = start.asBlockPos().asLong();
            if (!this.cells.containsKey(position)) {
                return null;
            }
            final List<Node> nodes = new ArrayList<>(Math.min(1_024, maxPathLength + 1));
            final it.unimi.dsi.fastutil.longs.LongOpenHashSet visited =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            nodes.add(start);
            visited.add(position);
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
                if (next == null || next.cost() >= cell.cost() || !visited.add(nextPosition)) {
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

    private record Key(ZvsSharedPathCache.PathProfile profile, long target, int accuracy, int range) {
    }

    private record Limits(int buildAfter, int maximumCells, int maximumFields) {
        boolean enabled() {
            return this.buildAfter > 0 && this.maximumCells > 0 && this.maximumFields > 0;
        }
    }
}
