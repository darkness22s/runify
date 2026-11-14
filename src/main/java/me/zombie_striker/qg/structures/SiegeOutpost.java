package me.zombie_striker.qg.structures;

import me.zombie_striker.qg.QAMain;
import me.zombie_striker.qg.hooks.protection.ProtectionHandler;
import me.zombie_striker.qg.utils.FoliaRunnable;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A purpose-built siege arena, not a vanilla pillager outpost.
 *
 * Each chunk is written on its own region thread for Folia compatibility,
 * with a small per-tick budget to avoid freezing the server.
 */
public final class SiegeOutpost {
    private static final int RADIUS = 16;
    private static final int HEIGHT = 39;
    private static final int BLOCKS_PER_TICK = 200;
    private static final int POSITIONS_PER_CHUNK = 16 * 16 * HEIGHT;
    private static final Set<UUID> ACTIVE_BUILDERS = ConcurrentHashMap.newKeySet();

    private SiegeOutpost() {
    }

    public static void spawn(QAMain plugin, Player player) {
        Location feet = player.getLocation();
        World world = feet.getWorld();
        int centerX = feet.getBlockX();
        int centerZ = feet.getBlockZ();
        int baseY = feet.getBlockY() - 1;

        if (baseY < world.getMinHeight() || baseY + HEIGHT > world.getMaxHeight()) {
            player.sendMessage(ChatColor.RED + "The outpost needs 39 blocks of vertical building space.");
            return;
        }

        WorldBorder border = world.getWorldBorder();
        for (int dx : new int[]{-RADIUS, RADIUS}) {
            for (int dz : new int[]{-RADIUS, RADIUS}) {
                if (!border.isInside(new Location(world, centerX + dx, baseY, centerZ + dz))) {
                    player.sendMessage(ChatColor.RED + "The entire outpost must fit inside the world border.");
                    return;
                }
            }
        }

        if (!ProtectionHandler.canBreak(feet) || !ACTIVE_BUILDERS.add(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You cannot build here, or you already have an outpost building.");
            return;
        }

        final int minChunkX = Math.floorDiv(centerX - RADIUS, 16);
        final int maxChunkX = Math.floorDiv(centerX + RADIUS, 16);
        final int minChunkZ = Math.floorDiv(centerZ - RADIUS, 16);
        final int maxChunkZ = Math.floorDiv(centerZ + RADIUS, 16);
        final int chunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        final AtomicInteger remaining = new AtomicInteger(chunks);
        final AtomicInteger protectedBlocks = new AtomicInteger();
        final AtomicInteger failedChunks = new AtomicInteger();

        player.sendMessage(ChatColor.GOLD + "Building your 33x33 siege outpost at your position..."
                + ChatColor.GRAY + " This replaces terrain inside the structure.");

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final int cx = chunkX;
                final int cz = chunkZ;
                Location region = new Location(world, cx * 16 + 8, baseY + 1, cz * 16 + 8);

                new FoliaRunnable() {
                    private int index;

                    @Override
                    public void run() {
                        try {
                            int end = Math.min(index + BLOCKS_PER_TICK, POSITIONS_PER_CHUNK);
                            while (index < end) {
                                int pos = index++;
                                int x = cx * 16 + (pos & 15);
                                int z = cz * 16 + ((pos >> 4) & 15);
                                int y = pos >> 8;
                                int dx = x - centerX;
                                int dz = z - centerZ;
                                if (Math.abs(dx) > RADIUS || Math.abs(dz) > RADIUS) {
                                    continue;
                                }

                                Material material = blockAt(dx, y, dz);
                                Block block = world.getBlockAt(x, baseY + y, z);
                                if (block.getType() == material) {
                                    continue;
                                }
                                if (block.getType() == Material.BEDROCK
                                        || block.getType() == Material.END_PORTAL_FRAME
                                        || !ProtectionHandler.canBreak(block.getLocation())) {
                                    protectedBlocks.incrementAndGet();
                                    continue;
                                }
                                // No physics updates for each block during construction.
                                block.setType(material, false);
                            }
                            if (index == POSITIONS_PER_CHUNK) {
                                cancel();
                                finish();
                            }
                        } catch (RuntimeException exception) {
                            plugin.getLogger().warning("Siege outpost chunk " + cx + "," + cz
                                    + " failed: " + exception.getMessage());
                            failedChunks.incrementAndGet();
                            cancel();
                            finish();
                        }
                    }

                    private void finish() {
                        if (remaining.decrementAndGet() == 0) {
                            ACTIVE_BUILDERS.remove(player.getUniqueId());
                            FoliaRunnable.runEntityTask(plugin, player, () -> {
                                if (failedChunks.get() > 0 || protectedBlocks.get() > 0) {
                                    player.sendMessage(ChatColor.YELLOW + "Outpost finished with "
                                            + failedChunks.get() + " failed chunks and "
                                            + protectedBlocks.get() + " protected blocks left unchanged.");
                                } else {
                                    player.sendMessage(ChatColor.GREEN
                                            + "Siege outpost ready! Use the scaffolding shafts to reach its fighting decks.");
                                }
                            });
                        }
                    }
                }.runTaskTimer(plugin, region, 1, 1);
            }
        }
    }

    /**
     * Deterministic cross-section: perimeter wall, four corner sniper towers,
     * five central fighting decks, sightlines, cover and a copper signal spire.
     * A non-null material is returned for every block, including carved air.
     */
    static Material blockAt(int x, int y, int z) {
        if (Math.abs(x) > RADIUS || Math.abs(z) > RADIUS || y < 0 || y >= HEIGHT) {
            return Material.AIR;
        }

        int ax = Math.abs(x);
        int az = Math.abs(z);
        if (y == 0) {
            return Material.STONE_BRICKS;
        }

        // Four corner towers, 7x7 and 30 blocks tall, each with climbable decks.
        int tower = Math.max(Math.abs(ax - 13), Math.abs(az - 13));
        if (tower <= 3 && y <= 30) {
            if (ax == 13 && az == 13 && y <= 28) {
                return Material.SCAFFOLDING;
            }
            if (y == 9 || y == 18 || y == 27) {
                return tower == 3 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.SPRUCE_PLANKS;
            }
            if (tower == 3 && y <= 28) {
                if (ax == 10 && az == 13 && y <= 4) {
                    return Material.AIR; // Courtyard-side tower doorway
                }
                if ((y % 9 == 4 || y % 9 == 5) && (ax == 13 || az == 13)) {
                    return Material.AIR; // Firing slits
                }
                return (y % 9 == 0) ? Material.CUT_COPPER : masonry(x, y, z);
            }
            if (y == 28 && tower <= 3) {
                return Material.POLISHED_BLACKSTONE_BRICKS;
            }
            if (y == 29 && tower == 3 && ((x + z) & 1) == 0) {
                return Material.STONE_BRICK_WALL;
            }
            return Material.AIR;
        }

        // External defensive wall and its elevated patrol walkway.
        if (y == 8 && (ax >= 14 || az >= 14)) {
            return Material.STONE_BRICKS;
        }
        if ((ax == 16 || az == 16) && y <= 7) {
            if (z == -16 && ax <= 3 && y <= 5) {
                return Material.AIR; // Wide entrance for both players
            }
            if (y == 4 && ((x + z) % 5 == 0)) {
                return Material.AIR;
            }
            return masonry(x, y, z);
        }
        if (y == 9 && (ax == 16 || az == 16) && ((x + z) & 1) == 0) {
            return Material.STONE_BRICK_WALL;
        }

        // Five storeys in the inner keep, with open combat rooms and cover.
        if (ax <= 10 && az <= 10) {
            if (ax == 7 && az == 7 && x * z > 0 && y <= 32) {
                return Material.SCAFFOLDING;
            }
            if (y == 8 || y == 16 || y == 24 || y == 32) {
                return (ax == 10 || az == 10) ? Material.POLISHED_BLACKSTONE_BRICKS
                        : Material.SPRUCE_PLANKS;
            }
            if ((ax == 10 || az == 10) && y <= 31) {
                if (z == -10 && ax <= 2 && y <= 5) {
                    return Material.AIR;
                }
                if (y % 8 >= 3 && y % 8 <= 5
                        && ((ax == 10 && az % 5 == 0) || (az == 10 && ax % 5 == 0))) {
                    return Material.AIR;
                }
                if (y % 8 == 7) {
                    return Material.RED_TERRACOTTA;
                }
                return masonry(x, y, z);
            }
            if (y <= 25 && y % 8 == 1
                    && ((ax == 4 && az <= 3) || (az == 4 && ax <= 3))) {
                return Material.POLISHED_BLACKSTONE_BRICKS; // Cover on each floor
            }
            if (y <= 32 && (y % 8 == 3) && ax == 6 && az == 6) {
                return Material.SEA_LANTERN;
            }

            // Distinctive stepped copper signal spire, visible from a distance.
            if (y >= 33 && y <= 35 && ax <= 2 && az <= 2) {
                return Material.CUT_COPPER;
            }
            if (y == 36 && ax <= 1 && az <= 1) {
                return Material.COPPER_BLOCK;
            }
            if (y == 37 && ax == 0 && az == 0) {
                return Material.COPPER_BLOCK;
            }
            if (y == 38 && ax == 0 && az == 0) {
                return Material.SEA_LANTERN;
            }
            if ((y == 33 || y == 34) && (ax == 10 || az == 10)
                    && ((x + z) & 1) == 0) {
                return Material.STONE_BRICK_WALL;
            }
        }
        return Material.AIR;
    }

    private static Material masonry(int x, int y, int z) {
        return ((x * 31 + y * 7 + z * 17) & 7) == 0
                ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
    }
}
