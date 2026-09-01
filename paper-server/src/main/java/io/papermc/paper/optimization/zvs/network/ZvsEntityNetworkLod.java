package io.papermc.paper.optimization.zvs.network;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Per-tracked-entity, per-viewer network LOD with bounded absolute recovery. */
@NullMarked
public final class ZvsEntityNetworkLod {
    private static final LongAdder NEAR_SENT = new LongAdder();
    private static final LongAdder MEDIUM_SENT = new LongAdder();
    private static final LongAdder MEDIUM_SKIPPED = new LongAdder();
    private static final LongAdder FAR_SENT = new LongAdder();
    private static final LongAdder FAR_SKIPPED = new LongAdder();
    private static final LongAdder PROMOTED_SENT = new LongAdder();
    private static final LongAdder ABSOLUTE_RESYNCS = new LongAdder();
    private static final LongAdder RECOVERY_RESYNCS = new LongAdder();
    private static final LongAdder MOVEMENT_SENT = new LongAdder();
    private static final LongAdder MOVEMENT_SKIPPED = new LongAdder();
    private static final LongAdder HEAD_ROTATION_SENT = new LongAdder();
    private static final LongAdder HEAD_ROTATION_SKIPPED = new LongAdder();

    private ZvsEntityNetworkLod() {
    }

    /**
     * Checks the opt-in before allocating per-viewer state. Tags can be attached
     * after tracking starts, so ChunkMap performs this check lazily on a
     * broadcast instead of constructing a controller for every entity.
     */
    public static boolean manages(final Entity entity) {
        final GlobalConfiguration configuration = GlobalConfiguration.get();
        if (configuration == null) {
            return false;
        }
        final GlobalConfiguration.Optimizations.ZvsEntityNetworkLod lod =
            configuration.optimizations.zvsEntityNetworkLod;
        return lod.enabled && entity.entityTags().contains(lod.markerTag);
    }

    public static PreparedPacket prepare(
        final Entity entity,
        final Packet<? super ClientGamePacketListener> packet,
        final long gameTime
    ) {
        if (!(packet instanceof ClientboundMoveEntityPacket || packet instanceof ClientboundRotateHeadPacket)) {
            return PreparedPacket.passThrough(entity, packet, gameTime);
        }
        if (!manages(entity)) {
            return PreparedPacket.passThrough(entity, packet, gameTime);
        }
        final GlobalConfiguration configuration = GlobalConfiguration.get();
        final GlobalConfiguration.Optimizations.ZvsEntityNetworkLod lod = configuration.optimizations.zvsEntityNetworkLod;
        final int safeNear = Math.max(0, lod.nearDistance);
        final int safeMedium = Math.max(safeNear, lod.mediumDistance);
        return new PreparedPacket(
            entity,
            packet,
            gameTime,
            true,
            entity.entityTags().contains(lod.fullRateTag) || entity instanceof EnderDragon || entity instanceof WitherBoss,
            entity instanceof Mob mob ? mob.getTarget() : null,
            (double)safeNear * safeNear,
            (double)safeMedium * safeMedium,
            Math.max(1, lod.mediumInterval),
            Math.max(1, lod.farInterval),
            Math.max(1, lod.maxRecoveryTicks),
            lod.metricsEnabled
        );
    }

    public static final class Controller {
        private final Entity entity;
        private final IdentityHashMap<ServerPlayer, ViewerState> viewers = new IdentityHashMap<>();

        public Controller(final Entity entity) {
            this.entity = entity;
        }

        public @Nullable Packet<? super ClientGamePacketListener> packetFor(
            final PreparedPacket prepared,
            final ServerPlayer viewer
        ) {
            if (!prepared.managed()) {
                return prepared.packet();
            }
            final ViewerState state = this.viewers.computeIfAbsent(viewer, ignored -> new ViewerState());
            final boolean promoted = prepared.promotedForAll() || prepared.promotedViewer() == viewer;
            final Tier tier = promoted ? Tier.NEAR : prepared.tierFor(viewer);
            final int interval = tier == Tier.NEAR ? 1 : tier == Tier.MEDIUM
                ? prepared.mediumInterval()
                : prepared.farInterval();

            if (prepared.packet() instanceof ClientboundMoveEntityPacket) {
                if (promoted || tier == Tier.NEAR) {
                    final Packet<? super ClientGamePacketListener> packet = state.movementDirty
                        ? prepared.absolutePosition()
                        : prepared.packet();
                    state.movementSent(tier);
                    recordTier(tier, promoted, prepared.metricsEnabled());
                    recordMovement(true, prepared.metricsEnabled());
                    return packet;
                }
                if (state.movementCadence.allow(tier, interval)) {
                    state.movementSent(tier);
                    recordTier(tier, false, prepared.metricsEnabled());
                    recordMovement(true, prepared.metricsEnabled());
                    return prepared.absolutePosition();
                }
                state.movementSkipped(prepared.gameTime(), prepared.maxRecoveryTicks(), tier, prepared.metricsEnabled());
                recordSkip(tier, prepared.metricsEnabled());
                recordMovement(false, prepared.metricsEnabled());
                return null;
            }

            if (promoted || tier == Tier.NEAR || state.headCadence.allow(tier, interval)) {
                state.headSent(tier);
                recordTier(tier, promoted, prepared.metricsEnabled());
                recordHead(true, prepared.metricsEnabled());
                return prepared.packet();
            }
            state.headSkipped(
                prepared.packet(), prepared.gameTime(), prepared.maxRecoveryTicks(), tier, prepared.metricsEnabled()
            );
            recordSkip(tier, prepared.metricsEnabled());
            recordHead(false, prepared.metricsEnabled());
            return null;
        }

        public void flushRecoveries(final long gameTime, final Set<ServerPlayerConnection> trackedConnections) {
            if (this.viewers.isEmpty()) {
                return;
            }
            final Set<ServerPlayer> tracked = java.util.Collections.newSetFromMap(
                new IdentityHashMap<>(trackedConnections.size())
            );
            for (final ServerPlayerConnection connection : trackedConnections) {
                tracked.add(connection.getPlayer());
            }
            this.flushRecoveries(gameTime, tracked, (viewer, packet) -> viewer.connection.send(packet));
        }

        void flushRecoveriesForTesting(
            final long gameTime,
            final Set<ServerPlayer> tracked,
            final BiConsumer<ServerPlayer, Packet<? super ClientGamePacketListener>> sender
        ) {
            this.flushRecoveries(gameTime, tracked, sender);
        }

        private void flushRecoveries(
            final long gameTime,
            final Set<ServerPlayer> tracked,
            final BiConsumer<ServerPlayer, Packet<? super ClientGamePacketListener>> sender
        ) {
            this.viewers.keySet().removeIf(viewer -> !tracked.contains(viewer));

            ClientboundEntityPositionSyncPacket absolute = null;
            for (final Map.Entry<ServerPlayer, ViewerState> entry : this.viewers.entrySet()) {
                final ViewerState state = entry.getValue();
                if (state.movementDirty && gameTime >= state.movementRecoveryDeadline) {
                    if (absolute == null) {
                        absolute = ClientboundEntityPositionSyncPacket.of(this.entity);
                    }
                    sender.accept(entry.getKey(), absolute);
                    state.movementSent(state.movementTier);
                    recordAbsolute(state.metricsEnabled, true);
                    recordMovement(true, state.metricsEnabled);
                }
                if (state.pendingHead != null && gameTime >= state.headRecoveryDeadline) {
                    sender.accept(entry.getKey(), state.pendingHead);
                    state.headSent(state.headTier);
                    recordHead(true, state.metricsEnabled);
                }
            }
        }

        public void remove(final ServerPlayer viewer) {
            this.viewers.remove(viewer);
        }

        public void clear() {
            this.viewers.clear();
        }
    }

    public static final class PreparedPacket {
        private final Entity entity;
        private final Packet<? super ClientGamePacketListener> packet;
        private final long gameTime;
        private final boolean managed;
        private final boolean promotedForAll;
        private final @Nullable Entity promotedViewer;
        private final double nearDistanceSquared;
        private final double mediumDistanceSquared;
        private final int mediumInterval;
        private final int farInterval;
        private final int maxRecoveryTicks;
        private final boolean metricsEnabled;
        private @Nullable ClientboundEntityPositionSyncPacket absolutePosition;

        PreparedPacket(
            final Entity entity,
            final Packet<? super ClientGamePacketListener> packet,
            final long gameTime,
            final boolean managed,
            final boolean promotedForAll,
            final @Nullable Entity promotedViewer,
            final double nearDistanceSquared,
            final double mediumDistanceSquared,
            final int mediumInterval,
            final int farInterval,
            final int maxRecoveryTicks,
            final boolean metricsEnabled
        ) {
            this.entity = entity;
            this.packet = packet;
            this.gameTime = gameTime;
            this.managed = managed;
            this.promotedForAll = promotedForAll;
            this.promotedViewer = promotedViewer;
            this.nearDistanceSquared = nearDistanceSquared;
            this.mediumDistanceSquared = mediumDistanceSquared;
            this.mediumInterval = mediumInterval;
            this.farInterval = farInterval;
            this.maxRecoveryTicks = maxRecoveryTicks;
            this.metricsEnabled = metricsEnabled;
        }

        private static PreparedPacket passThrough(
            final Entity entity,
            final Packet<? super ClientGamePacketListener> packet,
            final long gameTime
        ) {
            return new PreparedPacket(entity, packet, gameTime, false, false, null, 0.0D, 0.0D, 1, 1, 1, false);
        }

        private Tier tierFor(final ServerPlayer viewer) {
            final double dx = viewer.getX() - this.entity.getX();
            final double dz = viewer.getZ() - this.entity.getZ();
            return tier(dx * dx + dz * dz, this.nearDistanceSquared, this.mediumDistanceSquared);
        }

        private ClientboundEntityPositionSyncPacket absolutePosition() {
            if (this.absolutePosition == null) {
                this.absolutePosition = ClientboundEntityPositionSyncPacket.of(this.entity);
            }
            recordAbsolute(this.metricsEnabled, false);
            return this.absolutePosition;
        }

        Entity entity() { return this.entity; }
        Packet<? super ClientGamePacketListener> packet() { return this.packet; }
        long gameTime() { return this.gameTime; }
        boolean managed() { return this.managed; }
        boolean promotedForAll() { return this.promotedForAll; }
        @Nullable Entity promotedViewer() { return this.promotedViewer; }
        int mediumInterval() { return this.mediumInterval; }
        int farInterval() { return this.farInterval; }
        int maxRecoveryTicks() { return this.maxRecoveryTicks; }
        boolean metricsEnabled() { return this.metricsEnabled; }
    }

    static final class EmissionCadence {
        private @Nullable Tier tier;
        private int emissions;

        boolean allow(final Tier currentTier, final int interval) {
            if (interval <= 1) {
                this.reset(currentTier);
                return true;
            }
            if (this.tier != currentTier) {
                this.reset(currentTier);
            }
            if (++this.emissions >= interval) {
                this.emissions = 0;
                return true;
            }
            return false;
        }

        void reset(final Tier currentTier) {
            this.tier = currentTier;
            this.emissions = 0;
        }
    }

    private static final class ViewerState {
        private final EmissionCadence movementCadence = new EmissionCadence();
        private final EmissionCadence headCadence = new EmissionCadence();
        private boolean movementDirty;
        private long movementRecoveryDeadline = Long.MAX_VALUE;
        private Tier movementTier = Tier.NEAR;
        private @Nullable Packet<? super ClientGamePacketListener> pendingHead;
        private long headRecoveryDeadline = Long.MAX_VALUE;
        private Tier headTier = Tier.NEAR;
        private boolean metricsEnabled;

        private void movementSkipped(final long gameTime, final int recoveryTicks, final Tier tier, final boolean metrics) {
            this.movementDirty = true;
            this.movementTier = tier;
            this.metricsEnabled = metrics;
            if (this.movementRecoveryDeadline == Long.MAX_VALUE) {
                this.movementRecoveryDeadline = gameTime + recoveryTicks;
            }
        }

        private void movementSent(final Tier tier) {
            this.movementDirty = false;
            this.movementRecoveryDeadline = Long.MAX_VALUE;
            this.movementTier = tier;
            this.movementCadence.reset(tier);
        }

        private void headSkipped(
            final Packet<? super ClientGamePacketListener> packet,
            final long gameTime,
            final int recoveryTicks,
            final Tier tier,
            final boolean metrics
        ) {
            this.pendingHead = packet;
            this.headTier = tier;
            this.metricsEnabled = metrics;
            if (this.headRecoveryDeadline == Long.MAX_VALUE) {
                this.headRecoveryDeadline = gameTime + recoveryTicks;
            }
        }

        private void headSent(final Tier tier) {
            this.pendingHead = null;
            this.headRecoveryDeadline = Long.MAX_VALUE;
            this.headTier = tier;
            this.headCadence.reset(tier);
        }
    }

    static Tier tier(final double distanceSquared, final int nearDistance, final int mediumDistance) {
        final int safeNear = Math.max(0, nearDistance);
        final int safeMedium = Math.max(safeNear, mediumDistance);
        return tier(distanceSquared, (double)safeNear * safeNear, (double)safeMedium * safeMedium);
    }

    private static Tier tier(
        final double distanceSquared,
        final double nearDistanceSquared,
        final double mediumDistanceSquared
    ) {
        if (distanceSquared <= nearDistanceSquared) {
            return Tier.NEAR;
        }
        if (distanceSquared <= mediumDistanceSquared) {
            return Tier.MEDIUM;
        }
        return Tier.FAR;
    }

    private static void recordTier(final Tier tier, final boolean promoted, final boolean metrics) {
        if (!metrics) return;
        if (promoted) PROMOTED_SENT.increment();
        switch (tier) {
            case NEAR -> NEAR_SENT.increment();
            case MEDIUM -> MEDIUM_SENT.increment();
            case FAR -> FAR_SENT.increment();
        }
    }

    private static void recordSkip(final Tier tier, final boolean metrics) {
        if (!metrics) return;
        if (tier == Tier.MEDIUM) MEDIUM_SKIPPED.increment();
        if (tier == Tier.FAR) FAR_SKIPPED.increment();
    }

    private static void recordAbsolute(final boolean metrics, final boolean recovery) {
        if (!metrics) return;
        ABSOLUTE_RESYNCS.increment();
        if (recovery) RECOVERY_RESYNCS.increment();
    }

    private static void recordMovement(final boolean sent, final boolean metrics) {
        if (metrics) (sent ? MOVEMENT_SENT : MOVEMENT_SKIPPED).increment();
    }

    private static void recordHead(final boolean sent, final boolean metrics) {
        if (metrics) (sent ? HEAD_ROTATION_SENT : HEAD_ROTATION_SKIPPED).increment();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            NEAR_SENT.sum(), MEDIUM_SENT.sum(), MEDIUM_SKIPPED.sum(), FAR_SENT.sum(), FAR_SKIPPED.sum(),
            PROMOTED_SENT.sum(), ABSOLUTE_RESYNCS.sum(), RECOVERY_RESYNCS.sum(),
            MOVEMENT_SENT.sum(), MOVEMENT_SKIPPED.sum(), HEAD_ROTATION_SENT.sum(), HEAD_ROTATION_SKIPPED.sum()
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
        long recoveryResyncs,
        long movementSent,
        long movementSkipped,
        long headRotationSent,
        long headRotationSkipped
    ) {
    }
}
