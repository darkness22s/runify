package me.zombie_striker.qg.structures;

import me.zombie_striker.qg.QAMain;
import me.zombie_striker.qg.guns.Gun;
import me.zombie_striker.qg.guns.projectiles.ProjectileManager;
import me.zombie_striker.qg.handlers.ExplosionHandler;
import me.zombie_striker.qg.hooks.protection.ProtectionHandler;
import me.zombie_striker.qg.utils.FoliaRunnable;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource-pack-free 3D artillery: block display carriage, wheels, recoil housing,
 * barrel rings, muzzle and operating hitbox. Each gun has persisted reload state.
 * The projectile follows a ballistic arc rather than an instant player ray.
 */
public final class SiegeCannon implements Listener {
    private static final long RELOAD_MILLIS = 30000;
    private static final int MAX_FLIGHT_TICKS = 110;
    private final QAMain plugin;
    private final NamespacedKey cannonKey;
    private final Map<String, Battery> batteries = new ConcurrentHashMap<>();
    private final File dataFile;

    public SiegeCannon(QAMain plugin) {
        this.plugin = plugin;
        this.cannonKey = new NamespacedKey(plugin, "siege_cannon");
        this.dataFile = new File(plugin.getDataFolder(), "cannons.yml");
        load();
    }

    private static final class Battery {
        final String id;
        final UUID world;
        final double x, y, z;
        final float yaw;
        volatile long readyAt;

        Battery(String id, UUID world, double x, double y, double z, float yaw, long readyAt) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.readyAt = readyAt;
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, 0);
        }
    }

    private synchronized void load() {
        if (!dataFile.isFile()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        for (String id : config.getKeys(false)) {
            String root = id + ".";
            try {
                Battery battery = new Battery(id, UUID.fromString(config.getString(root + "world")),
                        config.getDouble(root + "x"), config.getDouble(root + "y"),
                        config.getDouble(root + "z"), (float) config.getDouble(root + "yaw"),
                        config.getLong(root + "readyAt"));
                batteries.put(id, battery);
            } catch (IllegalArgumentException | NullPointerException ex) {
                plugin.getLogger().warning("Skipping invalid artillery record " + id);
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Battery b : batteries.values()) {
            String root = b.id + ".";
            config.set(root + "world", b.world.toString());
            config.set(root + "x", b.x);
            config.set(root + "y", b.y);
            config.set(root + "z", b.z);
            config.set(root + "yaw", b.yaw);
            config.set(root + "readyAt", b.readyAt);
        }
        try {
            config.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save siege cannon state: " + ex.getMessage());
        }
    }

    /** Standalone battery for battle maps that do not need an entire civilization. */
    public void spawnStandalone(Player player) {
        Location anchor = player.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        if (!ProtectionHandler.canBreak(anchor)) {
            player.sendMessage(ChatColor.RED + "You cannot build a cannon in a protected region.");
            return;
        }
        String id = "manual-" + UUID.randomUUID();
        float yaw = player.getLocation().getYaw();
        FoliaRunnable.runRegionTask(plugin, anchor, () -> {
            place(id, anchor, yaw);
            FoliaRunnable.runEntityTask(plugin, player, () -> player.sendMessage(ChatColor.GREEN
                    + "Siege cannon deployed. Sneak + right-click to fire; 30 second reload."));
        });
    }

    /** Called on the location's region thread after a civilization site is built. */
    public void place(String id, Location anchor, float yaw) {
        if (batteries.containsKey(id)) return;
        Battery battery = new Battery(id, anchor.getWorld().getUID(),
                anchor.getX(), anchor.getY(), anchor.getZ(), yaw, 0);
        if (batteries.putIfAbsent(id, battery) != null) return;
        render(battery, anchor);
        save();
    }

    /** Restore a missing physical model when a chunk containing a persisted gun loads. */
    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        for (Battery b : batteries.values()) {
            if (!event.getWorld().getUID().equals(b.world)
                    || (int) Math.floor(b.x) >> 4 != event.getChunk().getX()
                    || (int) Math.floor(b.z) >> 4 != event.getChunk().getZ()) continue;
            Location loc = b.location();
            if (loc == null) continue;
            // Models and their hitboxes are persistent entities. Do not duplicate them.
            boolean found = false;
            for (Entity e : event.getChunk().getEntities()) {
                if (e instanceof Interaction && b.id.equals(e.getPersistentDataContainer()
                        .get(cannonKey, PersistentDataType.STRING))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                FoliaRunnable.runRegionTaskLater(plugin, loc, () -> {
                    boolean present = false;
                    for (Entity e : loc.getWorld().getNearbyEntities(loc, 3, 3, 3)) {
                        if (e instanceof Interaction && b.id.equals(e.getPersistentDataContainer()
                                .get(cannonKey, PersistentDataType.STRING))) {
                            present = true;
                            break;
                        }
                    }
                    if (!present) render(b, loc);
                }, 5);
            }
        }
    }

    private void render(Battery b, Location pos) {
        // Ebony steel and brass accents, timber artillery carriage, axle and wheels.
        part(pos, b.yaw, -1.05f, 0.15f, -1.15f, 2.1f, 0.35f, 2.3f, Material.DARK_OAK_PLANKS);
        part(pos, b.yaw, -0.86f, 0.46f, -0.95f, 1.72f, 0.20f, 1.95f, Material.POLISHED_BLACKSTONE);
        part(pos, b.yaw, -0.62f, 0.67f, -0.65f, 1.24f, 0.55f, 1.35f, Material.NETHERITE_BLOCK);
        part(pos, b.yaw, -0.75f, 0.74f, -0.78f, 1.50f, 0.15f, 0.18f, Material.CUT_COPPER);
        for (int side : new int[]{-1, 1}) {
            for (float z : new float[]{-0.87f, 0.91f}) {
                part(pos, b.yaw, side < 0 ? -1.37f : 0.94f, 0.12f, z - 0.40f,
                        0.43f, 1.06f, 0.82f, Material.POLISHED_BLACKSTONE_BRICKS);
                part(pos, b.yaw, side < 0 ? -1.41f : 1.05f, 0.35f, z - 0.20f,
                        0.36f, 0.60f, 0.42f, Material.COPPER_BLOCK);
            }
        }
        // Barrel is deliberately long and raised: its model faces toward +Z before yaw.
        part(pos, b.yaw, -0.39f, 0.99f, 0.10f, 0.78f, 0.78f, 2.75f, Material.POLISHED_BLACKSTONE);
        part(pos, b.yaw, -0.47f, 0.95f, 0.20f, 0.94f, 0.94f, 0.23f, Material.CUT_COPPER);
        part(pos, b.yaw, -0.46f, 0.96f, 1.28f, 0.92f, 0.92f, 0.17f, Material.CUT_COPPER);
        part(pos, b.yaw, -0.50f, 0.94f, 2.59f, 1.00f, 1.00f, 0.32f, Material.NETHERITE_BLOCK);
        part(pos, b.yaw, -0.27f, 1.17f, 2.91f, 0.54f, 0.54f, 0.07f, Material.BLACK_CONCRETE);
        part(pos, b.yaw, -0.18f, 1.81f, -0.57f, 0.36f, 0.38f, 0.35f, Material.LIGHTNING_ROD);

        Interaction hitbox = pos.getWorld().spawn(pos.clone().add(0, 1.05, 0), Interaction.class);
        hitbox.setInteractionWidth(3.2f);
        hitbox.setInteractionHeight(2.4f);
        hitbox.setPersistent(true);
        hitbox.getPersistentDataContainer().set(cannonKey, PersistentDataType.STRING, b.id);

        TextDisplay label = pos.getWorld().spawn(pos.clone().add(0, 2.9, 0), TextDisplay.class);
        label.setText(ChatColor.GOLD + "SIEGE CANNON" + ChatColor.GRAY + "  •  Sneak + Right-click");
        label.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        label.setSeeThrough(false);
        label.setDefaultBackground(false);
        label.setPersistent(true);
    }

    /** Shape translations rotate with the cannon: no texture pack is required. */
    private void part(Location origin, float yaw, float x, float y, float z,
                      float sx, float sy, float sz, Material block) {
        double radians = Math.toRadians(-yaw);
        double cos = Math.cos(radians), sin = Math.sin(radians);
        double rx = x * cos + z * sin;
        double rz = z * cos - x * sin;
        BlockDisplay display = origin.getWorld().spawn(origin.clone().add(rx, y, rz), BlockDisplay.class);
        display.setBlock(block.createBlockData());
        display.setTransformation(new Transformation(new Vector3f(), new Quaternionf()
                .rotateY((float) radians), new Vector3f(sx, sy, sz), new Quaternionf()));
        display.setPersistent(true);
        display.setViewRange(1.7f);
        display.setShadowRadius(0.2f);
        display.setShadowStrength(0.65f);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction)) return;
        String id = event.getRightClicked().getPersistentDataContainer()
                .get(cannonKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        Battery b = batteries.get(id);
        if (b == null) return;
        Player player = event.getPlayer();
        Location battery = b.location();
        if (battery == null || !battery.getWorld().equals(player.getWorld())
                || battery.distanceSquared(player.getLocation()) > 36) return;

        if (!player.isSneaking()) {
            player.sendMessage(ChatColor.YELLOW + "Cannon controls: sneak + right-click; elevate your aim "
                    + "8-45 degrees and stay within 28 degrees of the barrel's heading.");
            return;
        }
        long left = b.readyAt - System.currentTimeMillis();
        if (left > 0) {
            player.sendMessage(ChatColor.RED + "Breech loading: " + (left / 1000 + 1) + "s remaining.");
            return;
        }
        float playerYaw = player.getLocation().getYaw();
        float difference = Math.abs(((playerYaw - b.yaw + 540) % 360) - 180);
        float pitch = player.getLocation().getPitch();
        if (difference > 28 || pitch > -8 || pitch < -45) {
            player.sendMessage(ChatColor.RED + "Out of firing arc. Align with barrel (±28°), "
                    + "look upward between 8° and 45°, then try again.");
            return;
        }
        // Once fired, this gun is locked for thirty real-world seconds, including restarts.
        b.readyAt = System.currentTimeMillis() + effectiveReload(battery);
        save();
        player.sendMessage(ChatColor.GOLD + "FIRE! " + ChatColor.GRAY
                + ((effectiveReload(battery)+999)/1000) + "s reload. The heavy shell drops rapidly over distance.");
        battery.getWorld().playSound(battery, Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 0.55f);
        Vector aim = player.getEyeLocation().getDirection().normalize();
        Location muzzle = battery.clone().add(0, 1.35, 0).add(aim.clone().multiply(3));
        launch(player, muzzle, aim.multiply(1.65));
    }

    private void launch(Player shooter, Location pos, Vector initialVelocity) {
        // Region-affine step: each new step is scheduled at the new shell location.
        // This avoids a repeating task accessing arbitrary distant Folia chunks.
        step(shooter, pos.clone(), initialVelocity, 0);
    }

    private void step(Player shooter, Location position, Vector velocity, int ticks) {
        if (!plugin.isEnabled() || ticks >= MAX_FLIGHT_TICKS
                || position.getY() < position.getWorld().getMinHeight()
                || position.getY() > position.getWorld().getMaxHeight()) return;
        FoliaRunnable.runRegionTaskLater(plugin, position, () -> {
            if (!plugin.isEnabled()) return;
            Location next = position.clone();
            Vector move = velocity.clone().multiply(0.25);
            for (int i = 0; i < 4; i++) {
                next.add(move);
                Block block = next.getBlock();
                if (block.getType().isSolid() && !block.isPassable()) {
                    detonate(shooter, next);
                    return;
                }
                next.getWorld().spawnParticle(Particle.SMOKE, next, 3, 0.12, 0.12, 0.12, 0);
                next.getWorld().spawnParticle(Particle.FLAME, next, 1, 0.04, 0.04, 0.04, 0);
            }
            if (!next.getWorld().getNearbyEntities(next, 1.0, 1.0, 1.0,
                    e -> e instanceof org.bukkit.entity.LivingEntity
                            && !e.getUniqueId().equals(shooter.getUniqueId())).isEmpty()) {
                detonate(shooter, next);
                return;
            }
            Vector falling = velocity.clone().multiply(0.991).setY(velocity.getY() - 0.082);
            step(shooter, next, falling, ticks + 1);
        }, 1);
    }

    private void detonate(Player shooter, Location point) {
        if (!ProtectionHandler.canExplode(point)) {
            point.getWorld().spawnParticle(Particle.SMOKE, point, 28, 1, 1, 1, 0.02);
            return;
        }
        double upgrade = plugin.getTerritories() == null ? 1.0
                : plugin.getTerritories().cannonMultiplierAt(point);
        int power = Math.min(32, Math.max(2, (int) Math.ceil(QAMain.rpgExplosionPower * 2 * upgrade)));
        double damage = 2 * currentRpgDamage() * upgrade;
        ExplosionHandler.handleExplosion(point, power, power);
        ExplosionHandler.handleAOEExplosion(shooter, point, damage, power * 1.6, 1.4);
        point.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, point, 1);
        point.getWorld().playSound(point, Sound.ENTITY_GENERIC_EXPLODE, 6, 0.65f);
    }

    private long effectiveReload(Location location) {
        return plugin.getTerritories() == null ? RELOAD_MILLIS
                : plugin.getTerritories().cannonReloadAt(location);
    }

    /**
     * Guard-controlled artillery coverage. Only a purchased VexBot garrison can
     * request a salvo; shots originate from the existing real 3D cannon and use
     * its ordinary ballistic simulation and reload state.
     *
     * VexBot does not expose a Paper API for driving plugin-specific vehicles;
     * this is an artillery order adapter, not a fake claim of NPC vehicle AI.
     */
    public boolean fireGuardSalvo(UUID civilizationOwner, Player commander, Location target) {
        if (commander == null || target == null) return false;
        Battery candidate = null;
        double best = 55 * 55;
        for (Battery battery : batteries.values()) {
            if (!battery.id.startsWith(civilizationOwner.toString() + "-")) continue;
            if (System.currentTimeMillis() < battery.readyAt) continue;
            Location origin = battery.location();
            if (origin == null || !origin.getWorld().equals(target.getWorld())) continue;
            double distance = origin.distanceSquared(target);
            if (distance < best) {
                best = distance;
                candidate = battery;
            }
        }
        if (candidate == null) return false;
        final Battery gun = candidate;
        Location origin = gun.location();
        // The heavy shell has gravity; a small upward lead is necessary.
        Vector direction = target.clone().add(0, 4, 0).toVector()
                .subtract(origin.clone().add(0, 1.35, 0).toVector()).normalize();
        if (direction.lengthSquared() < 0.01) return false;
        gun.readyAt = System.currentTimeMillis() + effectiveReload(origin);
        save();
        Location muzzle = origin.clone().add(0, 1.35, 0).add(direction.clone().multiply(3));
        FoliaRunnable.runRegionTask(plugin, muzzle, () -> launch(commander, muzzle, direction.multiply(1.65)));
        return true;
    }

    private double currentRpgDamage() {
        for (Gun gun : QAMain.gunRegister.values()) {
            if (gun.getCustomProjectile() != null
                    && ProjectileManager.RPG.equalsIgnoreCase(gun.getCustomProjectile().getName())) {
                return Math.max(1, gun.getDamage());
            }
        }
        return plugin.getConfig().getDouble("cannon.fallbackRpgDamage", 20);
    }
}
