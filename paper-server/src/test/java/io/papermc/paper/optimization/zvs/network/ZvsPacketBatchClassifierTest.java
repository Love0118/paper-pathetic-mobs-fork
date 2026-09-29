package io.papermc.paper.optimization.zvs.network;

import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.Identifier;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Normal
class ZvsPacketBatchClassifierTest {
    @Test
    void protocolAndGameplayCriticalPacketsAreFlushBarriers() {
        assertBarrier(mock(ClientboundKeepAlivePacket.class));
        assertBarrier(customPayload("minecraft:register"));
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

    @Test
    void configurablePacketAndChannelPoliciesAreHonored() {
        final ZvsPacketBatchClassifier.Policy policy = new ZvsPacketBatchClassifier.Policy(
            false,
            true,
            Set.of("ClientboundDamageEventPacket"),
            Set.of("ClientboundLevelParticlesPacket"),
            Set.of("zvs:instant"),
            Set.of("zvs:direct"),
            Set.of("ClientboundLevelParticlesPacket")
        );

        assertEquals(
            ZvsPacketBatchClassifier.Action.BARRIER,
            ZvsPacketBatchClassifier.classify(mock(ClientboundDamageEventPacket.class), false, policy).action()
        );
        assertEquals(
            ZvsPacketBatchClassifier.Action.DIRECT,
            ZvsPacketBatchClassifier.classify(mock(ClientboundLevelParticlesPacket.class), false, policy).action()
        );
        assertEquals(
            ZvsPacketBatchClassifier.Action.BARRIER,
            ZvsPacketBatchClassifier.classify(customPayload("zvs:instant"), false, policy).action()
        );
        assertEquals(
            ZvsPacketBatchClassifier.Action.DIRECT,
            ZvsPacketBatchClassifier.classify(customPayload("zvs:direct"), false, policy).action()
        );
    }

    @Test
    void effectCoalescingClassificationIsExplicit() {
        final ZvsPacketBatchClassifier.Policy policy = new ZvsPacketBatchClassifier.Policy(
            true, true, Set.of(), Set.of(), Set.of(), Set.of(), Set.of("ClientboundLevelParticlesPacket")
        );
        assertEquals(
            ZvsPacketBatchClassifier.Action.COALESCE,
            ZvsPacketBatchClassifier.classify(mock(ClientboundLevelParticlesPacket.class), false, policy).action()
        );
    }

    private static ClientboundCustomPayloadPacket customPayload(final String channel) {
        final CustomPacketPayload payload = () -> new CustomPacketPayload.Type<>(Identifier.parse(channel));
        return new ClientboundCustomPayloadPacket(payload);
    }

    private static void assertBarrier(final Packet<?> packet) {
        assertTrue(ZvsPacketBatchClassifier.classify(packet, false).barrier());
    }
}
