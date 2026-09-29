package io.papermc.paper.optimization.zvs;

import io.papermc.paper.zvs.ZvsOptimization;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public enum ZvsOptimizationProvider implements ZvsOptimization.Provider {
    INSTANCE;

    @Override
    public double damage(final LivingEntity target, final double amount, final @Nullable DamageSource source) {
        return ZvsManagedDamage.damage(target, amount, source);
    }

    @Override
    public double[] damageBatch(
        final LivingEntity[] targets,
        final double[] amounts,
        final DamageSource @Nullable [] sources
    ) {
        return ZvsManagedDamage.damageBatch(targets, amounts, sources);
    }

    @Override
    public <T extends LivingEntity> @Nullable T spawn(
        final Location location,
        final Class<T> entityClass,
        final Consumer<? super T> initializer
    ) {
        return ZvsManagedLifecycle.spawn(location, entityClass, initializer);
    }

    @Override
    public void registerDeathHandler(final Object owner, final Consumer<EntityDeathEvent> handler) {
        ZvsManagedLifecycle.registerDeathHandler(owner, handler::accept);
    }

    @Override
    public void unregisterDeathHandler(final Object owner) {
        ZvsManagedLifecycle.unregisterDeathHandler(owner);
    }
}
