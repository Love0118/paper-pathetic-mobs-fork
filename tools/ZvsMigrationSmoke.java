import io.papermc.paper.zvs.ZvsOptimization;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Disposable-server probe. Never install on a production server: shuts down after verification. */
public final class ZvsMigrationSmoke extends JavaPlugin implements Listener {
    private int damageEvents;
    private int spawnEvents;
    private int deathEvents;
    private final List<Zombie> mobs = new ArrayList<>();

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                verify();
                getLogger().info("ZVS_MIGRATION_SMOKE_PASS");
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "ZVS_MIGRATION_SMOKE_FAIL", failure);
            } finally {
                ZvsOptimization.unregisterDeathHandler(this);
                mobs.forEach(Zombie::remove);
                Bukkit.shutdown();
            }
        }, 20);
    }

    @EventHandler public void damage(EntityDamageEvent event) {
        if (event.getEntity().getScoreboardTags().contains("zvs_smoke")) damageEvents++;
    }
    @EventHandler public void spawn(CreatureSpawnEvent event) {
        if (event.getEntity().getScoreboardTags().contains("zvs_smoke")) spawnEvents++;
    }

    @EventHandler public void death(EntityDeathEvent event) {
        if (event.getEntity().getScoreboardTags().contains("zvs_smoke")) deathEvents++;
    }

    private Zombie create(boolean managed, int profile) {
        var world = Bukkit.getWorlds().getFirst();
        Location location = new Location(world, 0, world.getHighestBlockYAt(0, 0) + 2, 0);
        java.util.function.Consumer<Zombie> init = mob -> {
            mob.setAI(false);
            mob.setAdult();
            mob.addScoreboardTag("zvs_smoke");
            mob.setGravity(false);
            mob.setSilent(true);
            mob.setPersistent(false);
            mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
            mob.setHealth(100);
            mob.getEquipment().clear();
            if (managed) mob.addScoreboardTag("zvs_managed");
            if (profile > 0) {
                mob.getEquipment().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 1));
                mob.getAttribute(Attribute.MAX_ABSORPTION).setBaseValue(30);
                mob.setAbsorptionAmount(30);
            }
        };
        Zombie mob = managed ? ZvsOptimization.spawn(location, Zombie.class, init) : world.spawn(location, Zombie.class, init);
        require(mob != null, "Spawn bridge must be installed");
        mobs.add(mob);
        return mob;
    }

    private void verify() {
        boolean trusted = "trusted".equals(System.getProperty("zvs.smoke.mode", "hybrid"));
        require(ZvsOptimization.API_VERSION == 2, "ZVS API v2 remains compatible");
        for (int profile = 0; profile < 2; profile++) {
            int beforeSpawn = spawnEvents;
            Zombie vanilla = create(false, profile);
            Zombie managed = create(true, profile);
            require(spawnEvents - beforeSpawn == 1, "Only explicit managed spawn skips the event; observed=" + (spawnEvents - beforeSpawn));
            for (double amount : new double[]{8, 15, 20, 35}) {
                vanilla.setNoDamageTicks(0);
                managed.setNoDamageTicks(0);
                vanilla.damage(amount);
                double before = managed.getHealth() + managed.getAbsorptionAmount();
                double delta = ZvsOptimization.damage(managed, amount, null);
                require(Math.abs(delta - (before - managed.getHealth() - managed.getAbsorptionAmount())) < .0001, "Bridge delta");
                require(Math.abs(vanilla.getHealth() - managed.getHealth()) < .0001, "Managed health matches Bukkit");
                require(Math.abs(vanilla.getAbsorptionAmount() - managed.getAbsorptionAmount()) < .0001, "Managed absorption matches Bukkit");
            }
            vanilla.remove();
            managed.remove();
        }
        Zombie batchTarget = create(true, 0);
        batchTarget.setMaximumNoDamageTicks(0);
        int beforeEvents = damageEvents;
        double[] deltas = ZvsOptimization.damageBatch(new Zombie[]{batchTarget, batchTarget, batchTarget}, new double[]{2, 3, 4}, null);
        require(java.util.Arrays.equals(deltas, new double[]{2, 3, 4}), "Ordered damage batch");
        require(damageEvents - beforeEvents == (trusted ? 0 : 1), "Damage event cadence matches configured mode");
        final int[] deaths = {0};
        ZvsOptimization.registerDeathHandler(this, event -> deaths[0]++);
        int beforeDeaths = deathEvents;
        ZvsOptimization.damage(batchTarget, 1000, null);
        require(batchTarget.isDead(), "Lethal damage is synchronous");
        require(deaths[0] == (trusted ? 1 : 0), "Dedicated callback applies only to trusted mode");
        require(deathEvents - beforeDeaths == (trusted ? 0 : 1), "Hybrid retains the global death event");
        Zombie untagged = create(false, 0);
        require(Double.isNaN(ZvsOptimization.damage(untagged, 2, null)), "Untagged targets retain Bukkit fallback");
        getLogger().info("Verified armor/resistance/absorption, ordered hybrid batches, trusted spawn/death and untagged fallback");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
