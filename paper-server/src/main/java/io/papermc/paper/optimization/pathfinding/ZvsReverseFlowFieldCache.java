package io.papermc.paper.optimization.pathfinding;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
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
        if (path.getNodeCount() < 2) {
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

            final ZvsSharedPathCache.SectionSnapshot routeSnapshot = snapshotFactory.apply(sections(path));
            final FlowField merged;
            if (existing == null) {
                merged = new FlowField(Math.min(limits.maximumCells(), path.getNodeCount() * 2));
                merged.withSnapshot(routeSnapshot);
            } else {
                merged = existing;
                if (!merged.mergeSnapshot(routeSnapshot)) {
                    // A section used by the existing routes changed between
                    // records. Discard those routes instead of mixing cells
                    // evaluated against two world revisions.
                    merged.clear();
                    merged.withSnapshot(routeSnapshot);
                }
            }
            merged.merge(path, accuracy, limits.maximumCells());
            if (merged.size() < 2) {
                return;
            }
            this.fields.put(key, merged);
            this.demand.remove(key);
            trim(this.fields, limits.maximumFields());
            if (existing == null) {
                ZvsPathfindingMetrics.flowFieldBuild(merged.size());
            }
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
        // A partial path can terminate because its caller exhausted the allowed
        // search range. Keep those fields isolated so a wider request is never
        // satisfied by a narrower request's terminal node.
        return new Key(profile, target.asLong(), accuracy, Float.floatToIntBits(maxRange));
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

    private static long[] sections(final Path path) {
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet sections =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int index = 0; index < path.getNodeCount(); index++) {
            final long position = path.getNode(index).asBlockPos().asLong();
            sections.add(SectionPos.asLong(
                SectionPos.blockToSectionCoord(BlockPos.getX(position)),
                SectionPos.blockToSectionCoord(BlockPos.getY(position)),
                SectionPos.blockToSectionCoord(BlockPos.getZ(position))
            ));
        }
        return sections.toLongArray();
    }

    static final class FlowField {
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Cell> cells;
        private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap sectionRevisions =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

        FlowField(final Map<Long, Cell> cells) {
            this(cells.size());
            this.cells.putAll(cells);
        }

        FlowField(final int expectedCells) {
            this.cells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(expectedCells);
            this.sectionRevisions.defaultReturnValue(Long.MIN_VALUE);
        }

        synchronized FlowField withSnapshot(final ZvsSharedPathCache.SectionSnapshot snapshot) {
            this.sectionRevisions.clear();
            for (int index = 0; index < snapshot.sections().length; index++) {
                this.sectionRevisions.put(snapshot.sections()[index], snapshot.revisions()[index]);
            }
            return this;
        }

        synchronized boolean mergeSnapshot(final ZvsSharedPathCache.SectionSnapshot snapshot) {
            for (int index = 0; index < snapshot.sections().length; index++) {
                final long section = snapshot.sections()[index];
                final long previous = this.sectionRevisions.get(section);
                if (previous != Long.MIN_VALUE && previous != snapshot.revisions()[index]) {
                    return false;
                }
            }
            for (int index = 0; index < snapshot.sections().length; index++) {
                this.sectionRevisions.put(snapshot.sections()[index], snapshot.revisions()[index]);
            }
            return true;
        }

        synchronized void merge(final Path path, final int accuracy, final int maximumCells) {
            long next = path.getNode(path.getNodeCount() - 1).asBlockPos().asLong();
            for (int index = path.getNodeCount() - 1; index >= 0; index--) {
                final Node node = path.getNode(index);
                final long position = node.asBlockPos().asLong();
                final Cell previous = this.cells.get(position);
                final double candidateCost;
                final boolean candidateReached;
                if (index == path.getNodeCount() - 1) {
                    candidateCost = path.canReach()
                        ? 0.0D
                        : Math.max(0, Math.abs(node.x - path.getTarget().getX())
                            + Math.abs(node.y - path.getTarget().getY())
                            + Math.abs(node.z - path.getTarget().getZ()) - accuracy);
                    candidateReached = path.canReach();
                } else {
                    final Cell nextCell = this.cells.get(next);
                    if (nextCell == null) {
                        // The field reached its cell cap before this route's
                        // suffix could be represented. Never insert a prefix
                        // that would point at a missing next hop.
                        break;
                    }
                    final Node nextNode = path.getNode(index + 1);
                    candidateCost = nextCell.cost()
                        + node.distanceTo(nextNode)
                        + Math.max(0.0F, nextNode.costMalus);
                    candidateReached = nextCell.reached();
                }

                if (previous == null && this.cells.size() >= maximumCells) {
                    break;
                }
                if (previous == null
                    || candidateReached && !previous.reached()
                    || candidateReached == previous.reached() && candidateCost < previous.cost()) {
                    this.cells.put(position, new Cell(
                        index == path.getNodeCount() - 1 ? position : next,
                        candidateCost, node.type, node.costMalus, candidateReached
                    ));
                }
                next = position;
            }
        }

        synchronized void clear() {
            this.cells.clear();
            this.sectionRevisions.clear();
        }

        synchronized int size() {
            return this.cells.size();
        }

        synchronized boolean isCurrent(final Predicate<ZvsSharedPathCache.SectionSnapshot> snapshotValidator) {
            if (this.sectionRevisions.isEmpty()) {
                return true;
            }
            final long[] sections = new long[this.sectionRevisions.size()];
            final long[] revisions = new long[this.sectionRevisions.size()];
            int index = 0;
            for (final it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry
                : this.sectionRevisions.long2LongEntrySet()) {
                sections[index] = entry.getLongKey();
                revisions[index] = entry.getLongValue();
                index++;
            }
            return snapshotValidator.test(new ZvsSharedPathCache.SectionSnapshot(sections, revisions));
        }

        @Nullable
        synchronized Path toPath(final Node start, final BlockPos target, final float maxRange, final int maxPathLength) {
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
                    return nodes.size() > 1 ? new Path(nodes, target, cell.reached()) : null;
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

    record Cell(long next, double cost, PathType pathType, float malus, boolean reached) {
    }

    private record Key(ZvsSharedPathCache.PathProfile profile, long target, int accuracy, int maxRangeBits) {
    }

    private record Limits(int buildAfter, int maximumCells, int maximumFields) {
        boolean enabled() {
            return this.buildAfter > 0 && this.maximumCells > 0 && this.maximumFields > 0;
        }
    }
}
