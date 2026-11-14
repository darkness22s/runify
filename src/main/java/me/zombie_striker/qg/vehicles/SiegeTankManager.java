package me.zombie_striker.qg.vehicles;

import com.cryptomorin.xseries.messages.ActionBar;
import me.zombie_striker.qg.QAMain;
import me.zombie_striker.qg.guns.Gun;
import me.zombie_striker.qg.guns.projectiles.RocketProjectile;
import me.zombie_striker.qg.handlers.ExplosionHandler;
import me.zombie_striker.qg.hooks.protection.ProtectionHandler;
import me.zombie_striker.qg.utils.FoliaRunnable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real, multipart block-display tank, with a vanilla-steerable invisible horse
 * as its health, collision and seating entity. Requires the 1.20.6 server API.
 *
 * Each tank is updated on its own entity scheduler for Folia compatibility.
 * The 3D parts are transient and reconstructed when a saved tank chunk loads.
 */
public final class SiegeTankManager implements Listener {
    private static final String TANK_TAG = "runify_siege_tank";
    private static final String SHELL_TAG = "runify_tank_shell";
    private static final int SHELL_RANGE_TICKS = 100;
    private final QAMain plugin;
    private final Map<UUID, Tank> tanks = new ConcurrentHashMap<>();

    public SiegeTankManager(QAMain plugin) {
        this.plugin = plugin;
    }

    public void spawn(Player player) {
        Location location = player.getLocation();
        if (!ProtectionHandler.canBreak(location) || !ProtectionHandler.canExplode(location)) {
            player.sendMessage(ChatColor.RED + "You cannot deploy a siege tank in this protected area.");
            return;
        }
        if (!location.getWorld().getWorldBorder().isInside(location)) {
            player.sendMessage(ChatColor.RED + "Move inside the world border to deploy a tank.");
            return;
        }
        for (Entity nearby : location.getWorld().getNearbyEntities(location, 5, 4, 5)) {
            if (nearby.getScoreboardTags().contains(TANK_TAG)) {
                player.sendMessage(ChatColor.RED + "Another siege tank is too close.");
                return;
            }
        }
        Horse horse = location.getWorld().spawn(location, Horse.class, spawned -> {
            spawned.setAdult();
            spawned.setTamed(true);
            spawned.setOwner(player);
            spawned.setSilent(true);
            spawned.setRemoveWhenFarAway(false);
            spawned.setCustomName(ChatColor.DARK_GREEN + "Siege Tank");
            spawned.setCustomNameVisible(true);
            spawned.addScoreboardTag(TANK_TAG);
            spawned.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            spawned.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true, false));
            double maxHealth = Math.max(40.0, plugin.getConfig().getDouble("tank.health", 400.0));
            spawned.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
            spawned.setHealth(maxHealth);
            spawned.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(
                    Math.max(0.06, Math.min(0.40, plugin.getConfig().getDouble("tank.movementSpeed", 0.20))));
            spawned.setJumpStrength(0.12);
            spawned.setAI(false);
        });
        attach(horse);
        player.sendMessage(ChatColor.GREEN + "Siege tank deployed (" + (int) horse.getHealth() + " HP). "
                + ChatColor.YELLOW + "Right-click its center to drive; press F to charge the cannon.");
        player.sendMessage(ChatColor.GRAY + "The cannon takes "
                + plugin.getConfig().getInt("tank.chargeSeconds", 2) + " seconds to fire and "
                + plugin.getConfig().getInt("tank.reloadSeconds", 35) + " seconds to reload.");
    }

    private void attach(Horse horse) {
        if (tanks.containsKey(horse.getUniqueId())) return;
        Tank tank = new Tank(horse);
        if (tanks.putIfAbsent(horse.getUniqueId(), tank) != null) return;
        tank.createModel();
        tank.tickTask = new FoliaRunnable() {
            private int counter;

            @Override
            public void run() {
                if (!horse.isValid() || horse.isDead()) {
                    tanks.remove(horse.getUniqueId(), tank);
                    tank.removeModel();
                    cancel();
                    return;
                }
                // Never let an empty, invisible mount wander away from its hull.
                horse.setAI(!horse.getPassengers().isEmpty());
                tank.syncModel();
                if (++counter % 10 == 0 && !horse.getPassengers().isEmpty()) {
                    Entity rider = horse.getPassengers().get(0);
                    if (rider instanceof Player) {
                        Player player = (Player) rider;
                        long seconds = Math.max(0L, (tank.readyAt - System.currentTimeMillis() + 999L) / 1000L);
                        String status = tank.charging ? "CHARGING CANNON"
                                : seconds > 0 ? "RELOADING: " + seconds + "s" : "READY (F TO FIRE)";
                        ActionBar.sendActionBar(player, ChatColor.DARK_GREEN + "TANK "
                                + ChatColor.GREEN + (int) Math.ceil(horse.getHealth()) + "/"
                                + (int) horse.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue()
                                + " HP  " + ChatColor.GOLD + status);
                    }
                }
            }
        };
        tank.tickTask.runTaskTimer(plugin, horse, 1, 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void enter(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Horse)) return;
        Horse horse = (Horse) event.getRightClicked();
        if (!horse.getScoreboardTags().contains(TANK_TAG)) return;
        event.setCancelled(true);
        if (!tanks.containsKey(horse.getUniqueId())) attach(horse);
        if (!horse.getPassengers().isEmpty()) {
            event.getPlayer().sendMessage(ChatColor.YELLOW + "The driver's seat is occupied.");
            return;
        }
        horse.setAI(true);
        horse.addPassenger(event.getPlayer());
        event.getPlayer().sendMessage(ChatColor.GOLD + "Drive with WASD, aim with your view, F = cannon. "
                + "Dismount with Shift.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onFire(PlayerSwapHandItemsEvent event) {
        if (!(event.getPlayer().getVehicle() instanceof Horse)) return;
        Horse horse = (Horse) event.getPlayer().getVehicle();
        if (!horse.getScoreboardTags().contains(TANK_TAG)) return;
        event.setCancelled(true);
        Tank tank = tanks.get(horse.getUniqueId());
        if (tank == null) return;
        if (tank.charging) {
            event.getPlayer().sendMessage(ChatColor.YELLOW + "The cannon is already charging.");
            return;
        }
        long remaining = tank.readyAt - System.currentTimeMillis();
        if (remaining > 0) {
            event.getPlayer().sendMessage(ChatColor.YELLOW + "Reloading: "
                    + ((remaining + 999) / 1000) + " seconds left.");
            return;
        }
        tank.charging = true;
        Player gunner = event.getPlayer();
        horse.getWorld().playSound(horse.getLocation(), Sound.BLOCK_PISTON_EXTEND, 2f, 0.6f);
        gunner.sendMessage(ChatColor.GOLD + "Loading the cannon... hold your position.");
        long chargeTicks = Math.max(1, Math.min(10, plugin.getConfig().getInt("tank.chargeSeconds", 2))) * 20L;
        new FoliaRunnable() {
            @Override
            public void run() {
                tank.charging = false;
                if (!horse.isValid() || horse.isDead()
                        || !horse.getPassengers().contains(gunner)
                        || !gunner.isOnline()) return;
                shoot(tank, gunner);
                long reloadMillis = Math.max(5, Math.min(120,
                        plugin.getConfig().getInt("tank.reloadSeconds", 35))) * 1000L;
                tank.readyAt = System.currentTimeMillis() + reloadMillis;
                gunner.sendMessage(ChatColor.RED + "BOOM! Cannon reloading for "
                        + (reloadMillis / 1000) + " seconds.");
            }
        }.runTaskLater(plugin, horse, chargeTicks);
    }

    private void shoot(Tank tank, Player gunner) {
        Horse horse = tank.horse;
        Location origin = horse.getLocation();
        Vector direction = gunner.getEyeLocation().getDirection().normalize();
        Vector forward = direction.clone().setY(0);
        if (forward.lengthSquared() < 0.001) forward = origin.getDirection().setY(0);
        forward.normalize();
        Location muzzle = origin.clone().add(forward.multiply(3.7)).add(0, 2.1, 0);
        muzzle.add(direction.clone().multiply(0.4));
        Snowball shell = origin.getWorld().spawn(muzzle, Snowball.class);
        shell.setShooter(gunner);
        shell.setItem(new ItemStack(Material.FIRE_CHARGE));
        shell.addScoreboardTag(SHELL_TAG);
        shell.setVelocity(direction.multiply(2.6));
        origin.getWorld().playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.6f);
        origin.getWorld().spawnParticle(Particle.EXPLOSION, muzzle, 2);
        // Remove rounds that miss without leaving tracked entities around forever.
        new FoliaRunnable() {
            @Override
            public void run() {
                if (shell.isValid()) shell.remove();
            }
        }.runTaskLater(plugin, shell, SHELL_RANGE_TICKS);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShellHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)
                || !event.getEntity().getScoreboardTags().contains(SHELL_TAG)) return;
        Snowball shell = (Snowball) event.getEntity();
        Location impact = shell.getLocation().clone();
        Entity shooter = shell.getShooter() instanceof Entity ? (Entity) shell.getShooter() : shell;
        shell.remove();
        if (!ProtectionHandler.canExplode(impact)) return;
        int blastStrength = Math.max(3, Math.min(48, QAMain.rpgExplosionPower * 3));
        // Scale the damage and radius of the actual registered RPG, not unrelated weapons.
        double rpgDamage = 20.0;
        double rpgRadius = 6.0;
        for (Gun gun : QAMain.gunRegister.values()) {
            if (gun.usesCustomProjctiles() && gun.getCustomProjectile() instanceof RocketProjectile) {
                rpgDamage = gun.getDamage();
                rpgRadius = gun.getExplosionRadius();
                break;
            }
        }
        ExplosionHandler.handleExplosion(impact, blastStrength, 2);
        ExplosionHandler.handleAOEExplosion(shooter, impact, rpgDamage * 3.0,
                Math.max(3.0, rpgRadius * 3.0), 1.6);
        impact.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 8f, 0.5f);
        impact.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, impact, 1);
    }

    @EventHandler
    public void onTankDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Horse)
                || !event.getEntity().getScoreboardTags().contains(TANK_TAG)) return;
        event.getDrops().clear();
        Tank tank = tanks.remove(event.getEntity().getUniqueId());
        if (tank != null) {
            tank.stop();
            tank.removeModel();
        }
        event.getEntity().getWorld().playSound(event.getEntity().getLocation(),
                Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.7f);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof Horse) || !entity.getScoreboardTags().contains(TANK_TAG)) continue;
            Tank tank = tanks.remove(entity.getUniqueId());
            if (tank != null) {
                tank.stop();
                tank.removeModel();
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Horse && entity.getScoreboardTags().contains(TANK_TAG)) {
                attach((Horse) entity);
            }
        }
    }

    public void shutdown() {
        for (Tank tank : new ArrayList<>(tanks.values())) {
            tank.stop();
            tank.removeModel();
        }
        tanks.clear();
    }

    /** A scaled Minecraft block is a genuinely 3D mesh part, not a 2D item texture. */
    private final class Tank {
        private final Horse horse;
        private final List<Part> parts = new ArrayList<>();
        private FoliaRunnable tickTask;
        private volatile boolean charging;
        private volatile long readyAt;

        private Tank(Horse horse) {
            this.horse = horse;
        }

        private void add(Material material, float x, float y, float z,
                         float width, float height, float length, boolean turret) {
            BlockDisplay display = horse.getWorld().spawn(horse.getLocation(), BlockDisplay.class);
            display.setPersistent(false);
            display.setBlock(Bukkit.createBlockData(material));
            display.setTransformation(new Transformation(
                    new Vector3f(x, y, z), new Quaternionf(),
                    new Vector3f(width, height, length), new Quaternionf()));
            display.setTeleportDuration(1);
            display.setShadowRadius(0);
            display.setViewRange(2.5f);
            parts.add(new Part(display, turret));
        }

        private void createModel() {
            // Heavy dual tracks, road-wheel accents and armored mudguards.
            add(Material.BLACK_CONCRETE, -1.95f, 0.02f, -2.70f, .82f, .94f, 5.45f, false);
            add(Material.BLACK_CONCRETE, 1.13f, 0.02f, -2.70f, .82f, .94f, 5.45f, false);
            for (float z : new float[]{-2.25f, -.85f, .55f, 1.95f}) {
                add(Material.GRAY_CONCRETE, -2.01f, .15f, z, .13f, .64f, .86f, false);
                add(Material.GRAY_CONCRETE, 1.88f, .15f, z, .13f, .64f, .86f, false);
            }
            add(Material.DARK_OAK_PLANKS, -1.85f, .90f, -2.60f, .72f, .18f, 5.15f, false);
            add(Material.DARK_OAK_PLANKS, 1.13f, .90f, -2.60f, .72f, .18f, 5.15f, false);

            // Wide plated hull, nose armor, engine deck and contrasting side skirts.
            add(Material.GREEN_TERRACOTTA, -1.48f, .47f, -2.42f, 2.96f, .96f, 4.95f, false);
            add(Material.MOSSY_STONE_BRICKS, -1.42f, 1.11f, 1.50f, 2.84f, .26f, 1.00f, false);
            add(Material.GRAY_CONCRETE, -1.30f, 1.38f, -2.30f, 2.60f, .16f, 1.35f, false);
            add(Material.POLISHED_BLACKSTONE, -1.55f, .55f, 2.27f, 3.10f, .48f, .32f, false);
            add(Material.POLISHED_BLACKSTONE, -1.55f, .57f, -2.57f, 3.10f, .45f, .28f, false);

            // Rotating angular turret with armored roof, commander hatch and long cannon.
            add(Material.GREEN_TERRACOTTA, -1.12f, 1.43f, -.92f, 2.24f, .77f, 2.25f, true);
            add(Material.MOSSY_STONE_BRICKS, -.94f, 2.20f, -.78f, 1.88f, .20f, 1.92f, true);
            add(Material.POLISHED_BLACKSTONE, -.48f, 2.39f, -.48f, .96f, .13f, .96f, true);
            add(Material.GRAY_CONCRETE, -.35f, 1.70f, 1.12f, .70f, .63f, .68f, true);
            add(Material.DEEPSLATE_TILES, -.20f, 1.84f, 1.70f, .40f, .38f, 2.55f, true);
            add(Material.NETHERITE_BLOCK, -.27f, 1.78f, 4.06f, .54f, .50f, .35f, true);
            add(Material.BLACK_CONCRETE, -.33f, 2.52f, -.07f, .66f, .10f, .66f, true);
            syncModel();
        }

        private void syncModel() {
            Location base = horse.getLocation();
            float hullYaw = base.getYaw();
            float turretYaw = hullYaw;
            if (!horse.getPassengers().isEmpty() && horse.getPassengers().get(0) instanceof Player) {
                turretYaw = ((Player) horse.getPassengers().get(0)).getLocation().getYaw();
            }
            for (Part part : parts) {
                if (!part.display.isValid()) continue;
                Location target = base.clone();
                target.setYaw(part.turret ? turretYaw : hullYaw);
                target.setPitch(0);
                FoliaRunnable.teleport(part.display, target);
            }
        }

        private void stop() {
            if (tickTask != null) tickTask.cancel();
        }

        private void removeModel() {
            for (Part part : parts) {
                if (part.display.isValid()) part.display.remove();
            }
            parts.clear();
        }
    }

    private static final class Part {
        private final BlockDisplay display;
        private final boolean turret;

        private Part(BlockDisplay display, boolean turret) {
            this.display = display;
            this.turret = turret;
        }
    }
}
