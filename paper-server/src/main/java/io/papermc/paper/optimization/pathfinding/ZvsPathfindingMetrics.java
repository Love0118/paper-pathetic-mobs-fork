package io.papermc.paper.optimization.pathfinding;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.NullMarked;

/**
 * Low-overhead counters for the ZVS two-dimensional pathfinding fast path.
 */
@NullMarked
public final class ZvsPathfindingMetrics {
    public enum Result {
        CACHED,
        FLOW_FIELD,
        DIRECT,
        DETOUR,
        ASTAR,
        PARTIAL,
        NO_PATH
    }

    public enum RejectionReason {
        MARKER_TAG,
        EVALUATOR,
        TARGET_COUNT,
        ACCURACY,
        START_TYPE,
        START_MALUS,
        RANGE,
        VERTICAL,
        ALREADY_REACHED,
        OUT_OF_RANGE,
        EVALUATION_BUDGET
    }

    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder EVALUATIONS = new LongAdder();
    private static final LongAdder EXHAUSTED = new LongAdder();
    private static final LongAdder INVALIDATIONS = new LongAdder();
    private static final LongAdder FLOW_FIELD_BUILDS = new LongAdder();
    private static final LongAdder FLOW_FIELD_CELLS = new LongAdder();
    private static final LongAdder SHARED_CELL_HITS = new LongAdder();
    private static final LongAdder SHARED_CELL_MISSES = new LongAdder();
    private static final LongAdder[] RESULTS = adders(Result.values().length);
    private static final LongAdder[] REJECTIONS = adders(RejectionReason.values().length);

    private ZvsPathfindingMetrics() {
    }

    static void attempt() {
        attempt(true);
    }

    static void attempt(final boolean enabled) {
        if (!enabled) {
            return;
        }
        ATTEMPTS.increment();
    }

    static void reject(final RejectionReason reason) {
        reject(true, reason);
    }

    static void reject(final boolean enabled, final RejectionReason reason) {
        if (!enabled) {
            return;
        }
        REJECTIONS[reason.ordinal()].increment();
    }

    static void result(final Result result, final int evaluations, final boolean exhausted) {
        result(true, result, evaluations, exhausted);
    }

    static void result(final boolean enabled, final Result result, final int evaluations, final boolean exhausted) {
        if (!enabled) {
            return;
        }
        RESULTS[result.ordinal()].increment();
        EVALUATIONS.add(Math.max(0, evaluations));
        if (exhausted) {
            EXHAUSTED.increment();
        }
    }

    static void invalidation() {
        INVALIDATIONS.increment();
    }

    static void flowFieldBuild(final int cells) {
        FLOW_FIELD_BUILDS.increment();
        FLOW_FIELD_CELLS.add(Math.max(0, cells));
    }

    static void sharedCell(final boolean hit) {
        sharedCell(true, hit);
    }

    static void sharedCell(final boolean enabled, final boolean hit) {
        if (!enabled) {
            return;
        }
        (hit ? SHARED_CELL_HITS : SHARED_CELL_MISSES).increment();
    }

    public static Snapshot snapshot() {
        final EnumMap<Result, Long> results = new EnumMap<>(Result.class);
        for (final Result result : Result.values()) {
            results.put(result, RESULTS[result.ordinal()].sum());
        }
        final EnumMap<RejectionReason, Long> rejections = new EnumMap<>(RejectionReason.class);
        for (final RejectionReason reason : RejectionReason.values()) {
            rejections.put(reason, REJECTIONS[reason.ordinal()].sum());
        }
        return new Snapshot(
            ATTEMPTS.sum(),
            EVALUATIONS.sum(),
            EXHAUSTED.sum(),
            INVALIDATIONS.sum(),
            FLOW_FIELD_BUILDS.sum(),
            FLOW_FIELD_CELLS.sum(),
            SHARED_CELL_HITS.sum(),
            SHARED_CELL_MISSES.sum(),
            Map.copyOf(results),
            Map.copyOf(rejections)
        );
    }

    static void resetForTesting() {
        ATTEMPTS.reset();
        EVALUATIONS.reset();
        EXHAUSTED.reset();
        INVALIDATIONS.reset();
        FLOW_FIELD_BUILDS.reset();
        FLOW_FIELD_CELLS.reset();
        SHARED_CELL_HITS.reset();
        SHARED_CELL_MISSES.reset();
        for (final LongAdder counter : RESULTS) {
            counter.reset();
        }
        for (final LongAdder counter : REJECTIONS) {
            counter.reset();
        }
    }

    private static LongAdder[] adders(final int count) {
        final LongAdder[] counters = new LongAdder[count];
        for (int index = 0; index < count; index++) {
            counters[index] = new LongAdder();
        }
        return counters;
    }

    public record Snapshot(
        long attempts,
        long evaluations,
        long exhausted,
        long invalidations,
        long flowFieldBuilds,
        long flowFieldCells,
        long sharedCellHits,
        long sharedCellMisses,
        Map<Result, Long> results,
        Map<RejectionReason, Long> rejections
    ) {
    }
}
