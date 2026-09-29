package io.papermc.paper.optimization.zvs;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.PiercingWeapon;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Normal
class StabArmSwingEventTest {
    @Test
    void spearPacketPublishesOneMainHandSwingAndKeepsNativeAttack() {
        verifyStab(false, false, 1);
    }

    @Test
    void nativeChargeGateDoesNotDropPluginProjectileInput() {
        verifyStab(true, false, 0);
    }

    @Test
    void cancelledSwingDoesNotExecuteTheStab() {
        verifyStab(false, true, 0);
    }

    private void verifyStab(boolean charging, boolean cancelled, int nativeAttacks) {
        ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CraftPlayer bukkitPlayer = mock(CraftPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PacketProcessor processor = mock(PacketProcessor.class);
        ItemStack item = mock(ItemStack.class);
        PiercingWeapon piercing = mock(PiercingWeapon.class);
        PluginManager plugins = mock(PluginManager.class);
        listener.player = player;
        when(listener.hasClientLoaded()).thenReturn(true);
        when(listener.getCraftPlayer()).thenReturn(bukkitPlayer);
        when(player.level()).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        when(server.packetProcessor()).thenReturn(processor);
        when(processor.isSameThread()).thenReturn(true);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(item);
        when(item.get(DataComponents.PIERCING_WEAPON)).thenReturn(piercing);
        when(player.cannotAttackWithItem(item, 5)).thenReturn(charging);
        doCallRealMethod().when(listener).handlePlayerAction(any());
        doAnswer(invocation -> {
            ((PlayerArmSwingEvent) invocation.getArgument(0)).setCancelled(cancelled);
            return null;
        }).when(plugins).callEvent(any(PlayerArmSwingEvent.class));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            listener.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN));
        }

        ArgumentCaptor<PlayerArmSwingEvent> event = ArgumentCaptor.forClass(PlayerArmSwingEvent.class);
        verify(plugins).callEvent(event.capture());
        assertSame(bukkitPlayer, event.getValue().getPlayer());
        assertEquals(org.bukkit.inventory.EquipmentSlot.HAND, event.getValue().getHand());
        assertEquals(PlayerAnimationType.ARM_SWING, event.getValue().getAnimationType());
        verify(piercing, times(nativeAttacks)).attack(player, EquipmentSlot.MAINHAND);
    }
}
