package io.papermc.paper.optimization.zvs;

import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.NullMarked;

/** Tag-gated selector cadence for large managed waves. */
@NullMarked
public final class ZvsManagedMobAi {
    private ZvsManagedMobAi() {
    }

    public static boolean shouldThrottleSelectors(final Mob mob) {
        final GlobalConfiguration configuration = GlobalConfiguration.get();
        if (configuration == null) {
            return false;
        }
        final GlobalConfiguration.Optimizations.ZvsManagedMobAi managed =
            configuration.optimizations.zvsManagedMobAi;
        if (!managed.enabled
            || managed.markerTag == null
            || managed.markerTag.isEmpty()
            || !mob.entityTags().contains(managed.markerTag)
            || managed.fullRateTag != null
                && !managed.fullRateTag.isEmpty()
                && mob.entityTags().contains(managed.fullRateTag)) {
            return false;
        }

        final LivingEntity target = mob.getTarget();
        if (target == null) {
            return true;
        }
        final double fullRateDistance = Math.max(0.0D, managed.fullRateTargetDistance);
        return horizontalDistanceSquared(mob, target) > fullRateDistance * fullRateDistance;
    }

    public static boolean shouldRunThrottledSelectors(final Mob mob) {
        final GlobalConfiguration configuration = GlobalConfiguration.get();
        final int interval = configuration == null
            ? 1
            : configuration.optimizations.zvsManagedMobAi.selectorInterval;
        return selectorPhase(mob.tickCount, mob.getId(), interval);
    }

    static boolean selectorPhase(final int tickCount, final int entityId, final int configuredInterval) {
        final int interval = Math.max(1, configuredInterval);
        return Math.floorMod(tickCount + entityId, interval) == 0;
    }

    static boolean outsideFullRateDistance(
        final double mobX,
        final double mobZ,
        final double targetX,
        final double targetZ,
        final double configuredDistance
    ) {
        final double distance = Math.max(0.0D, configuredDistance);
        final double deltaX = mobX - targetX;
        final double deltaZ = mobZ - targetZ;
        return deltaX * deltaX + deltaZ * deltaZ > distance * distance;
    }

    private static double horizontalDistanceSquared(final Mob mob, final LivingEntity target) {
        final double deltaX = mob.getX() - target.getX();
        final double deltaZ = mob.getZ() - target.getZ();
        return deltaX * deltaX + deltaZ * deltaZ;
    }
}
