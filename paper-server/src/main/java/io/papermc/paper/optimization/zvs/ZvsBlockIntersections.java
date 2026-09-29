package io.papermc.paper.optimization.zvs;

import io.papermc.paper.configuration.GlobalConfiguration;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Reuses only temporary traversal storage, never block results or world state. */
public final class ZvsBlockIntersections {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private ZvsBlockIntersections() {}

    public static boolean visit(final Entity entity, final Vec3 from, final Vec3 to, final AABB bounds,
                                final BlockGetter.BlockStepVisitor visitor) {
        final GlobalConfiguration configuration = GlobalConfiguration.get();
        if (!(entity instanceof Mob) || configuration == null || !configuration.optimizations.zvsBlockIntersections.enabled
            || !entity.entityTags().contains(configuration.optimizations.zvsBlockIntersections.markerTag)) {
            return BlockGetter.forEachBlockIntersectedBetween(from, to, bounds, visitor);
        }
        return visit(from, to, bounds, visitor);
    }

    static boolean visit(final Vec3 from, final Vec3 to, final AABB bounds, final BlockGetter.BlockStepVisitor visitor) {
        final Scratch scratch = SCRATCH.get();
        if (scratch.busy) {
            // A block effect may move another entity synchronously; it cannot share this traversal's set.
            return BlockGetter.forEachBlockIntersectedBetween(from, to, bounds, visitor);
        }
        scratch.busy = true;
        try {
            return BlockGetter.forEachBlockIntersectedBetween(from, to, bounds, visitor, scratch.blocks);
        } finally {
            // Long teleports must not permanently retain a large hash table on a server thread.
            if (scratch.blocks.size() > 4096) scratch.blocks = new LongOpenHashSet();
            else scratch.blocks.clear();
            scratch.busy = false;
        }
    }

    private static final class Scratch {
        private LongOpenHashSet blocks = new LongOpenHashSet();
        private boolean busy;
    }
}
