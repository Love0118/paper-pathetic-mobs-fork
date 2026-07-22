package io.papermc.paper.optimization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in counters used to verify that MobOpt fast paths are exercised by a
 * real server workload. The state and shutdown hook do not exist unless the
 * verification JVM property is enabled.
 */
public final class MobOptRuntimeMetrics {
    public static final boolean ENABLED = requestedEnabled();
    private static final String RUN_ID = readRunId();
    private static final Path OUTPUT_FILE = readOutputFile();
    private static final State STATE = initializeState();

    private MobOptRuntimeMetrics() {
    }

    public enum PathfindingResult {
        DIRECT,
        DETOUR,
        ASTAR,
        FALLBACK
    }

    public static void frameDecoded(final boolean retained, final int bytes) {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            (retained ? state.frameRetained : state.frameCopied).add(bytes);
        } catch (final RuntimeException ignored) {
        }
    }

    public static void compressionPassthrough(final boolean retained, final int bytes) {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            (retained ? state.compressionRetained : state.compressionCopied).add(bytes);
        } catch (final RuntimeException ignored) {
        }
    }

    public static void framePrefixInPlace(final int bodyBytes) {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            state.prefixInPlace.add(bodyBytes);
        } catch (final RuntimeException ignored) {
        }
    }

    public static void framePrefixFallback(final boolean optimizationEnabled, final int bodyBytes) {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            (optimizationEnabled ? state.prefixEnabledIneligible : state.prefixDisabled).add(bodyBytes);
        } catch (final RuntimeException ignored) {
        }
    }

    public static void writeDispatch(final boolean lazy) {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            (lazy ? state.writeLazyTasks : state.writeNormalTasks).increment();
        } catch (final RuntimeException ignored) {
        }
    }

    public static void explosionLookup(final boolean indexed, final int worldPlayers, final int candidates) {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            (indexed ? state.explosionIndexedLookups : state.explosionFullScans).increment();
            state.explosionWorldPlayers.add(worldPlayers);
            state.explosionCandidates.add(candidates);
        } catch (final RuntimeException ignored) {
        }
    }

    public static void explosionDistancePassed() {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            state.explosionDistancePassed.increment();
        } catch (final RuntimeException ignored) {
        }
    }

    public static void pathfindingAttempt() {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            state.pathfindingAttempts.increment();
        } catch (final RuntimeException ignored) {
        }
    }

    public static void pathfindingResult(
        final PathfindingResult result,
        final int providerEvaluations,
        final boolean budgetExhausted
    ) {
        final State state = STATE;
        if (state == null) {
            return;
        }
        try {
            switch (result) {
                case DIRECT -> state.pathfindingDirect.increment();
                case DETOUR -> state.pathfindingDetour.increment();
                case ASTAR -> state.pathfindingAStar.increment();
                case FALLBACK -> state.pathfindingFallback.increment();
            }
            state.pathfindingProviderEvaluations.add(providerEvaluations);
            if (budgetExhausted) {
                state.pathfindingBudgetExhausted.increment();
            }
        } catch (final RuntimeException ignored) {
        }
    }

    private static boolean requestedEnabled() {
        try {
            return Boolean.parseBoolean(System.getProperty("paper.mobopt.verifyFastPaths", "false"));
        } catch (final SecurityException | IllegalStateException ignored) {
            return false;
        }
    }

    private static String readRunId() {
        if (!ENABLED) {
            return "unset";
        }
        try {
            return sanitizeRunId(System.getProperty("paper.mobopt.verifyFastPaths.runId", "unset"));
        } catch (final SecurityException | IllegalStateException ignored) {
            return "unset";
        }
    }

    private static Path readOutputFile() {
        if (!ENABLED) {
            return null;
        }
        try {
            final String configured = System.getProperty("paper.mobopt.verifyFastPaths.outputFile", "");
            return configured.isBlank() ? null : Path.of(configured).toAbsolutePath().normalize();
        } catch (final SecurityException | IllegalStateException | InvalidPathException ignored) {
            return null;
        }
    }

    private static State initializeState() {
        if (!ENABLED) {
            return null;
        }
        try {
            final State state = new State();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> emitOnce(state), "MobOpt fast-path metrics"));
            return state;
        } catch (final SecurityException | IllegalStateException ignored) {
            return null;
        }
    }

    private static void emitOnce(final State state) {
        try {
            if (state.emitted.compareAndSet(false, true)) {
                final String snapshot = formatSnapshot(state);
                System.out.println(snapshot);
                if (OUTPUT_FILE != null) {
                    try {
                        Files.writeString(
                            OUTPUT_FILE,
                            snapshot + System.lineSeparator(),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                        );
                    } catch (final IOException | RuntimeException ignored) {
                    }
                }
            }
        } catch (final RuntimeException ignored) {
        }
    }

    private static String formatSnapshot(final State state) {
        return "MOBOPT_FASTPATH_METRICS"
            + " run_id=" + RUN_ID
            + state.frameRetained.format(" frame_retained")
            + state.frameCopied.format(" frame_copied")
            + state.compressionRetained.format(" compression_passthrough_retained")
            + state.compressionCopied.format(" compression_passthrough_copied")
            + state.prefixInPlace.format(" frame_prefix_in_place")
            + state.prefixEnabledIneligible.format(" frame_prefix_enabled_ineligible")
            + state.prefixDisabled.format(" frame_prefix_disabled")
            + " write_lazy_tasks=" + state.writeLazyTasks.sum()
            + " write_normal_tasks=" + state.writeNormalTasks.sum()
            + " explosion_indexed_lookups=" + state.explosionIndexedLookups.sum()
            + " explosion_full_scans=" + state.explosionFullScans.sum()
            + " explosion_world_players=" + state.explosionWorldPlayers.sum()
            + " explosion_candidates=" + state.explosionCandidates.sum()
            + " explosion_distance_passed=" + state.explosionDistancePassed.sum()
            + " pathfinding_attempts=" + state.pathfindingAttempts.sum()
            + " pathfinding_direct=" + state.pathfindingDirect.sum()
            + " pathfinding_detour=" + state.pathfindingDetour.sum()
            + " pathfinding_astar=" + state.pathfindingAStar.sum()
            + " pathfinding_fallback=" + state.pathfindingFallback.sum()
            + " pathfinding_budget_exhausted=" + state.pathfindingBudgetExhausted.sum()
            + " pathfinding_provider_evaluations=" + state.pathfindingProviderEvaluations.sum();
    }

    private static String sanitizeRunId(final String runId) {
        if (runId.isEmpty()) {
            return "unset";
        }
        final StringBuilder sanitized = new StringBuilder(Math.min(runId.length(), 128));
        for (int i = 0; i < runId.length() && sanitized.length() < 128; ++i) {
            final char character = runId.charAt(i);
            sanitized.append(character >= '!' && character <= '~' && character != '=' ? character : '_');
        }
        return sanitized.toString();
    }

    private static final class CountAndBytes {
        private final LongAdder count = new LongAdder();
        private final LongAdder bytes = new LongAdder();

        private void add(final long value) {
            this.count.increment();
            this.bytes.add(value);
        }

        private String format(final String prefix) {
            return prefix + "_count=" + this.count.sum() + prefix + "_bytes=" + this.bytes.sum();
        }
    }

    private static final class State {
        private final AtomicBoolean emitted = new AtomicBoolean();
        private final CountAndBytes frameRetained = new CountAndBytes();
        private final CountAndBytes frameCopied = new CountAndBytes();
        private final CountAndBytes compressionRetained = new CountAndBytes();
        private final CountAndBytes compressionCopied = new CountAndBytes();
        private final CountAndBytes prefixInPlace = new CountAndBytes();
        private final CountAndBytes prefixEnabledIneligible = new CountAndBytes();
        private final CountAndBytes prefixDisabled = new CountAndBytes();
        private final LongAdder writeLazyTasks = new LongAdder();
        private final LongAdder writeNormalTasks = new LongAdder();
        private final LongAdder explosionIndexedLookups = new LongAdder();
        private final LongAdder explosionFullScans = new LongAdder();
        private final LongAdder explosionWorldPlayers = new LongAdder();
        private final LongAdder explosionCandidates = new LongAdder();
        private final LongAdder explosionDistancePassed = new LongAdder();
        private final LongAdder pathfindingAttempts = new LongAdder();
        private final LongAdder pathfindingDirect = new LongAdder();
        private final LongAdder pathfindingDetour = new LongAdder();
        private final LongAdder pathfindingAStar = new LongAdder();
        private final LongAdder pathfindingFallback = new LongAdder();
        private final LongAdder pathfindingBudgetExhausted = new LongAdder();
        private final LongAdder pathfindingProviderEvaluations = new LongAdder();
    }
}
