package io.papermc.paper.optimization.zvs;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Opt-in spawn and death shortcuts for the dedicated ZVS plugin. */
@NullMarked
public final class ZvsManagedLifecycle {
    private static final ThreadLocal<Boolean> TRUSTED_SPAWN = new ThreadLocal<>();
    private static volatile @Nullable DeathHandler deathHandler;
    private static final LongAdder TRUSTED_SPAWNS = new LongAdder();
    private static final LongAdder TRUSTED_DEATHS = new LongAdder();

    private ZvsManagedLifecycle() {
    }

    /** Returns {@code null} when the caller must use the normal Bukkit spawn path. */
    public static <T extends LivingEntity> @Nullable T spawn(
        final Location location,
        final Class<T> entityClass,
        final Consumer<? super T> initializer
    ) {
        final World world = location.getWorld();
        final GlobalConfiguration.Optimizations.ZvsManagedDamage configuration =
            GlobalConfiguration.get().optimizations.zvsManagedDamage;
        if (!configuration.enabled
            || !configuration.trustedSpawnEvents
            || !Bukkit.isPrimaryThread()
            || world == null) {
            return null;
        }

        final Boolean previous = TRUSTED_SPAWN.get();
        TRUSTED_SPAWN.set(Boolean.TRUE);
        try {
            final T spawned = world.spawn(location, entityClass, initializer);
            TRUSTED_SPAWNS.increment();
            return spawned;
        } finally {
            if (previous == null) {
                TRUSTED_SPAWN.remove();
            } else {
                TRUSTED_SPAWN.set(previous);
            }
        }
    }

    public static boolean shouldDispatchCreatureSpawnEvent() {
        return !Boolean.TRUE.equals(TRUSTED_SPAWN.get());
    }

    public static void registerDeathHandler(final @Nullable DeathHandler handler) {
        deathHandler = handler;
    }

    /** Returns true when the dedicated handler replaced global event dispatch. */
    public static boolean dispatchManagedDeath(final Entity victim, final EntityDeathEvent event) {
        final DeathHandler handler = deathHandler;
        if (handler == null
            || !GlobalConfiguration.get().optimizations.zvsManagedDamage.trustedDeathHandler
            || !ZvsManagedDamage.isTrustedContext(victim)) {
            return false;
        }
        handler.handle(event);
        TRUSTED_DEATHS.increment();
        return true;
    }

    public static Snapshot snapshot() {
        return new Snapshot(TRUSTED_SPAWNS.sum(), TRUSTED_DEATHS.sum());
    }

    static void resetForTesting() {
        TRUSTED_SPAWNS.reset();
        TRUSTED_DEATHS.reset();
        TRUSTED_SPAWN.remove();
        deathHandler = null;
    }

    @FunctionalInterface
    public interface DeathHandler {
        void handle(EntityDeathEvent event);
    }

    public record Snapshot(long trustedSpawns, long trustedDeaths) {
    }
}
