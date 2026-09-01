package io.papermc.paper.zvs;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Versioned internal contract for the dedicated ZVS runtime optimizations.
 *
 * <p>This is deliberately not a general Paper gameplay API. Callers must check
 * {@link #isAvailable()} and {@link #API_VERSION}; stock Paper has no provider.</p>
 */
@ApiStatus.Internal
public final class ZvsOptimization {
    /** Contract version implemented by this fork. */
    public static final int API_VERSION = 2;
    private static volatile @Nullable Provider provider;

    private ZvsOptimization() {
    }

    /**
     * Checks whether the running server installed the ZVS provider.
     *
     * @return whether optimized operations are available
     */
    public static boolean isAvailable() {
        return provider != null;
    }

    /**
     * Applies one ordered managed damage request.
     *
     * @param target managed damage target
     * @param amount requested damage
     * @param source optional Bukkit damage source
     * @return effective health-plus-absorption delta, or NaN when rejected
     */
    public static double damage(
        final LivingEntity target,
        final double amount,
        final @Nullable DamageSource source
    ) {
        final Provider current = provider;
        return current == null ? Double.NaN : current.damage(target, amount, source);
    }

    /**
     * Applies managed damage requests in array order.
     *
     * @param targets managed targets
     * @param amounts requested damage values
     * @param sources optional array containing optional damage sources
     * @return per-request effective deltas, with NaN for rejected requests
     */
    public static double[] damageBatch(
        final LivingEntity[] targets,
        final double[] amounts,
        final @Nullable DamageSource @Nullable [] sources
    ) {
        final Provider current = provider;
        if (current == null) {
            final double[] rejected = new double[targets.length];
            Arrays.fill(rejected, Double.NaN);
            return rejected;
        }
        return current.damageBatch(targets, amounts, sources);
    }

    /**
     * Spawns an entity through the trusted managed lifecycle path.
     *
     * @param location    spawn location
     * @param entityClass Bukkit entity class
     * @param initializer synchronous initializer
     * @param <T>         entity type
     * @return the spawned entity, or null when the provider rejects the request
     */
    public static <T extends LivingEntity> @Nullable T spawn(
        final Location location,
        final Class<T> entityClass,
        final Consumer<? super T> initializer
    ) {
        final Provider current = provider;
        return current == null ? null : current.spawn(location, entityClass, initializer);
    }

    /**
     * Registers the single owner-checked managed death handler.
     *
     * @param owner   stable owner identity used for unregistering
     * @param handler managed death handler
     */
    public static void registerDeathHandler(
        final Object owner,
        final Consumer<EntityDeathEvent> handler
    ) {
        final Provider current = Objects.requireNonNull(provider, "ZVS optimization provider is unavailable");
        current.registerDeathHandler(owner, handler);
    }

    /**
     * Unregisters the managed death handler only when its owner matches.
     *
     * @param owner owner identity supplied during registration
     */
    public static void unregisterDeathHandler(final Object owner) {
        final Provider current = provider;
        if (current != null) {
            current.unregisterDeathHandler(owner);
        }
    }

    /**
     * Installs the server implementation once during CraftServer construction.
     *
     * @param installedProvider server provider
     * @throws IllegalStateException if a different provider is already installed
     */
    @ApiStatus.Internal
    public static synchronized void installProvider(final Provider installedProvider) {
        if (provider != null && provider != installedProvider) {
            throw new IllegalStateException("ZVS optimization provider is already installed");
        }
        provider = installedProvider;
    }

    /** Server-side implementation contract. */
    @ApiStatus.Internal
    public interface Provider {
        /**
         * Applies one damage request.
         *
         * @param target target
         * @param amount damage
         * @param source optional source
         * @return effective delta or NaN
         */
        double damage(LivingEntity target, double amount, @Nullable DamageSource source);

        /**
         * Applies a damage batch.
         *
         * @param targets targets
         * @param amounts damage values
         * @param sources optional array containing optional sources
         * @return effective deltas
         */
        double[] damageBatch(LivingEntity[] targets, double[] amounts, @Nullable DamageSource @Nullable [] sources);

        /**
         * Spawns a managed entity.
         *
         * @param location    location
         * @param entityClass entity type
         * @param initializer initializer
         * @param <T>         entity type
         * @return spawned entity or null
         */
        <T extends LivingEntity> @Nullable T spawn(Location location, Class<T> entityClass, Consumer<? super T> initializer);

        /**
         * Registers an owner-checked handler.
         *
         * @param owner   owner identity
         * @param handler handler
         */
        void registerDeathHandler(Object owner, Consumer<EntityDeathEvent> handler);

        /**
         * Unregisters an owned handler.
         *
         * @param owner owner identity
         */
        void unregisterDeathHandler(Object owner);
    }
}
