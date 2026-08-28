<div align="center">

<img src="src/main/resources/icon.png" alt="Logo" width="160" height="160">

# NoMoreZombies — Hypixel Zombies Assistant Mod

[English](README.md) | [简体中文](README_ZHS.md)

[![GitHub release](https://img.shields.io/github/v/release/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Downloads](https://img.shields.io/github/downloads/GongSunFangYun/NoMoreZombies/total?style=flat-square)]()
[![Stars](https://img.shields.io/github/stars/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Forks](https://img.shields.io/github/forks/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Issues](https://img.shields.io/github/issues/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![License](https://img.shields.io/github/license/GongSunFangYun/NoMoreZombies?style=flat-square)]()

</div>

NoMoreZombies is a Fabric mod for Minecraft 1.21.4. It operates entirely on the client side. Its functionality is limited to reading data already received by the client—chat messages, scoreboard, titles, world sounds, and entity metadata. The mod does not send any packets to the server, nor does it modify any server-side state.

This mod is still in early testing, so there may be a lot of undiscovered bugs. If you find any, please be sure to submit issues, and I'll fix them all when I have some free time.

All features are strictly gated to the Zombies mode. In any other game mode, every feature is fully disabled and has no effect.

All UI elements and messages are available in both Chinese and English; the display language is determined by the client's language setting.

![The HUD editor interface](https://cdn.modrinth.com/data/cached_images/51c9eed6d78ca7606a1ce1cc6143a19e12091005.png)

---

## Disclaimer

- **Client-side only: ** NoMoreZombies reads only data already received by the client (chat, scoreboard, titles, world sounds, and entity metadata). The mod does not modify server data, send packets, or affect what other players see or how the server logic operates.
- **Server rules and ban risk: ** Different servers have different policies regarding third-party tools. Some features of this mod—such as through-wall ESP—may be considered disallowed on certain servers. Use of this mod may result in warnings or account suspensions. Users are solely responsible for evaluating the risk and bearing any consequences.
- **Provided as-is: ** This mod is provided "as is" without any express or implied warranty. The author assumes no liability for any direct or indirect damages arising from its use.
- **No affiliation: ** This mod is not affiliated with, endorsed by, or associated with Hypixel Inc., Mojang Studios, or Microsoft.
- **Anti-cheat detection: ** This mod does not attempt to evade server-side anti-cheat systems. No guarantee is made regarding the detectability or safety of any specific feature.

---

## Feature List

### Wave Timing
- Displays a table of spawn times for each wave in the current round, with the next wave highlighted.
- Per-map configurable wave spawn sound alerts; plays a 3-2-1 countdown before the final wave.
- On the Alien Arcadium map, boss waves are indicated with a color change.

### Powerup Tracker
- Detects powerups through three independent channels: armor stand scanning, entity metadata parsing, and chat message matching.
- After the first observation, automatically identifies the map's powerup spawn pattern and predicts subsequent spawn rounds.
- Provides drop and pickup notifications; an on-screen timer shows the remaining duration of the active powerup and the countdown to the next spawn.

### Team Stats Panel
- Displays a scoreboard-style HUD in the top-left corner showing each teammate's health, status (in combat / downed / dead / left), kills, downs, deaths, and gold.
- Statistics are cached to a local file and restored automatically after reconnection.

### Game Timer HUD
- Displays total game time and elapsed time of the current round. When the game ends (win or wipe), the displayed values freeze and no longer update.

### Round Records (RKPM)
- At the end of each round, outputs the round duration, total kills, and RKPM (round kills per minute = net kills × 60 / round seconds) to chat. Clicking the message copies its content.

### Player Stats Query
- With a configured Hypixel API key, supports querying Zombies stats for any player by name or UUID. Can also automatically query stats for current teammates at the start of each round.

### Chat Filter and Sidebar Optimization
- Filters out the following types of chat messages: gold pickups, window repairs, hit confirmations, lucky chests, area unlocks, and player joins/leaves.
- Cleans up the Hypixel sidebar: removes empty lines and player rows; removes the native time row when the timer HUD is enabled.

### Entity ESP
- Draws bounding boxes for the following entities: teammates (green for active, yellow for downed), zombies and hostile mobs (red), and spawned powerups (white).

- Each ESP category can be toggled independently and configured with two rendering modes:
  - **Normal mode:** respects depth testing—entities occluded by blocks are not rendered.
  - **Through-walls mode:** bypasses depth testing—entities remain rendered even when occluded, facilitating rapid positioning of mobs and teammates in the PvE environment.

- A global render distance slider (range 5–200 blocks) controls the effective range of the through-walls mode.

- Through-walls mode is designed exclusively for PvE mob and teammate positioning and does not apply to PvP scenarios.

### Zombie Health Bar
- Displays a world-space health bar above each hostile mob, formatted as `[#######------] 12/20HP`. The color changes based on the remaining health percentage.

### Alien Arcadium Auto Commander
- On the Alien Arcadium map, displays round command information: giant spawn, elder spawn, difficulty level, and recommended hold position.
- Automatically sends a command message to chat at the start of each round.

### Lightning Rod Cooldown HUD
- On the Alien Arcadium map, displays a four-slot HUD tracking each lightning rod's 20-second cooldown. The color of each slot changes according to the remaining cooldown time.

### QoL Toolset
- **Smooth zoom:** key-triggered, scroll wheel fine-tuning, easing curves, and sensitivity compensation.
- **Always sneak** / **always sprint** / **gamma override** (forced brightness).
- **Free camera:** detaches the camera from the player model for unobstructed observation.
- **Hide nearby players:** makes nearby player models semi-transparent to reduce visual obstruction.
- **Hide vanilla boss bar** and **hide vanilla scoreboard**.
- **Right-click to fire only:** blocks non-firing right-click interactions.
- **Disable gun-fire particles** and **disable fire overlay**.
- **CPS counter:** displays left-click and right-click clicks per second.

### HUD Editor
- In the configuration screen, each HUD element can be individually dragged, scaled, and toggled.

---

## Installation

1. Install Fabric Loader 0.16.14 and create a Minecraft 1.21.4 game instance.
2. Place `NoMoreZombies-pre-release-0.1.jar` into the `mods/` folder.
3. Install the following dependencies (also placed in `mods/`):
   - Fabric API
   - MaLiLib

---

## Configuration

- Open the configuration screen via ModMenu (NoMoreZombies > Config), or use the default hotkey **Z + X**.
- All feature toggles are disabled by default. Hotkeys can be bound to any toggle in the config screen for in-game switching.
- The configuration file is located at `config/nomorezombies.json` and is stored in plain JSON format, editable manually.
- Data tables (wave timing, powerup spawn patterns) support hot-reload in-game via **F3 + T**.

---

## Notes

- This mod is client-side only. It does not modify server data, send packets, or affect other players' game experience or server logic.
- Text parsing and message output are compatible with both Chinese and English in-game languages.
- When submitting bug reports or feature requests, please describe the steps to reproduce and include the relevant log output.

---

## References

Some of the primary features of this mod are implemented with reference to the following projects:
- [ShowSpawnTime](https://github.com/Seosean/ShowSpawnTime)
- [NotEnoughZombies](https://github.com/PingIsFun/NotEnoughZombies)
- [Hypixel-Zombies-Mod](https://github.com/FairCauth/Hypixel-Zombies-Mod)

Some of the secondary features are implemented with reference to the following projects:
- [Zoomify](https://github.com/isXander/Zoomify)
- [tweakeroo](https://github.com/maruohon/tweakeroo)

This mod does not copy any code directly from these projects. It only references their functionality and implements the logic independently.