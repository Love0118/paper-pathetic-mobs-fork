package io.papermc.paper.optimization.zvs;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Explicit, server-internal bridge for trusted ZVS programmatic damage.
 *
 * <p>The bridge is deliberately absent from the Paper API. A plugin may detect
 * and invoke it reflectively, while stock Paper keeps using the Bukkit damage
 * methods. Calls are accepted only on the primary tick thread and only for a
 * target carrying the configured scoreboard marker. Armor, enchantments,
 * resistance, absorption, attribution, invulnerability and death are still
 * processed by {@code LivingEntity#hurtServer} in request order.</p>
 */
@NullMarked
public final class ZvsManagedDamage {
    public enum EventMode {
        COMPATIBILITY,
        HYBRID,
        TRUSTED
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final Map<Entity, Boolean> EVENTS_THIS_TICK = new IdentityHashMap<>();
    private static final Map<Entity, Boolean> HURT_STATUS_THIS_TICK = new IdentityHashMap<>();
    private static int eventTick = Integer.MIN_VALUE;
    private static int hurtStatusTick = Integer.MIN_VALUE;

    private static final LongAdder BATCHES = new LongAdder();
    private static final LongAdder REQUESTS = new LongAdder();
    private static final LongAdder APPLIED = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder EVENTS_DISPATCHED = new LongAdder();
    private static final LongAdder EVENTS_SKIPPED = new LongAdder();
    private static final LongAdder HURT_STATUS_SKIPPED = new LongAdder();

    private ZvsManagedDamage() {
    }

    /**
     * Applies one managed request synchronously. {@link Double#NaN} means the
     * bridge did not accept the request and the caller must use its normal
     * Bukkit compatibility path.
     */
    public static double damage(
        final LivingEntity target,
        final double amount,
        final @Nullable DamageSource source
    ) {
        BATCHES.increment();
        return apply(target, amount, source);
    }

    /**
     * Applies a tick-local batch in array order and returns each effective
     * health-plus-absorption delta. Rejected entries contain {@link Double#NaN}.
     */
    public static double[] damageBatch(
        final LivingEntity[] targets,
        final double[] amounts,
        final @Nullable DamageSource[] sources
    ) {
        if (targets.length != amounts.length || (sources != null && sources.length != targets.length)) {
            throw new IllegalArgumentException("Managed damage arrays must have equal lengths");
        }
        BATCHES.increment();
        final double[] applied = new double[targets.length];
        for (int index = 0; index < targets.length; index++) {
            applied[index] = apply(targets[index], amounts[index], sources == null ? null : sources[index]);
        }
        return applied;
    }

    private static double apply(
        final LivingEntity target,
        final double amount,
        final @Nullable DamageSource source
    ) {
        REQUESTS.increment();
        if (!eligible(target, amount)) {
            REJECTED.increment();
            return Double.NaN;
        }

        final double before = effectiveHealth(target);
        final Context previous = CURRENT.get();
        CURRENT.set(new Context(target, configuredMode()));
        try {
            if (source == null) {
                target.damage(amount);
            } else {
                target.damage(amount, source);
            }
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
        APPLIED.increment();
        return Math.max(0.0D, before - effectiveHealth(target));
    }

    private static boolean eligible(final LivingEntity target, final double amount) {
        final GlobalConfiguration.Optimizations.ZvsManagedDamage configuration =
            GlobalConfiguration.get().optimizations.zvsManagedDamage;
        return configuration.enabled
            && Bukkit.isPrimaryThread()
            && Double.isFinite(amount)
            && amount > 0.0D
            && !target.isDead()
            && target.isValid()
            && !configuration.markerTag.isEmpty()
            && target.getScoreboardTags().contains(configuration.markerTag);
    }

    private static double effectiveHealth(final LivingEntity target) {
        return Math.max(0.0D, target.getHealth()) + Math.max(0.0D, target.getAbsorptionAmount());
    }

    private static EventMode configuredMode() {
        final String configured = GlobalConfiguration.get().optimizations.zvsManagedDamage.eventMode;
        if (configured == null) {
            return EventMode.COMPATIBILITY;
        }
        try {
            return EventMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ignored) {
            return EventMode.COMPATIBILITY;
        }
    }

    /** Called by the CraftBukkit event bridge for an already-created damage event. */
    public static boolean shouldDispatchEvent(final Entity damagee) {
        final Context context = CURRENT.get();
        if (context == null || context.target().getEntityId() != damagee.getId()) {
            return true;
        }
        if (context.mode() == EventMode.COMPATIBILITY) {
            EVENTS_DISPATCHED.increment();
            return true;
        }
        if (context.mode() == EventMode.TRUSTED) {
            EVENTS_SKIPPED.increment();
            return false;
        }

        final int tick = MinecraftServer.currentTick;
        if (eventTick != tick) {
            eventTick = tick;
            EVENTS_THIS_TICK.clear();
        }
        if (EVENTS_THIS_TICK.put(damagee, Boolean.TRUE) == null) {
            EVENTS_DISPATCHED.increment();
            return true;
        }
        EVENTS_SKIPPED.increment();
        return false;
    }

    /** Called immediately before the managed damage-status packet is broadcast. */
    public static boolean shouldBroadcastHurtStatus(final Entity damagee) {
        final Context context = CURRENT.get();
        if (context == null
            || context.target().getEntityId() != damagee.getId()
            || context.mode() == EventMode.COMPATIBILITY
            || !GlobalConfiguration.get().optimizations.zvsManagedDamage.coalesceHurtStatus) {
            return true;
        }
        final int tick = MinecraftServer.currentTick;
        if (hurtStatusTick != tick) {
            hurtStatusTick = tick;
            HURT_STATUS_THIS_TICK.clear();
        }
        if (HURT_STATUS_THIS_TICK.put(damagee, Boolean.TRUE) == null) {
            return true;
        }
        HURT_STATUS_SKIPPED.increment();
        return false;
    }

    static boolean isTrustedContext(final Entity entity) {
        final Context context = CURRENT.get();
        return context != null
            && context.mode() == EventMode.TRUSTED
            && context.target().getEntityId() == entity.getId();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            BATCHES.sum(),
            REQUESTS.sum(),
            APPLIED.sum(),
            REJECTED.sum(),
            EVENTS_DISPATCHED.sum(),
            EVENTS_SKIPPED.sum(),
            HURT_STATUS_SKIPPED.sum()
        );
    }

    static void resetForTesting() {
        BATCHES.reset();
        REQUESTS.reset();
        APPLIED.reset();
        REJECTED.reset();
        EVENTS_DISPATCHED.reset();
        EVENTS_SKIPPED.reset();
        HURT_STATUS_SKIPPED.reset();
        EVENTS_THIS_TICK.clear();
        HURT_STATUS_THIS_TICK.clear();
        eventTick = Integer.MIN_VALUE;
        hurtStatusTick = Integer.MIN_VALUE;
        CURRENT.remove();
    }

    private record Context(LivingEntity target, EventMode mode) {
    }

    public record Snapshot(
        long batches,
        long requests,
        long applied,
        long rejected,
        long eventsDispatched,
        long eventsSkipped,
        long hurtStatusSkipped
    ) {
    }
}
