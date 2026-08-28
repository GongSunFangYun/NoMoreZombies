<div align="center">

<img src="src/main/resources/icon.png" alt="Logo" width="160" height="160">

# NoMoreZombies - Hypixel Zombies Assistant Mod

[English](README.md) | [简体中文](README_ZHT.md)

[![GitHub release](https://img.shields.io/github/v/release/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Downloads](https://img.shields.io/github/downloads/GongSunFangYun/NoMoreZombies/total?style=flat-square)]()
[![Stars](https://img.shields.io/github/stars/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Forks](https://img.shields.io/github/forks/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Issues](https://img.shields.io/github/issues/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![License](https://img.shields.io/github/license/GongSunFangYun/NoMoreZombies?style=flat-square)]()

</div>

NoMoreZombies is a **client-side** Fabric mod for **Minecraft 1.21.4** that assists you in the **Hypixel Zombies** minigame. It reads only what your client can already see (chat, scoreboard, titles, world sounds, and entity metadata) and changes nothing on the server side.

The interface and all messages are **bilingual (Chinese / English)** and follow your client language automatically.

---

## Disclaimer

- **Client-side only.** NoMoreZombies reads only what your client already receives (chat, scoreboard, titles, world sounds, and entity metadata). It never modifies the server, never sends packets, and changes nothing other players can see.
- **Server rules and ban risk.** Depending on the server's rules, some features in this mod (for example, the through-wall ESP) may be considered cheating. Using the mod may result in warnings or account bans. You are solely responsible for how and where you use it.
- **Use at your own risk.** The mod is provided "as is" without warranty of any kind. The author is not liable for any loss or damage arising from its use.
- **Not affiliated.** This mod is not affiliated with, endorsed by, or associated with Hypixel Inc., Mojang Studios, or Microsoft.
- **No anti-cheat guarantee.** The mod does not try to evade server anti-cheat; some features may be detectable. No claim is made that any particular feature is safe or undetectable.

---

## Feature Overview

### Round Timing
- A spawn-countdown table for every wave of the current round, with the next wave highlighted.
- Optional per-map wave spawn sound alerts, plus a 3-2-1 countdown before the final wave.
- A color alert on boss waves in Alien Arcadium.

### Powerup Tracker
- Detects powerups through three redundant channels (armor stand scanning, entity metadata, and chat activation).
- Locks onto the map's powerup pattern after the first observation and predicts the refresh round.
- Drop and pickup notifications, plus an on-screen timer showing how long a powerup has been active and how long until the next one.

### Team Stats
- A scoreboard-style HUD in the top-left showing each teammate's health, status (in combat / downed / dead / left), kills, downs, deaths, and gold.
- Data is cached to a local file so it survives a quick rejoin.

### Game Timer HUD
- Total game time and current-round time, frozen at the value shown when the game ends (win or wipe).

### Round Records (RKPM)
- Per-round time and kill totals, summarized in chat at the end of each round, with **RKPM** (round kills per minute = net kills x 60 / round seconds). Click the message to copy it.

### Player Stats Query
- With a Hypixel API key configured, query the Zombies stats of any player (by name or UUID) or of your current teammates automatically each round.

### Chat Filter & Sidebar
- Hide noisy chat lines (gold pickups, window repairs, hit confirmations, lucky chests, opened areas, player joins/leaves).
- Clean up the Hypixel sidebar: strip empty lines and player rows, and remove the vanilla time line when the timer HUD is on.

### Entity ESP
- Outline boxes for teammates (green, yellow when downed), zombies and other hostiles (red), and spawned powerups (white).
- Each ESP type has its own toggle and a **render mode**: **Normal** (respects occlusion, hidden behind walls) or **Through walls** (visible through obstacles).
- A global **through-wall render distance** slider (5-200 blocks) controls how far the through-wall effect reaches.

### Zombie Health Bar
- A world-space health bar above each hostile mob (`[#######------] 12/20HP`), colored by remaining health.

### AA Auto Commander
- On Alien Arcadium, a round-command HUD (giant spawn, elder spawn, difficulty, recommended points) plus an automatic chat broadcast at the start of each round.

### Lightning Rod Cooldown HUD
- On Alien Arcadium, a 4-slot HUD tracking each lightning rod's 20-second cooldown, colored by remaining time.

### QoL Toolkit
- **Smooth zoom** (key-based, with scroll-wheel fine tuning, easing curves, and sensitivity compensation).
- **Always sneak** / **always sprint** / **gamma override** (forced brightness).
- **Free camera** (detach the camera from your body to scout around).
- **Hide nearby players** (translucent so they don't block your view).
- **Hide the vanilla boss bar** and **the vanilla scoreboard**.
- **Right-click fires only** (blocks non-firing right-click interactions).
- **No gun-fire particles** and **no fire overlay**.
- **CPS counter** (left/right clicks per second).

### HUD Editor
- Drag, scale, and toggle each of the HUD elements individually from the config screen.

---

## Installation

1. Install **Fabric Loader 0.16.14** and create a **1.21.4** instance.
2. Put `NoMoreZombies-pre-release-0.1.jar` into your `mods/` folder.
3. Dependencies (install alongside):
   - **Fabric API**
   - **MaLiLib** (external dependency, install it separately)
   - **ModMenu** (optional, lets you open the config from the mod list)

## Configuration

- Open the config from ModMenu (NoMoreZombies > Config), or press the default hotkey **Z+X**.
- Every feature toggle is **off by default** (Tweakeroo-style). Bind a hotkey to any toggle in the config screen to flip it in-game instantly.
- The config file is `config/nomorezombies.json` (plain text, editable by hand).
- Data tables (wave times, powerup patterns) hot-reload with **F3+T** in-game.

## Notes

- This mod is purely client-side: it never modifies the server, sends no packets, and changes nothing other players can see.
- It is designed for the Chinese Hypixel Zombies community; all parsing and messages accept both Chinese and English game text.
- If you have a bug report or a feature request, describe what you were doing and what appeared in the log when it happened.
