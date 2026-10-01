package cn.gsfy.nmz.client.config.hud;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.data.model.MapId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cn.gsfy.nmz.client.config.GlobalConfig;

/**
 * HUD registry--which HUDs the editor picks up, in what order they list,
 * and where each preview comes from, all decided here
 *
 * <p><b>All the work of adding a HUD</b>:
 * <ol>
 *  <li>expose size on the renderer ({@code hudWidth}/{@code hudHeight}, or
 *  the preview-measuring {@code previewWidth}/{@code previewHeight}) plus a
 *  preview painter;</li>
 *  <li>add the five {@code X_/Y_/SCALE_/VISIBLE_/PLACED_} keys to
 *  {@code GlobalConfig.Hud};</li>
 *  <li>append one {@code register(HudEntry.of(...)…build())} in
 *  {@link #registerBuiltins()}</li>
 * </ol>
 * <p>The editor, component library, bottom-right config panel, and canvas
 * all stay untouched--no concrete HUD class name may appear in the editor,
 * and the declaration sites are what keeps that contract intact
 *
 * <p><b>Dependency direction</b>: the registry depends on the renderers;
 * renderers <b>never</b> depend on the registry or the editor--otherwise
 * you get a package-level cycle and couple live rendering to the editor
 *
 * <p><b>When to register</b>: {@link #registerBuiltins()} must run once
 * before any {@link HUDEditor} is constructed
 * (owned by {@code NoMoreZombiesClient.onInitializeClient}). Registration
 * is <b>idempotent</b>: a repeat call or repeat id logs one WARN and adds
 * no duplicate
 */
public final class RegisterHUD {

    /** Registration order = component library order ({@link LinkedHashMap} preserves it) */
    private static final Map<String, HudEntry> ENTRIES = new LinkedHashMap<>();

    private static boolean bootstrapped;

    private RegisterHUD() {
    }

    /**
     * Registers a HUD--the public entry point for future extensions
     *
     * @param entry HUD description (built with {@link HudEntry#of(String, String)})
     * @return the passed-in {@code entry}, for chained calls
     */
    public static HudEntry register(HudEntry entry) {
        HudEntry existing = ENTRIES.get(entry.id);
        if (existing != null) {
            NoMoreZombies.LOGGER.warn("[HUD注册] id '{}' 已存在，忽略本次重复注册", entry.id);
            return existing;
        }
        ENTRIES.put(entry.id, entry);
        return entry;
    }

    /**
     * Lists all registered HUDs (registration order)
     *
     * @return a fresh immutable list on every call (a copy in registration
     * order); the component library lays out by it
     */
    public static List<HudEntry> entries() {
        return List.copyOf(ENTRIES.values());
    }

    /**
     * Registers the mod's 11 built-in HUDs--idempotent, repeat calls
     * return at once
     *
     * <p>Only "which accessors a HUD has" lives here, no layout or
     * interaction: the position and scale accessors point at the existing
     * {@code GlobalConfig.Hud} keys, and keeping the key names unchanged
     * keeps the config file backward compatible. There is no "default
     * position resolver": the configured number is the anchor ratio,
     * clamped into 0-1 by {@code HUDEditor.resolveWorkX}; centering a HUD
     * on the canvas is {@code placeAtCenter} (a placement action) writing
     * 0.5, not sentinel parsing
     */
    public static void registerBuiltins() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;

        register(HudEntry.of("spawntime", "nomorezombies.hudeditor.element.spawntime")
                .position(GlobalConfig.Hud.X_SPAWN_TIME::getDoubleValue, GlobalConfig.Hud.X_SPAWN_TIME::setDoubleValue,
                        GlobalConfig.Hud.Y_SPAWN_TIME::getDoubleValue, GlobalConfig.Hud.Y_SPAWN_TIME::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_SPAWN_TIME::getDoubleValue, GlobalConfig.Hud.SCALE_SPAWN_TIME::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_SPAWN_TIME::getBooleanValue, GlobalConfig.Hud.VISIBLE_SPAWN_TIME::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_SPAWN_TIME::getBooleanValue, GlobalConfig.Hud.PLACED_SPAWN_TIME::setBooleanValue)
                // No reserve declared: the hotbar occupies only the lower-middle
                // strip, while a reserve closes an entire edge--that would stop
                // this table from reaching any of the four corners. Where it
                // goes and whether to dodge the hotbar is the player's call
                // (factory X=1.0 already lands bottom-right, clear of the hotbar)
                .preview(ctx -> HudSampleData.spawnTime())
                .build());

        register(HudEntry.of("powerup", "nomorezombies.hudeditor.element.powerup")
                .position(GlobalConfig.Hud.X_POWERUP::getDoubleValue, GlobalConfig.Hud.X_POWERUP::setDoubleValue,
                        GlobalConfig.Hud.Y_POWERUP::getDoubleValue, GlobalConfig.Hud.Y_POWERUP::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_POWERUP::getDoubleValue, GlobalConfig.Hud.SCALE_POWERUP::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_POWERUP::getBooleanValue, GlobalConfig.Hud.VISIBLE_POWERUP::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_POWERUP::getBooleanValue, GlobalConfig.Hud.PLACED_POWERUP::setBooleanValue)
                .preview(ctx -> HudSampleData.powerup())
                .build());

        register(HudEntry.of("teamstats", "nomorezombies.hudeditor.element.teamstats")
                .position(GlobalConfig.Hud.X_TEAM_STATS::getDoubleValue, GlobalConfig.Hud.X_TEAM_STATS::setDoubleValue,
                        GlobalConfig.Hud.Y_TEAM_STATS::getDoubleValue, GlobalConfig.Hud.Y_TEAM_STATS::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_TEAM_STATS::getDoubleValue, GlobalConfig.Hud.SCALE_TEAM_STATS::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_TEAM_STATS::getBooleanValue, GlobalConfig.Hud.VISIBLE_TEAM_STATS::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_TEAM_STATS::getBooleanValue, GlobalConfig.Hud.PLACED_TEAM_STATS::setBooleanValue)
                .preview(ctx -> HudSampleData.teamStats())
                .build());

        register(HudEntry.of("gametime", "nomorezombies.hudeditor.element.gametime")
                .position(GlobalConfig.Hud.X_GAME_TIME::getDoubleValue, GlobalConfig.Hud.X_GAME_TIME::setDoubleValue,
                        GlobalConfig.Hud.Y_GAME_TIME::getDoubleValue, GlobalConfig.Hud.Y_GAME_TIME::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_GAME_TIME::getDoubleValue, GlobalConfig.Hud.SCALE_GAME_TIME::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_GAME_TIME::getBooleanValue, GlobalConfig.Hud.VISIBLE_GAME_TIME::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_GAME_TIME::getBooleanValue, GlobalConfig.Hud.PLACED_GAME_TIME::setBooleanValue)
                .preview(ctx -> HudSampleData.gameTime())
                .build());

        register(HudEntry.of("lrqueue", "nomorezombies.hudeditor.element.lrqueue")
                .position(GlobalConfig.Hud.X_LRQUEUE::getDoubleValue, GlobalConfig.Hud.X_LRQUEUE::setDoubleValue,
                        GlobalConfig.Hud.Y_LRQUEUE::getDoubleValue, GlobalConfig.Hud.Y_LRQUEUE::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_LRQUEUE::getDoubleValue, GlobalConfig.Hud.SCALE_LRQUEUE::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_LRQUEUE::getBooleanValue, GlobalConfig.Hud.VISIBLE_LRQUEUE::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_LRQUEUE::getBooleanValue, GlobalConfig.Hud.PLACED_LRQUEUE::setBooleanValue)
                .availableIn(map -> map == MapId.ALIEN_ARCADIUM)
                .preview(ctx -> HudSampleData.lrQueue())
                .build());

        register(HudEntry.of("aaautocommand", "nomorezombies.hudeditor.element.aaautocommand")
                .position(GlobalConfig.Hud.X_AA_AUTO_COMMAND::getDoubleValue, GlobalConfig.Hud.X_AA_AUTO_COMMAND::setDoubleValue,
                        GlobalConfig.Hud.Y_AA_AUTO_COMMAND::getDoubleValue, GlobalConfig.Hud.Y_AA_AUTO_COMMAND::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_AA_AUTO_COMMAND::getDoubleValue, GlobalConfig.Hud.SCALE_AA_AUTO_COMMAND::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_AA_AUTO_COMMAND::getBooleanValue, GlobalConfig.Hud.VISIBLE_AA_AUTO_COMMAND::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_AA_AUTO_COMMAND::getBooleanValue, GlobalConfig.Hud.PLACED_AA_AUTO_COMMAND::setBooleanValue)
                .availableIn(map -> map == MapId.ALIEN_ARCADIUM)
                .preview(ctx -> HudSampleData.aaAutoCommand())
                .build());

        register(HudEntry.of("cps", "nomorezombies.hudeditor.element.cps")
                .position(GlobalConfig.Hud.X_CPS::getDoubleValue, GlobalConfig.Hud.X_CPS::setDoubleValue,
                        GlobalConfig.Hud.Y_CPS::getDoubleValue, GlobalConfig.Hud.Y_CPS::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_CPS::getDoubleValue, GlobalConfig.Hud.SCALE_CPS::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_CPS::getBooleanValue, GlobalConfig.Hud.VISIBLE_CPS::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_CPS::getBooleanValue, GlobalConfig.Hud.PLACED_CPS::setBooleanValue)
                .preview(ctx -> HudSampleData.cps())
                .build());

        register(HudEntry.of("globaloverview", "nomorezombies.hudeditor.element.globaloverview")
                .position(GlobalConfig.Hud.X_GLOBAL_OVERVIEW::getDoubleValue, GlobalConfig.Hud.X_GLOBAL_OVERVIEW::setDoubleValue,
                        GlobalConfig.Hud.Y_GLOBAL_OVERVIEW::getDoubleValue, GlobalConfig.Hud.Y_GLOBAL_OVERVIEW::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_GLOBAL_OVERVIEW::getDoubleValue, GlobalConfig.Hud.SCALE_GLOBAL_OVERVIEW::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_GLOBAL_OVERVIEW::getBooleanValue, GlobalConfig.Hud.VISIBLE_GLOBAL_OVERVIEW::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_GLOBAL_OVERVIEW::getBooleanValue, GlobalConfig.Hud.PLACED_GLOBAL_OVERVIEW::setBooleanValue)
                // Under the anchor convention "travel" is computed from the width
                // measured this frame, so a right-pinned HUD always stays on
                // screen--no right-edge clamp needed
                .preview(ctx -> HudSampleData.globalOverview())
                .build());

        register(HudEntry.of("statuseffects", "nomorezombies.hudeditor.element.statuseffects")
                .position(GlobalConfig.Hud.X_STATUS_EFFECTS::getDoubleValue, GlobalConfig.Hud.X_STATUS_EFFECTS::setDoubleValue,
                        GlobalConfig.Hud.Y_STATUS_EFFECTS::getDoubleValue, GlobalConfig.Hud.Y_STATUS_EFFECTS::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_STATUS_EFFECTS::getDoubleValue, GlobalConfig.Hud.SCALE_STATUS_EFFECTS::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_STATUS_EFFECTS::getBooleanValue, GlobalConfig.Hud.VISIBLE_STATUS_EFFECTS::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_STATUS_EFFECTS::getBooleanValue, GlobalConfig.Hud.PLACED_STATUS_EFFECTS::setBooleanValue)
                .preview(ctx -> HudSampleData.statusEffects())
                .build());

        register(HudEntry.of("scoreboard", "nomorezombies.hudeditor.element.scoreboard")
                .position(GlobalConfig.Hud.X_SCOREBOARD::getDoubleValue, GlobalConfig.Hud.X_SCOREBOARD::setDoubleValue,
                        GlobalConfig.Hud.Y_SCOREBOARD::getDoubleValue, GlobalConfig.Hud.Y_SCOREBOARD::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_SCOREBOARD::getDoubleValue, GlobalConfig.Hud.SCALE_SCOREBOARD::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_SCOREBOARD::getBooleanValue, GlobalConfig.Hud.VISIBLE_SCOREBOARD::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_SCOREBOARD::getBooleanValue, GlobalConfig.Hud.PLACED_SCOREBOARD::setBooleanValue)
                // 1px reserve: anchor 1.0 lands at "screenWidth - width - 1", exactly
                // the native background box's right edge (W-1), so the pinned-right
                // level overlaps the native sidebar pixel-for-pixel--one constant
                // is all it takes
                .reserve(1, 0)
                // The scoreboard paints its own two background segments: see
                // ScoreboardHudRenderer.drawInternal. The 25% black layer would
                // gray its colors out, so it declares ownBackground to skip
                // the backing
                .ownBackground()
                .preview(HudSampleData::scoreboard)
                .build());

        register(HudEntry.of("rollstats", "nomorezombies.hudeditor.element.rollstats")
                .position(GlobalConfig.Hud.X_ROLL_STATS::getDoubleValue, GlobalConfig.Hud.X_ROLL_STATS::setDoubleValue,
                        GlobalConfig.Hud.Y_ROLL_STATS::getDoubleValue, GlobalConfig.Hud.Y_ROLL_STATS::setDoubleValue)
                .scale(GlobalConfig.Hud.SCALE_ROLL_STATS::getDoubleValue, GlobalConfig.Hud.SCALE_ROLL_STATS::setDoubleValue)
                .visible(GlobalConfig.Hud.VISIBLE_ROLL_STATS::getBooleanValue, GlobalConfig.Hud.VISIBLE_ROLL_STATS::setBooleanValue)
                .placed(GlobalConfig.Hud.PLACED_ROLL_STATS::getBooleanValue, GlobalConfig.Hud.PLACED_ROLL_STATS::setBooleanValue)
                .preview(ctx -> HudSampleData.rollStats())
                .build());
    }
}
