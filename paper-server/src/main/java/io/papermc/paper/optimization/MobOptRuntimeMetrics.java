package io.papermc.paper.optimization;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in counters used to verify that MobOpt fast paths are exercised by a
 * real server workload. The state and shutdown hook do not exist unless the
 * verification JVM property is enabled.
 */
public final class MobOptRuntimeMetrics {
    public static final boolean ENABLED = Boolean.getBoolean("paper.mobopt.verifyFastPaths");
    private static final String RUN_ID = sanitizeRunId(System.getProperty("paper.mobopt.verifyFastPaths.runId", "unset"));
    private static final State STATE = ENABLED ? new State() : null;

    static {
        if (ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(MobOptRuntimeMetrics::emitOnce, "MobOpt fast-path metrics"));
        }
    }

    private MobOptRuntimeMetrics() {
    }

    public static void frameDecoded(final boolean retained, final int bytes) {
        if (!ENABLED) {
            return;
        }
        (retained ? STATE.frameRetained : STATE.frameCopied).add(bytes);
    }

    public static void compressionPassthrough(final boolean retained, final int bytes) {
        if (!ENABLED) {
            return;
        }
        (retained ? STATE.compressionRetained : STATE.compressionCopied).add(bytes);
    }

    public static void framePrefixInPlace(final int bodyBytes) {
        if (ENABLED) {
            STATE.prefixInPlace.add(bodyBytes);
        }
    }

    public static void framePrefixFallback(final boolean optimizationEnabled, final int bodyBytes) {
        if (!ENABLED) {
            return;
        }
        (optimizationEnabled ? STATE.prefixEnabledIneligible : STATE.prefixDisabled).add(bodyBytes);
    }

    public static void playWriteDispatch(final boolean lazy) {
        if (!ENABLED) {
            return;
        }
        (lazy ? STATE.playLazyTasks : STATE.playNormalTasks).increment();
    }

    public static void explosionLookup(final boolean indexed, final int worldPlayers, final int candidates) {
        if (!ENABLED) {
            return;
        }
        (indexed ? STATE.explosionIndexedLookups : STATE.explosionFullScans).increment();
        STATE.explosionWorldPlayers.add(worldPlayers);
        STATE.explosionCandidates.add(candidates);
    }

    public static void explosionRecipient() {
        if (ENABLED) {
            STATE.explosionRecipients.increment();
        }
    }

    private static void emitOnce() {
        if (STATE.emitted.compareAndSet(false, true)) {
            System.out.println(formatSnapshot(STATE));
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
            + " play_lazy_tasks=" + state.playLazyTasks.sum()
            + " play_normal_tasks=" + state.playNormalTasks.sum()
            + " explosion_indexed_lookups=" + state.explosionIndexedLookups.sum()
            + " explosion_full_scans=" + state.explosionFullScans.sum()
            + " explosion_world_players=" + state.explosionWorldPlayers.sum()
            + " explosion_candidates=" + state.explosionCandidates.sum()
            + " explosion_recipients=" + state.explosionRecipients.sum();
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
        private final LongAdder playLazyTasks = new LongAdder();
        private final LongAdder playNormalTasks = new LongAdder();
        private final LongAdder explosionIndexedLookups = new LongAdder();
        private final LongAdder explosionFullScans = new LongAdder();
        private final LongAdder explosionWorldPlayers = new LongAdder();
        private final LongAdder explosionCandidates = new LongAdder();
        private final LongAdder explosionRecipients = new LongAdder();
    }
}
