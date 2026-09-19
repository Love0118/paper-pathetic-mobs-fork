package io.papermc.paper.optimization.mud;

import java.nio.file.Path;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.entity.CraftEntity;

/** Optional JNI batch. Java validates ownership/lifecycle before Rust invokes actual NMS methods. */
public final class MudNativeEntities {
    private static final boolean JAVA_BATCH = Boolean.getBoolean("mud.native.entities.javaControl");
    private static final boolean AVAILABLE = load();
    private static long batches;
    public static long completedBatches() { return batches; }
    private MudNativeEntities() {}
    private static native int init0(Class<Entity> entityClass);
    private static native boolean move0(Entity[] entities, double[] positions, int count);

    private static boolean load() {
        final String path = System.getProperty("mud.native.entities", "");
        if (path.isBlank()) return false;
        try {
            System.load(Path.of(path).toAbsolutePath().toString());
            if (init0(Entity.class) != 1) throw new IllegalStateException("MUD entity ABI initialization failed");
            System.getLogger(MudNativeEntities.class.getName()).log(System.Logger.Level.INFO, "MUD Rust JNI entity batch loaded: " + path);
            return true;
        } catch (LinkageError | RuntimeException error) {
            System.getLogger(MudNativeEntities.class.getName()).log(System.Logger.Level.WARNING, "MUD Rust entity batch unavailable; using Java", error);
            return false;
        }
    }

    public static boolean move(final org.bukkit.entity.Entity[] entities, final double[] positions, final int count) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread("MUD native motion must run on server thread");
        if (!AVAILABLE && !JAVA_BATCH) return false;
        if (count < 0 || count > 4096 || entities.length < count || positions.length < count * 5) throw new IllegalArgumentException("Invalid batch size");
        final Entity[] handles = new Entity[count];
        for (int i = 0; i < count; i++) {
            if (!(entities[i] instanceof CraftEntity craft)) return false;
            final Entity handle = craft.getHandle();
            if (!handle.valid || !MudPresentation.skipGameplayTick(handle)) return false;
            for (int j = 0; j < 5; j++) if (!Double.isFinite(positions[i * 5 + j])) throw new IllegalArgumentException("Non-finite position");
            if (Math.abs(positions[i * 5 + 3]) > Float.MAX_VALUE || Math.abs(positions[i * 5 + 4]) > Float.MAX_VALUE) throw new IllegalArgumentException("Invalid rotation");
            if (!craft.getWorld().isChunkLoaded(org.bukkit.util.NumberConversions.floor(positions[i * 5]) >> 4,
                org.bukkit.util.NumberConversions.floor(positions[i * 5 + 2]) >> 4)) return false;
            handles[i] = handle;
        }
        if (JAVA_BATCH) {
            for (int i = 0; i < count; i++) {
                final Entity entity = handles[i];
                entity.setPos(positions[i*5], positions[i*5+1], positions[i*5+2]);
                entity.setYRot((float) positions[i*5+3]);
                entity.setYHeadRot((float) positions[i*5+3]);
                entity.setXRot((float) positions[i*5+4]);
            }
        } else if (!move0(handles, positions, count)) throw new IllegalStateException("MUD native entity batch rejected; no retry performed");
        batches++;
        return true;
    }
}
