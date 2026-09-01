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
    public static final int API_VERSION = ZvsManagedDamage.API_VERSION;
    private static final ThreadLocal<Boolean> TRUSTED_SPAWN = new ThreadLocal<>();
    private static volatile @Nullable DeathHandler deathHandler;
    private static volatile @Nullable Object deathHandlerOwner;
    private static final Object LEGACY_OWNER = new Object();
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

    public static synchronized void registerDeathHandler(final @Nullable DeathHandler handler) {
        if (handler == null) {
            unregisterDeathHandler(LEGACY_OWNER);
        } else {
            registerDeathHandler(LEGACY_OWNER, handler);
        }
    }

    public static synchronized void registerDeathHandler(final Object owner, final DeathHandler handler) {
        if (deathHandler != null && deathHandlerOwner != owner) {
            throw new IllegalStateException("A ZVS managed death handler is already registered by another owner");
        }
        deathHandlerOwner = owner;
        deathHandler = handler;
    }

    public static synchronized void unregisterDeathHandler(final Object owner) {
        if (deathHandlerOwner == owner) {
            deathHandler = null;
            deathHandlerOwner = null;
        }
    }

    /** Returns true when the dedicated handler replaced global event dispatch. */
    public static boolean dispatchManagedDeath(final Entity victim, final EntityDeathEvent event) {
        final DeathHandler handler = deathHandler;
        if (handler == null
            || !GlobalConfiguration.get().optimizations.zvsManagedDamage.trustedDeathHandler
            || !ZvsManagedDamage.usesTrustedFastPath(victim)) {
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
        deathHandlerOwner = null;
    }

    @FunctionalInterface
    public interface DeathHandler {
        void handle(EntityDeathEvent event);
    }

    public record Snapshot(long trustedSpawns, long trustedDeaths) {
    }
}
