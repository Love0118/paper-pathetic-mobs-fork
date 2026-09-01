package io.papermc.paper.optimization.zvs;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Normal
class ZvsManagedDamageTest {
    @Test
    void batchAppliesRequestsInArrayOrderAndReturnsEffectiveDeltas() {
        final GlobalConfiguration.Optimizations.ZvsManagedDamage configuration = configuration();
        final String previousMode = configuration.eventMode;
        configuration.eventMode = "compatibility";
        try {
            ZvsManagedDamage.resetForTesting();
            final AtomicReference<Double> health = new AtomicReference<>(10.0D);
            final List<Double> order = new ArrayList<>();
            final LivingEntity target = managedTarget(31, health, order, null);

            final double[] actual = ZvsManagedDamage.damageBatch(
                new LivingEntity[] {target, target},
                new double[] {2.0D, 3.0D},
                null
            );

            assertEquals(List.of(2.0D, 3.0D), order);
            assertEquals(2.0D, actual[0]);
            assertEquals(3.0D, actual[1]);
            assertEquals(5.0D, health.get());
            assertEquals(1L, ZvsManagedDamage.snapshot().batches());
            assertEquals(2L, ZvsManagedDamage.snapshot().requests());
        } finally {
            configuration.eventMode = previousMode;
        }
    }

    @Test
    void hybridDispatchesOneEventAndOneHurtStatusPerTargetAndTick() {
        final GlobalConfiguration.Optimizations.ZvsManagedDamage configuration = configuration();
        final String previousMode = configuration.eventMode;
        configuration.eventMode = "hybrid";
        try {
            ZvsManagedDamage.resetForTesting();
            final AtomicReference<Double> health = new AtomicReference<>(10.0D);
            final List<Boolean> eventDispatch = new ArrayList<>();
            final List<Boolean> hurtBroadcast = new ArrayList<>();
            final Entity handle = mock(Entity.class);
            when(handle.getId()).thenReturn(43);
            final LivingEntity target = managedTarget(43, health, new ArrayList<>(), () -> {
                eventDispatch.add(ZvsManagedDamage.shouldDispatchEvent(handle));
                hurtBroadcast.add(ZvsManagedDamage.shouldBroadcastHurtStatus(handle));
            });

            assertEquals(1.0D, ZvsManagedDamage.damage(target, 1.0D, null));
            assertEquals(1.0D, ZvsManagedDamage.damage(target, 1.0D, null));

            assertEquals(List.of(true, false), eventDispatch);
            assertEquals(List.of(true, false), hurtBroadcast);
            assertEquals(1L, ZvsManagedDamage.snapshot().eventsSkipped());
            assertEquals(1L, ZvsManagedDamage.snapshot().hurtStatusSkipped());
        } finally {
            configuration.eventMode = previousMode;
        }
    }

    @Test
    void hybridReservesItsFirstEventBeforeAllocatingLaterEvents() {
        final GlobalConfiguration.Optimizations.ZvsManagedDamage configuration = configuration();
        final String previousMode = configuration.eventMode;
        configuration.eventMode = "hybrid";
        try {
            ZvsManagedDamage.resetForTesting();
            final AtomicReference<Double> health = new AtomicReference<>(10.0D);
            final List<Boolean> fastPath = new ArrayList<>();
            final List<Boolean> dispatched = new ArrayList<>();
            final Entity handle = mock(Entity.class);
            when(handle.getId()).thenReturn(44);
            final LivingEntity target = managedTarget(44, health, new ArrayList<>(), () -> {
                final boolean allocationLight = ZvsManagedDamage.usesAllocationLightFastPath(handle);
                fastPath.add(allocationLight);
                if (allocationLight) {
                    ZvsManagedDamage.recordAllocationLightFastPath(handle);
                } else {
                    dispatched.add(ZvsManagedDamage.shouldDispatchEvent(handle));
                }
            });

            ZvsManagedDamage.damage(target, 1.0D, null);
            ZvsManagedDamage.damage(target, 1.0D, null);

            assertEquals(List.of(false, true), fastPath);
            assertEquals(List.of(true), dispatched);
            assertEquals(1L, ZvsManagedDamage.snapshot().eventsDispatched());
            assertEquals(1L, ZvsManagedDamage.snapshot().eventsSkipped());
        } finally {
            configuration.eventMode = previousMode;
        }
    }

    @Test
    void unmarkedTargetsAreRejectedForCompatibilityFallback() {
        final LivingEntity target = mock(LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        when(target.getScoreboardTags()).thenReturn(Set.of());

        assertTrue(Double.isNaN(ZvsManagedDamage.damage(target, 1.0D, null)));
    }

    @Test
    void trustedDeathUsesTheDedicatedHandlerInsideTheManagedDamageCall() {
        final GlobalConfiguration.Optimizations.ZvsManagedDamage configuration = configuration();
        final String previousMode = configuration.eventMode;
        configuration.eventMode = "trusted";
        try {
            ZvsManagedDamage.resetForTesting();
            ZvsManagedLifecycle.resetForTesting();
            final AtomicReference<Double> health = new AtomicReference<>(1.0D);
            final Entity handle = mock(Entity.class);
            when(handle.getId()).thenReturn(57);
            final EntityDeathEvent deathEvent = mock(EntityDeathEvent.class);
            final List<EntityDeathEvent> handled = new ArrayList<>();
            ZvsManagedLifecycle.registerDeathHandler(handled::add);
            final LivingEntity target = managedTarget(57, health, new ArrayList<>(), () ->
                assertTrue(ZvsManagedLifecycle.dispatchManagedDeath(handle, deathEvent))
            );

            assertEquals(1.0D, ZvsManagedDamage.damage(target, 1.0D, null));
            assertEquals(List.of(deathEvent), handled);
            assertEquals(1L, ZvsManagedLifecycle.snapshot().trustedDeaths());
        } finally {
            ZvsManagedLifecycle.registerDeathHandler(null);
            configuration.eventMode = previousMode;
        }
    }

    @Test
    void effectiveDeltaIncludesLateRoundAbsorptionOverflow() {
        final AtomicReference<Double> health = new AtomicReference<>(20.0D);
        final AtomicReference<Double> absorption = new AtomicReference<>(8.0D);
        final LivingEntity target = managedTarget(71, health, new ArrayList<>(), null);
        when(target.getAbsorptionAmount()).thenAnswer(ignored -> absorption.get());
        doAnswer(invocation -> {
            double remaining = invocation.getArgument(0);
            double absorbed = Math.min(absorption.get(), remaining);
            absorption.set(absorption.get() - absorbed);
            remaining -= absorbed;
            health.set(Math.max(0.0D, health.get() - remaining));
            return null;
        }).when(target).damage(anyDouble());

        assertEquals(10.0D, ZvsManagedDamage.damage(target, 10.0D, null));
        assertEquals(18.0D, health.get());
        assertEquals(0.0D, absorption.get());
    }

    @Test
    void batchRejectsMismatchedRequestArraysBeforeApplyingAnything() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ZvsManagedDamage.damageBatch(new LivingEntity[2], new double[1], null)
        );
    }

    @Test
    void deathHandlerOwnershipPreventsSilentGlobalSlotReplacement() {
        ZvsManagedLifecycle.resetForTesting();
        final Object firstOwner = new Object();
        final Object secondOwner = new Object();
        try {
            ZvsManagedLifecycle.registerDeathHandler(firstOwner, ignored -> {});
            assertThrows(
                IllegalStateException.class,
                () -> ZvsManagedLifecycle.registerDeathHandler(secondOwner, ignored -> {})
            );
            ZvsManagedLifecycle.unregisterDeathHandler(secondOwner);
            assertThrows(
                IllegalStateException.class,
                () -> ZvsManagedLifecycle.registerDeathHandler(secondOwner, ignored -> {})
            );
        } finally {
            ZvsManagedLifecycle.unregisterDeathHandler(firstOwner);
        }
    }

    private static LivingEntity managedTarget(
        final int entityId,
        final AtomicReference<Double> health,
        final List<Double> order,
        final Runnable callback
    ) {
        final LivingEntity target = mock(LivingEntity.class);
        when(target.getEntityId()).thenReturn(entityId);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(target.getScoreboardTags()).thenReturn(Set.of(configuration().markerTag));
        when(target.getHealth()).thenAnswer(ignored -> health.get());
        when(target.getAbsorptionAmount()).thenReturn(0.0D);
        doAnswer(invocation -> {
            final double amount = invocation.getArgument(0);
            order.add(amount);
            if (callback != null) {
                callback.run();
            }
            health.set(Math.max(0.0D, health.get() - amount));
            return null;
        }).when(target).damage(anyDouble());
        return target;
    }

    private static GlobalConfiguration.Optimizations.ZvsManagedDamage configuration() {
        return GlobalConfiguration.get().optimizations.zvsManagedDamage;
    }
}
