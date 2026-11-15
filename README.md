<div align="center">

![QualityArmory](images/banner.png)

[![CurseForge](https://img.shields.io/curseforge/dt/278412?style=for-the-badge&logo=curseforge&label=CurseForge&color=%23F16436)](https://www.curseforge.com/minecraft/bukkit-plugins/qualityarmory)
[![Spigot](https://img.shields.io/spiget/downloads/47561?style=for-the-badge&logo=spigotmc&color=%23ED8106&label=Spigot)](https://www.spigotmc.org/resources/quality-armory.47561/)
[![Modrinth](https://img.shields.io/modrinth/dt/qualityarmory?style=for-the-badge&logo=modrinth&color=%231BD96A&label=Modrinth)](https://modrinth.com/plugin/qualityarmory)

**A powerful, feature-rich gun plugin for Minecraft servers**

</div>

---

## ✨ Features

### 🎯 Core Functionality

- **Custom Guns** - Create unlimited custom weapons with unique stats, sounds, and behaviors
- **Ammunition System** - Realistic ammo management with different ammo types
- **Armor & Protection** - Custom armor sets with bullet protection mechanics
- **Attachments** - Weapon attachments system for enhanced customization
- **Resource Pack Support** - Custom 3D models and textures via resource packs
- **Multi-Version Support** - Works across different Minecraft versions with ViaVersion

### 🛠️ Advanced Features

- **Recoil System** - Realistic weapon recoil with smooth camera movement (ProtocolLib)
- **Muzzle Flashes** - Dynamic lighting effects when firing (LightAPI)
- **Friendly Fire Protection** - Party-based friendly fire system (Parties)
- **Economy Integration** - Weapon shops with Vault support
- **NPC Integration** - Citizens NPC support for custom gun-wielding NPCs
- **GUI System** - Intuitive menu system for crafting, shopping, and item management
- **Hit Preview (Fabric client mod)** - Colors the crosshair and estimates damage and lethal hits for the targeted entity (Minecraft 1.20.6)

### 🔌 Plugin Integrations

QualityArmory seamlessly integrates with popular plugins:

- **Protection**: WorldGuard, GriefPrevention, Residence, Towny
- **Economy**: Vault, ChestShop, QuickShop
- **AntiCheats**: Vulcan, Matrix, Spartan
- **Utilities**: PlaceholderAPI, ProtocolLib, ViaVersion, LightAPI
- **NPCs**: Citizens, Sentinel
- **Items**: ItemBridge, Mimic

---

## 📥 Download

[![Download on Modrinth](https://img.shields.io/badge/Download-Modrinth-%231BD96A?style=for-the-badge&logo=modrinth)](https://modrinth.com/plugin/qualityarmory)
[![Dev Builds](https://img.shields.io/badge/Download-Dev%20Builds-blue?style=for-the-badge&logo=github)](https://ci.codemc.io/job/ZombieStriker/job/QualityArmory/lastSuccessfulBuild/artifact/target/QualityArmory.jar)

---

## 🚀 Quick Start

1. **Download** the latest QualityArmory.jar
2. **Place** it in your server's `plugins` folder
3. **Start** your server
4. **Configure** guns, ammo, and items in the generated config files
5. **Enjoy!** No additional dependencies required

### Optional Enhancements

While QualityArmory works standalone, these plugins add extra features:

- **LightAPI** - Muzzle flash lighting effects
- **ProtocolLib** - Smooth recoil animations
- **Vault** - Economy integration for weapon shops
- **ViaVersion** - Cross-version compatibility
- **Parties** - Friendly fire protection

---

## 👨‍💻 For Developers

### Maven Dependency

Add QualityArmory to your project:

```xml
<repository>
    <id>codemc-repo</id>
    <url>https://repo.codemc.io/repository/maven-public/</url>
</repository>

<dependency>
    <groupId>me.zombie_striker</groupId>
    <artifactId>QualityArmory</artifactId>
    <version>VERSION</version>
    <scope>provided</scope>
</dependency>
```

> **📦 Version Note:** Replace `VERSION` with the latest release version
>
> [![GitHub Release](https://img.shields.io/github/v/release/Lorenzo0111/QualityArmory?style=logo=github&color=lightgray&label=Latest%20Version)](https://github.com/Lorenzo0111/QualityArmory/releases/latest)

### API Documentation

Check out the [Developer Documentation](https://wiki.lorenzo0111.me/qualityarmory/developers/api) for API usage examples and integration guides.

---

## 📚 Documentation

The documentation is available on our [website](https://wiki.lorenzo0111.me/qualityarmory):

---

## 🎮 Commands & Permissions

### Main Commands

- `/qa` or `/qualityarmory` - Main command
- `/qa give <player> <item>` - Give items to players
- `/qa reload` - Reload configuration
- `/qa shop` - Open weapon shop
- `/qa craft` - Open crafting menu
- `/qa outpost` - Spawn a custom 33×33, 39-block-tall siege fortress at your feet (OPs by default)

> **Siege mode:** The outpost includes four tall corner towers, five fighting decks, cover, and scaffolding shafts. It replaces terrain inside its footprint, so use it in a dedicated arena world. The normal RPG block blast now defaults to power **8** (previously 3); edit `rpgExplosionPower` in `plugins/QualityArmory/config.yml` (range 1–16) and run `/qa reload` to tune it. Region-protection rules still apply.

### Key Permissions

- `qualityarmory.*` - All permissions
- `qualityarmory.admin.*` - Admin commands
- `qualityarmory.give` - Give items
- `qualityarmory.shop` - Access shop
- `qualityarmory.craft` - Access crafting
- `qualityarmory.usegun` - Use weapons
- `qualityarmory.outpost` - Spawn the siege outpost (OP-only by default)

---

## 🤝 Contributing

Contributions are welcome! Feel free to:

- Report bugs
- Suggest features
- Submit pull requests
- Improve documentation

---

## 👥 Authors

- **Zombie_Striker** - Original Creator
- **Lorenzo0111** - Maintainer

---

## 📄 License

See the [LICENSE](LICENSE) file for details.

---

## 🔗 Links

- [Spigot Resource Page](https://www.spigotmc.org/resources/quality-armory.47561/)
- [CI/CD Builds](https://ci.codemc.io/job/ZombieStriker/job/QualityArmory/)
- [Maven Repository](https://repo.codemc.io/repository/maven-public/)

## Crownlands — player-owned territory warfare (Paper)

**The compact nine-district prototype has been replaced.** Each civilization owns a
circular **territory** with a player-assigned owner, a central fortress and
**five scattered 33×33 multi-storey outposts**. Outposts are placed hundreds
of blocks apart inside the selected radius rather than clustered around the
player. Further outposts (up to 15) can be bought later.

**Danger:** Fort generation overwrites terrain. Back up your world and use a
dedicated, unclaimed battle map. The owner must be online at creation time.
A civilization will not be placed over another civilization's claim.

### Create a civilization

```text
/qa civ                                    # claim a 512-block radius for yourself
/qa civ create Alex 768                    # admin assigns Alex a 768-block radius
/qa civ status                             # owner, radius, fortress HP, upgrades
/qa civ wars                               # active enemies and kill score
/qa civ resume                             # resume construction interrupted by a restart
/qa civ locate                             # coordinates for every outpost
/qa civ damage                             # damaged forts + last damage coordinates
/qa civ ally Jordan                        # add/remove an allied teammate
/qa civ target Alex                         # select a rival civilization to attack
```

`qualityarmory.civ` permits ordinary civilization commands (by default OP).
`qualityarmory.civ.manage` allows assigning land to **other** online owners.
Territory radius is configurable from **192 to 1536 blocks** when created,
and can be expanded with coins. Players in your ally list can access and
defend your claim without triggering a border war.

### War declarations and goat horns

A non-allied player crossing into a territory automatically starts a war.
Members of the attacked team get a large, centered
**WAR DECLARED!** screen alert and a horn sound, with the intruder's name.
Impacts from **RPGs, artillery, grenades and other AOE explosives**
also trigger alerts. **Ordinary bullets do not**.
War alerts have a spam-prevention cooldown.

The owner assigns **three distinct goat horn instrument types** by holding
the instrument and using:

```text
/qa civ horn attack
/qa civ horn retreat
/qa civ horn victory
```

When a bound horn is played, Runify interprets it as the owner's order:

- **Attack**: declares war on the selected rival and tells recruited VexBot
  guards to move to and attack the rival leader (if online).
- **Retreat**: orders VexBot guards back to the capital, ends the current
  engagement and awards 200 coins to the opposing civilization owner.
- **Victory**: after the team has killed at least one enemy player in its
  current war, ends that war, awards **350 coins to the horn blower**
  and **100 to the civilization treasury**. Defenders must also clear
  hostile players from their border. A four-minute truce prevents instant
  repeat claims.

A successful kill of an enemy player involved in a territorial battle pays
**100 coins to the killer**, plus **25 treasury coins** to their civilization
when applicable. Repeated kills of the same victim cannot be farmed every
second: the same killer-victim pair has a **90-second payout cooldown**.

### Coin economy and persistent upgrades

```text
/qa civ balance
/qa civ shop
/qa civ upgrade cannon
/qa civ upgrade weapon
/qa civ upgrade outpost
/qa civ upgrade radius
/qa civ upgrade guards
/qa civ upgrade repair
```

| Upgrade | Cost | Effect |
| --- | ---: | --- |
| Cannon (levels 1–5) | 300 | Each level +25% cannon shell power, -3 seconds reload |
| Weapon (levels 1–5) | 250 | Each level +10% QualityArmory weapon damage |
| Outpost | 500+, scales | Construct another distant fortress and cannon |
| Radius | 450 | Enlarge civilization territory by 128 blocks |
| Guards | 250 | Add two VexBot fighters, stationed at an outpost |
| Repair | 120 | Restore a damaged nearby fortress and its core |

Upgrades deduct the player's coins first, then the shared treasury if needed.
Balances, active wars, truces, horn assignments, allies, outpost locations, damage coordinates and
upgrade levels persist to `plugins/QualityArmory/territories.yml`.
A castle's floating objective name shows its integrity. If blocks are
destroyed, use `/qa civ damage` to find the most recently hit position.
For repairs, stand within 48 blocks of a damaged outpost.

### VexBot Paper integration

Runify optionally uses the **official VexBot Paper plugin** through its
documented `/vexbot team` commands. It does **not** compile against
VexBot's Fabric-only extension API. When VexBot is installed, purchasing
guards creates a dedicated bot team and stations each purchased pair at a fort.
An attack horn sends a team attack order; a retreat/victory horn returns
them to hold the capital; intrusions trigger defensive guard orders.
Artillery coverage is coordinated with a garrison: if a purchased guard
team is active, a nearby existing cannon can fire a ballistic defensive
salvo and must complete its normal reload afterward.

**Compatibility warning:** Official VexBot Paper 3.x needs a matching
Minecraft **1.21.11+ Paper build** and its required Java runtime.
Runify's current Maven dependency still targets **Spigot API 1.20.6**;
compile success alone does not establish in-game compatibility with VexBot.
Use the vendor's matching Paper plugin on a test server first.

**Not supported by VexBot's documented Paper command interface:** native
mounting/driving of Runify tanks or actual fake-player interaction with
Runify's cannon hitboxes. Guard-triggered artillery is operated by Runify
on behalf of the VexBot garrison; it is not a claim that VexBot has a vehicle
driver API. Tank integration needs a separate vehicle-control interface and
runtime playtesting.

### Standalone cannon

`/qa cannon` still creates an independent modeled 3D siege cannon.
Sneak + right-click the model, align the barrel within ±28° and aim
8°–45° above horizontal. Cannon shells have gravity and a baseline
30-second reload. Base damage and destruction are 2× the configured RPG,
before purchased cannon upgrades. Normal explosion/protection settings apply.
