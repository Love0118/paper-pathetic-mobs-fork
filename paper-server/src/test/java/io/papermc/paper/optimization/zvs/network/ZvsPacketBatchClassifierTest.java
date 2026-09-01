package io.papermc.paper.optimization.zvs.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Normal
class ZvsPacketBatchClassifierTest {
    @Test
    void protocolAndGameplayCriticalPacketsAreFlushBarriers() {
        assertBarrier(mock(ClientboundKeepAlivePacket.class));
        assertBarrier(mock(ClientboundCustomPayloadPacket.class));
        assertBarrier(mock(ClientboundDamageEventPacket.class));
        assertBarrier(mock(ClientboundLevelChunkWithLightPacket.class));
        assertBarrier(mock(ClientboundPlayerPositionPacket.class));
    }

    @Test
    void effectsCanBundleButAnExplicitCallerFlushStillWins() {
        final ClientboundLevelParticlesPacket particle = mock(ClientboundLevelParticlesPacket.class);
        final ZvsPacketBatchClassifier.Classification queued = ZvsPacketBatchClassifier.classify(particle, false);
        assertFalse(queued.barrier());
        assertTrue(queued.effectBundleCandidate());
        assertTrue(ZvsPacketBatchClassifier.classify(particle, true).barrier());
    }

    private static void assertBarrier(final Packet<?> packet) {
        assertTrue(ZvsPacketBatchClassifier.classify(packet, false).barrier());
    }
}
