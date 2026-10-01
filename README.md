<div align="center">

<img src="src/main/resources/icon.png" alt="Logo" width="160" height="160">

# NoMoreZombies — Hypixel Zombies Helper Mod

[English](README.md) | [简体中文](README_ZHS.md)

[![GitHub release](https://img.shields.io/github/v/release/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Downloads](https://img.shields.io/github/downloads/GongSunFangYun/NoMoreZombies/total?style=flat-square)]()
[![Stars](https://img.shields.io/github/stars/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Forks](https://img.shields.io/github/forks/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Issues](https://img.shields.io/github/issues/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![License](https://img.shields.io/github/license/GongSunFangYun/NoMoreZombies?style=flat-square)]()

</div>

A **client-side** Fabric mod for **Minecraft 1.21.4** that assists you in the **Hypixel Zombies** minigame. It reads only what your client already receives (chat, scoreboard, titles, world sounds, entity metadata), and changes nothing on the server side.

---

## Disclaimer

- **Client-side only.** Reads only what your client already receives (chat, scoreboard, titles, world sounds, and entity metadata). It never modifies the server, never sends packets, and changes nothing other players can see.
- **Server rules and ban risk.** Depending on the server's rules, some features (for example, the through-wall ESP) may be considered cheating. Using the mod may result in warnings or account bans. You are solely responsible for how and where you use it.
- **Use at your own risk.** The mod is provided "as is" without warranty of any kind. The author is not liable for any loss or damage arising from its use.
- **Not affiliated.** This mod is not affiliated with, endorsed by, or associated with Hypixel Inc., Mojang Studios, or Microsoft.
- **No anti-cheat guarantee.** The mod does not try to evade server anti-cheat; some features may be detectable. No claim is made that any particular feature is safe or undetectable.

---

## Feature Overview

### Round Timing
- A spawn-countdown table for every wave of the current round, with the next wave highlighted.
- Optional per-map wave spawn sound alerts. The final wave plays two cues: a 3-2-1 warning countdown before the mobs appear, then a distinct final-wave spawn sound the moment they do.
- A color alert on boss waves in Alien Arcadium.

### Global Overview HUD
- Three rows, six columns: `Round` / `Boss` / `Powerups`. At a glance, see which of the **current round and the next five** have a boss spawn and a powerup refresh.
- The current round's column is green and always leftmost. Each new round scrolls the whole block one column left.
- Round row white, boss row red, powerup row blue, row labels gold; every cell is a bracketed round number (`[XX]`, and `[100]` and up keep their brackets).
- The boss and powerup rows read from shared data tables (per-map, per-difficulty boss rounds, and powerup patterns). The whole table is drawn even before you observe anything this game. Columns past the map's clear round are left blank: Dead End / Bad Blood / Prison 30, Alien Arcadium 105.

### Powerup Tracker
- Three redundant channels detect powerups: armor stand scanning, entity metadata, chat activation.
- Locks onto the map's powerup pattern on the first observation, then predicts the refresh round.
- Drop and pickup notices. An on-screen timer shows how long a powerup has been active and how long until the next one.

### Team Stats
- A scoreboard-style HUD (top-left by default): each teammate's health, status (in combat / downed / dead / left), kills, downs, deaths, and gold.
- Data caches to a local file, so a quick rejoin restores it.

### Game Timer HUD
- Total game time and current-round time. Both freeze at the value shown when the game ends (win or wipe).

### Round Records (RKPM)
- Per-round time and kill totals, summarized in chat at the end of each round, with **RKPM** (round kills per minute = net kills x 60 / round seconds). Click the message to copy it.

### Player Stats Query
- With a Hypixel API key configured, query any player's Zombies stats by name or UUID, or auto-query your current teammates each round.
- **In-game query** is **one flat overview**. Nothing to expand — read top to bottom:
    - Below the account info sit eight **four-map totals**: `Total Rounds`, `Total Wins`, `Total Kills`, `Total Revives`, `Total Knockdowns`, `Total Deaths`, `Total Windows`, `Total Doors`. Each is the four maps' Normal + Hard + RIP results added together. A map's own combined figure is left out, so the same games are not counted twice.
    - The last two rows are derived: **`Fastest Clear`** (the map and difficulty with the shortest time) and **`Best Map`**, which shows only a **map-difficulty** pair. Behind it, three figures are weighted: **most wins (primary)**, **most zombie kills (secondary)**, **shortest average clear time (least)**, at 3 : 2 : 1. Each figure is first **added up across that map's difficulties**, then compared. Alien Arcadium has only Normal, so the sum is just itself. The average time divides by **however many difficulties actually produced a time** — one difficulty cleared means a divisor of 1, not 3.
    - Both rows have a **double gate**: your best round on that map must reach its clear round, **and** you must have won at least once on that exact map and difficulty. Clear rounds: Dead End 30, Bad Blood 30, Prison 30, Alien Arcadium 105. So reaching round 30 and wiping there does not count as a clear. If neither gate passes, both rows read `-`.
    - **Every row is always drawn; a missing figure is a `-`.** If no map reports a statistic at all, that whole row (all four map slots and the `Total` slot) reads `-`. If only one map is missing it, that map counts as `0` and still goes into the sum. Rows never disappear because of missing data, so every row's position is fixed.
- **Everything is colour-coded**: titles gold, field labels grey, **all data white**. Difficulty words are tinted — Total orange, Normal green, Hard red, RIP dark red. In free query's cumulative and map-detail rows, the **four map names and `Total` are orange as well**.
- **The in-game roster has no ◀ / ▶ and no auto-rotate.** Click a teammate in the list to switch; nothing re-selects itself while you read. The selected row's highlight covers both lines of the entry.
- **Free query** (type a name yourself) keeps its **collapsible tree**. At the top, the account overview is laid out flat (name / level / karma / exp / three dates). Below sit `[+] Overall Stats`, `[+] Fastest Times`, `[+] Enemy Kills` — **in that order, and the parents carry no totals**. Click one to expand it. Inside `Overall Stats`, the first child is **`[-] Cumulative`**: eight rows identical to the in-game panel's eight cumulative items. Below it are **four map nodes** — every map gets one, even a map you have never played — and each map lists a fixed nine rows: `Best Round` first, then `Rounds Survived`, `Wins`, `Zombie Kills`, `Players Revived`, `Knockdowns`, `Deaths`, `Windows Repaired`, `Doors Opened`. A statistic the map does not report is drawn as `-` on its own row rather than removed — positions stay fixed so you can remember which row is where.
- **The eight `Cumulative` rows are merged rows.** The name is centred on the left, the figures sit on the right against the panel edge. **Four figures or fewer stay on one line; more than four wrap onto two**, both lines starting at the same left edge. Only these eight rows can wrap — the four maps' detail rows are unchanged (fixed four tiers, short numbers, no wrapping possible). Long numbers never push the layout out of shape. **The four map names and `Total` are orange**, figures white. Inside a map row, difficulty names keep their own tints: Normal green, Hard red, RIP dark red, Total orange, with `Total` last. The branch lines down the left are **drawn as continuous lines**, not `├─`-style characters, so they never break between rows. Hovering or selecting a merged row highlights **the whole block, both lines together**. Fold state is remembered per queried name.
- **`Fastest Times` lists exactly ten entries per round.** The format is **`map-difficulty`** (e.g. `Dead End-RIP`), **the whole label in that difficulty's colour** — `Dead End-Normal` fully green, `…-Hard` fully red, `…-RIP` dark red — with the time white. The order is fixed: Dead End Normal/Hard/RIP → Bad Blood Normal/Hard/RIP → Alien Arcadium Normal → Prison Normal/Hard/RIP. Alien Arcadium has no Hard or RIP records, so it only ever shows Normal. The old cross-map "Overall" line is gone.
- **On the Chinese client the Prison map is called 监狱风云** (English still says `Prison`), consistently in the query screen and the wave-sound settings.

### Roll Stats HUD
- Counts **your own** Lucky Chest rolls and shows them as a table: `Total Rolls`, then one row per reward with a small item icon, how many times you got it, and **at which roll** (so `4,6` means the 4th and 6th chest you opened this game).
- **On by default**: a fresh install (or a deleted `config/nomorezombies.json`) shows this one HUD in game. Before your first chest it reads `Total Rolls 0` over an empty table.
- Eleven rewards are tracked (the Zombies weapons and the two skills). A reward outside that list — gold, for example — is not counted. A reward whose name differs from the table is skipped rather than guessed at.
- A chest announces the roll and then confirms the claim. Both lines belong to the same roll, so opening one chest raises the total by one, not two.
- The count is per game: starting a new game resets it. **An accidental exit is not a reset** — rejoining the same game restores the counts from a temporary cache file.
- **If the count stays at zero while you open chests, that is worth reporting.** This version removed all of the mod's diagnostic logging, so the log will not say why. Send us the exact chest message you saw so the wording can be re-matched.

### Chat Filter & Sidebar
- Hide noisy chat lines: gold pickups, window repairs, hit confirmations, lucky chests, opened areas, player joins/leaves.
- Clean up the Hypixel sidebar: strip empty lines and player rows; remove the vanilla time line when the timer HUD is on.
- **Draggable scoreboard** (off by default, like every HUD element except the Roll Stats HUD): move and scale the sidebar from the HUD editor. Rows are the same cleaned-up result — only position and size change. Ticking it **off hides the sidebar completely**; the utility-HUD master switch also hides it. The old separate "Hide vanilla scoreboard" option is gone.
- The editor's scoreboard sample is a **fixed exemplar**. It only counts the "Team Stats HUD" and "Timer HUD" entries that are **actually on the canvas and enabled**: unticking "Show", or dragging them back into the component library, immediately expands the sample to the full sidebar (player rows and the complete time line come back), without waiting for Save. Opening the editor from the main menu and from inside a game shows the same form.

### Entity ESP
- Outline boxes for teammates (**red** in combat, **yellow** when downed), zombies and other hostiles (**green**), bosses (**orange**), and spawned powerups (**white**).
- Each ESP type has its own toggle and a **render mode**: **Normal** (respects occlusion, hidden behind walls) or **Through walls** (visible through obstacles).
- A global **through-wall render distance** slider (5–200 blocks) caps how far the through-wall effect reaches.

### Zombie Health Bar
- A world-space health bar above each hostile mob (`[#######------] 12/20HP`), colored by remaining health. Figures keep one decimal (`19.6/20`), the same reading the damage numbers use, so a number popping off a mob and the bar above it always agree.
- **Bosses get their own palette**: purple above 75 % health, then yellow, orange, red as it drops. The bar is also lifted higher, clearing the boss's own name tag. Ordinary mobs use the green / yellow / red ladder.

### AA Auto Command
- On Alien Arcadium: a round-command HUD (giant spawn / Old One spawn / difficulty / recommended points) plus an automatic chat broadcast at the start of each round.

### Lightning Rod Cooldown HUD
- On Alien Arcadium: a 4-slot HUD tracking each lightning rod's 20-second cooldown, colored by remaining time.

### Status Effect HUD
- Inside a Zombies game, replaces the vanilla top-right potion-effect icon grid with a text list. Each effect takes two lines: `[icon] name level` first, then the remaining time (`M:SS`, `∞` for an infinite effect, red at 3 seconds or less).
- Soonest-to-expire sits on top. The whole block disappears when you have no effects. Levels use Roman numerals; level I is left off, as in the vanilla tooltip.

### QoL Toolkit
- **Smooth zoom**: key-based, with scroll-wheel fine tuning, easing curves, and sensitivity compensation.
- **Always sneak** / **always sprint** / **gamma override** (forced brightness).
- **Free camera**: detach the camera from your body to scout around.
- **Hide nearby players**: players within 1.4 blocks are not rendered.
- **Hide the vanilla boss bar.** The vanilla scoreboard is now hidden through the draggable scoreboard's own element toggle, above.
- **Right-click fires only**: blocks non-firing right-click interactions.
- **Remove all particles** and **no fire overlay**.
- **CPS counter**: left/right clicks per second.
- **Damage numbers**: red digits pop off a mob losing health, green digits on healing. Digits only — an instakill is also just a number. Normal hides behind walls; Through walls shows them behind obstacles, up to the through-wall render distance.

### HUD Editor
- Drag, scale, and toggle each HUD element individually from the config screen.
- **A layout is resolution-independent, and a position is exactly where you drop it.** No more snapping to an edge behind your back: drag a HUD to 1 pixel from an edge and it stays those 1 pixel away, so you can fine-tune it; drag it all the way onto an edge and it lands exactly flush, and it stays flush after a window resize, a GUI-scale change, or on a different machine. A centred element stays centred, and nothing can end up off-screen. Need it exactly centred? Use the "Place" button.
- **You can drag an element down into the lower half of the component list.** It follows the pointer the whole way instead of stopping at the bottom of the preview area — which matters because the list is taller than the preview. Dropping it there behaves exactly as before: past the removal threshold it leaves the canvas; short of that the element goes flush left and keeps the height you dropped it at.
- **Every edge of the drag area is a real wall, and the walls do not move under your cursor.** You cannot drag an element off the left of the screen — pushing it left stops it exactly flush against the component list's left edge, with nothing hidden. The bottom of the preview area stops an element along its **whole** width; the only place an element may go lower is inside the component list column, which is taller. Dragging up and down across the top of the list no longer steps by a pixel, and dragging along the bottom-right corner no longer flicks the element up or down into the canvas. An element wider than the list column still cannot go below the preview's bottom edge — there is no list column to be in — but it can still be dragged into the list to be removed.
- The list holds **eleven elements — ten utility HUDs plus the draggable scoreboard** (off by default, same as the other HUD elements). The editor's scoreboard box always shows a representative sample so you can position it from anywhere. The Roll Stats HUD is the exception: it ships placed and visible.
- The controls live in an action area **directly below the canvas**, in two rows: "Show" and "Place/Remove" plus the scale slider, then "Reset", "Save", "Cancel". The top bar keeps only the title and the status icon in its right corner.
- Hovering a library row gives a tooltip with a **blank line** between the text and the HUD preview so the preview is easier to read. Dragging a row out of the library shows the HUD's **real appearance** under the cursor, so you can tell whether it fits there before letting go.

---

## Installation

1. Install **Fabric Loader 0.16.14** and create a **1.21.4** instance.
2. Put `NoMoreZombies-0.2.0-pre.jar` into your `mods/` folder.
3. Dependencies (install alongside):
    - **Fabric API**
    - **MaLiLib** (external dependency, install it separately)
    - **ModMenu** (optional; lets you open the config from the mod list)

## Configuration

- Open the config from ModMenu (NoMoreZombies > Config), or press the default hotkey **Z+X**.
- Every feature toggle is **off by default** (Tweakeroo-style), with one exception: the utility-HUD master switch ships on, and the Roll Stats HUD ships placed and visible, so a fresh install shows that single element. Damage numbers are off by default too. Bind a hotkey to any toggle in the config screen to flip it in-game instantly.
- The config file is `config/nomorezombies.json` (plain text, editable by hand).
- Data tables (wave times, boss rounds, powerup patterns, AA round details) hot-reload with **F3+T** in-game.

## Credits

Main features were implemented with reference to:

- [ShowSpawnTime](https://github.com/Seosean/ShowSpawnTime)
- [NotEnoughZombies](https://github.com/PingIsFun/NotEnoughZombies)
- [Hypixel-Zombies-Mod](https://github.com/FairCauth/Hypixel-Zombies-Mod)

Secondary features were implemented with reference to:

- [Zoomify](https://github.com/isXander/Zoomify)
- [tweakeroo](https://github.com/maruohon/tweakeroo)
- [TslatEntityStatus](https://github.com/Tslat/TslatEntityStatus)

---

## License

[LGPL-3.0-only](LICENSE) — see the `LICENSE` file.