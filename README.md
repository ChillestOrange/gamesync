<div align="center">

# ☁️ Game Sync

**Never lose a world. Play from anywhere.**

Game Sync is a client-side Minecraft mod that syncs your singleplayer worlds to the cloud,
keeping your progress safe and accessible from any machine you play on.

<a href="https://www.curseforge.com/minecraft/mc-mods/game-sync"><img alt="curseforge" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg"></a>&nbsp;&nbsp;<a href="https://modrinth.com/mod/game-sync"><img alt="modrinth" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg"></a>

</div>

## ✨ Features

- **☁️ Google Drive Sync** - Push and pull your worlds directly to your own Google Drive storage
- **🖥️ Client-Side Only** - No server-side installation required; works entirely on your local client
- **🔄 Autosave Sync** - Optionally sync after every autosave cycle to keep your remote copy current
- **⚡ Threaded Transfers** - Large worlds are transferred in parallel to keep sync times reasonable
- **🎛️ In-Game Config** - Full settings UI via [Mod Menu](https://modrinth.com/mod/modmenu) and [YACL](https://modrinth.com/mod/yacl) on Fabric
- **🔁 Resilient Uploads** - Configurable retry logic handles flaky connections without aborting the whole sync

## 📋 Requirements

### Fabric

| Dependency | Version |
|---|---|
| Minecraft | `26.1.2` |
| Fabric Loader | `0.19.3` |
| [Fabric API](https://modrinth.com/mod/fabric-api) | latest for `26.1.2` |
| [YACL](https://modrinth.com/mod/yacl) | latest for `26.1.2` |
| [Mod Menu](https://modrinth.com/mod/modmenu) | latest for `26.1.2` *(optional, for in-game config UI)* |

### NeoForge

| Dependency | Version |
|---|---|
| Minecraft | `26.1.2` |
| NeoForge | `26.1.2.76` |
| [YACL](https://modrinth.com/mod/yacl) | latest for `26.1.2` |

## 📥 Installation

> [!TIP]
> If you're using the CurseForge or Modrinth app, steps 1 and 2 are handled automatically. Search for **Game Sync**, click Install, and your mod manager will resolve the correct loader and all dependencies for your platform.

1. Download and install the correct mod loader for your platform: [Fabric Loader](https://fabricmc.net/use/installer/) `0.19.3` or [NeoForge](https://neoforged.net/) `26.1.2.76`
2. Download all required dependencies for your platform as listed in the [Requirements](#-requirements) section and place them in `.minecraft/mods/`
3. Download the latest Game Sync release for your platform from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/game-sync/files) or [Modrinth](https://modrinth.com/mod/game-sync/versions) and place the `.jar` in `.minecraft/mods/`
4. Launch Minecraft using the appropriate loader profile. Game Sync will generate its config file at `.minecraft/config/gamesync.jsonc` on first run
5. Follow the [Cloud Setup](#-cloud-setup) guide below to configure your credentials before loading a world

## 🔑 Cloud Setup

### Google Drive

Game Sync uses your own Google OAuth credentials to authenticate with Drive. Your world data is transferred directly to your Google account; nothing is routed through any third-party server.

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a new project, or select an existing one
2. Navigate to *APIs & Services > Library*, search for **Google Drive API**, and enable it
3. Go to *APIs & Services > Credentials*, click **Create Credentials**, and select **OAuth 2.0 Client ID**. Set the application type to **Desktop app**
4. Once created, copy the **Client ID** and **Client Secret** values into `gamesync.jsonc` under `clientId` and `clientSecret` respectively
5. Set `remoteFolderId` to the ID of the Drive folder you want to sync into. This is the last segment of the folder URL: `drive.google.com/drive/folders/<ID>`
6. On your next world load, a browser window will open to complete the OAuth authorization flow. Once authorized, the token is stored locally and authentication is handled automatically going forward

> [!TIP]
> Keep your `clientId`, `clientSecret`, and OAuth token private. Don't share your config file with others.

## 🛠️ Building from Source

### Prerequisites

- [JDK 21](https://adoptium.net/) or newer
- Git

No special IDE configuration is required. Any IDE with Java and Gradle support will work out of the box.

1. Clone the repository and navigate into it:
   ```bash
   git clone https://github.com/YOUR_USERNAME/game-sync.git
   cd game-sync
   ```

2. Build for your target platform:
   ```bash
   ./gradlew :fabric:build
   ./gradlew :neoforge:build
   ```

3. Compiled artifacts are output to `build/libs/` in the project root. Use the jar without the `-sources` suffix when installing locally for testing.

## 🐛 Reporting Issues

Please [open an issue](../../issues/new) and include the following:

- Minecraft version, mod loader (Fabric or NeoForge), and loader version
- Game Sync version
- A description of what happened and what you expected instead
- Your `latest.log` from `.minecraft/logs/`
- If the game crashed, the crash report from `.minecraft/crash-reports/`

## 📄 License

Licensed under the [MIT License](LICENSE). You're free to use, modify, and redistribute this mod, including in modpacks.

---
<div align="center">Made with ❤️ for the Minecraft community</div>
