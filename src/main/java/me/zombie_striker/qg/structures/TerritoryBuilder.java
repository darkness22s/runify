package me.zombie_striker.qg.structures;

import me.zombie_striker.qg.QAMain;
import me.zombie_striker.qg.hooks.protection.ProtectionHandler;
import me.zombie_striker.qg.utils.FoliaRunnable;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.atomic.AtomicInteger;

/** Chunk-budgeted fabrication of unique, remotely placed fighting outposts. */
final class TerritoryBuilder {
    static final int RADIUS = 16;
    static final int HEIGHT = 39;
    private static final int BUDGET = 160;
    private final QAMain plugin;
    private final SiegeCannon cannons;
    private final NamespacedKey labelKey;

    TerritoryBuilder(QAMain plugin, SiegeCannon cannons) {
        this.plugin = plugin;
        this.cannons = cannons;
        this.labelKey = new NamespacedKey(plugin, "civ_site_label");
    }

    void build(TerritoryCivilizations.Civ civ, TerritoryCivilizations.Site site, Runnable done) {
        World world = Bukkit.getWorld(civ.world);
        if (world == null) {
            done.run();
            return;
        }
        Location regionCenter = new Location(world, site.x + 0.5, world.getHighestBlockYAt(site.x, site.z), site.z + 0.5);
        // HighestBlockYAt is only called here after the correct region task is scheduled.
        FoliaRunnable.runRegionTask(plugin, regionCenter, () -> {
            site.y = Math.max(world.getMinHeight() + 1,
                    Math.min(world.getMaxHeight() - HEIGHT - 1, world.getHighestBlockYAt(site.x, site.z) - 1));
            if (site.y + HEIGHT >= world.getMaxHeight()) {
                done.run();
                return;
            }
            startChunks(civ, site, done);
        });
    }

    private void startChunks(TerritoryCivilizations.Civ civ, TerritoryCivilizations.Site site, Runnable done) {
        World world = Bukkit.getWorld(civ.world);
        int minCX = Math.floorDiv(site.x - RADIUS, 16);
        int maxCX = Math.floorDiv(site.x + RADIUS, 16);
        int minCZ = Math.floorDiv(site.z - RADIUS, 16);
        int maxCZ = Math.floorDiv(site.z + RADIUS, 16);
        AtomicInteger pending = new AtomicInteger((maxCX - minCX + 1) * (maxCZ - minCZ + 1));
        AtomicInteger refused = new AtomicInteger();
        final int volume = 16 * 16 * HEIGHT;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                final int chunkX = cx, chunkZ = cz;
                Location region = new Location(world, cx * 16 + 8, site.y + 1, cz * 16 + 8);
                new FoliaRunnable() {
                    int index;
                    @Override public void run() {
                        try {
                            int end = Math.min(volume, index + BUDGET);
                            while (index < end) {
                                int i = index++;
                                int x = chunkX * 16 + (i & 15);
                                int z = chunkZ * 16 + ((i >> 4) & 15);
                                int y = i >> 8;
                                int dx = x - site.x, dz = z - site.z;
                                if (Math.abs(dx) > RADIUS || Math.abs(dz) > RADIUS) continue;
                                Block block = world.getBlockAt(x, site.y + y, z);
                                Material desired = styled(site, dx, y, dz);
                                if (block.getType() == desired) continue;
                                if (block.getType() == Material.BEDROCK
                                        || block.getType() == Material.END_PORTAL_FRAME
                                        || !ProtectionHandler.canBreak(block.getLocation())) {
                                    refused.incrementAndGet();
                                    continue;
                                }
                                block.setType(desired, false);
                            }
                        } catch (RuntimeException ex) {
                            plugin.getLogger().warning("Outpost " + site.name
                                    + " chunk failed: " + ex.getMessage());
                            refused.incrementAndGet();
                            cancel();
                            finish();
                            return;
                        }
                        if (index == volume) {
                            cancel();
                            finish();
                        }
                    }
                    private void finish() {
                        if (pending.decrementAndGet() == 0) {
                            if (refused.get() > 0) plugin.getLogger().warning(
                                    site.name + ": " + refused.get() + " protected/failed blocks unchanged");
                            site.built = refused.get() == 0;
                            Location center = new Location(world, site.x + .5, site.y + 1, site.z + .5);
                            FoliaRunnable.runRegionTask(plugin, center, () -> {
                                decorate(civ, site, center);
                                done.run();
                            });
                        }
                    }
                }.runTaskTimer(plugin, region, 1, 1);
            }
        }
    }

    static Material styled(TerritoryCivilizations.Site site, int dx, int y, int dz) {
        if (dx == 0 && dz == 0 && y == 2) return Material.LODESTONE;
        Material base = SiegeOutpost.blockAt(dx, y, dz);
        switch (site.style % 4) {
            case 1:
                if (base == Material.STONE_BRICKS) return Material.DEEPSLATE_BRICKS;
                if (base == Material.CRACKED_STONE_BRICKS) return Material.CRACKED_DEEPSLATE_BRICKS;
                if (base == Material.RED_TERRACOTTA) return Material.BLUE_TERRACOTTA;
                break;
            case 2:
                if (base == Material.STONE_BRICKS) return Material.SANDSTONE;
                if (base == Material.CRACKED_STONE_BRICKS) return Material.CUT_SANDSTONE;
                if (base == Material.RED_TERRACOTTA) return Material.ORANGE_TERRACOTTA;
                break;
            case 3:
                if (base == Material.STONE_BRICKS) return Material.TUFF_BRICKS;
                if (base == Material.CRACKED_STONE_BRICKS) return Material.POLISHED_TUFF;
                if (base == Material.RED_TERRACOTTA) return Material.GREEN_TERRACOTTA;
                break;
        }
        return base;
    }

    private void decorate(TerritoryCivilizations.Civ civ, TerritoryCivilizations.Site site, Location center) {
        if (!site.built) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-site.x + civ.centerX, site.z - civ.centerZ));
        Location terrace = center.clone().add(12, HEIGHT - 5, 12);
        FoliaRunnable.runRegionTask(plugin, terrace, () ->
                cannons.place(civ.owner + "-" + site.id, terrace, yaw));

        showStatus(civ, site);
        // Minecraft citizens/guards make empty forts feel lived-in without requiring VexBot.
        Villager resident = center.getWorld().spawn(center.clone().add(3, 1, -3), Villager.class);
        resident.setCustomName(ChatColor.GOLD + site.name + " Steward");
        resident.setCustomNameVisible(true);
        resident.setPersistent(true);
        IronGolem guardian = center.getWorld().spawn(center.clone().add(-3, 1, -3), IronGolem.class);
        guardian.setCustomName(ChatColor.AQUA + site.name + " Watch");
        guardian.setPlayerCreated(true);
        guardian.setPersistent(true);
    }

    void showStatus(TerritoryCivilizations.Civ civ, TerritoryCivilizations.Site site) {
        World world = Bukkit.getWorld(civ.world);
        if (world == null || !world.isChunkLoaded(site.x >> 4, site.z >> 4)) return;
        Location labelPos = new Location(world, site.x + .5, site.y + 4.2, site.z + .5);
        FoliaRunnable.runRegionTask(plugin, labelPos, () -> {
            TextDisplay text = null;
            for (Entity e : world.getNearbyEntities(labelPos, 3, 3, 3)) {
                if (e instanceof TextDisplay && (civ.owner.toString() + site.id).equals(
                        e.getPersistentDataContainer().get(labelKey, PersistentDataType.STRING))) {
                    text = (TextDisplay) e;
                    break;
                }
            }
            if (text == null) {
                text = world.spawn(labelPos, TextDisplay.class);
                text.setBillboard(Display.Billboard.CENTER);
                text.setPersistent(true);
                text.getPersistentDataContainer().set(labelKey, PersistentDataType.STRING,
                        civ.owner.toString() + site.id);
            }
            text.setText(ChatColor.GOLD + site.name + ChatColor.WHITE + " [" + site.hp
                    + "/100]" + ChatColor.GRAY + "  -  DEFEND THE CORE");
        });
    }
}
