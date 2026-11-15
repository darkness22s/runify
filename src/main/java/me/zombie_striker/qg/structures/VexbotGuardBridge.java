package me.zombie_striker.qg.structures;

import me.zombie_striker.qg.QAMain;
import me.zombie_striker.qg.utils.FoliaRunnable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Optional integration with VexBot's documented Paper 3.x command interface.
 * VexBot's Fabric API is not a Paper API. No VexBot internals are linked here.
 * Commands run as the server console, NEVER constructed from arbitrary chat input.
 */
public final class VexbotGuardBridge {
    private final QAMain plugin;

    public VexbotGuardBridge(QAMain plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return Bukkit.getPluginCommand("vexbot") != null
                && Bukkit.getPluginManager().isPluginEnabled("VexBot");
    }

    private void send(String command) {
        FoliaRunnable.runTask(plugin, () -> {
            if (available() && !Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command))
                plugin.getLogger().warning("VexBot rejected: " + command);
        });
    }

    private static String team(java.util.UUID owner) {
        return "qa" + owner.toString().replace("-", "").substring(0, 14);
    }

    public void provision(java.util.UUID owner, int amount, Location location) {
        if (!available() || amount < 1 || amount > 8) return;
        String name = team(owner);
        send("vexbot team create " + name);
        Player leader = Bukkit.getPlayer(owner);
        if (leader != null) send("vexbot team " + name + " addplayer " + leader.getName());
        send("vexbot team " + name + " settings retreatwhenlow on");
        send("vexbot team " + name + " massspawn " + amount + " randomgear "
                + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ());
    }

    public void setMember(java.util.UUID owner, Player player, boolean add) {
        if (!available()) return;
        send("vexbot team " + team(owner) + (add ? " addplayer " : " removeplayer ")
                + player.getName());
    }

    /** Give a specific fake player a physical route to a vehicle or gun station. */
    public void gotoBot(Player bot, Location target) {
        if (!available() || bot == null || target == null) return;
        send("vexbot goto " + bot.getName() + " "
                + target.getBlockX() + " " + target.getBlockY() + " "
                + target.getBlockZ() + " then hold");
    }

    public void stopBot(Player bot) {
        if (!available() || bot == null) return;
        send("vexbot goto " + bot.getName() + " stop");
        send("vexbot follow " + bot.getName() + " stop");
        send("vexbot attack stop " + bot.getName());
    }

    public void lookBot(Player bot, Location target) {
        if (!available() || bot == null || target == null) return;
        send("vexbot look " + bot.getName() + " "
                + target.getBlockX() + " " + target.getBlockY() + " "
                + target.getBlockZ());
    }

    public void attack(java.util.UUID owner, Player target) {
        if (!available()) return;
        String name = team(owner);
        // Names come from Bukkit player objects and are safe against command injection.
        send("vexbot team " + name + " goto " + target.getName() + " then attack " + target.getName());
    }

    public void follow(java.util.UUID owner, Player leader) {
        if (!available()) return;
        send("vexbot team " + team(owner) + " follow " + leader.getName());
    }

    public void retreat(java.util.UUID owner, Location at) {
        if (!available()) return;
        String prefix = "vexbot team " + team(owner) + " ";
        send(prefix + "follow stop");
        send(prefix + "goto " + at.getBlockX() + " " + at.getBlockY() + " "
                + at.getBlockZ() + " then hold");
    }
}
