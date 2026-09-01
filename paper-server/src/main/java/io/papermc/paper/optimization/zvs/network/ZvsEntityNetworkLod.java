package io.papermc.paper.optimization.zvs.network;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.jspecify.annotations.Nullable;

public final class ZvsEntityNetworkLod {
    private static final LongAdder NEAR_SENT = new LongAdder();
    private static final LongAdder MEDIUM_SENT = new LongAdder();
    private static final LongAdder MEDIUM_SKIPPED = new LongAdder();
    private static final LongAdder FAR_SENT = new LongAdder();
    private static final LongAdder FAR_SKIPPED = new LongAdder();
    private static final LongAdder PROMOTED_SENT = new LongAdder();
    private static final LongAdder ABSOLUTE_RESYNCS = new LongAdder();
    private static final LongAdder MOVEMENT_SENT = new LongAdder();
    private static final LongAdder MOVEMENT_SKIPPED = new LongAdder();
    private static final LongAdder HEAD_ROTATION_SENT = new LongAdder();
    private static final LongAdder HEAD_ROTATION_SKIPPED = new LongAdder();

    private ZvsEntityNetworkLod() {
    }

    public static @Nullable Packet<? super ClientGamePacketListener> packetFor(
        final Entity entity,
        final ServerPlayer viewer,
        final Packet<? super ClientGamePacketListener> packet,
        final long gameTime
    ) {
        if (!isCadencePacket(packet)) {
            return packet;
        }
        final GlobalConfiguration configuration = GlobalConfiguration.get();
        if (configuration == null || !configuration.optimizations.zvsEntityNetworkLod.enabled) {
            return packet;
        }
        final GlobalConfiguration.Optimizations.ZvsEntityNetworkLod lod = configuration.optimizations.zvsEntityNetworkLod;
        if (!entity.entityTags().contains(lod.markerTag)) {
            return packet;
        }

        final boolean promoted = isPromoted(entity, viewer, lod.fullRateTag);
        if (promoted) {
            PROMOTED_SENT.increment();
            recordPacket(packet, true);
            return packet;
        }
        final double dx = viewer.getX() - entity.getX();
        final double dz = viewer.getZ() - entity.getZ();
        final double distanceSquared = dx * dx + dz * dz;
        final Tier tier = tier(distanceSquared, lod.nearDistance, lod.mediumDistance);
        final int interval = tier == Tier.NEAR ? 1 : tier == Tier.MEDIUM ? Math.max(1, lod.mediumInterval) : Math.max(1, lod.farInterval);
        if (!cadenceAllows(gameTime, entity.getId(), viewer.getId(), interval)) {
            if (tier == Tier.MEDIUM) {
                MEDIUM_SKIPPED.increment();
            } else {
                FAR_SKIPPED.increment();
            }
            recordPacket(packet, false);
            return null;
        }

        switch (tier) {
            case NEAR -> NEAR_SENT.increment();
            case MEDIUM -> MEDIUM_SENT.increment();
            case FAR -> FAR_SENT.increment();
        }
        recordPacket(packet, true);
        if (packet instanceof ClientboundMoveEntityPacket) {
            ABSOLUTE_RESYNCS.increment();
            return ClientboundEntityPositionSyncPacket.of(entity);
        }
        return packet;
    }

    static boolean cadenceAllows(final long gameTime, final int entityId, final int viewerId, final int interval) {
        if (interval <= 1) {
            return true;
        }
        final int phase = Math.floorMod(entityId * 31 + viewerId, interval);
        return Math.floorMod(gameTime, interval) == phase;
    }

    static Tier tier(final double distanceSquared, final int nearDistance, final int mediumDistance) {
        final int safeNear = Math.max(0, nearDistance);
        final int safeMedium = Math.max(safeNear, mediumDistance);
        if (distanceSquared <= (double)safeNear * safeNear) {
            return Tier.NEAR;
        }
        if (distanceSquared <= (double)safeMedium * safeMedium) {
            return Tier.MEDIUM;
        }
        return Tier.FAR;
    }

    private static boolean isCadencePacket(final Packet<?> packet) {
        return packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundRotateHeadPacket;
    }

    private static void recordPacket(final Packet<?> packet, final boolean sent) {
        if (packet instanceof ClientboundRotateHeadPacket) {
            (sent ? HEAD_ROTATION_SENT : HEAD_ROTATION_SKIPPED).increment();
        } else {
            (sent ? MOVEMENT_SENT : MOVEMENT_SKIPPED).increment();
        }
    }

    private static boolean isPromoted(final Entity entity, final ServerPlayer viewer, final String fullRateTag) {
        return entity.entityTags().contains(fullRateTag)
            || entity instanceof EnderDragon
            || entity instanceof WitherBoss
            || entity instanceof Mob mob && mob.getTarget() == viewer;
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            NEAR_SENT.sum(), MEDIUM_SENT.sum(), MEDIUM_SKIPPED.sum(), FAR_SENT.sum(), FAR_SKIPPED.sum(),
            PROMOTED_SENT.sum(), ABSOLUTE_RESYNCS.sum(), MOVEMENT_SENT.sum(), MOVEMENT_SKIPPED.sum(),
            HEAD_ROTATION_SENT.sum(), HEAD_ROTATION_SKIPPED.sum()
        );
    }

    public enum Tier {
        NEAR,
        MEDIUM,
        FAR
    }

    public record Snapshot(
        long nearSent,
        long mediumSent,
        long mediumSkipped,
        long farSent,
        long farSkipped,
        long promotedSent,
        long absoluteResyncs,
        long movementSent,
        long movementSkipped,
        long headRotationSent,
        long headRotationSkipped
    ) {
    }
}
