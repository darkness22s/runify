package me.zombie_striker.qg.structures;

import me.zombie_striker.qg.QAMain;
import me.zombie_striker.qg.handlers.GunDamageHandler;
import me.zombie_striker.qg.hooks.protection.ProtectionHandler;
import me.zombie_striker.qg.utils.FoliaRunnable;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Territorial warfare and persistent economy. A civilization owns one circular
 * claim, a capital and scattered, individually damageable forts. Wars start on
 * hostile entry or destructive impact; normal bullets never trigger alerts.
 *
 * Does not require VexBot. When its Paper plugin is present, the documented
 * commands are used to create guard teams and issue horn orders.
 */
public final class TerritoryCivilizations implements Listener {
    private static final int DEFAULT_RADIUS = 512;
    private static final int MIN_RADIUS = 192;
    private static final int MAX_RADIUS = 1536;
    private static final int INITIAL_FORTS = 5;
    private static final int MAX_FORTS = 15;
    private static final int MAX_COINS = 2000000;
    private static final long KILL_COOLDOWN = 90000;
    private static final long ALERT_COOLDOWN = 9000;
    private final QAMain plugin;
    private final TerritoryBuilder builder;
    private final SiegeCannon siegeCannons;
    private final VexbotGuardBridge guards;
    private final VexbotVehicleController vehicleGuards;
    private final CivilizationGui gui;
    private final File dataFile;
    private final Map<UUID,Civ> civilizations = new ConcurrentHashMap<>();
    private final Map<UUID,Integer> wallets = new ConcurrentHashMap<>();
    private final Map<UUID,UUID> currentlyInside = new ConcurrentHashMap<>();
    private final Map<String,War> wars = new ConcurrentHashMap<>();
    private final Map<String,Long> peaceUntil = new ConcurrentHashMap<>();
    private final Map<String,Long> alerts = new ConcurrentHashMap<>();
    private final Map<String,Long> killPayouts = new ConcurrentHashMap<>();
    private final Set<UUID> creating = ConcurrentHashMap.newKeySet();

    static final class Site {
        final String id;
        final String name;
        final int x, z, style;
        volatile int y, hp = 100;
        volatile int lastX, lastY, lastZ;
        volatile boolean built;
        Site(String id, String name, int x, int z, int style) {
            this.id=id; this.name=name; this.x=x; this.z=z; this.style=style;
        }
    }

    static final class Civ {
        final UUID owner;
        final UUID world;
        final int centerX, centerZ;
        volatile int radius;
        volatile int treasury = 150, cannonLevel, weaponLevel, hiredGuards;
        volatile boolean complete;
        volatile UUID target;
        final List<Site> sites = Collections.synchronizedList(new ArrayList<>());
        final Set<UUID> allies = ConcurrentHashMap.newKeySet();
        final Map<String,String> horns = new ConcurrentHashMap<>();
        Civ(UUID owner, UUID world, int centerX, int centerZ, int radius) {
            this.owner=owner; this.world=world; this.centerX=centerX;
            this.centerZ=centerZ; this.radius=radius;
        }
        boolean member(UUID who) { return owner.equals(who) || allies.contains(who); }
        Location centerLocation() {
            World w=Bukkit.getWorld(world);
            if (w == null || sites.isEmpty()) return null;
            Site s=sites.get(0);
            return new Location(w, s.x+.5, s.y+1, s.z+.5);
        }
    }

    private static final class War {
        final UUID attacker, defender;
        final long started = System.currentTimeMillis();
        volatile int attackKills, defenseKills;
        volatile UUID lastKiller;
        volatile long lastKillTime;
        War(UUID attacker, UUID defender) { this.attacker=attacker; this.defender=defender; }
    }

    public TerritoryCivilizations(QAMain plugin, SiegeCannon cannons) {
        this.plugin=plugin;
        this.siegeCannons=cannons;
        this.builder=new TerritoryBuilder(plugin,cannons);
        this.guards=new VexbotGuardBridge(plugin);
        this.dataFile=new File(plugin.getDataFolder(),"territories.yml");
        load();
        this.vehicleGuards = new VexbotVehicleController(plugin,this,cannons,guards);
        this.gui = new CivilizationGui();
        Bukkit.getPluginManager().registerEvents(gui,plugin);
    }

    public void shutdown() { vehicleGuards.shutdown(); save(); }

    public boolean hasCivilization(UUID owner) { return civilizations.containsKey(owner); }

    public Location capitalOf(UUID owner) {
        Civ civ=civilizations.get(owner);
        return civ==null?null:civ.centerLocation();
    }

    public boolean isFriendly(UUID owner,UUID player) {
        Civ civ=civilizations.get(owner);
        return civ!=null&&(civ.member(player)||vehicleGuards!=null&&vehicleGuards.isGuard(owner,player));
    }

    private synchronized void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration data=YamlConfiguration.loadConfiguration(dataFile);
        if (data.isConfigurationSection("wallets"))
            for (String key : data.getConfigurationSection("wallets").getKeys(false)) {
                try { wallets.put(UUID.fromString(key), Math.max(0,data.getInt("wallets."+key))); }
                catch (IllegalArgumentException ignored) { }
            }
        if (!data.isConfigurationSection("civilizations")) return;
        for (String id : data.getConfigurationSection("civilizations").getKeys(false)) {
            String p="civilizations."+id+".";
            try {
                UUID owner=UUID.fromString(id), world=UUID.fromString(data.getString(p+"world"));
                Civ c=new Civ(owner,world,data.getInt(p+"x"),data.getInt(p+"z"),
                        data.getInt(p+"radius",DEFAULT_RADIUS));
                c.treasury=data.getInt(p+"treasury",150);
                c.cannonLevel=data.getInt(p+"cannonLevel");
                c.weaponLevel=data.getInt(p+"weaponLevel");
                c.hiredGuards=data.getInt(p+"guards");
                c.complete=data.getBoolean(p+"complete");
                if (data.isString(p+"target")) c.target=UUID.fromString(data.getString(p+"target"));
                for (String friend : data.getStringList(p+"allies"))
                    c.allies.add(UUID.fromString(friend));
                for (String horn : Arrays.asList("attack","retreat","victory"))
                    if (data.isString(p+"horns."+horn)) c.horns.put(horn,data.getString(p+"horns."+horn));
                if (data.isConfigurationSection(p+"sites")) {
                    for (String sid : data.getConfigurationSection(p+"sites").getKeys(false)) {
                        String sp=p+"sites."+sid+".";
                        Site s=new Site(sid,data.getString(sp+"name",sid),
                                data.getInt(sp+"x"),data.getInt(sp+"z"),data.getInt(sp+"style"));
                        s.y=data.getInt(sp+"y");
                        s.hp=data.getInt(sp+"hp",100);
                        s.lastX=data.getInt(sp+"lastX");
                        s.lastY=data.getInt(sp+"lastY");
                        s.lastZ=data.getInt(sp+"lastZ");
                        s.built=data.getBoolean(sp+"built");
                        c.sites.add(s);
                    }
                }
                if (!c.sites.isEmpty()) civilizations.put(owner,c);
            } catch (IllegalArgumentException | NullPointerException e) {
                plugin.getLogger().warning("Invalid territory for "+id+": "+e.getMessage());
            }
        }
        if(data.isConfigurationSection("wars")) {
            for(String id:data.getConfigurationSection("wars").getKeys(false)) {
                String p="wars."+id+".";
                try {
                    UUID attacker=UUID.fromString(data.getString(p+"attacker"));
                    UUID defender=UUID.fromString(data.getString(p+"defender"));
                    if(!civilizations.containsKey(defender))continue;
                    War war=new War(attacker,defender);
                    war.attackKills=data.getInt(p+"attackKills");
                    war.defenseKills=data.getInt(p+"defenseKills");
                    war.lastKillTime=data.getLong(p+"lastKillTime");
                    if(data.isString(p+"lastKiller"))
                        war.lastKiller=UUID.fromString(data.getString(p+"lastKiller"));
                    wars.put(warKey(attacker,defender),war);
                } catch(IllegalArgumentException | NullPointerException ignored){}
            }
        }
        if(data.isConfigurationSection("truces"))
            for(String key:data.getConfigurationSection("truces").getKeys(false))
                if(data.getLong("truces."+key)>System.currentTimeMillis())
                    peaceUntil.put(key,data.getLong("truces."+key));
    }

    public synchronized void save() {
        YamlConfiguration data=new YamlConfiguration();
        for (Map.Entry<UUID,Integer> e : wallets.entrySet())
            data.set("wallets."+e.getKey(),e.getValue());
        for(War war:wars.values()) {
            String prefix="wars."+war.attacker+"_"+war.defender+".";
            data.set(prefix+"attacker",war.attacker.toString());
            data.set(prefix+"defender",war.defender.toString());
            data.set(prefix+"attackKills",war.attackKills);
            data.set(prefix+"defenseKills",war.defenseKills);
            data.set(prefix+"lastKillTime",war.lastKillTime);
            if(war.lastKiller!=null)data.set(prefix+"lastKiller",war.lastKiller.toString());
        }
        for(Map.Entry<String,Long> truce:peaceUntil.entrySet())
            if(truce.getValue()>System.currentTimeMillis())
                data.set("truces."+truce.getKey(),truce.getValue());
        for (Civ c : civilizations.values()) {
            String p="civilizations."+c.owner+".";
            data.set(p+"world",c.world.toString());
            data.set(p+"x",c.centerX);data.set(p+"z",c.centerZ);
            data.set(p+"radius",c.radius);data.set(p+"treasury",c.treasury);
            data.set(p+"cannonLevel",c.cannonLevel);
            data.set(p+"weaponLevel",c.weaponLevel);
            data.set(p+"guards",c.hiredGuards);
            data.set(p+"complete",c.complete);
            if(c.target!=null)data.set(p+"target",c.target.toString());
            List<String> friends=new ArrayList<>();
            for(UUID uid:c.allies)friends.add(uid.toString());
            data.set(p+"allies",friends);
            for(Map.Entry<String,String> horn:c.horns.entrySet())
                data.set(p+"horns."+horn.getKey(),horn.getValue());
            synchronized(c.sites) {
                for(Site s:c.sites) {
                    String sp=p+"sites."+s.id+".";
                    data.set(sp+"name",s.name);
                    data.set(sp+"x",s.x);data.set(sp+"y",s.y);data.set(sp+"z",s.z);
                    data.set(sp+"style",s.style);data.set(sp+"hp",s.hp);
                    data.set(sp+"built",s.built);
                    data.set(sp+"lastX",s.lastX);data.set(sp+"lastY",s.lastY);
                    data.set(sp+"lastZ",s.lastZ);
                }
            }
        }
        try { data.save(dataFile); }
        catch(IOException ex){plugin.getLogger().warning("Failed saving territory state: "+ex.getMessage());}
    }

    private static int parseInt(String value,int fallback) {
        try{return Integer.parseInt(value);}
        catch(NumberFormatException e){return fallback;}
    }

    public void command(Player player,String[] args) {
        if(args.length==1){gui.open(player);return;}
        String action=args[1].toLowerCase(Locale.ROOT);
        if("create".equals(action)) {
            if(args.length<3 || args.length>4){
                player.sendMessage(ChatColor.YELLOW+"Usage: /qa civ create <online-owner> [radius]");
                return;
            }
            Player owner=Bukkit.getPlayerExact(args[2]);
            if(owner==null){player.sendMessage(ChatColor.RED+"Owner must be online.");return;}
            if(!owner.equals(player)&&!player.hasPermission("qualityarmory.civ.manage")){
                player.sendMessage(ChatColor.RED+"You cannot assign land to another owner.");return;
            }
            int radius=args.length==4?parseInt(args[3],-1):DEFAULT_RADIUS;
            create(player,owner,radius);return;
        }
        if("balance".equals(action)){player.sendMessage(ChatColor.GOLD+"Coins: "+coins(player.getUniqueId()));return;}
        Civ c=memberCiv(player);
        if(c==null){player.sendMessage(ChatColor.RED+"No civilization membership. Use /qa civ.");return;}
        switch(action) {
            case "status": status(player,c);break;
            case "guard":
                if(!c.owner.equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED+"Only the civilization owner can assign vehicle crews.");
                } else vehicleGuards.command(player,c.owner,args);
                break;
            case "wars": warReport(player,c);break;
            case "resume": resume(player,c);break;
            case "locate": locate(player,c);break;
            case "damage": damageReport(player,c);break;
            case "shop": shop(player,c);break;
            case "upgrade":
                if(args.length!=3){player.sendMessage(ChatColor.YELLOW+"Usage: /qa civ upgrade <cannon|weapon|outpost|radius|guards|repair>");return;}
                upgrade(player,c,args[2].toLowerCase(Locale.ROOT));break;
            case "horn":
                if(args.length!=3){player.sendMessage(ChatColor.YELLOW+"Usage: /qa civ horn <attack|retreat|victory> while holding a goat horn");return;}
                assignHorn(player,c,args[2].toLowerCase(Locale.ROOT));break;
            case "target":
                if(args.length!=3){player.sendMessage(ChatColor.YELLOW+"Usage: /qa civ target <rival-civilization-owner>");return;}
                target(player,c,args[2]);break;
            case "ally":
                if(args.length!=3){player.sendMessage(ChatColor.YELLOW+"Usage: /qa civ ally <online-player>");return;}
                ally(player,c,args[2]);break;
            case "raid":
                player.sendMessage(ChatColor.YELLOW+"War now starts when outsiders cross your borders or explosive weapons strike.");break;
            default:
                player.sendMessage(ChatColor.YELLOW+"Commands: /qa civ [create|status|locate|damage|balance|shop|upgrade|horn|target|ally]");
        }
    }

    // The entire player workflow is now click-driven. Old subcommands remain
    // accepted for server scripts, but are not suggested to players.
    public List<String> complete(String[] args) {
        return Collections.emptyList();
    }

    private void create(Player builderPlayer,Player owner,int radius) {
        if(radius<MIN_RADIUS||radius>MAX_RADIUS){
            builderPlayer.sendMessage(ChatColor.RED+"Territory radius must be "+MIN_RADIUS+"-"+MAX_RADIUS+".");
            return;
        }
        if(civilizations.containsKey(owner.getUniqueId())){
            builderPlayer.sendMessage(ChatColor.YELLOW+"This player already owns a civilization.");return;
        }
        if(!creating.add(owner.getUniqueId()))return;
        try {
            Location origin=builderPlayer.getLocation();
            if(!ProtectionHandler.canBreak(origin)){
                builderPlayer.sendMessage(ChatColor.RED+"Protected terrain: cannot create here.");return;
            }
            Civ c=new Civ(owner.getUniqueId(),origin.getWorld().getUID(),
                    origin.getBlockX(),origin.getBlockZ(),radius);
            for(Civ other:civilizations.values()) {
                if(!other.world.equals(c.world))continue;
                double dx=c.centerX-other.centerX,dz=c.centerZ-other.centerZ;
                if(dx*dx+dz*dz<Math.pow(radius+other.radius+40,2)){
                    builderPlayer.sendMessage(ChatColor.RED+"This territory overlaps another civilization.");return;
                }
            }
            WorldBorder border=origin.getWorld().getWorldBorder();
            for(int dx:new int[]{-radius,radius})for(int dz:new int[]{-radius,radius})
                if(!border.isInside(new Location(origin.getWorld(),c.centerX+dx,origin.getY(),c.centerZ+dz))){
                    builderPlayer.sendMessage(ChatColor.RED+"Claim extends outside the world border.");return;
                }
            c.sites.add(new Site("capital","Crown Capital",c.centerX,c.centerZ,0));
            Random random=new Random(owner.getUniqueId().getMostSignificantBits() ^ origin.getBlockX() ^ origin.getBlockZ());
            for(int i=0;i<INITIAL_FORTS;i++){
                Site s=chooseOutpost(c,random,i+1);
                if(s==null){builderPlayer.sendMessage(ChatColor.RED+"Could not position scattered outposts.");return;}
                c.sites.add(s);
            }
            c.complete=false;
            civilizations.put(c.owner,c);
            wallets.putIfAbsent(c.owner,200);
            save();
            builderPlayer.sendMessage(ChatColor.GOLD+"Claiming "+radius+"-block territory for "+owner.getName()
                    +" with "+c.sites.size()+" strongholds spread across the world.");
            builderPlayer.sendMessage(ChatColor.RED+"Warning: outpost construction overwrites terrain! Use a battle world.");
            AtomicInteger pending=new AtomicInteger(c.sites.size());
            for(Site s:c.sites){
                builder.build(c,s,()->{
                    if(pending.decrementAndGet()==0) {
                        c.complete=true;save();
                        FoliaRunnable.runEntityTask(plugin,owner,()->owner.sendMessage(ChatColor.GREEN
                                +"Civilization ready! Use /qa civ to assign battle horns and buy upgrades."));
                    }
                });
            }
        } finally { creating.remove(owner.getUniqueId()); }
    }

    private void resume(Player player,Civ c) {
        if(!c.owner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED+"Only a civilization owner can resume construction.");
            return;
        }
        if(c.complete){player.sendMessage(ChatColor.GREEN+"Your civilization is already built.");return;}
        if(!creating.add(c.owner))return;
        List<Site> unfinished=new ArrayList<>();
        synchronized(c.sites) {
            for(Site site:c.sites)if(!site.built)unfinished.add(site);
        }
        if(unfinished.isEmpty()) {
            c.complete=true;creating.remove(c.owner);save();
            player.sendMessage(ChatColor.GREEN+"All outposts are built.");return;
        }
        AtomicInteger pending=new AtomicInteger(unfinished.size());
        for(Site site:unfinished)builder.build(c,site,()->{
            if(pending.decrementAndGet()==0){
                c.complete=true;
                creating.remove(c.owner);
                save();
                FoliaRunnable.runEntityTask(plugin,player,()->player.sendMessage(
                        ChatColor.GREEN+"Resumed construction complete. Open /qa civ to review your forts."));
            }
        });
        player.sendMessage(ChatColor.YELLOW+"Resuming "+unfinished.size()+" unfinished outposts.");
    }

    private Site chooseOutpost(Civ c,Random rng,int index) {
        for(int attempt=0;attempt<100;attempt++) {
            double angle=2*Math.PI*(index-1)/INITIAL_FORTS+(rng.nextDouble()-.5)*.65;
            double distance=c.radius*(.45+.32*rng.nextDouble());
            int x=c.centerX+(int)(distance*Math.cos(angle));
            int z=c.centerZ+(int)(distance*Math.sin(angle));
            if(Math.hypot(x-c.centerX,z-c.centerZ)+TerritoryBuilder.RADIUS>c.radius-12)continue;
            boolean separated=true;
            for(Site site:c.sites)
                if(Math.hypot(site.x-x,site.z-z)<75){separated=false;break;}
            if(separated)return new Site("outpost"+index,"Sentinel "+index,x,z,index);
        }
        return null;
    }

    private Civ memberCiv(Player player) {
        if (vehicleGuards != null) {
            UUID botOwner = vehicleGuards.civilizationOf(player.getUniqueId());
            if (botOwner != null) return civilizations.get(botOwner);
        }
        Civ own=civilizations.get(player.getUniqueId());
        if(own!=null)return own;
        for(Civ c:civilizations.values())if(c.allies.contains(player.getUniqueId()))return c;
        return null;
    }

    private Civ at(Location location) {
        if(location==null||location.getWorld()==null)return null;
        for(Civ c:civilizations.values()) {
            if(!c.world.equals(location.getWorld().getUID()))continue;
            double dx=location.getX()-c.centerX,dz=location.getZ()-c.centerZ;
            if(dx*dx+dz*dz<=((double)c.radius*c.radius))return c;
        }
        return null;
    }

    public double cannonMultiplierAt(Location l) {
        Civ c=at(l);
        return c==null?1.0:1.0+c.cannonLevel*.25;
    }

    public long cannonReloadAt(Location l) {
        Civ c=at(l);
        return c==null?30000L:Math.max(15000L,30000L-c.cannonLevel*3000L);
    }

    private int coins(UUID uid){return wallets.getOrDefault(uid,0);}
    private synchronized void award(UUID uid,int amount){
        wallets.put(uid,Math.min(MAX_COINS,coins(uid)+amount));save();
    }
    private synchronized boolean pay(Player payer,Civ c,int amount){
        UUID id=payer.getUniqueId();
        int personal=coins(id);
        if((long)personal+c.treasury<amount){
            payer.sendMessage(ChatColor.RED+"Need "+amount+" coins (wallet "+personal
                    +", treasury "+c.treasury+").");return false;
        }
        int fromWallet=Math.min(personal,amount);
        wallets.put(id,personal-fromWallet);
        c.treasury-=amount-fromWallet;
        save();return true;
    }

    private static String describe(int x,int y,int z){return x+", "+y+", "+z;}

    private void status(Player player,Civ c) {
        player.sendMessage(ChatColor.GOLD+"=== Territory of "+Bukkit.getOfflinePlayer(c.owner).getName()
                +" === "+ChatColor.AQUA+"radius "+c.radius+" | coins "+coins(player.getUniqueId()));
        player.sendMessage(ChatColor.GRAY+"Cannon L"+c.cannonLevel+" | Gun L"+c.weaponLevel
                +" | VexBot guards "+c.hiredGuards+" | Treasury "+c.treasury);
        synchronized(c.sites) {
            for(Site s:c.sites)player.sendMessage((s.hp<40?ChatColor.RED:ChatColor.GREEN)
                    +s.name+" "+s.hp+"/100 @ "+s.x+", "+s.z+(s.built?"":" [BUILDING]"));
        }
        player.sendMessage(ChatColor.GRAY+"Attack/Retreat/Victory horns: "+c.horns.keySet());
    }

    private void warReport(Player player,Civ c) {
        player.sendMessage(ChatColor.GOLD+"=== Wars involving "+c.owner+" ===");
        int count=0;
        for(War war:wars.values()) {
            if(!c.owner.equals(war.attacker)&&!c.owner.equals(war.defender))continue;
            count++;
            UUID opponent=c.owner.equals(war.defender)?war.attacker:war.defender;
            player.sendMessage(ChatColor.RED+"vs "+Bukkit.getOfflinePlayer(opponent).getName()
                    +ChatColor.GRAY+" | attack kills "+war.attackKills
                    +" | defense kills "+war.defenseKills);
        }
        if(count==0)player.sendMessage(ChatColor.GRAY+"No active declared wars.");
    }

    private void locate(Player player,Civ c) {
        player.sendMessage(ChatColor.AQUA+"Claim center "+c.centerX+", "+c.centerZ
                +" / radius "+c.radius+" (world "+c.world+").");
        synchronized(c.sites){for(Site s:c.sites)player.sendMessage(ChatColor.GOLD+s.name
                +ChatColor.GRAY+" at "+describe(s.x,s.y+1,s.z));}
    }

    private void damageReport(Player player,Civ c) {
        player.sendMessage(ChatColor.GOLD+"=== District damage report ===");
        synchronized(c.sites) {
            for(Site s:c.sites)if(s.hp<100){
                player.sendMessage(ChatColor.RED+s.name+" "+s.hp+"/100 - last damaged at "
                        +describe(s.lastX,s.lastY,s.lastZ));
            }
        }
    }

    private void shop(Player p,Civ c) {
        p.sendMessage(ChatColor.GOLD+"=== Crownlands upgrades (balance "+coins(p.getUniqueId())+") ===");
        p.sendMessage(ChatColor.YELLOW+"cannon 300 coins: "+ChatColor.GRAY+"+25% shell power, -3s reload (max 5)");
        p.sendMessage(ChatColor.YELLOW+"weapon 250 coins: "+ChatColor.GRAY+"+10% QA gun damage (max 5)");
        p.sendMessage(ChatColor.YELLOW+"outpost 500+ coins: "+ChatColor.GRAY+"construct a distant new stronghold (max 15)");
        p.sendMessage(ChatColor.YELLOW+"radius 450 coins: "+ChatColor.GRAY+"expand borders by 128 blocks");
        p.sendMessage(ChatColor.YELLOW+"guards 250 coins: "+ChatColor.GRAY+"hire two VexBot guards (Paper 1.21.11+)");
        p.sendMessage(ChatColor.YELLOW+"repair 120 coins: "+ChatColor.GRAY+"restore a damaged outpost core and walls");
        p.sendMessage(ChatColor.AQUA+"Purchase: /qa civ upgrade <type>");
    }

    private void upgrade(Player p,Civ c,String type) {
        if(!c.member(p.getUniqueId()))return;
        if(!c.complete){p.sendMessage(ChatColor.RED+"Wait until outposts have finished building.");return;}
        switch(type) {
            case "cannon":
                if(c.cannonLevel>=5){p.sendMessage(ChatColor.YELLOW+"Max cannon level.");return;}
                if(pay(p,c,300)){c.cannonLevel++;save();p.sendMessage(ChatColor.GREEN+"Cannon level "+c.cannonLevel);}
                break;
            case "weapon":
                if(c.weaponLevel>=5){p.sendMessage(ChatColor.YELLOW+"Max weapon level.");return;}
                if(pay(p,c,250)){c.weaponLevel++;save();p.sendMessage(ChatColor.GREEN+"Weapon level "+c.weaponLevel);}
                break;
            case "radius":
                if(c.radius+128>MAX_RADIUS){p.sendMessage(ChatColor.YELLOW+"Territory at maximum radius.");return;}
                for(Civ other:civilizations.values()) {
                    if(other==c||!other.world.equals(c.world))continue;
                    if(Math.hypot(c.centerX-other.centerX,c.centerZ-other.centerZ)<c.radius+128+other.radius+40){
                        p.sendMessage(ChatColor.RED+"Expansion would overlap another civilization.");return;
                    }
                }
                WorldBorder wb=Bukkit.getWorld(c.world).getWorldBorder();
                for(int dx:new int[]{-c.radius-128,c.radius+128})
                    for(int dz:new int[]{-c.radius-128,c.radius+128})
                        if(!wb.isInside(new Location(Bukkit.getWorld(c.world),
                                c.centerX+dx,80,c.centerZ+dz))){
                            p.sendMessage(ChatColor.RED+"Expansion exceeds world border.");return;
                        }
                if(pay(p,c,450)){c.radius+=128;save();p.sendMessage(ChatColor.GREEN+"Territory radius "+c.radius);}
                break;
            case "outpost":
                if(c.sites.size()>=MAX_FORTS+1){p.sendMessage(ChatColor.YELLOW+"Max outposts reached.");return;}
                Random r=new Random(System.nanoTime());
                Site s=chooseExtraOutpost(c,r);
                if(s==null){p.sendMessage(ChatColor.RED+"No suitable separated space inside borders.");return;}
                int price=500+Math.max(0,c.sites.size()-INITIAL_FORTS-1)*150;
                if(pay(p,c,price)) {
                    c.sites.add(s);save();
                    p.sendMessage(ChatColor.GOLD+"Scouting "+s.name+" at "+s.x+", "+s.z+"...");
                    builder.build(c,s,()->{save();FoliaRunnable.runEntityTask(plugin,p,()->p.sendMessage(ChatColor.GREEN+"Outpost completed: "+s.name));});
                }
                break;
            case "guards":
                if(c.hiredGuards>=24){p.sendMessage(ChatColor.YELLOW+"Maximum guards reached.");return;}
                if(!guards.available()){p.sendMessage(ChatColor.RED+"Install compatible VexBot Paper first (1.21.11+).");return;}
                Site station=c.sites.get((c.hiredGuards/2)%c.sites.size());
                World w=Bukkit.getWorld(c.world);
                if(w==null){p.sendMessage(ChatColor.RED+"Civilization world unavailable.");return;}
                Location loc=new Location(w,station.x+2,station.y+1,station.z-2);
                if(pay(p,c,250)){
                    c.hiredGuards+=2;save();
                    Location tankPark=new Location(w,station.x+7.5,station.y+1,station.z+6.5);
                    plugin.getTanks().spawnForCivilization(c.owner,tankPark);
                    vehicleGuards.recruitNew(c.owner,loc,2,()->guards.provision(c.owner,2,loc));
                    p.sendMessage(ChatColor.GREEN+"Stationed 2 VexBot guards (tank pilot and cannon gunner) at "+station.name+".");
                }
                break;
            case "repair":
                Site damaged=nearestDamaged(c,p.getLocation());
                if(damaged==null){p.sendMessage(ChatColor.YELLOW+"No damaged outpost within 48 blocks.");return;}
                if(pay(p,c,120)){
                    damaged.hp=100;save();
                    p.sendMessage(ChatColor.GREEN+"Restoring "+damaged.name+" walls and its objective.");
                    builder.build(c,damaged,()->{builder.showStatus(c,damaged);save();});
                }
                break;
            default:p.sendMessage(ChatColor.YELLOW+"Choose cannon, weapon, outpost, radius, guards, repair.");
        }
    }

    private Site chooseExtraOutpost(Civ c,Random random) {
        for(int i=0;i<120;i++){
            double angle=random.nextDouble()*2*Math.PI;
            double dist=c.radius*(.32+.51*random.nextDouble());
            int x=c.centerX+(int)(Math.cos(angle)*dist);
            int z=c.centerZ+(int)(Math.sin(angle)*dist);
            boolean ok=true;
            for(Site t:c.sites)if(Math.hypot(t.x-x,t.z-z)<80){ok=false;break;}
            if(ok)return new Site("outpost"+c.sites.size(),"Border Fort "+c.sites.size(),
                    x,z,c.sites.size());
        }
        return null;
    }

    private Site nearestDamaged(Civ c,Location location) {
        if(!c.world.equals(location.getWorld().getUID()))return null;
        Site nearest=null;double best=48*48;
        synchronized(c.sites) {
            for(Site s:c.sites) {
                double d=Math.pow(location.getX()-s.x,2)+Math.pow(location.getZ()-s.z,2);
                if(s.hp<100&&d<best){best=d;nearest=s;}
            }
        }
        return nearest;
    }

    private static String hornKey(ItemStack item) {
        if(item==null||item.getType()!=Material.GOAT_HORN)return null;
        ItemMeta meta=item.getItemMeta();
        if(!(meta instanceof MusicInstrumentMeta))return null;
        MusicInstrumentMeta horn=(MusicInstrumentMeta)meta;
        return horn.getInstrument()==null?null:horn.getInstrument().getKey().toString();
    }

    private void assignHorn(Player player,Civ c,String action) {
        if(!c.owner.equals(player.getUniqueId())){player.sendMessage(ChatColor.RED+"Only owner assigns horns.");return;}
        if(!Arrays.asList("attack","retreat","victory").contains(action)){
            player.sendMessage(ChatColor.YELLOW+"Choose attack, retreat or victory.");return;
        }
        String key=hornKey(player.getInventory().getItemInMainHand());
        if(key==null){player.sendMessage(ChatColor.RED+"Hold a vanilla goat horn with an instrument.");return;}
        for(Map.Entry<String,String> other:c.horns.entrySet())
            if(!other.getKey().equals(action)&&key.equals(other.getValue())){
                player.sendMessage(ChatColor.RED+"That horn is already assigned to "+other.getKey());return;
            }
        c.horns.put(action,key);save();
        player.sendMessage(ChatColor.GREEN+"Assigned "+key+" to "+action.toUpperCase(Locale.ROOT));
    }

    private void target(Player player,Civ c,String name) {
        if(!c.owner.equals(player.getUniqueId()))return;
        Player opponent=Bukkit.getPlayerExact(name);
        if(opponent==null||!civilizations.containsKey(opponent.getUniqueId())
                ||opponent.getUniqueId().equals(c.owner)){
            player.sendMessage(ChatColor.RED+"Target an online rival civilization owner.");return;
        }
        c.target=opponent.getUniqueId();save();
        player.sendMessage(ChatColor.YELLOW+"Target set: "+opponent.getName());
    }

    private void ally(Player player,Civ c,String name) {
        if(!c.owner.equals(player.getUniqueId()))return;
        Player friend=Bukkit.getPlayerExact(name);
        if(friend==null||friend.equals(player)||civilizations.containsKey(friend.getUniqueId())
                ||(memberCiv(friend)!=null&&!c.allies.contains(friend.getUniqueId()))){
            player.sendMessage(ChatColor.RED+"Choose another online player without a civilization.");return;
        }
        if(c.allies.remove(friend.getUniqueId())){
            player.sendMessage(ChatColor.YELLOW+"Removed ally "+friend.getName());
        } else {
            c.allies.add(friend.getUniqueId());
            player.sendMessage(ChatColor.GREEN+"Added ally "+friend.getName());
        }
        save();
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onMove(PlayerMoveEvent event) {
        if(event.getTo()==null || (event.getFrom().getWorld().equals(event.getTo().getWorld())
                && event.getFrom().getBlockX()==event.getTo().getBlockX()
                && event.getFrom().getBlockZ()==event.getTo().getBlockZ()))return;
        Player p=event.getPlayer();
        // A just-spawned VexBot must not declare war against its own host before
        // the bot has been assigned to its purchased civilization squad.
        if (p.getAddress() == null && vehicleGuards != null
                && !vehicleGuards.isAnyGuard(p.getUniqueId())) return;
        Civ territory=at(event.getTo());
        UUID last=currentlyInside.get(p.getUniqueId());
        UUID now=territory==null?null:territory.owner;
        if(now==null)currentlyInside.remove(p.getUniqueId());
        else currentlyInside.put(p.getUniqueId(),now);
        if(territory!=null&&!now.equals(last)&&!isFriendly(now,p.getUniqueId()))
            declareWar(p,territory,"BORDER BREACH");
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        currentlyInside.remove(event.getPlayer().getUniqueId());
    }

    private static String warKey(UUID attacker,UUID defender){return attacker+"!"+defender;}

    private boolean warExists(UUID a,UUID b) {
        return wars.containsKey(warKey(a,b))||wars.containsKey(warKey(b,a));
    }

    private void declareWar(Player invader,Civ territory,String reason) {
        if(isFriendly(territory.owner,invader.getUniqueId()))return;
        Civ own=memberCiv(invader);
        UUID attacker=own==null?invader.getUniqueId():own.owner;
        if(attacker.equals(territory.owner))return;
        String key=warKey(attacker,territory.owner);
        Long peace=peaceUntil.get(key);
        if(peace!=null&&System.currentTimeMillis()<peace)return;
        War existing=wars.putIfAbsent(key,new War(attacker,territory.owner));
        if(existing==null)save();
        if(territory.hiredGuards>0&&guards.available()) {
            // Vehicle crews receive individual combat targets. An overarching
            // VexBot team goto order would drag pilots out of their tanks.
            vehicleGuards.attack(territory.owner,invader.getUniqueId());
        }
        showWarAlert(territory,invader.getName(),reason,key);
        if(own!=null)showWarAlert(own,Bukkit.getOfflinePlayer(territory.owner).getName(),
                "WAR DECLARED",key+"-attacker");
    }

    private void showWarAlert(Civ civ,String intruder,String reason,String key) {
        long now=System.currentTimeMillis();
        // One title per civilization per cooldown, regardless of overlapping
        // RPG hit / explosion events or multiple approaching entities.
        Long previous=alerts.put(civ.owner.toString(),now);
        if(previous!=null&&now-previous<ALERT_COOLDOWN)return;
        for(Player p:Bukkit.getOnlinePlayers()) {
            if(!isFriendly(civ.owner,p.getUniqueId()))continue;
            FoliaRunnable.runEntityTask(plugin,p,()->{
                p.sendTitle(ChatColor.DARK_RED+"WAR DECLARED!",
                        ChatColor.GOLD+reason+" - "+intruder,10,75,15);
                p.playSound(p.getLocation(),Sound.EVENT_RAID_HORN,1.3f,0.75f);
                p.sendMessage(ChatColor.RED+"[CROWNLANDS] "+reason+" by "+intruder
                        +". Use /qa civ damage to locate destroyed defenses.");
            });
        }
    }

    /** Called only by the explosive weapon pipeline, NOT ordinary bullet hits. */
    public void destructiveImpact(Player shooter,Location impact) {
        Civ territory=at(impact);
        if(territory==null)return;
        if(shooter!=null&&!territory.member(shooter.getUniqueId()))
            declareWar(shooter,territory,"EXPLOSIVE STRIKE");
        else if(shooter==null)
            showWarAlert(territory,"unknown artillery","EXPLOSIVE STRIKE",
                    "environment-"+territory.owner);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onWeaponHit(EntityDamageByEntityEvent event) {
        if(!GunDamageHandler.isWeaponDamage())return;
        Player shooter=null;
        if(event.getDamager() instanceof Player)shooter=(Player)event.getDamager();
        else if(event.getDamager() instanceof Projectile) {
            ProjectileSource source=((Projectile)event.getDamager()).getShooter();
            if(source instanceof Player)shooter=(Player)source;
        }
        if(shooter==null)return;
        Civ c=memberCiv(shooter);
        if(c!=null&&c.weaponLevel>0)
            event.setDamage(event.getDamage()*(1+.10*c.weaponLevel));
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onKill(PlayerDeathEvent event) {
        Player victim=event.getEntity();
        Player killer=victim.getKiller();
        if(killer==null||killer.equals(victim))return;
        Civ defending=at(victim.getLocation());
        Civ attacking=memberCiv(killer);
        Civ victimTeam=memberCiv(victim);
        if(victimTeam!=null&&attacking!=null&&victimTeam.owner.equals(attacking.owner))return;
        if(defending==null && attacking==null && victimTeam==null)return;
        if(defending!=null&&defending.member(killer.getUniqueId())
                &&defending.member(victim.getUniqueId()))return;
        boolean oppositeSides=defending!=null
                &&(defending.member(killer.getUniqueId())!=defending.member(victim.getUniqueId()));
        if(attacking!=null&&victimTeam!=null
                &&!attacking.owner.equals(victimTeam.owner))
            oppositeSides=oppositeSides||warExists(attacking.owner,victimTeam.owner)
                    ||at(killer.getLocation())!=null||at(victim.getLocation())!=null;
        if(!oppositeSides)return;
        String payKey=killer.getUniqueId()+"!"+victim.getUniqueId();
        long now=System.currentTimeMillis();
        Long old=killPayouts.put(payKey,now);
        if(old!=null&&now-old<KILL_COOLDOWN)return;
        UUID botCiv = vehicleGuards == null ? null : vehicleGuards.civilizationOf(killer.getUniqueId());
        UUID beneficiary = botCiv == null ? killer.getUniqueId() : botCiv;
        award(beneficiary,100);
        Civ recipient=attacking!=null?attacking
                :defending!=null&&defending.member(killer.getUniqueId())?defending:null;
        if(recipient!=null){recipient.treasury+=25;save();}
        Player payee=Bukkit.getPlayer(beneficiary);
        if(payee!=null) FoliaRunnable.runEntityTask(plugin,payee,()->
                payee.sendMessage(ChatColor.GOLD+"+100 coins for an enemy elimination"
                        +ChatColor.GRAY+" (use /qa civ shop)"));
        UUID killerSide=attacking==null?killer.getUniqueId():attacking.owner;
        UUID victimSide=victimTeam==null?victim.getUniqueId():victimTeam.owner;
        for(War w:wars.values()) {
            if(killerSide.equals(w.attacker)&&victimSide.equals(w.defender)) {
                w.attackKills++;w.lastKiller=killer.getUniqueId();w.lastKillTime=now;
            } else if(killerSide.equals(w.defender)&&victimSide.equals(w.attacker)) {
                w.defenseKills++;w.lastKiller=killer.getUniqueId();w.lastKillTime=now;
            }
        }
        save();
        currentlyInside.remove(victim.getUniqueId());
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onHorn(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND
                ||(event.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                &&event.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK))return;
        String instrument=hornKey(event.getItem());
        if(instrument==null)return;
        Player player=event.getPlayer();
        Civ c=memberCiv(player);
        if(c==null)return;
        for(Map.Entry<String,String> mapping:c.horns.entrySet())
            if(instrument.equals(mapping.getValue())){
                commandHorn(player,c,mapping.getKey());
                return;
            }
    }

    private boolean hasEnemyInside(Civ c) {
        for(Map.Entry<UUID,UUID> entry:currentlyInside.entrySet()) {
            if(c.owner.equals(entry.getValue())&&!c.member(entry.getKey())
                    &&Bukkit.getPlayer(entry.getKey())!=null)return true;
        }
        return false;
    }

    private void commandHorn(Player leader,Civ c,String order) {
        if(!c.member(leader.getUniqueId()))return;
        if("attack".equals(order)) {
            if(c.target==null){leader.sendMessage(ChatColor.RED
                    +"Choose an enemy owner with /qa civ target <player> first.");return;}
            Civ enemy=civilizations.get(c.target);
            if(enemy==null){leader.sendMessage(ChatColor.RED+"Target missing.");return;}
            Player target=Bukkit.getPlayer(enemy.owner);
            if(target==null){leader.sendMessage(ChatColor.RED+"Enemy commander must be online.");return;}
            declareWar(leader,enemy,"ATTACK HORN");
            vehicleGuards.attack(c.owner,target.getUniqueId());
            leader.sendMessage(ChatColor.RED+"ATTACK order issued to "+c.hiredGuards+" VexBot guards.");
            return;
        }
        if("retreat".equals(order)) {
            for(War war:new ArrayList<>(wars.values())) {
                if(c.owner.equals(war.attacker)||c.owner.equals(war.defender)) {
                    String key=warKey(war.attacker,war.defender);
                    if(!wars.remove(key,war))continue;
                    peaceUntil.put(key,System.currentTimeMillis()+240000);
                    save();
                    UUID winner=c.owner.equals(war.attacker)?war.defender:war.attacker;
                    Civ rival=civilizations.get(winner);
                    if(rival!=null) {
                        award(rival.owner,200);
                        rival.treasury+=50;save();
                        showWarAlert(rival,leader.getName(),"ENEMY RETREAT (+200 COINS)","retreat-"+key);
                    }
                }
            }
            Location base=c.centerLocation();
            vehicleGuards.retreat(c.owner);
            leader.sendMessage(ChatColor.YELLOW+"RETREAT! Your guards are returning to the capital.");
            return;
        }
        if("victory".equals(order)) {
            for(War war:new ArrayList<>(wars.values())) {
                boolean participant=c.owner.equals(war.attacker)||c.owner.equals(war.defender);
                if(!participant||war.lastKillTime==0||System.currentTimeMillis()-war.lastKillTime>600000)
                    continue;
                boolean earned=c.owner.equals(war.attacker)?war.attackKills>0:war.defenseKills>0;
                if(!earned){leader.sendMessage(ChatColor.RED+"Your side must win an elimination first.");continue;}
                if(c.owner.equals(war.defender) && hasEnemyInside(c)) {
                    leader.sendMessage(ChatColor.RED+"Clear invading players from your territory before declaring victory.");
                    continue;
                }
                String key=warKey(war.attacker,war.defender);
                if(wars.remove(key,war)){
                    peaceUntil.put(key,System.currentTimeMillis()+240000);
                    award(leader.getUniqueId(),350);
                    c.treasury+=100;save();
                    leader.sendTitle(ChatColor.GOLD+"VICTORY!",ChatColor.GREEN+"+350 coins",10,65,15);
                    vehicleGuards.retreat(c.owner);
                    return;
                }
            }
            leader.sendMessage(ChatColor.RED+"No qualifying war victory. Defeat an enemy first.");
        }
    }

    private Site underAttack(Civ c,Block block) {
        if(!c.world.equals(block.getWorld().getUID()))return null;
        synchronized(c.sites) {
            for(Site s:c.sites)
                if(Math.abs(block.getX()-s.x)<=TerritoryBuilder.RADIUS
                    &&Math.abs(block.getZ()-s.z)<=TerritoryBuilder.RADIUS
                    &&block.getY()>=s.y&&block.getY()<=s.y+TerritoryBuilder.HEIGHT)
                    return s;
        }
        return null;
    }

    private void damage(Civ c,Site s,Location location,int amount) {
        synchronized(s) {
            s.hp=Math.max(0,s.hp-amount);
            s.lastX=location.getBlockX();s.lastY=location.getBlockY();s.lastZ=location.getBlockZ();
        }
        save();
        builder.showStatus(c,s);
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event) {
        for(Civ c:civilizations.values()) {
            Site s=underAttack(c,event.getBlock());
            if(s==null||c.member(event.getPlayer().getUniqueId()))continue;
            int amount=event.getBlock().getX()==s.x&&event.getBlock().getY()==s.y+2
                    &&event.getBlock().getZ()==s.z?100:2;
            damage(c,s,event.getBlock().getLocation(),amount);
            declareWar(event.getPlayer(),c,"FORTIFICATION ATTACK");
        }
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        Player source=null;
        if(event.getEntity() instanceof Projectile) {
            ProjectileSource shooter=((Projectile)event.getEntity()).getShooter();
            if(shooter instanceof Player)source=(Player)shooter;
        } else if(event.getEntity() instanceof TNTPrimed
                &&((TNTPrimed)event.getEntity()).getSource() instanceof Player)
            source=(Player)((TNTPrimed)event.getEntity()).getSource();
        registerExplosion(event.getLocation(),event.blockList(),source);
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        registerExplosion(event.getBlock().getLocation(),event.blockList(),null);
    }

    private void registerExplosion(Location impact,List<Block> blocks,Player source) {
        // For source-less vanilla explosions, still log structural damage. RPGs
        // report their actual shooter via the explosive weapon pipeline afterward.
        if(source!=null)destructiveImpact(source,impact);
        for(Civ c:civilizations.values()) {
            Map<Site,Integer> count=new HashMap<>();
            for(Block block:blocks) {
                Site site=underAttack(c,block);
                if(site!=null) {
                    int value=block.getX()==site.x&&block.getY()==site.y+2&&block.getZ()==site.z?100:1;
                    count.put(site,Math.min(100,count.getOrDefault(site,0)+value));
                }
            }
            for(Map.Entry<Site,Integer> e:count.entrySet())damage(c,e.getKey(),impact,
                    Math.min(75,e.getValue()));
        }
    }

    /**
     * The single-entry-point civilization UI. Custom InventoryHolder identity
     * and viewer UUID prevent ordinary renamed chests or other plugins from
     * triggering economy actions. Every click is re-authorized against the
     * current civilization state rather than trusting stale menu metadata.
     */
    private final class CivilizationGui implements Listener {
        private static final int SIZE = 54;
        private final int[] radii = {192,256,384,512,768,1024,1536};

        private enum Screen {
            HOME, CREATE, OVERVIEW, SHOP, HORNS, WAR, FORTS, DAMAGE, ALLIES, RIVALS, GUARDS, TOOLS
        }

        private final class View implements InventoryHolder {
            final Screen screen;
            final UUID viewer, chosenOwner;
            final int radius, page;
            final Map<Integer,UUID> listedPlayers = new HashMap<>();
            final Map<Integer,String> listedForts = new HashMap<>();
            private Inventory contents;

            View(Screen screen, Player viewer, UUID chosenOwner, int radius, int page) {
                this.screen = screen;
                this.viewer = viewer.getUniqueId();
                this.chosenOwner = chosenOwner;
                this.radius = radius;
                this.page = Math.max(0,page);
            }

            @Override public Inventory getInventory() { return contents; }
        }

        void open(Player player) {
            show(player,Screen.HOME,player.getUniqueId(),DEFAULT_RADIUS,0);
        }

        private ItemStack icon(Material material,String name,String... details) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            for(String detail:details)lore.add(ChatColor.GRAY+detail);
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }

        private void slot(View view,int index,Material material,String name,String... description) {
            view.contents.setItem(index,icon(material,name,description));
        }

        private void base(View view,String heading) {
            view.contents=Bukkit.createInventory(view,SIZE,ChatColor.DARK_GREEN+"Crownlands "+heading);
            ItemStack border=icon(Material.GRAY_STAINED_GLASS_PANE," ");
            for(int i=0;i<SIZE;i++)view.contents.setItem(i,border);
            if(view.screen!=Screen.HOME) slot(view,49,Material.ARROW,ChatColor.YELLOW+"Back","Return to the civilization menu");
            else slot(view,49,Material.BARRIER,ChatColor.RED+"Close","Leave the menu");
        }

        private void show(Player player,Screen screen,UUID chosenOwner,int radius,int page) {
            if(!player.isOnline())return;
            Civ c=memberCiv(player);
            if(c==null && screen!=Screen.CREATE && screen!=Screen.HOME) screen=Screen.HOME;
            View v=new View(screen,player,chosenOwner,radius,page);
            base(v,screen==Screen.HOME?"":screen.name().toLowerCase(Locale.ROOT));
            switch(screen) {
                case HOME: home(v,player,c);break;
                case CREATE: creation(v,player);break;
                case OVERVIEW: overview(v,player,c);break;
                case SHOP: upgrades(v,player,c);break;
                case HORNS: horns(v,player,c);break;
                case WAR: war(v,player,c);break;
                case FORTS: forts(v,c,false);break;
                case DAMAGE: forts(v,c,true);break;
                case ALLIES: players(v,player,c,false);break;
                case RIVALS: players(v,player,c,true);break;
                case GUARDS: guardRoster(v,player,c);break;
                case TOOLS: tools(v,player);break;
            }
            player.openInventory(v.contents);
        }

        private void later(Player player,Screen screen,UUID owner,int radius,int page) {
            FoliaRunnable.runEntityTaskLater(plugin,player,()->
                    show(player,screen,owner,radius,page),1);
        }

        private boolean owner(Player player,Civ civ) {
            if(civ!=null && civ.owner.equals(player.getUniqueId()))return true;
            player.sendMessage(ChatColor.RED+"Only the civilization owner can do that.");
            return false;
        }

        private void home(View v,Player player,Civ c) {
            if(c==null) {
                slot(v,22,Material.BEACON,ChatColor.GREEN+"Create a Civilization",
                        "Choose an owner and claim radius","Then build widely scattered outposts");
                slot(v,13,Material.MAP,ChatColor.GOLD+"Welcome to Crownlands",
                        "Everything is managed from this one menu",
                        "Battle orders are played with assigned goat horns");
                return;
            }
            slot(v,4,Material.EMERALD,ChatColor.GREEN+"Your Coins: "+coins(player.getUniqueId()),
                    "Civilization treasury: "+c.treasury,
                    "Owner: "+Bukkit.getOfflinePlayer(c.owner).getName(),
                    "Radius: "+c.radius+" blocks",
                    "Outposts: "+c.sites.size());
            slot(v,10,Material.MAP,ChatColor.AQUA+"Civilization Overview",
                    "Check owner, size and defensive structures");
            slot(v,12,Material.EMERALD_BLOCK,ChatColor.GREEN+"Upgrades & Shop",
                    "Cannons, guns, new outposts, guards and repairs");
            slot(v,14,Material.GOAT_HORN,ChatColor.GOLD+"Assign Battle Horns",
                    "Choose attack, retreat and victory horns");
            slot(v,16,Material.IRON_SWORD,ChatColor.RED+"Wars & Enemies",
                    "Active wars, target selection, combat orders");
            slot(v,28,Material.COMPASS,ChatColor.YELLOW+"Find Outposts",
                    "Locate all your forts with a click");
            slot(v,30,Material.CRACKED_STONE_BRICKS,ChatColor.RED+"Damage Report",
                    "See damaged defenses and impact locations");
            slot(v,32,Material.PLAYER_HEAD,ChatColor.AQUA+"Allies & Team",
                    "Invite or remove online teammates");
            slot(v,34,Material.IRON_GOLEM_SPAWN_EGG,ChatColor.GREEN+"VexBot Guards",
                    "Manage tank drivers and cannon gunners");
            slot(v,40,Material.TNT,ChatColor.GOLD+"Tanks & Cannons",
                    "Deploy a playable 3D tank or cannon");
            if(!c.complete)slot(v,38,Material.CLOCK,ChatColor.YELLOW+"Resume Construction",
                    "Retry building any unfinished outposts");
            if(player.hasPermission("qualityarmory.civ.manage"))
                slot(v,46,Material.BEACON,ChatColor.AQUA+"Create Another Civilization",
                        "Choose an online owner and territory radius");
        }

        private void overview(View v,Player viewer,Civ c) {
            if(c==null)return;
            slot(v,4,Material.BEACON,ChatColor.GOLD+"Territory Overview",
                    "Owner: "+Bukkit.getOfflinePlayer(c.owner).getName(),
                    "Radius: "+c.radius+" blocks",
                    "Claim center: "+c.centerX+", "+c.centerZ,
                    c.complete?"Construction complete":"Construction in progress");
            slot(v,11,Material.EMERALD,ChatColor.GREEN+"Economy",
                    "Wallet: "+coins(viewer.getUniqueId())+" coins",
                    "Treasury: "+c.treasury+" coins");
            slot(v,13,Material.CROSSBOW,ChatColor.GOLD+"Military Upgrades",
                    "Cannons: level "+c.cannonLevel+"/5",
                    "Weapons: level "+c.weaponLevel+"/5",
                    "VexBot guards: "+c.hiredGuards);
            slot(v,15,Material.STONE_BRICKS,ChatColor.AQUA+"Outposts: "+c.sites.size(),
                    "Click to see every fort and coordinate");
            int damage=0;
            for(Site site:c.sites)if(site.hp<100)damage++;
            slot(v,30,Material.REDSTONE,damage==0?ChatColor.GREEN+"No Damaged Forts":
                    ChatColor.RED+""+damage+" Damaged Forts",
                    "Click to inspect the damage report");
            slot(v,32,Material.EMERALD_BLOCK,ChatColor.GREEN+"Open the Shop",
                    "Invest your battle coins in upgrades");
        }

        private void creation(View v,Player viewer) {
            Player selected=Bukkit.getPlayer(v.chosenOwner);
            String ownerName=selected==null?"Offline - choose another player":selected.getName();
            slot(v,13,Material.MAP,ChatColor.GOLD+"New Civilization",
                    "Construction creates a capital and five distant outposts",
                    "WARNING: Structures will overwrite terrain!",
                    "Use a dedicated battle world");
            slot(v,20,Material.PLAYER_HEAD,ChatColor.AQUA+"Owner: "+ownerName,
                    viewer.hasPermission("qualityarmory.civ.manage")?
                            "Click to cycle online players":"This civilization will belong to you");
            slot(v,24,Material.COMPASS,ChatColor.YELLOW+"Radius: "+v.radius+" blocks",
                    "Click to choose the next territory radius",
                    "Valid range: 192 to 1536 blocks");
            slot(v,31,Material.LIME_CONCRETE,ChatColor.GREEN+"Create at Your Position",
                    "Owner: "+ownerName,"Radius: "+v.radius,
                    "Click to confirm terrain construction");
        }

        private void upgrades(View v,Player player,Civ c) {
            if(c==null)return;
            slot(v,4,Material.EMERALD,ChatColor.GOLD+"Coins: "+coins(player.getUniqueId()),
                    "Treasury: "+c.treasury,"Purchases use wallet first, then treasury");
            slot(v,10,Material.BLAST_FURNACE,ChatColor.GREEN+"Upgrade Cannons - 300",
                    "Level: "+c.cannonLevel+"/5",
                    "+25% damage and -3 seconds reload per level",
                    "Click to buy");
            slot(v,12,Material.IRON_SWORD,ChatColor.GREEN+"Upgrade Weapons - 250",
                    "Level: "+c.weaponLevel+"/5",
                    "+10% gun damage per level",
                    "Click to buy");
            slot(v,14,Material.STONE_BRICKS,ChatColor.GREEN+"Build New Outpost - 500+",
                    "Owned sites: "+c.sites.size(),"Place a distant fortress with a cannon",
                    "Click to purchase");
            slot(v,16,Material.MAP,ChatColor.GREEN+"Expand Territory - 450",
                    "Radius: "+c.radius+" blocks","+128 blocks per upgrade",
                    "Click to purchase");
            slot(v,29,Material.IRON_GOLEM_SPAWN_EGG,ChatColor.GREEN+"Hire VexBot Guards - 250",
                    "Guards: "+c.hiredGuards+"/24",
                    "Hire two guards plus a guarded tank",
                    "Click to purchase");
            slot(v,33,Material.ANVIL,ChatColor.GREEN+"Repair Nearby Fort - 120",
                    "Stand within 48 blocks of damaged fort",
                    "Rebuild damaged walls and core",
                    "Click to repair");
        }

        private void horns(View v,Player player,Civ c) {
            if(c==null)return;
            slot(v,4,Material.PAPER,ChatColor.YELLOW+"Hold a Goat Horn in Your Main Hand",
                    "Click one of the three orders to assign it",
                    "Each order must use a distinct horn sound",
                    "Playing that horn later issues the battle command");
            for(int i=0;i<3;i++) {
                String key=i==0?"attack":i==1?"retreat":"victory";
                String label=i==0?"Attack":i==1?"Retreat":"Victory";
                int slot=i==0?20:i==1?22:24;
                slot(v,slot,Material.GOAT_HORN,ChatColor.GOLD+label+" Horn",
                        "Assigned instrument: "+c.horns.getOrDefault(key,"Not set"),
                        "Hold the horn, then click to assign");
            }
        }

        private void war(View v,Player player,Civ c) {
            if(c==null)return;
            String selected=c.target==null?"None":String.valueOf(Bukkit.getOfflinePlayer(c.target).getName());
            slot(v,11,Material.NETHERITE_SWORD,ChatColor.RED+"Choose Rival Civilization",
                    "Target: "+selected,"Click to select an online enemy leader");
            slot(v,15,Material.GOAT_HORN,ChatColor.YELLOW+"Battle Horns",
                    "Attack, Retreat, Victory","Configure the sounds here");
            int count=0, attack=0, defense=0;
            for(War w:wars.values()) {
                if(!w.attacker.equals(c.owner)&&!w.defender.equals(c.owner))continue;
                count++;attack+=w.attackKills;defense+=w.defenseKills;
            }
            slot(v,13,Material.IRON_HELMET,ChatColor.GOLD+"Active Wars: "+count,
                    "Attacker eliminations: "+attack,
                    "Defender eliminations: "+defense,
                    "Use your goat horn to command the guards");
            slot(v,29,Material.SHIELD,ChatColor.AQUA+"Manage Allies",
                    "Choose teammates who can defend your territory");
        }

        private void forts(View v,Civ c,boolean damagedOnly) {
            if(c==null)return;
            List<Site> sites=new ArrayList<>();
            synchronized(c.sites) {
                for(Site site:c.sites)if(!damagedOnly||site.hp<100)sites.add(site);
            }
            if(sites.isEmpty()) {
                slot(v,22,Material.LIME_CONCRETE,ChatColor.GREEN+"No Damaged Outposts",
                        "Your fort objectives are at full health");
                return;
            }
            int index=0;
            for(Site site:sites) {
                if(index>=45)break;
                v.listedForts.put(index,site.id);
                slot(v,index,site.hp<40?Material.RED_CONCRETE:
                                site.hp<100?Material.ORANGE_CONCRETE:Material.STONE_BRICKS,
                        (site.hp<40?ChatColor.RED:ChatColor.GOLD)+site.name,
                        "Integrity: "+site.hp+"/100",
                        "Position: "+site.x+", "+(site.y+1)+", "+site.z,
                        site.built?"Built":"Construction in progress",
                        site.hp<100?"Last impact: "+site.lastX+", "+site.lastY+", "+site.lastZ:
                                "No structural damage",
                        "Click to set compass destination");
                index++;
            }
        }

        private void players(View v,Player viewer,Civ c,boolean rivals) {
            if(c==null)return;
            List<Player> choices=new ArrayList<>();
            for(Player other:Bukkit.getOnlinePlayers()) {
                if(other.getAddress()==null || other.getUniqueId().equals(c.owner))continue;
                if(rivals ? civilizations.containsKey(other.getUniqueId()) :
                        !civilizations.containsKey(other.getUniqueId())
                            && !c.owner.equals(other.getUniqueId()))choices.add(other);
            }
            choices.sort(Comparator.comparing(Player::getName,String.CASE_INSENSITIVE_ORDER));
            int from=v.page*45;
            for(int i=from;i<Math.min(from+45,choices.size());i++) {
                Player other=choices.get(i);
                int at=i-from;
                v.listedPlayers.put(at,other.getUniqueId());
                slot(v,at,Material.PLAYER_HEAD,
                        (c.allies.contains(other.getUniqueId())?ChatColor.GREEN:ChatColor.YELLOW)+other.getName(),
                        rivals?"Click to target this rival commander":
                                c.allies.contains(other.getUniqueId())?"Click to remove ally":"Click to add ally");
            }
            if(choices.isEmpty())slot(v,22,Material.BARRIER,ChatColor.RED+"No Eligible Players Online",
                    rivals?"Rival civilization leaders must be online":
                            "A teammate must be online and not own a civilization");
            pages(v,choices.size());
        }

        private void guardRoster(View v,Player player,Civ c) {
            if(c==null)return;
            List<Player> bots=new ArrayList<>();
            for(Player candidate:Bukkit.getOnlinePlayers())
                if(candidate.getAddress()==null)bots.add(candidate);
            bots.sort(Comparator.comparing(Player::getName,String.CASE_INSENSITIVE_ORDER));
            for(int i=v.page*45;i<Math.min((v.page+1)*45,bots.size());i++) {
                Player bot=bots.get(i);
                int cell=i-v.page*45;
                v.listedPlayers.put(cell,bot.getUniqueId());
                VexbotVehicleController.Role role=vehicleGuards.roleFor(c.owner,bot.getUniqueId());
                slot(v,cell,Material.PLAYER_HEAD,ChatColor.GREEN+bot.getName(),
                        "Current role: "+(role==null?"Unassigned":role.name()),
                        "Left-click: assign tank driver",
                        "Right-click: assign cannon gunner",
                        "Shift-right-click: unassign");
            }
            if(bots.isEmpty())slot(v,22,Material.BARRIER,ChatColor.RED+"No VexBot Guards Online",
                    "Purchase guards in the Shop to recruit a squad",
                    "Install VexBot Paper 1.21.11 on this server");
            pages(v,bots.size());
            slot(v,47,Material.EMERALD,ChatColor.GOLD+"Purchased Guards: "+c.hiredGuards,
                    "To buy more, open the Shop",
                    "Left/right click an online guard to set its role");
        }

        private void pages(View v,int count) {
            if(v.page>0)slot(v,45,Material.ARROW,ChatColor.YELLOW+"Previous Page");
            if(count>(v.page+1)*45)slot(v,53,Material.ARROW,ChatColor.YELLOW+"Next Page");
        }

        private void tools(View v,Player player) {
            slot(v,20,Material.MINECART,ChatColor.GREEN+"Deploy 3D Tank",
                    "Ride with WASD, fire with F",
                    "Uses existing tank permission and protection checks");
            slot(v,24,Material.DISPENSER,ChatColor.GOLD+"Deploy Siege Cannon",
                    "Sneak + right-click to operate",
                    "Uses existing cannon permission and protection checks");
            slot(v,13,Material.PAPER,ChatColor.YELLOW+"No Extra Commands Needed",
                    "Your civilization, upgrades, guards, and war",
                    "are controlled from this menu");
        }

        @EventHandler(ignoreCancelled=false)
        public void clicked(InventoryClickEvent event) {
            if(!(event.getView().getTopInventory().getHolder() instanceof View))return;
            View v=(View)event.getView().getTopInventory().getHolder();
            event.setCancelled(true);
            if(!(event.getWhoClicked() instanceof Player))return;
            Player player=(Player)event.getWhoClicked();
            if(!player.getUniqueId().equals(v.viewer))return;
            int index=event.getRawSlot();
            if(index<0||index>=SIZE)return;
            if(index==49) {
                if(v.screen==Screen.HOME) {
                    FoliaRunnable.runEntityTaskLater(plugin,player,player::closeInventory,1);
                } else later(player,Screen.HOME,v.chosenOwner,v.radius,0);
                return;
            }
            Civ c=memberCiv(player);
            if(v.screen!=Screen.HOME&&v.screen!=Screen.CREATE&&c==null) {
                later(player,Screen.HOME,player.getUniqueId(),DEFAULT_RADIUS,0);
                return;
            }
            Screen next=null;
            int page=0;
            switch(v.screen) {
                case HOME:
                    if(c==null) {
                        if(index==22) next=Screen.CREATE;
                    } else {
                        if(index==10)next=Screen.OVERVIEW;
                        if(index==12)next=Screen.SHOP;
                        if(index==14)next=Screen.HORNS;
                        if(index==16)next=Screen.WAR;
                        if(index==28)next=Screen.FORTS;
                        if(index==30)next=Screen.DAMAGE;
                        if(index==32)next=Screen.ALLIES;
                        if(index==34)next=Screen.GUARDS;
                        if(index==40)next=Screen.TOOLS;
                        if(index==38&&!c.complete){resume(player,c);later(player,Screen.HOME,v.chosenOwner,v.radius,0);return;}
                        if(index==46&&player.hasPermission("qualityarmory.civ.manage")) next=Screen.CREATE;
                    }
                    break;
                case CREATE:
                    if(index==20) {
                        if(!player.hasPermission("qualityarmory.civ.manage"))return;
                        List<Player> owners=new ArrayList<>();
                        for(Player candidate:Bukkit.getOnlinePlayers())
                            if(candidate.getAddress()!=null)owners.add(candidate);
                        owners.sort(Comparator.comparing(Player::getName,String.CASE_INSENSITIVE_ORDER));
                        if(owners.isEmpty())return;
                        int at=-1;
                        for(int i=0;i<owners.size();i++)
                            if(owners.get(i).getUniqueId().equals(v.chosenOwner)){at=i;break;}
                        UUID selected=owners.get((at+1)%owners.size()).getUniqueId();
                        later(player,Screen.CREATE,selected,v.radius,0);
                        return;
                    }
                    if(index==24) {
                        int at=0;
                        for(int i=0;i<radii.length;i++)if(radii[i]==v.radius)at=i;
                        later(player,Screen.CREATE,v.chosenOwner,radii[(at+1)%radii.length],0);
                        return;
                    }
                    if(index==31) {
                        Player ownerPlayer=Bukkit.getPlayer(v.chosenOwner);
                        if(ownerPlayer==null){player.sendMessage(ChatColor.RED+"Selected owner is offline.");return;}
                        if(!player.getUniqueId().equals(ownerPlayer.getUniqueId())
                                && !player.hasPermission("qualityarmory.civ.manage"))return;
                        FoliaRunnable.runEntityTaskLater(plugin,player,()->{
                            player.closeInventory();
                            create(player,ownerPlayer,v.radius);
                        },1);
                    }
                    return;
                case OVERVIEW:
                    if(index==15)next=Screen.FORTS;
                    if(index==30)next=Screen.DAMAGE;
                    if(index==32)next=Screen.SHOP;
                    break;
                case SHOP:
                    if(c==null)return;
                    String purchase=null;
                    if(index==10)purchase="cannon";
                    if(index==12)purchase="weapon";
                    if(index==14)purchase="outpost";
                    if(index==16)purchase="radius";
                    if(index==29)purchase="guards";
                    if(index==33)purchase="repair";
                    if(purchase!=null) {
                        upgrade(player,c,purchase);
                        later(player,Screen.SHOP,v.chosenOwner,v.radius,0);
                    }
                    return;
                case HORNS:
                    if(c==null||!owner(player,c))return;
                    String order=index==20?"attack":index==22?"retreat":
                            index==24?"victory":null;
                    if(order!=null) {
                        assignHorn(player,c,order);
                        later(player,Screen.HORNS,v.chosenOwner,v.radius,0);
                    }
                    return;
                case WAR:
                    if(index==11)next=Screen.RIVALS;
                    if(index==15)next=Screen.HORNS;
                    if(index==29)next=Screen.ALLIES;
                    break;
                case FORTS:
                case DAMAGE:
                    String id=v.listedForts.get(index);
                    if(id!=null) {
                        for(Site site:c.sites)if(site.id.equals(id)) {
                            World world=Bukkit.getWorld(c.world);
                            if(world!=null&&world.equals(player.getWorld()))
                                player.setCompassTarget(new Location(world,site.x,site.y+1,site.z));
                            player.sendMessage(ChatColor.GOLD+site.name+ChatColor.AQUA+
                                    " coordinates: "+site.x+", "+(site.y+1)+", "+site.z);
                            player.sendMessage(ChatColor.GRAY+"Compass points there when you're in the same world.");
                            break;
                        }
                    }
                    return;
                case ALLIES:
                case RIVALS:
                    if(index==45||index==53) {
                        page=Math.max(0,v.page+(index==45?-1:1));
                        if(index==45&&v.page==0)return;
                        next=v.screen;
                    } else {
                        UUID selected=v.listedPlayers.get(index);
                        if(selected==null||!owner(player,c))return;
                        Player candidate=Bukkit.getPlayer(selected);
                        if(candidate!=null) {
                            if(v.screen==Screen.ALLIES) ally(player,c,candidate.getName());
                            else target(player,c,candidate.getName());
                        }
                        next=v.screen;page=v.page;
                    }
                    break;
                case GUARDS:
                    if(index==45||index==53) {
                        page=Math.max(0,v.page+(index==45?-1:1));
                        next=Screen.GUARDS;
                    } else {
                        UUID selected=v.listedPlayers.get(index);
                        if(selected==null||!owner(player,c))return;
                        Player candidate=Bukkit.getPlayer(selected);
                        if(candidate==null)return;
                        if(event.isShiftClick()&&event.isRightClick())
                            vehicleGuards.unbindFromMenu(player,c.owner,candidate);
                        else if(event.isRightClick())
                            vehicleGuards.bindFromMenu(player,c.owner,candidate,VexbotVehicleController.Role.CANNON);
                        else
                            vehicleGuards.bindFromMenu(player,c.owner,candidate,VexbotVehicleController.Role.TANK);
                        next=Screen.GUARDS;page=v.page;
                    }
                    break;
                case TOOLS:
                    if(index==20) {
                        if(player.hasPermission("qualityarmory.tank")) {
                            FoliaRunnable.runEntityTaskLater(plugin,player,()->
                                    {player.closeInventory();plugin.getTanks().spawn(player);},1);
                        } else player.sendMessage(ChatColor.RED+"You cannot deploy tanks.");
                    }
                    if(index==24) {
                        if(player.hasPermission("qualityarmory.cannon")) {
                            FoliaRunnable.runEntityTaskLater(plugin,player,()->
                                    {player.closeInventory();siegeCannons.spawnStandalone(player);},1);
                        } else player.sendMessage(ChatColor.RED+"You cannot deploy cannons.");
                    }
                    return;
            }
            if(next!=null)later(player,next,v.chosenOwner,v.radius,page);
        }

        @EventHandler(ignoreCancelled=false)
        public void dragged(InventoryDragEvent event) {
            if(event.getView().getTopInventory().getHolder() instanceof View)
                event.setCancelled(true);
        }
    }

}
