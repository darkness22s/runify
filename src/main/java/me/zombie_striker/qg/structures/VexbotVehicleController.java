package me.zombie_striker.qg.structures;

import me.zombie_striker.qg.QAMain;
import me.zombie_striker.qg.utils.FoliaRunnable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real vehicle AI for VexBot fake Player entities on Paper 1.21.11.
 *
 * VexBot handles spawning, equipment and foot combat through documented
 * commands. Runify physically mounts those fake Player entities in real tanks,
 * steers the moving horse chassis, operates the *real* tank shell/reload
 * mechanism, and moves artillery crew to *real* cannon stations before firing.
 * No undocumented VexBot internal classes are used.
 */
public final class VexbotVehicleController implements Listener {
    public enum Role { TANK, CANNON }
    private final QAMain plugin;
    private final TerritoryCivilizations territories;
    private final SiegeCannon cannon;
    private final VexbotGuardBridge bridge;
    private final File dataFile;
    private final Map<UUID, Assignment> assignments = new ConcurrentHashMap<>();
    private final Map<UUID, Track> locations = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> attackTargets = new ConcurrentHashMap<>();
    private final Set<UUID> retreating = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;

    private static final class Assignment {
        final UUID bot, owner;
        final Role role;
        volatile long lastOrder;
        final Location station;
        Assignment(UUID bot, UUID owner, Role role, Location station) {
            this.bot = bot; this.owner = owner; this.role = role;
            this.station = station;
        }
    }

    private static final class Track {
        final UUID world;
        final double x, y, z;
        final long at;
        Track(Location loc) {
            this.world = loc.getWorld().getUID();
            this.x = loc.getX(); this.y = loc.getY(); this.z = loc.getZ();
            this.at = System.currentTimeMillis();
        }
        Location location() {
            World worldObj = Bukkit.getWorld(world);
            return worldObj == null ? null : new Location(worldObj, x, y, z);
        }
    }

    public VexbotVehicleController(QAMain plugin, TerritoryCivilizations territories,
                                   SiegeCannon cannon, VexbotGuardBridge bridge) {
        this.plugin = plugin;
        this.territories = territories;
        this.cannon = cannon;
        this.bridge = bridge;
        this.dataFile = new File(plugin.getDataFolder(), "vexbot-vehicle-crews.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // The coordinator reads cached player coordinates; the actual vehicle
        // movement and combat happen on each bot's / tank's Folia entity thread.
        new FoliaRunnable() {
            @Override public void run() {
                if (!enabled || !bridge.available()) return;
                for (Assignment assignment : new ArrayList<>(assignments.values())) {
                    Player bot = Bukkit.getPlayer(assignment.bot);
                    if (bot != null && bot.isOnline()) {
                        FoliaRunnable.runEntityTask(plugin, bot, () -> tick(bot, assignment));
                    }
                }
            }
        }.runTaskTimer(plugin, 40, 20);
    }

    private synchronized void load() {
        if (!dataFile.isFile()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        for (String id : config.getKeys(false)) {
            try {
                UUID bot = UUID.fromString(id);
                UUID owner = UUID.fromString(config.getString(id + ".owner"));
                Role role = Role.valueOf(config.getString(id + ".role"));
                UUID world = UUID.fromString(config.getString(id + ".world"));
                World destination = Bukkit.getWorld(world);
                Location station = destination == null ? null : new Location(destination,
                        config.getDouble(id + ".x"), config.getDouble(id + ".y"),
                        config.getDouble(id + ".z"));
                assignments.put(bot, new Assignment(bot, owner, role, station));
            } catch (IllegalArgumentException | NullPointerException ex) {
                plugin.getLogger().warning("Invalid vehicle guard crew assignment: " + id);
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Assignment a : assignments.values()) {
            config.set(a.bot + ".owner", a.owner.toString());
            config.set(a.bot + ".role", a.role.name());
            if (a.station != null) {
                config.set(a.bot + ".world", a.station.getWorld().getUID().toString());
                config.set(a.bot + ".x", a.station.getX());
                config.set(a.bot + ".y", a.station.getY());
                config.set(a.bot + ".z", a.station.getZ());
            }
        }
        try { config.save(dataFile); }
        catch (IOException ex) { plugin.getLogger().warning("Cannot save guard crews: " + ex.getMessage()); }
    }

    public void shutdown() {
        enabled = false;
        save();
    }

    public boolean isGuard(UUID civilization, UUID bot) {
        Assignment a = assignments.get(bot);
        return a != null && a.owner.equals(civilization);
    }

    public UUID civilizationOf(UUID bot) {
        Assignment assignment = assignments.get(bot);
        return assignment == null ? null : assignment.owner;
    }

    public boolean isAnyGuard(UUID bot) {
        return assignments.containsKey(bot);
    }

    /**
     * A VexBot fake player has a synthetic server connection without a normal
     * client address. Reject real player accounts so the owner cannot forcibly
     * hijack human players' movement or mount them.
     */
    private static boolean fakePlayer(Player player) {
        return player != null && player.getAddress() == null;
    }

    public void command(Player leader, UUID owner, String[] args) {
        if (!bridge.available()) {
            leader.sendMessage(ChatColor.RED + "VexBot Paper for 1.21.11 must be installed and enabled.");
            return;
        }
        if (args.length == 3 && "list".equalsIgnoreCase(args[2])) {
            int count = 0;
            for (Assignment a : assignments.values()) {
                if (a.owner.equals(owner)) {
                    count++;
                    leader.sendMessage(ChatColor.AQUA + Bukkit.getOfflinePlayer(a.bot).getName()
                            + ChatColor.GRAY + " — " + a.role.name());
                }
            }
            leader.sendMessage(ChatColor.GOLD + "Active crew registrations: " + count);
            return;
        }
        if (args.length == 5 && "bind".equalsIgnoreCase(args[2])) {
            Player candidate = Bukkit.getPlayerExact(args[3]);
            if (!fakePlayer(candidate)) {
                leader.sendMessage(ChatColor.RED
                        + "The guard must be an online fake player from VexBot (no real client connection).");
                return;
            }
            Role role;
            try { role = Role.valueOf(args[4].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) {
                leader.sendMessage(ChatColor.YELLOW + "Choose tank or cannon.");
                return;
            }
            if (assignments.containsKey(candidate.getUniqueId())
                    && !isGuard(owner, candidate.getUniqueId())) {
                leader.sendMessage(ChatColor.RED + "That bot already belongs to another civilization.");
                return;
            }
            bind(owner, candidate, role);
            leader.sendMessage(ChatColor.GREEN + candidate.getName()
                    + " assigned as a " + role.name().toLowerCase(Locale.ROOT) + " crew member.");
            return;
        }
        if (args.length == 4 && "unbind".equalsIgnoreCase(args[2])) {
            Player candidate = Bukkit.getPlayerExact(args[3]);
            if (candidate == null || !isGuard(owner, candidate.getUniqueId())) {
                leader.sendMessage(ChatColor.RED + "This guard is not assigned to your civilization.");
                return;
            }
            assignments.remove(candidate.getUniqueId());
            plugin.getTanks().releaseGuard(candidate);
            save();
            leader.sendMessage(ChatColor.YELLOW + "Guard unassigned.");
            return;
        }
        leader.sendMessage(ChatColor.YELLOW + "Usage: /qa civ guard bind <VexBotName> <tank|cannon>");
        leader.sendMessage(ChatColor.YELLOW + "Also: /qa civ guard list | /qa civ guard unbind <VexBotName>");
    }

    /** Menu-facing crew management, identical permission/ownership checks to the command bridge. */
    public Role roleFor(UUID owner, UUID bot) {
        Assignment current = assignments.get(bot);
        return current != null && current.owner.equals(owner) ? current.role : null;
    }

    public boolean bindFromMenu(Player commander, UUID owner, Player bot, Role role) {
        if (!commander.getUniqueId().equals(owner) || !bridge.available() || !fakePlayer(bot)) {
            commander.sendMessage(ChatColor.RED+"Only your civilization owner may assign a VexBot fake player.");
            return false;
        }
        Assignment prior = assignments.get(bot.getUniqueId());
        if (prior != null && !prior.owner.equals(owner)) {
            commander.sendMessage(ChatColor.RED+"That guard already belongs to another civilization.");
            return false;
        }
        if (prior != null && prior.role == Role.TANK && role != Role.TANK)
            plugin.getTanks().releaseGuard(bot);
        bind(owner,bot,role);
        commander.sendMessage(ChatColor.GREEN+bot.getName()+" assigned to "+role.name().toLowerCase(Locale.ROOT)+".");
        return true;
    }

    public boolean unbindFromMenu(Player commander, UUID owner, Player bot) {
        if (!commander.getUniqueId().equals(owner) || !isGuard(owner,bot.getUniqueId())) {
            commander.sendMessage(ChatColor.RED+"This guard isn't assigned to your civilization.");
            return false;
        }
        assignments.remove(bot.getUniqueId());
        plugin.getTanks().releaseGuard(bot);
        save();
        commander.sendMessage(ChatColor.YELLOW+bot.getName()+" removed from crew duty.");
        return true;
    }

    private void bind(UUID owner, Player bot, Role role) {
        assignments.put(bot.getUniqueId(), new Assignment(bot.getUniqueId(), owner, role, bot.getLocation().clone()));
        save();
    }

    /**
     * When VexBot creates a purchased squad, find the freshly spawned fake
     * players at this fort and bind them automatically to a tank and a cannon.
     * Manual /qa civ guard bind exists for custom VexBot squad configurations.
     */
    public void recruitNew(UUID owner, Location fort, int count, Runnable spawn) {
        if (!bridge.available()) return;
        // All world entity inspection must occur on the fort's region thread.
        FoliaRunnable.runRegionTask(plugin, fort, () -> {
            final Set<UUID> alreadyPresent = new HashSet<>();
            World world = fort.getWorld();
            for (Entity entity : world.getNearbyEntities(fort, 24, 12, 24)) {
                if (entity instanceof Player) alreadyPresent.add(entity.getUniqueId());
            }
            spawn.run();
            FoliaRunnable.runRegionTaskLater(plugin, fort, () -> {
                int recruited = 0;
                for (Entity entity : world.getNearbyEntities(fort, 24, 12, 24)) {
                    if (!(entity instanceof Player)) continue;
                    Player p = (Player) entity;
                    if (!fakePlayer(p) || alreadyPresent.contains(p.getUniqueId())
                            || assignments.containsKey(p.getUniqueId())) continue;
                    Role role = recruited == 0 ? Role.TANK : Role.CANNON;
                    bind(owner, p, role);
                    recruited++;
                    if (recruited >= count) break;
                }
                if (recruited < count)
                    plugin.getLogger().warning("VexBot purchased guards: automatically identified "
                            + recruited + "/" + count + " new bots. Use /qa civ guard bind <name> tank|cannon "
                            + "if your VexBot build does not expose them as Bukkit Player entities.");
            }, 100);
        });
    }

    public void attack(UUID civilization, UUID target) {
        if (target == null) return;
        attackTargets.put(civilization, target);
        retreating.remove(civilization);
    }

    public void retreat(UUID civilization) {
        attackTargets.remove(civilization);
        retreating.add(civilization);
    }

    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null) locations.put(event.getPlayer().getUniqueId(),
                new Track(event.getTo()));
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        locations.put(event.getPlayer().getUniqueId(), new Track(event.getPlayer().getLocation()));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        locations.remove(event.getPlayer().getUniqueId());
    }

    private Location nearestEnemy(UUID owner, Location botLocation) {
        // Guards do not attack unrelated bystanders on sight. Only a declared
        // intruder or the owner-designated enemy activates tank artillery.
        UUID focus = attackTargets.get(owner);
        if (focus == null || territories.isFriendly(owner, focus)) return null;
        Track track = locations.get(focus);
        if (track == null || System.currentTimeMillis() - track.at > 30000) return null;
        Location location = track.location();
        return location != null && location.getWorld().equals(botLocation.getWorld())
                ? location : null;
    }

    private void tick(Player bot, Assignment assignment) {
        if (!fakePlayer(bot) || !territories.hasCivilization(assignment.owner)) return;
        Location here = bot.getLocation();
        Location capital = territories.capitalOf(assignment.owner);
        if (capital == null || !capital.getWorld().equals(here.getWorld())) return;

        boolean withdrawing = retreating.contains(assignment.owner);
        Location target = withdrawing ? null : nearestEnemy(assignment.owner, here);
        if (assignment.role == Role.TANK) {
            if (!withdrawing && target != null) {
                if (!plugin.getTanks().commandGuard(assignment.owner, bot, target)) {
                    Location tank = plugin.getTanks().closestOwnedTank(assignment.owner, here);
                    if (tank != null && System.currentTimeMillis() - assignment.lastOrder > 16000) {
                        assignment.lastOrder = System.currentTimeMillis();
                        bridge.gotoBot(bot, tank);
                    }
                } else if (bot.getVehicle() != null
                        && System.currentTimeMillis() - assignment.lastOrder > 16000) {
                    // Clear VexBot foot navigation once the bot is seated;
                    // otherwise its walking task may fight the tank autopilot.
                    assignment.lastOrder = System.currentTimeMillis();
                    bridge.stopBot(bot);
                }
            } else {
                Location home = withdrawing || assignment.station == null ? capital : assignment.station;
                plugin.getTanks().commandGuard(assignment.owner, bot, home);
                if (bot.getVehicle() == null && here.distanceSquared(home) > 100
                        && System.currentTimeMillis() - assignment.lastOrder > 20000) {
                    assignment.lastOrder = System.currentTimeMillis();
                    bridge.gotoBot(bot, home);
                }
            }
            return;
        }

        Location gun = cannon.operatorPosition(assignment.owner, here);
        if (gun == null) return;
        if (here.distanceSquared(gun) > 30) {
            if (System.currentTimeMillis() - assignment.lastOrder > 18000) {
                assignment.lastOrder = System.currentTimeMillis();
                bridge.gotoBot(bot, gun);
            }
            return;
        }
        if (target != null && !withdrawing) {
            // Fire only when the actual VexBot crew is physically standing
            // at its cannon and the gun's realistic traverse/range permits.
            cannon.fireGuardSalvo(assignment.owner, bot, target);
        }
    }
}
