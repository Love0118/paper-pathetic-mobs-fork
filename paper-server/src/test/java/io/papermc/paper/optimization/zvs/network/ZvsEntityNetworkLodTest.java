package io.papermc.paper.optimization.zvs.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Normal
class ZvsEntityNetworkLodTest {
    @Test
    void assignsStableDistanceTiers() {
        assertEquals(ZvsEntityNetworkLod.Tier.NEAR, ZvsEntityNetworkLod.tier(32.0 * 32.0, 32, 64));
        assertEquals(ZvsEntityNetworkLod.Tier.MEDIUM, ZvsEntityNetworkLod.tier(33.0 * 33.0, 32, 64));
        assertEquals(ZvsEntityNetworkLod.Tier.FAR, ZvsEntityNetworkLod.tier(65.0 * 65.0, 32, 64));
    }

    @Test
    void cadenceCountsActualServerEntityEmissionsInsteadOfAbsoluteTicks() {
        final ZvsEntityNetworkLod.EmissionCadence cadence = new ZvsEntityNetworkLod.EmissionCadence();
        int allowed = 0;
        for (int emission = 0; emission < 12; emission++) {
            if (cadence.allow(ZvsEntityNetworkLod.Tier.FAR, 4)) {
                allowed++;
            }
        }
        assertEquals(3, allowed);
        assertTrue(cadence.allow(ZvsEntityNetworkLod.Tier.NEAR, 1));
    }

    @Test
    void onePreparedBroadcastReusesItsAbsoluteResyncPacketAcrossViewers() {
        final Entity entity = mock(Entity.class);
        when(entity.entityTags()).thenReturn(Set.of("zvs_managed"));
        when(entity.getId()).thenReturn(17);
        when(entity.trackingPosition()).thenReturn(Vec3.ZERO);
        when(entity.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(entity.onGround()).thenReturn(true);
        final ServerPlayer firstViewer = farViewer(4);
        final ServerPlayer secondViewer = farViewer(8);
        final ClientboundMoveEntityPacket relativeMove = mock(ClientboundMoveEntityPacket.class);
        final ZvsEntityNetworkLod.PreparedPacket prepared = new ZvsEntityNetworkLod.PreparedPacket(
            entity, relativeMove, 3L, true, false, null,
            32.0D * 32.0D, 64.0D * 64.0D, 2, 4, 20, false
        );
        final ZvsEntityNetworkLod.Controller controller = new ZvsEntityNetworkLod.Controller(entity);
        for (int skipped = 0; skipped < 3; skipped++) {
            assertNull(controller.packetFor(prepared, firstViewer));
            assertNull(controller.packetFor(prepared, secondViewer));
        }

        final Packet<? super ClientGamePacketListener> first = controller.packetFor(prepared, firstViewer);
        final Packet<? super ClientGamePacketListener> second = controller.packetFor(prepared, secondViewer);

        assertInstanceOf(ClientboundEntityPositionSyncPacket.class, first);
        assertSame(first, second);
    }

    @Test
    void finalSkippedMovementGetsBoundedAbsoluteRecoveryWhenEmissionsStop() {
        final Entity entity = mock(Entity.class);
        when(entity.trackingPosition()).thenReturn(Vec3.ZERO);
        when(entity.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(entity.onGround()).thenReturn(true);
        final ServerPlayer viewer = farViewer(4);
        final ClientboundMoveEntityPacket relativeMove = mock(ClientboundMoveEntityPacket.class);
        final ZvsEntityNetworkLod.PreparedPacket prepared = new ZvsEntityNetworkLod.PreparedPacket(
            entity, relativeMove, 10L, true, false, null,
            32.0D * 32.0D, 64.0D * 64.0D, 2, 4, 20, false
        );
        final ZvsEntityNetworkLod.Controller controller = new ZvsEntityNetworkLod.Controller(entity);
        assertNull(controller.packetFor(prepared, viewer));
        final List<Packet<? super ClientGamePacketListener>> recovered = new ArrayList<>();

        controller.flushRecoveriesForTesting(29L, Set.of(viewer), (ignored, packet) -> recovered.add(packet));
        assertTrue(recovered.isEmpty());
        controller.flushRecoveriesForTesting(30L, Set.of(viewer), (ignored, packet) -> recovered.add(packet));

        assertEquals(1, recovered.size());
        assertInstanceOf(ClientboundEntityPositionSyncPacket.class, recovered.getFirst());
    }

    private static ServerPlayer farViewer(final int id) {
        final ServerPlayer viewer = mock(ServerPlayer.class);
        when(viewer.getId()).thenReturn(id);
        when(viewer.getX()).thenReturn(100.0D);
        when(viewer.getZ()).thenReturn(100.0D);
        return viewer;
    }
}
