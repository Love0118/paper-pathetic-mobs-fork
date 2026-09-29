package io.papermc.paper.optimization.zvs.network;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/** Cached PLAY packet classification equivalent to PulseNet's configurable policy. */
public final class ZvsPacketBatchClassifier {
    private static final Set<String> INFRASTRUCTURE_CHANNELS = Set.of("minecraft:register", "minecraft:unregister");

    private ZvsPacketBatchClassifier() {
    }

    public static Classification classify(final Packet<?> packet, final boolean requestedFlush, final Policy policy) {
        // A normal caller flush is a latency request, not an ordering barrier.
        // Tick/interval draining supplies the physical flush for queued traffic.
        if (packet.isTerminal()
            || packet instanceof ClientboundKeepAlivePacket
            || packet instanceof ClientboundDisconnectPacket
            || packet instanceof ClientboundLevelChunkWithLightPacket
            || packet instanceof ClientboundBundlePacket
            || packet instanceof ClientboundPlayerPositionPacket) {
            return new Classification(Action.BARRIER, estimate(packet));
        }

        if (packet instanceof ClientboundCustomPayloadPacket customPayload) {
            final String channel = customPayload.payload().type().id().toString();
            if (INFRASTRUCTURE_CHANNELS.contains(channel) || policy.instantChannels().contains(channel)) {
                return new Classification(Action.BARRIER, estimate(packet));
            }
            if (policy.ignoredChannels().contains(channel)) {
                return new Classification(Action.DIRECT, estimate(packet));
            }
        }

        if (matches(packet, policy.instantPackets())) {
            return new Classification(Action.BARRIER, estimate(packet));
        }
        if (matches(packet, policy.ignoredPackets())) {
            return new Classification(Action.DIRECT, estimate(packet));
        }
        if (policy.chatBypass() && isChatOrResourcePack(packet)) {
            return new Classification(Action.BARRIER, estimate(packet));
        }
        if (policy.packetCoalescing() && matches(packet, policy.coalescePackets()) && isCoalescibleEffect(packet)) {
            return new Classification(Action.COALESCE, estimate(packet));
        }
        return new Classification(Action.QUEUE, estimate(packet));
    }

    public static Classification classify(final Packet<?> packet, final boolean requestedFlush) {
        return classify(packet, requestedFlush, Policy.DEFAULT);
    }

    private static boolean isChatOrResourcePack(final Packet<?> packet) {
        return packet instanceof ClientboundPlayerChatPacket
            || packet instanceof ClientboundSystemChatPacket
            || packet instanceof ClientboundDisguisedChatPacket
            || packet instanceof ClientboundResourcePackPushPacket;
    }

    private static boolean isCoalescibleEffect(final Packet<?> packet) {
        return packet instanceof ClientboundLevelParticlesPacket
            || packet instanceof ClientboundSoundPacket
            || packet instanceof ClientboundSoundEntityPacket;
    }

    private static boolean matches(final Packet<?> packet, final Set<String> configuredNames) {
        final Class<?> type = packet.getClass();
        return configuredNames.contains(type.getSimpleName()) || configuredNames.contains(type.getName());
    }

    private static int estimate(final Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            return 65_536;
        }
        if (packet instanceof ClientboundCustomPayloadPacket) {
            return 1_024;
        }
        return 96;
    }

    public enum Action {
        QUEUE,
        BARRIER,
        DIRECT,
        COALESCE
    }

    public record Classification(Action action, int estimatedBytes) {
        public boolean barrier() {
            return this.action == Action.BARRIER || this.action == Action.DIRECT;
        }
    }

    public record Policy(
        boolean chatBypass,
        boolean packetCoalescing,
        Set<String> instantPackets,
        Set<String> ignoredPackets,
        Set<String> instantChannels,
        Set<String> ignoredChannels,
        Set<String> coalescePackets
    ) {
        static final Policy DEFAULT = new Policy(true, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

        public Policy {
            instantPackets = Set.copyOf(instantPackets);
            ignoredPackets = Set.copyOf(ignoredPackets);
            instantChannels = Set.copyOf(instantChannels);
            ignoredChannels = Set.copyOf(ignoredChannels);
            coalescePackets = Set.copyOf(coalescePackets);
        }

        public static Policy from(final GlobalConfiguration.Optimizations.ZvsPlayNetwork configuration) {
            return new Policy(
                configuration.chatBypass,
                configuration.packetCoalescing,
                safeSet(configuration.instantPackets),
                safeSet(configuration.ignoredPackets),
                safeSet(configuration.instantChannels),
                safeSet(configuration.ignoredChannels),
                safeSet(configuration.coalescePackets)
            );
        }

        private static Set<String> safeSet(final List<String> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            final Set<String> result = new HashSet<>(values.size());
            for (final String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
            return Set.copyOf(result);
        }
    }
}
