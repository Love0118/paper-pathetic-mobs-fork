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
        DIRECT,
        DETOUR,
        ASTAR,
        FALLBACK
    }

    public enum RejectionReason {
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
    private static final LongAdder[] RESULTS = adders(Result.values().length);
    private static final LongAdder[] REJECTIONS = adders(RejectionReason.values().length);

    private ZvsPathfindingMetrics() {
    }

    static void attempt() {
        ATTEMPTS.increment();
    }

    static void reject(final RejectionReason reason) {
        REJECTIONS[reason.ordinal()].increment();
    }

    static void result(final Result result, final int evaluations, final boolean exhausted) {
        RESULTS[result.ordinal()].increment();
        EVALUATIONS.add(Math.max(0, evaluations));
        if (exhausted) {
            EXHAUSTED.increment();
        }
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
            Map.copyOf(results),
            Map.copyOf(rejections)
        );
    }

    static void resetForTesting() {
        ATTEMPTS.reset();
        EVALUATIONS.reset();
        EXHAUSTED.reset();
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
        Map<Result, Long> results,
        Map<RejectionReason, Long> rejections
    ) {
    }
}
