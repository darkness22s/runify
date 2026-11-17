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
- **Hit Preview (Fabric client mod)** - Colors the crosshair and estimates damage and lethal hits for the targeted entity (Minecraft 1.21.11)

### 🔌 Plugin Integrations

QualityArmory seamlessly integrates with popular plugins:

- **Protection**: WorldGuard, GriefPrevention, Residence, Towny
- **Economy**: Vault, ChestShop, QuickShop
- **AntiCheats**: Vulcan, Matrix, Spartan
- **Utilities**: PlaceholderAPI, ProtocolLib, ViaVersion, LightAPI
- **NPCs**: Citizens, Sentinel
- **Items**: ItemBridge, Mimic

---

## 📥 Download — Runify for Minecraft 1.21.11 ONLY

**Use the Runify release, not the upstream QualityArmory/Modrinth JAR.**
Upstream QualityArmory lacks all the Runify civilization, tank and cannon
features. Builds from this project's `main` branch may also be outdated
until the Runify PR is merged.

- [Runify 1.21.11 release — plugin JAR and matching texture pack](https://github.com/darkness22s/runify/releases/tag/runify-1.21.11)
- [Direct plugin JAR](https://github.com/darkness22s/runify/releases/download/runify-1.21.11/Runify-QualityArmory-1.21.11.jar)
- [Direct 1.21.11 gun texture pack ZIP](https://github.com/darkness22s/runify/releases/download/runify-1.21.11/Runify-ResourcePack-1.21.11.zip)
- [GitHub Actions build results](https://github.com/darkness22s/runify/actions/workflows/build-runify.yml)

The public release files are generated automatically **after the updated code
passes CI and lands on `main`**. PR builds are available as Actions artifacts,
but are not a public release. The Runify JAR registers the normal plugin
name `QualityArmory` for ecosystem compatibility.

## 🚀 Install on a Paper 1.21.11 server

1. Shut down the server. Use **Paper 1.21.11 and Java 21** (not a Fabric
   client mod, older server build, or a server running from the `mods/` folder).
2. Remove any old `QualityArmory.jar` or `Runify*.jar` from `plugins/`
   so **only one** QualityArmory-derived plugin remains.
3. Copy **Runify-QualityArmory-1.21.11.jar** into `plugins/` and start
   the server. In server console look for **"Runify Crownlands registered"**.
4. Run `/plugins`: **QualityArmory** should be green. As a server OP, run
   **`/qa civ`** to open the in-game civilization management menu.
   `/cua` and `/runify` are also aliases for `/qa`.
5. Accept the server's resource-pack prompt. The default Runify URL
   points to the **matching 1.21.11 pack from this project's release**.
   The texture ZIP is a separate asset fetched over HTTPS by the client,
   *not* textures stored inside the JAR or your server's `mods/` folder.
   If you already declined it, enable Server Resource Packs in Minecraft's
   multiplayer server settings and reconnect.

**Important:** Runify has disabled upstream QualityArmory auto-updates so
that the custom tank/war code cannot silently be overwritten by an ordinary
QualityArmory release. It also migrates the old QualityArmory 2.0.2 Dropbox
pack setting on existing installations. If you use your own merged texture
pack, specify its publicly accessible direct ZIP URL in
`plugins/QualityArmory/config.yml` with
`DefaultResourcepackOverride: true`.

The `/qa civ` menu and terrain-generation features are an **OP-only**
permission by default (`qualityarmory.civ`); remove that restriction
through a permission manager if desired. **Do not generate outposts on a
world without a backup.** VexBot Paper for Minecraft 1.21.11 is optional
but required for NPC tank-driver/cannon-crew behavior.

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

> **📦 Version Note:** For Runify, use the release JAR linked above, not an upstream QualityArmory binary; this Maven snippet documents the upstream API dependency only.
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

### One command for the whole civilization

```text
/qa civ
```

Opens the **Crownlands in-game menu**. Click the icons—no command list
to memorize:

- **Create:** Choose the owner (admins can cycle online players), choose a
  radius from 192 to 1,536 blocks, and click Create at Your Position.
- **Overview & Forts:** See your owner, claim radius, treasury, defenses,
  integrity and coordinates. Click a fort to set your compass destination.
- **Shop:** Buy cannon/weapon upgrades, more outposts, territory expansion,
  VexBot squads and repairs with coins.
- **Battle Horns:** Hold a goat horn and click Attack, Retreat or Victory to
  assign its sound. Blow that horn to order guards in combat.
- **Wars & Rivals:** See war scores and click an online rival to target them.
- **Team:** Click players to add/remove allies.
- **Guards:** View VexBot guards, left-click to assign a tank driver,
  right-click for a cannon operator, or Shift-right-click to unassign.
- **Siege Tools:** Deploy a standalone 3D tank or cannon from the menu.

An old `/qa civ ...` subcommand still works for server administrators,
macros and existing setups, but it is not needed or offered in tab suggestions.
The menu checks ownership and existing permissions before taking any action.
A civilization can only be created for an online owner. Building forts
**overwrites terrain**, so use a backed-up battle world.

### War declarations and goat horns

A non-allied player crossing into a territory automatically starts a war.
Members of the attacked team get a large, centered
**WAR DECLARED!** screen alert and a horn sound, with the intruder's name.
Impacts from **RPGs, artillery, grenades and other AOE explosives**
also trigger alerts. **Ordinary bullets do not**.
War alerts have a spam-prevention cooldown.

The owner assigns **three distinct goat horn instrument types** from
**Crownlands → Battle Horns**, holding the desired goat horn in the main
hand and clicking the appropriate order. No extra commands are required.

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

Open **`/qa civ` → Upgrades & Shop**. Click an upgrade to purchase it.
Your balance and civilization treasury appear at the top of the shop.

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
destroyed, open **Damage Report** from the menu to see the latest impact.
For repairs, stand within 48 blocks of a damaged outpost.

### VexBot Paper 1.21.11 – actual vehicle crews

Runify's server plugin now builds against **Spigot API 1.21.11, Java 21**.
The optional official VexBot **Paper** plugin supplies the fake-player guards.
Runify uses documented VexBot team/spawn/navigation commands and controls
its *own* physical tank/cannon entities; no nonexistent VexBot Fabric APIs
or undocumented tank-driving methods are assumed.

When purchasing **two guards for 250 coins**, Runify creates a bot squad and
places a real 3D tank at the chosen fortress. New VexBot fake-player entities
near that fortress are recruited as a **tank driver** and a **cannon operator**.
The driver is mounted into an actual tank and the vehicle navigates toward a
target by adjusting its horse chassis velocity, avoiding obstructed ground.
The real tank turret aims, charges a shell, fires a 3×-RPG explosion, and
waits its full 35-second reload. On a retreat order it returns home.
Cannoneers are ordered to walk to the gun's physical operator station; only
after arriving within the permitted distance can they use that cannon,
subject to 28° traverse, 8°–45° elevation, ballistic trajectory and
persistent reload.

If VexBot's Paper build does not automatically expose purchased guards
as Bukkit fake-player entities, open **`/qa civ` → VexBot Guards**.
Left-click an online bot to assign the tank driver role, right-click for
cannon operation, or Shift-right-click to remove its assignment.
There is no need to type the bot's exact name into a command.

The owner chooses a rival from **`/qa civ` → Wars & Enemies → Choose Rival**,
then blows their configured attack horn. Invasions also alert the defending
crew. Retreat/victory horns end attack orders, and the crew returns to
their garrison. Bot crews and their home stations persist in
`plugins/QualityArmory/vexbot-vehicle-crews.yml`.

**Requirements and verification:** Use the VexBot Paper build that matches
Minecraft **1.21.11**, on a Java 21 server. The code compiles against
the declared 1.21.11 server dependency. Actual compatibility, pathfinding
around complicated terrain, fake-player mounting, VexBot command registration,
and tactical balance still require an **in-game Paper + VexBot playtest**.
The road navigation is obstacle-aware steering, not a full long-distance
pathfinding engine; tanks stop when no safe route is found rather than
teleporting through walls.

### Minecraft 1.21.11 distribution

The Paper plugin and optional Fabric hit-preview client are independently
built by GitHub Actions. The server is strictly targeting **1.21.11**:
`pom.xml` uses Spigot `1.21.11-R0.1-SNAPSHOT`, compiler release 21,
and `plugin.yml` specifies API version `1.21.11`. The optional Fabric
module is configured for Minecraft 1.21.11 and current Yarn mappings; its
Gradle build is checked separately in CI.

### Standalone tank

`/qa tank` deploys a human-ridable 3D vehicle with heavy armor and a
slow-reloading gun. Ride with WASD, aim with your view, press F to fire;
VexBot-controlled tanks use the same physical tank and shell mechanics.

### Standalone cannon

`/qa cannon` still creates an independent modeled 3D siege cannon.
Sneak + right-click the model, align the barrel within ±28° and aim
8°–45° above horizontal. Cannon shells have gravity and a baseline
30-second reload. Base damage and destruction are 2× the configured RPG,
before purchased cannon upgrades. Normal explosion/protection settings apply.
