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
        assertBarrier(mock(ClientboundLevelChunkWithLightPacket.class));
        assertBarrier(mock(ClientboundPlayerPositionPacket.class));
    }

    @Test
    void callerFlushAndOrdinaryGameplayPacketsAreNotOrderingBarriers() {
        final ClientboundLevelParticlesPacket particle = mock(ClientboundLevelParticlesPacket.class);
        assertFalse(ZvsPacketBatchClassifier.classify(particle, false).barrier());
        assertFalse(ZvsPacketBatchClassifier.classify(particle, true).barrier());
        assertFalse(ZvsPacketBatchClassifier.classify(mock(ClientboundDamageEventPacket.class), true).barrier());
    }

    private static void assertBarrier(final Packet<?> packet) {
        assertTrue(ZvsPacketBatchClassifier.classify(packet, false).barrier());
    }
}
