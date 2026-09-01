package io.papermc.paper.optimization.zvs.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

public final class ZvsPacketBatchClassifier {
    private ZvsPacketBatchClassifier() {
    }

    public static Classification classify(final Packet<?> packet, final boolean requestedFlush) {
        final boolean barrier = requestedFlush || packet.isTerminal()
            || packet instanceof ClientboundKeepAlivePacket
            || packet instanceof ClientboundDisconnectPacket
            || packet instanceof ClientboundCustomPayloadPacket
            || packet instanceof ClientboundDamageEventPacket
            || packet instanceof ClientboundLevelChunkWithLightPacket
            || packet instanceof ClientboundBundlePacket
            || packet instanceof ClientboundPlayerChatPacket
            || packet instanceof ClientboundSystemChatPacket
            || packet instanceof ClientboundPlayerPositionPacket
            || packet instanceof ClientboundRemoveEntitiesPacket;
        final int estimatedBytes;
        if (packet instanceof ClientboundLevelParticlesPacket) {
            estimatedBytes = 64;
        } else if (packet instanceof ClientboundSoundPacket || packet instanceof ClientboundSoundEntityPacket) {
            estimatedBytes = 48;
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            estimatedBytes = 65_536;
        } else {
            estimatedBytes = 96;
        }
        final boolean effectBundleCandidate = packet instanceof ClientboundLevelParticlesPacket
            || packet instanceof ClientboundSoundPacket
            || packet instanceof ClientboundSoundEntityPacket;
        return new Classification(barrier, estimatedBytes, effectBundleCandidate);
    }

    public record Classification(boolean barrier, int estimatedBytes, boolean effectBundleCandidate) {
    }
}
