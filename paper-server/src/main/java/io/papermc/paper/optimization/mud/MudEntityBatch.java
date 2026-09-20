package io.papermc.paper.optimization.mud;

import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.entity.CraftEntity;

/** Java-only movement batch; validate every entity before changing any positions. */
public final class MudEntityBatch {
    private MudEntityBatch() {}

    public static boolean move(final org.bukkit.entity.Entity[] entities, final double[] positions, final int count) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread("MUD motion must run on server thread");
        if (count < 0 || count > 4096 || entities.length < count || positions.length < count * 5) throw new IllegalArgumentException("Invalid batch size");
        final Entity[] handles = new Entity[count];
        for (int i = 0; i < count; i++) {
            if (!(entities[i] instanceof CraftEntity craft)) return false;
            final Entity handle = craft.getHandle();
            if (!handle.valid || !MudPresentation.skipGameplayTick(handle)) return false;
            for (int j = 0; j < 5; j++) if (!Double.isFinite(positions[i * 5 + j])) throw new IllegalArgumentException("Non-finite position");
            if (Math.abs(positions[i * 5 + 3]) > Float.MAX_VALUE || Math.abs(positions[i * 5 + 4]) > Float.MAX_VALUE) throw new IllegalArgumentException("Invalid rotation");
            if (!craft.getWorld().isChunkLoaded(org.bukkit.util.NumberConversions.floor(positions[i * 5]) >> 4,
                org.bukkit.util.NumberConversions.floor(positions[i * 5 + 2]) >> 4)) return false;
            handles[i] = handle;
        }
        for (int i = 0; i < count; i++) {
            final Entity entity = handles[i];
            entity.setPos(positions[i*5], positions[i*5+1], positions[i*5+2]);
            entity.setYRot((float) positions[i*5+3]);
            entity.setYHeadRot((float) positions[i*5+3]);
            entity.setXRot((float) positions[i*5+4]);
        }
        return true;
    }
}
