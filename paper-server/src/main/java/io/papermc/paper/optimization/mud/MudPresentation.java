package io.papermc.paper.optimization.mud;

import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.NullMarked;

/** Plugin-owned visual bodies. The plugin owns health, movement, combat and removal. */
@NullMarked
public final class MudPresentation {
    public static final String MARKER = "mud_presentation_v1";

    private MudPresentation() {
    }

    public static boolean skipGameplayTick(final Entity entity) {
        final GlobalConfiguration config = GlobalConfiguration.get();
        return config != null && config.mudOptimizations != null && config.mudOptimizations.presentationMobTick
            && entity instanceof Mob mob && eligible(mob);
    }

    static boolean eligible(final Mob mob) {
        return mob.entityTags().contains(MARKER)
            && mob.isNoAi() && mob.noPhysics && mob.isNoGravity() && mob.isInvulnerable()
            && mob.isAlive() && !mob.isPassenger() && !mob.isVehicle();
    }
}
