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

## Crownlands civilization and siege cannons (Minecraft 1.20.6+)

Run `/qa civ` as a player with `qualityarmory.civ` permission to generate a
large **185 × 185** dedicated siege playground at your feet. **Back up the world:**
construction clears and replaces blocks inside nine structures and levels roads.
Prefer a fresh flat battle world. Protected blocks and claimed regions are skipped.

The generated civilization includes a 38-block **Crown Citadel**, four tall
**Sentinel Outposts**, the **Golden Granary**, **Iron Foundry**,
**Sanctuary Clinic** and **Azure Waterworks**. Illuminated roads connect the
districts. The citadel and four outposts each receive a detailed, resource-pack-free
3D siege cannon assembled from Minecraft block-display entities.

Players defend nine separate **lodestone objectives** (100 integrity each).
Raiders periodically pressure the objectives when summoned; failing to eliminate
attackers or losing a core block ruins a district until it is repaired.
The tracked objective state persists through server restarts.

| Command | Purpose |
| --- | --- |
| `/qa civ` | Build your nine-district civilization (one per player) |
| `/qa civ status` | Display each district's integrity and coordinates |
| `/qa civ locate` | Locate your capital and other districts |
| `/qa civ raid` | Start a multi-front hostile raid (four-minute cooldown) |
| `/qa civ repair` | Near an objective, spend 12 iron ingots to restore 40 integrity |
| `/qa outpost` | Existing independent siege outpost (unchanged) |

**Cannon operation:** Stand near the cannon, **sneak and right-click** its
3D model, face within **28°** of the barrel's preset direction, and elevate
your view **8°–45°** above horizontal. Cannonballs follow gravity, so targets
need range estimation. Each cannon requires **30 seconds** to reload (persisted
through restarts). A hit deals **2× the registered RPG's damage** and generates
**2× its configurable explosion power** (capped at 32). If no RPG is loaded,
`cannon.fallbackRpgDamage` provides a configurable reference value.
World protection hooks and normal server explosion settings still apply.

**Compatibility:** Block displays, text displays and interaction hitboxes
require modern server APIs (1.20.6 recommended). The build uses the
existing Folia-aware scheduler for distributed structure construction.
The existing `/qa outpost` and RPG command functionality remain available.
