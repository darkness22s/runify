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
    }

    public synchronized void save() {
        YamlConfiguration data=new YamlConfiguration();
        for (Map.Entry<UUID,Integer> e : wallets.entrySet())
            data.set("wallets."+e.getKey(),e.getValue());
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
        if(args.length==1){create(player,player,DEFAULT_RADIUS);return;}
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

    public List<String> complete(String[] args) {
        if(args.length==2) {
            List<String> choices=Arrays.asList("create","resume","status","locate","damage","balance","shop","upgrade","horn","target","ally");
            List<String> result=new ArrayList<>();
            for(String s:choices)if(s.startsWith(args[1].toLowerCase(Locale.ROOT)))result.add(s);
            return result;
        }
        if(args.length==3&&args[1].equalsIgnoreCase("upgrade"))
            return Arrays.asList("cannon","weapon","outpost","radius","guards","repair");
        if(args.length==3&&args[1].equalsIgnoreCase("horn"))
            return Arrays.asList("attack","retreat","victory");
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
                                +"Civilization ready! Set your attack, retreat, and victory horns with /qa civ horn."));
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
                        ChatColor.GREEN+"Resumed construction complete. Review /qa civ status."));
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
                    guards.provision(c.owner,2,loc);
                    p.sendMessage(ChatColor.GREEN+"Stationed 2 VexBot guards at "+station.name+".");
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
            guards.setMember(c.owner,friend,false);
            player.sendMessage(ChatColor.YELLOW+"Removed ally "+friend.getName());
        } else {
            c.allies.add(friend.getUniqueId());
            guards.setMember(c.owner,friend,true);
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
        Civ territory=at(event.getTo());
        UUID last=currentlyInside.get(p.getUniqueId());
        UUID now=territory==null?null:territory.owner;
        if(now==null)currentlyInside.remove(p.getUniqueId());
        else currentlyInside.put(p.getUniqueId(),now);
        if(territory!=null&&!now.equals(last)&&!territory.member(p.getUniqueId()))
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
        if(territory.member(invader.getUniqueId()))return;
        Civ own=memberCiv(invader);
        UUID attacker=own==null?invader.getUniqueId():own.owner;
        if(attacker.equals(territory.owner))return;
        String key=warKey(attacker,territory.owner);
        Long peace=peaceUntil.get(key);
        if(peace!=null&&System.currentTimeMillis()<peace)return;
        War war=wars.computeIfAbsent(key,k->new War(attacker,territory.owner));
        if(territory.hiredGuards>0&&guards.available()) {
            guards.attack(territory.owner,invader);
            Player commander=Bukkit.getPlayer(territory.owner);
            if(commander!=null)siegeCannons.fireGuardSalvo(territory.owner,commander,invader.getLocation());
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
            if(!civ.member(p.getUniqueId()))continue;
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
        award(killer.getUniqueId(),100);
        Civ recipient=attacking!=null?attacking
                :defending!=null&&defending.member(killer.getUniqueId())?defending:null;
        if(recipient!=null){recipient.treasury+=25;save();}
        killer.sendMessage(ChatColor.GOLD+"+100 coins for an enemy elimination"
                +ChatColor.GRAY+" (use /qa civ shop)");
        UUID killerSide=attacking==null?killer.getUniqueId():attacking.owner;
        UUID victimSide=victimTeam==null?victim.getUniqueId():victimTeam.owner;
        for(War w:wars.values()) {
            if(killerSide.equals(w.attacker)&&victimSide.equals(w.defender)) {
                w.attackKills++;w.lastKiller=killer.getUniqueId();w.lastKillTime=now;
            } else if(killerSide.equals(w.defender)&&victimSide.equals(w.attacker)) {
                w.defenseKills++;w.lastKiller=killer.getUniqueId();w.lastKillTime=now;
            }
        }
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
            if(c.hiredGuards>0)guards.attack(c.owner,target);
            leader.sendMessage(ChatColor.RED+"ATTACK order issued to "+c.hiredGuards+" VexBot guards.");
            return;
        }
        if("retreat".equals(order)) {
            for(War war:new ArrayList<>(wars.values())) {
                if(c.owner.equals(war.attacker)||c.owner.equals(war.defender)) {
                    String key=warKey(war.attacker,war.defender);
                    if(!wars.remove(key,war))continue;
                    peaceUntil.put(key,System.currentTimeMillis()+240000);
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
            if(base!=null&&c.hiredGuards>0)guards.retreat(c.owner,base);
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
                    if(c.hiredGuards>0&&c.centerLocation()!=null)
                        guards.retreat(c.owner,c.centerLocation());
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
}
