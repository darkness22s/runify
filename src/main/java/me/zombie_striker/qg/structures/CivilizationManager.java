package me.zombie_striker.qg.structures;

import me.zombie_striker.qg.QAMain;
import me.zombie_striker.qg.hooks.protection.ProtectionHandler;
import me.zombie_striker.qg.utils.FoliaRunnable;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A multi-location, persistent siege civilization: citadel, four unique watch
 * outposts and four resource districts, connected by a defensible road network.
 * Players must defend each district's lodestone objective during optional raids.
 */
public final class CivilizationManager implements Listener {
    private static final int BLOCKS_PER_TICK = 180;
    private static final int MAX_HP = 100;
    private static final int GRID_DISTANCE = 76;
    private final QAMain plugin;
    private final SiegeCannon cannons;
    private final File dataFile;
    private final NamespacedKey raidKey;
    private final Map<UUID, Civilization> civilizations = new ConcurrentHashMap<>();
    private final Set<UUID> constructing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastRaid = new ConcurrentHashMap<>();

    private enum Kind { CITADEL, OUTPOST, GRANARY, FORGE, HOSPITAL, WATERWORKS }

    private static final class Site {
        final String id;
        final String title;
        final Kind kind;
        final int dx, dz, radius, height;
        volatile int hp = MAX_HP;
        Site(String id, String title, Kind kind, int dx, int dz, int radius, int height) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.dx = dx;
            this.dz = dz;
            this.radius = radius;
            this.height = height;
        }
    }

    private static final class Civilization {
        final UUID owner, worldId;
        final int x, y, z;
        final List<Site> sites = sites();
        volatile boolean complete;
        Civilization(UUID owner, UUID worldId, int x, int y, int z) {
            this.owner = owner;
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
        World world() { return Bukkit.getWorld(worldId); }
        Location location(Site s) {
            World w = world();
            return w == null ? null : new Location(w, x + s.dx, y, z + s.dz);
        }
    }

    private static List<Site> sites() {
        return Arrays.asList(
                new Site("citadel", "Crown Citadel", Kind.CITADEL, 0, 0, 20, 38),
                new Site("northwest", "Northwest Sentinel", Kind.OUTPOST, -GRID_DISTANCE, -GRID_DISTANCE, 13, 30),
                new Site("northeast", "Northeast Sentinel", Kind.OUTPOST, GRID_DISTANCE, -GRID_DISTANCE, 13, 30),
                new Site("southwest", "Southwest Sentinel", Kind.OUTPOST, -GRID_DISTANCE, GRID_DISTANCE, 13, 30),
                new Site("southeast", "Southeast Sentinel", Kind.OUTPOST, GRID_DISTANCE, GRID_DISTANCE, 13, 30),
                new Site("granary", "Golden Granary", Kind.GRANARY, 0, -GRID_DISTANCE, 10, 19),
                new Site("forge", "Iron Foundry", Kind.FORGE, GRID_DISTANCE, 0, 10, 19),
                new Site("hospital", "Sanctuary Clinic", Kind.HOSPITAL, -GRID_DISTANCE, 0, 10, 19),
                new Site("water", "Azure Waterworks", Kind.WATERWORKS, 0, GRID_DISTANCE, 10, 19));
    }

    public CivilizationManager(QAMain plugin, SiegeCannon cannons) {
        this.plugin = plugin;
        this.cannons = cannons;
        this.dataFile = new File(plugin.getDataFolder(), "civilizations.yml");
        this.raidKey = new NamespacedKey(plugin, "civilization_raider");
        load();
    }

    private synchronized void load() {
        if (!dataFile.isFile()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        for (String id : config.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(id);
                UUID world = UUID.fromString(config.getString(id + ".world"));
                Civilization c = new Civilization(owner, world, config.getInt(id + ".x"),
                        config.getInt(id + ".y"), config.getInt(id + ".z"));
                c.complete = config.getBoolean(id + ".complete");
                for (Site site : c.sites)
                    site.hp = Math.max(0, Math.min(MAX_HP, config.getInt(id + ".hp." + site.id, MAX_HP)));
                civilizations.put(owner, c);
            } catch (IllegalArgumentException | NullPointerException e) {
                plugin.getLogger().warning("Skipping invalid civilization " + id);
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Civilization c : civilizations.values()) {
            String root = c.owner.toString() + ".";
            config.set(root + "world", c.worldId.toString());
            config.set(root + "x", c.x);
            config.set(root + "y", c.y);
            config.set(root + "z", c.z);
            config.set(root + "complete", c.complete);
            for (Site s : c.sites) config.set(root + "hp." + s.id, s.hp);
        }
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save civilization state: " + e.getMessage());
        }
    }

    public void command(Player player, String[] args) {
        if (args.length == 1) {
            build(player);
            return;
        }
        if (args.length != 2) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /qa civ [status|raid|repair|locate]");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status": status(player); break;
            case "raid": raid(player); break;
            case "repair": repair(player); break;
            case "locate": locate(player); break;
            default: player.sendMessage(ChatColor.YELLOW + "Usage: /qa civ [status|raid|repair|locate]");
        }
    }

    public void build(Player player) {
        UUID owner = player.getUniqueId();
        if (constructing.contains(owner)) {
            player.sendMessage(ChatColor.YELLOW + "The civilization is still being constructed.");
            return;
        }
        Civilization old = civilizations.get(owner);
        if (old != null && old.complete) {
            player.sendMessage(ChatColor.YELLOW + "Your civilization already exists at "
                    + old.x + ", " + old.y + ", " + old.z + ". Use /qa civ status or /qa civ locate.");
            return;
        }

        Location here = player.getLocation();
        World world = here.getWorld();
        // An interrupted build can be retried in its original location.
        Civilization c = old == null ? new Civilization(owner, world.getUID(),
                here.getBlockX(), here.getBlockY() - 1, here.getBlockZ()) : old;
        if (old != null && !old.worldId.equals(world.getUID())) {
            player.sendMessage(ChatColor.RED + "Finish your earlier civilization in its original world.");
            return;
        }
        if (c.y < world.getMinHeight() || c.y + 38 > world.getMaxHeight()) {
            player.sendMessage(ChatColor.RED + "The civilization needs 38 blocks of vertical space.");
            return;
        }

        WorldBorder border = world.getWorldBorder();
        int extent = GRID_DISTANCE + 16;
        for (int dx : new int[]{-extent, extent}) {
            for (int dz : new int[]{-extent, extent}) {
                if (!border.isInside(new Location(world, c.x + dx, c.y, c.z + dz))) {
                    player.sendMessage(ChatColor.RED + "The civilization needs about 185x185 blocks within the world border.");
                    return;
                }
            }
        }
        // Never start in a claimed region or near a different player's civilization.
        if (!ProtectionHandler.canBreak(here)) {
            player.sendMessage(ChatColor.RED + "Construction is forbidden in this protected region.");
            return;
        }
        for (Civilization other : civilizations.values()) {
            if (!owner.equals(other.owner) && other.worldId.equals(c.worldId)
                    && Math.abs(other.x - c.x) < 200 && Math.abs(other.z - c.z) < 200) {
                player.sendMessage(ChatColor.RED + "Too close to another civilization (minimum 200 blocks).");
                return;
            }
        }
        if (!constructing.add(owner)) return;
        civilizations.put(owner, c);
        save();

        player.sendMessage(ChatColor.GOLD + "Establishing the Crownlands: 9 districts, 4 outposts, "
                + "5 siege cannons and objective defenses." + ChatColor.RED
                + " This WILL replace terrain. Build in an open dedicated world!");
        AtomicInteger remaining = new AtomicInteger(c.sites.size());
        AtomicInteger failures = new AtomicInteger();
        for (Site site : c.sites) {
            buildSite(c, site, player, remaining, failures);
        }
    }

    private void buildSite(Civilization c, Site site, Player player,
                           AtomicInteger remaining, AtomicInteger failures) {
        World world = c.world();
        if (world == null) {
            failures.incrementAndGet();
            siteFinished(c, site, player, remaining, failures, false);
            return;
        }
        int x0 = c.x + site.dx, z0 = c.z + site.dz;
        int r = site.radius;
        int minX = Math.floorDiv(x0 - r, 16), maxX = Math.floorDiv(x0 + r, 16);
        int minZ = Math.floorDiv(z0 - r, 16), maxZ = Math.floorDiv(z0 + r, 16);
        AtomicInteger chunks = new AtomicInteger((maxX - minX + 1) * (maxZ - minZ + 1));
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        final int volume = 16 * 16 * site.height;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                final int chunkX = cx, chunkZ = cz;
                Location region = new Location(world, cx * 16 + 8, c.y + 1, cz * 16 + 8);
                new FoliaRunnable() {
                    int index;
                    @Override
                    public void run() {
                        try {
                            int end = Math.min(index + BLOCKS_PER_TICK, volume);
                            while (index < end) {
                                int pos = index++;
                                int x = chunkX * 16 + (pos & 15);
                                int z = chunkZ * 16 + ((pos >> 4) & 15);
                                int y = pos >> 8;
                                int dx = x - x0, dz = z - z0;
                                if (Math.abs(dx) > r || Math.abs(dz) > r) continue;
                                Material shape = materialAt(site, dx, y, dz);
                                Block block = world.getBlockAt(x, c.y + y, z);
                                if (block.getType() == shape) continue;
                                if (block.getType() == Material.BEDROCK
                                        || block.getType() == Material.END_PORTAL_FRAME
                                        || !ProtectionHandler.canBreak(block.getLocation())) {
                                    skipped.incrementAndGet();
                                    continue;
                                }
                                block.setType(shape, false);
                            }
                            if (index == volume) {
                                cancel();
                                finish();
                            }
                        } catch (RuntimeException ex) {
                            plugin.getLogger().warning("Civilization chunk " + chunkX + ","
                                    + chunkZ + " failed: " + ex.getMessage());
                            errors.incrementAndGet();
                            cancel();
                            finish();
                        }
                    }
                    void finish() {
                        if (chunks.decrementAndGet() == 0) {
                            boolean clean = errors.get() == 0 && skipped.get() == 0;
                            if (!clean) {
                                failures.incrementAndGet();
                                plugin.getLogger().warning(site.id + " had " + skipped.get()
                                        + " protected blocks and " + errors.get() + " failed chunks.");
                            }
                            siteFinished(c, site, player, remaining, failures, clean);
                        }
                    }
                }.runTaskTimer(plugin, region, 1, 1);
            }
        }
    }

    private void siteFinished(Civilization c, Site site, Player owner,
                              AtomicInteger remaining, AtomicInteger failures, boolean clean) {
        Location center = c.location(site);
        if (center != null && clean) {
            FoliaRunnable.runRegionTask(plugin, center, () -> {
                addLife(center, site);
                if (site.kind == Kind.CITADEL || site.kind == Kind.OUTPOST) {
                    float yaw = site.kind == Kind.CITADEL ? 180.0f
                            : (float) Math.toDegrees(Math.atan2(-site.dx, site.dz));
                    cannons.place(c.owner + "-" + site.id,
                            center.clone().add(5.5, 1.0, 4.5), yaw);
                }
            });
        }
        if (remaining.decrementAndGet() == 0) {
            buildRoads(c, () -> {
                c.complete = true;
                constructing.remove(c.owner);
                save();
                FoliaRunnable.runEntityTask(plugin, owner, () -> {
                    owner.sendMessage(ChatColor.GREEN + "Civilization created: nine objectives, "
                            + "connecting roads and artillery across the Crownlands.");
                    if (failures.get() > 0)
                        owner.sendMessage(ChatColor.YELLOW + failures.get()
                                + " districts hit protection limits or failed to finish; check server logs.");
                    owner.sendMessage(ChatColor.AQUA + "/qa civ status" + ChatColor.GRAY + " for defenses; "
                            + ChatColor.AQUA + "/qa civ raid" + ChatColor.GRAY + " to unleash a siege.");
                });
            });
        }
    }

    /**
     * Three-dimensional districts are linked with wide illuminated stone roads:
     * capital-to-facility spokes and a perimeter ring joining the outposts.
     * Road construction is chunk-bounded and uses the same per-tick budget.
     */
    private void buildRoads(Civilization c, Runnable complete) {
        World world = c.world();
        if (world == null) {
            complete.run();
            return;
        }
        int minX = Math.floorDiv(c.x - 80, 16), maxX = Math.floorDiv(c.x + 80, 16);
        int minZ = Math.floorDiv(c.z - 80, 16), maxZ = Math.floorDiv(c.z + 80, 16);
        AtomicInteger pending = new AtomicInteger((maxX - minX + 1) * (maxZ - minZ + 1));
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                final int chunkX = cx, chunkZ = cz;
                Location region = new Location(world, cx * 16 + 8, c.y + 1, cz * 16 + 8);
                new FoliaRunnable() {
                    int index;
                    @Override
                    public void run() {
                        try {
                            int end = Math.min(index + BLOCKS_PER_TICK, 256);
                            while (index < end) {
                                int pos = index++;
                                int x = chunkX * 16 + (pos & 15);
                                int z = chunkZ * 16 + (pos >> 4);
                                Material road = roadAt(x - c.x, z - c.z);
                                if (road == null) continue;
                                Block b = world.getBlockAt(x, c.y, z);
                                if (b.getType() == road || b.getType() == Material.BEDROCK
                                        || b.getType() == Material.END_PORTAL_FRAME
                                        || !ProtectionHandler.canBreak(b.getLocation())) continue;
                                b.setType(road, false);
                            }
                        } catch (RuntimeException e) {
                            plugin.getLogger().warning("Road chunk " + chunkX + ","
                                    + chunkZ + " failed: " + e.getMessage());
                            cancel();
                            finish();
                            return;
                        }
                        if (index == 256) {
                            cancel();
                            finish();
                        }
                    }
                    void finish() {
                        if (pending.decrementAndGet() == 0) complete.run();
                    }
                }.runTaskTimer(plugin, region, 1, 1);
            }
        }
    }

    private static Material roadAt(int x, int z) {
        int ax = Math.abs(x), az = Math.abs(z);
        // Radial streets have no overlap with the citadel or the side districts.
        boolean eastWest = az <= 2 && ax >= 21 && ax <= 65;
        boolean northSouth = ax <= 2 && az >= 21 && az <= 65;
        // The ring links each side facility to both adjacent watch outposts.
        boolean ringEW = Math.abs(az - GRID_DISTANCE) <= 2 && ax >= 11 && ax <= 63;
        boolean ringNS = Math.abs(ax - GRID_DISTANCE) <= 2 && az >= 11 && az <= 63;
        if (!(eastWest || northSouth || ringEW || ringNS)) return null;
        boolean edge = (eastWest || ringEW) ? az == 2 || Math.abs(az - GRID_DISTANCE) == 2
                : ax == 2 || Math.abs(ax - GRID_DISTANCE) == 2;
        if (edge && ((ax + az) % 13 == 0)) return Material.SEA_LANTERN;
        return edge ? Material.CUT_COPPER : Material.SMOOTH_STONE;
    }

    private void addLife(Location base, Site site) {
        // Civilian NPCs give raiders natural targets, and each garrison has a guardian.
        for (Entity existing : base.getWorld().getNearbyEntities(base.clone().add(0, 2, 0), 7, 7, 7)) {
            if (existing instanceof Villager && existing.getCustomName() != null
                    && existing.getCustomName().contains(site.title)) return;
        }
        for (int i = 0; i < (site.kind == Kind.CITADEL ? 3 : 1); i++) {
            Location at = base.clone().add(2 + i, 1, -2);
            Villager citizen = base.getWorld().spawn(at, Villager.class);
            citizen.setCustomName(ChatColor.GOLD + site.title + " • Citizen");
            citizen.setCustomNameVisible(true);
            citizen.setProfession(site.kind == Kind.FORGE ? Villager.Profession.TOOLSMITH
                    : site.kind == Kind.GRANARY ? Villager.Profession.FARMER
                    : site.kind == Kind.HOSPITAL ? Villager.Profession.CLERIC
                    : Villager.Profession.LIBRARIAN);
            citizen.setPersistent(true);
        }
        if (site.kind == Kind.CITADEL || site.kind == Kind.OUTPOST) {
            IronGolem guard = base.getWorld().spawn(base.clone().add(-3, 1, -1), IronGolem.class);
            guard.setCustomName(ChatColor.AQUA + site.title + " Guardian");
            guard.setPlayerCreated(true);
            guard.setPersistent(true);
        }
    }

    private Material materialAt(Site s, int x, int y, int z) {
        int r = s.radius, ax = Math.abs(x), az = Math.abs(z);
        if (y == 0) return s.kind == Kind.WATERWORKS ? Material.PRISMARINE_BRICKS : Material.STONE_BRICKS;
        if (x == 0 && y == 2 && z == 0) return Material.LODESTONE; // Destroyable strategic objective
        if (s.kind == Kind.CITADEL || s.kind == Kind.OUTPOST) {
            int towerR = r - 3;
            boolean tower = ax >= towerR - 2 && az >= towerR - 2;
            int deck = s.kind == Kind.CITADEL ? 8 : 7;
            if (tower && y < s.height - 4) {
                if (ax == towerR && az == towerR && y < s.height - 6) return Material.SCAFFOLDING;
                if (y == deck || y == 2 * deck || y == 3 * deck)
                    return Material.DARK_OAK_PLANKS;
                if ((ax == r || az == r) && y < s.height - 6) {
                    if (y % 7 == 3 && (ax == towerR || az == towerR)) return Material.AIR;
                    return masonry(x, y, z);
                }
                if (y == s.height - 5 && (ax == r || az == r) && (x + z) % 2 == 0)
                    return Material.STONE_BRICK_WALL;
            }
            if ((ax == r || az == r) && y <= 8) {
                if (z == -r && ax <= 3 && y < 5) return Material.AIR;
                if (y == 5 && ((x + z) % 6 == 0)) return Material.AIR;
                return masonry(x, y, z);
            }
            if (y == 9 && (ax == r || az == r) && ((x + z) & 1) == 0)
                return Material.STONE_BRICK_WALL;
            if (y == 8 && (ax == r - 1 || az == r - 1))
                return Material.STONE_BRICKS;

            int inner = s.kind == Kind.CITADEL ? 10 : 6;
            if (ax <= inner && az <= inner) {
                if (ax == inner - 2 && az == inner - 2 && y < s.height - 6)
                    return Material.SCAFFOLDING;
                if (y == deck || y == 2 * deck || y == 3 * deck)
                    return ax == inner || az == inner ? Material.POLISHED_BLACKSTONE_BRICKS
                            : Material.SPRUCE_PLANKS;
                if ((ax == inner || az == inner) && y < s.height - 5) {
                    if (z == -inner && ax <= 2 && y < 5) return Material.AIR;
                    if (y % deck >= 3 && y % deck <= 5 && (x + z) % 4 == 0)
                        return Material.AIR;
                    if (y % deck == 0) return Material.CUT_COPPER;
                    return masonry(x, y, z);
                }
                if (y == s.height - 4 && ax <= 2 && az <= 2) return Material.COPPER_BLOCK;
                if (y == s.height - 3 && ax <= 1 && az <= 1) return Material.CUT_COPPER;
                if (y == s.height - 2 && ax == 0 && az == 0) return Material.SEA_LANTERN;
                if (y == 1 && (ax == 3 || az == 3) && ax < 5 && az < 5)
                    return Material.POLISHED_BLACKSTONE_BRICKS;
            }
            if (y == 1 && ((ax == r - 4 && az < r - 4) || (az == r - 4 && ax < r - 4)))
                return Material.DARK_OAK_SLAB;
            return Material.AIR;
        }

        // Four visibly different civilian facilities, with decorative roofs and interiors.
        if (ax == r || az == r) {
            if (y <= 7) {
                if (z == -r && ax <= 2 && y <= 3) return Material.AIR;
                if ((x + z) % 7 == 0 && y >= 3 && y <= 5) return Material.GLASS_PANE;
                if (s.kind == Kind.WATERWORKS) return Material.PRISMARINE_BRICKS;
                if (s.kind == Kind.GRANARY) return Material.STRIPPED_SPRUCE_WOOD;
                if (s.kind == Kind.HOSPITAL) return Material.QUARTZ_BRICKS;
                return Material.DEEPSLATE_BRICKS;
            }
        }
        if (y >= 8 && y <= 14 && ax + az <= (15 - y) * 2 + 11) {
            if (y == 8 && ax < r && az < r) {
                if (s.kind == Kind.GRANARY) return Material.HAY_BLOCK;
                if (s.kind == Kind.FORGE) return Material.DEEPSLATE_TILES;
                if (s.kind == Kind.HOSPITAL) return Material.WHITE_CONCRETE;
                return Material.DARK_PRISMARINE;
            }
            if (y == 10 && ax <= 4 && az <= 4) return Material.AIR;
            if (ax >= r - (y - 8) || az >= r - (y - 8)) {
                return s.kind == Kind.GRANARY ? Material.SPRUCE_PLANKS
                        : s.kind == Kind.WATERWORKS ? Material.DARK_PRISMARINE
                        : s.kind == Kind.FORGE ? Material.POLISHED_BLACKSTONE
                        : Material.QUARTZ_BLOCK;
            }
        }
        if (s.kind == Kind.FORGE && y == 1 && (ax == 5 && az <= 4))
            return Material.BLAST_FURNACE;
        if (s.kind == Kind.GRANARY && y == 1 && ax >= 4 && ax <= 6 && az <= 5)
            return Material.HAY_BLOCK;
        if (s.kind == Kind.HOSPITAL && y == 1 && ax >= 4 && ax <= 6 && az <= 5)
            return Material.WHITE_WOOL;
        if (s.kind == Kind.WATERWORKS && y == 1 && (ax == 6 || az == 6))
            return Material.SEA_LANTERN;
        if (y == 1 && ax <= 1 && az <= 1) return Material.POLISHED_BLACKSTONE_BRICKS;
        if (y == 3 && ax == 4 && az == 4) return Material.SEA_LANTERN;
        return Material.AIR;
    }

    private static Material masonry(int x, int y, int z) {
        return ((x * 31 + y * 17 + z * 11) & 7) == 0
                ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
    }

    private Civilization ours(Player player) {
        Civilization c = civilizations.get(player.getUniqueId());
        if (c == null) player.sendMessage(ChatColor.RED + "Create a civilization first: /qa civ");
        return c;
    }

    private void status(Player player) {
        Civilization c = ours(player);
        if (c == null) return;
        player.sendMessage(ChatColor.GOLD + "=== Crownlands defenses ==="
                + (c.complete ? "" : ChatColor.YELLOW + " (constructing)"));
        for (Site s : c.sites) {
            ChatColor color = s.hp == 0 ? ChatColor.RED : s.hp < 40 ? ChatColor.YELLOW : ChatColor.GREEN;
            player.sendMessage(color + s.title + " " + s.hp + "/100"
                    + ChatColor.GRAY + " (" + (c.x + s.dx) + ", " + (c.z + s.dz) + ")"
                    + (s.kind == Kind.CITADEL || s.kind == Kind.OUTPOST ? ChatColor.AQUA + " [cannon]" : ""));
        }
        player.sendMessage(ChatColor.GRAY + "Protect each district's lodestone. "
                + "Repair for 12 iron ingots nearby; trigger attacks with /qa civ raid.");
    }

    private void locate(Player player) {
        Civilization c = ours(player);
        if (c == null) return;
        player.sendMessage(ChatColor.AQUA + "Capital: " + c.x + ", " + (c.y + 1) + ", " + c.z
                + " in " + c.worldId + ". Outposts ±76 blocks diagonally; "
                + "facilities ±76 blocks on cardinal roads.");
    }

    private void repair(Player player) {
        Civilization c = ours(player);
        if (c == null) return;
        if (c.world() == null || !c.worldId.equals(player.getWorld().getUID())) return;
        Site nearest = null;
        double best = 144;
        for (Site s : c.sites) {
            Location center = c.location(s);
            if (center == null) continue;
            double distance = center.clone().add(0, 2, 0).distanceSquared(player.getLocation());
            if (distance < best) {
                best = distance;
                nearest = s;
            }
        }
        if (nearest == null) {
            player.sendMessage(ChatColor.RED + "Stand within 12 blocks of a district's objective.");
            return;
        }
        final Site s = nearest;
        if (s.hp >= MAX_HP) {
            player.sendMessage(ChatColor.GREEN + s.title + " is already fully defended.");
            return;
        }
        if (!player.getInventory().contains(Material.IRON_INGOT, 12)) {
            player.sendMessage(ChatColor.RED + "You need 12 iron ingots to restore 40 integrity.");
            return;
        }
        Location core = c.location(s).add(0, 2, 0);
        if (!ProtectionHandler.canBreak(core)) {
            player.sendMessage(ChatColor.RED + "Cannot repair in a protected region.");
            return;
        }
        // Remove exactly twelve ingots, without consuming unrelated inventory items.
        int owed = 12;
        ItemStack[] inventory = player.getInventory().getStorageContents();
        for (int i = 0; i < inventory.length && owed > 0; i++) {
            ItemStack stack = inventory[i];
            if (stack == null || stack.getType() != Material.IRON_INGOT) continue;
            int taken = Math.min(owed, stack.getAmount());
            stack.setAmount(stack.getAmount() - taken);
            inventory[i] = stack.getAmount() == 0 ? null : stack;
            owed -= taken;
        }
        player.getInventory().setStorageContents(inventory);
        core.getBlock().setType(Material.LODESTONE, false);
        s.hp = Math.min(MAX_HP, s.hp + 40);
        save();
        player.sendMessage(ChatColor.GREEN + s.title + " repaired to " + s.hp + "/100 integrity.");
    }

    private void raid(Player player) {
        Civilization c = ours(player);
        if (c == null || !c.complete) {
            player.sendMessage(ChatColor.RED + "Finish the civilization before starting a raid.");
            return;
        }
        if (c.world() == null) return;
        long now = System.currentTimeMillis();
        Long previous = lastRaid.putIfAbsent(c.owner, now);
        if (previous != null && now - previous < 240000) {
            player.sendMessage(ChatColor.YELLOW + "The raiders need time to regroup: "
                    + ((240000 - (now - previous)) / 1000 + 1) + " seconds.");
            return;
        }
        lastRaid.put(c.owner, now);
        player.sendMessage(ChatColor.RED + "A raid is attacking every district! "
                + "Protect the lodestones and the citizens.");
        for (Site s : c.sites) {
            if (s.hp <= 0) continue;
            Location center = c.location(s);
            FoliaRunnable.runRegionTask(plugin, center, () -> {
                Location entrance = center.clone().add(0, 1, -s.radius - 5);
                for (int i = 0; i < 3; i++) {
                    Location enemyLocation = entrance.clone().add(i * 2 - 2, 0, -(i % 2));
                    Entity enemy;
                    if (i == 0) {
                        enemy = center.getWorld().spawn(enemyLocation, Vindicator.class);
                    } else {
                        enemy = center.getWorld().spawn(enemyLocation, Pillager.class);
                    }
                    enemy.getPersistentDataContainer().set(raidKey, PersistentDataType.STRING, c.owner.toString());
                    enemy.setCustomName(ChatColor.RED + "Crownlands Raider");
                    enemy.setCustomNameVisible(true);
                }
                startSiegePressure(c, s, center);
            });
        }
    }

    /** Raiders near a lodestone gradually damage its objective. Kill them or lose the district. */
    private void startSiegePressure(Civilization c, Site s, Location center) {
        new FoliaRunnable() {
            int rounds, quiet;
            @Override
            public void run() {
                if (++rounds > 100 || s.hp <= 0) { cancel(); return; }
                int close = 0, far = 0;
                Location core = center.clone().add(0, 2, 0);
                for (Entity e : center.getWorld().getNearbyEntities(core, s.radius + 12,
                        10, s.radius + 12)) {
                    if (c.owner.toString().equals(e.getPersistentDataContainer()
                            .get(raidKey, PersistentDataType.STRING))) {
                        far++;
                        if (e.getLocation().distanceSquared(core) <= 100) close++;
                    }
                }
                if (far == 0) {
                    if (++quiet >= 4) cancel();
                    return;
                }
                quiet = 0;
                if (close > 0) hurt(c, s, Math.min(15, close * 5), "raiders");
            }
        }.runTaskTimer(plugin, center, 60, 60);
    }

    private static boolean coreAt(Civilization c, Site s, Block block) {
        return c.worldId.equals(block.getWorld().getUID())
                && block.getX() == c.x + s.dx && block.getY() == c.y + 2
                && block.getZ() == c.z + s.dz;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCoreBreak(BlockBreakEvent event) {
        coreBroken(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCoreExplosion(EntityExplodeEvent event) {
        for (Block block : event.blockList()) coreBroken(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        for (Block block : event.blockList()) coreBroken(block);
    }

    private void coreBroken(Block block) {
        for (Civilization c : civilizations.values()) {
            for (Site s : c.sites) {
                if (s.hp > 0 && coreAt(c, s, block)) hurt(c, s, MAX_HP, "an explosion or sabotage");
            }
        }
    }

    private void hurt(Civilization c, Site s, int damage, String cause) {
        if (s.hp <= 0) return;
        synchronized (s) {
            if (s.hp <= 0) return;
            s.hp = Math.max(0, s.hp - damage);
            save();
        }
        Player owner = Bukkit.getPlayer(c.owner);
        if (owner != null) {
            FoliaRunnable.runEntityTask(plugin, owner, () -> owner.sendMessage(
                    (s.hp == 0 ? ChatColor.DARK_RED + "[FALLEN] " : ChatColor.RED + "[UNDER ATTACK] ")
                            + s.title + ": " + s.hp + "/100; " + cause + "!"));
        }
        if (s.hp == 0) {
            // Create a visible ruined objective. A player can rebuild it with /qa civ repair.
            Location core = c.location(s);
            if (core != null) FoliaRunnable.runRegionTask(plugin,
                    core, () -> core.clone().add(0, 2, 0).getBlock().setType(Material.AIR, false));
        }
    }
}
