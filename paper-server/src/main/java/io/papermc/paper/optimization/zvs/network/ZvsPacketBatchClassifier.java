package io.papermc.paper.optimization.zvs.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

public final class ZvsPacketBatchClassifier {
    private ZvsPacketBatchClassifier() {
    }

    public static Classification classify(final Packet<?> packet, final boolean requestedFlush) {
        // A caller flush is a latency request, not a protocol ordering barrier.
        // The queue honors it by flushing the drained burst once; treating every
        // Connection.send(packet) as a barrier disables batching entirely.
        final boolean barrier = packet.isTerminal()
            || packet instanceof ClientboundKeepAlivePacket
            || packet instanceof ClientboundDisconnectPacket
            || packet instanceof ClientboundCustomPayloadPacket
            || packet instanceof ClientboundLevelChunkWithLightPacket
            || packet instanceof ClientboundBundlePacket
            || packet instanceof ClientboundPlayerChatPacket
            || packet instanceof ClientboundSystemChatPacket
            || packet instanceof ClientboundPlayerPositionPacket;
        final int estimatedBytes;
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            estimatedBytes = 65_536;
        } else {
            estimatedBytes = 96;
        }
        return new Classification(barrier, estimatedBytes);
    }

    public record Classification(boolean barrier, int estimatedBytes) {
    }
}
