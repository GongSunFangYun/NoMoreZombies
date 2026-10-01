package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.config.hud.HUDEditor;
import cn.gsfy.nmz.client.config.hud.HudCanvasMode;
import cn.gsfy.nmz.client.config.hud.HudCanvas;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Global config holder (MaLiLib)--the single home for every setting: grouped
 * by feature into static config constants, edited in the UI by
 * {@link GlobalConfigGui}, persisted to
 * {@code config/nomorezombies.json} (MaLiLib format, written per group)
 *
 * <p>Anything with an on/off semantic becomes a {@link ConfigBooleanHotkeyed};
 * with a hotkey bound, it toggles in game with one keypress (toggle message
 * included) instead of a menu visit every time. Items without a switch
 * semantic--sound IDs, HUD coordinates, scale--each take their natural type:
 * ConfigString, ConfigDouble, ConfigOptionList
 */
public class GlobalConfig implements IConfigHandler {

    private static final String CONFIG_FILE_NAME = NoMoreZombies.MOD_ID + ".json";

    // ----Translation key prefixes: .apply(prefix) prepends prefix to
    // name/prettyName/comment----
    // forming translation keys (prefix.name/prettyName/comment.<cleanName>);
    // the UI looks words up in the client language
    private static final String PREFIX_SST = "nomorezombies.config.sst";
    private static final String PREFIX_POWERUP = "nomorezombies.config.powerup";
    private static final String PREFIX_RECORD = "nomorezombies.config.record";
    private static final String PREFIX_AA_AUTO_COMMAND = "nomorezombies.config.aaautocommand";
    private static final String PREFIX_QOL = "nomorezombies.config.qol";
    private static final String PREFIX_HIDE = "nomorezombies.config.hide";
    private static final String PREFIX_ZOOM = "nomorezombies.config.zoom";
    private static final String PREFIX_SNEAK = "nomorezombies.config.sneak";
    private static final String PREFIX_GAMMA = "nomorezombies.config.gamma";
    private static final String PREFIX_FREECAM = "nomorezombies.config.freecamera";

    // ----Wave timing (spawntimes)----

    /**
     * Wave timing config group: wave spawn / final-wave alert sounds (ID
     * and pitch), the final-wave countdown, and the whole-run color alert,
     * all under one roof
     *
     * <p>The master switch for wave sounds is {@link QoL#WAVE_SOUND_ENABLED};
     * map coverage is per-map via the four switches below--all four on means
     * all four maps sound, turn off just the map you don't want. This
     * group's own two switches, final-wave countdown and AA color alert,
     * are independent of that master switch
     */
    public static class Spawntimes {
        /** Whether per-wave spawn sounds play on Alien Arcadium, default on */
        public static final ConfigBoolean WAVE_SOUND_AA =
                new ConfigBoolean("waveSoundAA", true).apply(PREFIX_SST);
        /** Whether per-wave spawn sounds play on Dead End, default on */
        public static final ConfigBoolean WAVE_SOUND_DE =
                new ConfigBoolean("waveSoundDE", true).apply(PREFIX_SST);
        /** Whether per-wave spawn sounds play on Bad Blood, default on */
        public static final ConfigBoolean WAVE_SOUND_BB =
                new ConfigBoolean("waveSoundBB", true).apply(PREFIX_SST);
        /** Whether per-wave spawn sounds play on Prison, default on */
        public static final ConfigBoolean WAVE_SOUND_PRISON =
                new ConfigBoolean("waveSoundPrison", true).apply(PREFIX_SST);
        /** Sound ID of the regular wave spawn alert (default: note block pling) */
        public static final ConfigString PRECEDED_WAVE_SOUND =
                new ConfigString("precededWaveSound", "minecraft:block.note_block.pling").apply(PREFIX_SST);
        /** Pitch of the regular wave alert (0.0-2.0, default 2.0) */
        public static final ConfigDouble PRECEDED_WAVE_PITCH =
                new ConfigDouble("precededWavePitch", 2.0, 0.0, 2.0, true).apply(PREFIX_SST);
        /** Sound ID of the final wave (last spawn wave of the round) alert (default: experience orb pickup) */
        public static final ConfigString LAST_WAVE_SOUND =
                new ConfigString("lastWaveSound", "minecraft:entity.experience_orb.pickup").apply(PREFIX_SST);
        /** Pitch of the final wave alert (0.0-2.0, default 0.5) */
        public static final ConfigDouble LAST_WAVE_PITCH =
                new ConfigDouble("lastWavePitch", 0.5, 0.0, 2.0, true).apply(PREFIX_SST);
        /**
         * Final-wave countdown (hotkeyed, works on every map, not just Dead
         * End / Bad Blood): plays a 3-2-1 countdown before each round's
         * final wave, signaling "finish this wave and the cleanup phase
         * starts"
         */
        public static final ConfigBooleanHotkeyed FINAL_WAVE_COUNTDOWN =
                new ConfigBooleanHotkeyed("finalWaveCountDown", false, "").apply(PREFIX_SST);
        /** Sound ID of each 3-2-1 countdown beep (default: note block pling) */
        public static final ConfigString COUNTDOWN_SOUND =
                new ConfigString("countDownSound", "minecraft:block.note_block.pling").apply(PREFIX_SST);
        /** Pitch of each 3-2-1 countdown beep (0.0-2.0, default 1.5) */
        public static final ConfigDouble COUNTDOWN_PITCH =
                new ConfigDouble("countDownPitch", 1.5, 0.0, 2.0, true).apply(PREFIX_SST);
        /**
         * Master switch for danger-wave coloring of AA wave rows (hotkeyed,
         * default off): when on, the wave HUD recolors upcoming waves on
         * Alien Arcadium per the aa_color_alert data table--pure giant
         * waves blue, to1-exclusive waves green, to1 + giant waves red, so
         * the boss wave stands out at a glance. Other maps are unaffected
         */
        public static final ConfigBooleanHotkeyed COLOR_ALERT =
                new ConfigBooleanHotkeyed("colorAlert", false, "").apply(PREFIX_SST);

        // The OPTIONS ordering (switches first, pitches mid, sound IDs last)
        // only sets the write order inside the config file: the UI's display
        // order is aggregated by GlobalConfigGui.getConfigs() and has nothing
        // to do with it
        /** The 12 items written to the {@code Spawntimes} section; order here only governs persistence */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                WAVE_SOUND_AA,
                WAVE_SOUND_DE,
                WAVE_SOUND_BB,
                WAVE_SOUND_PRISON,
                FINAL_WAVE_COUNTDOWN,
                COLOR_ALERT,
                PRECEDED_WAVE_PITCH,
                LAST_WAVE_PITCH,
                COUNTDOWN_PITCH,
                PRECEDED_WAVE_SOUND,
                LAST_WAVE_SOUND,
                COUNTDOWN_SOUND
        );
    }

    // ----Powerup system (powerups)----

    /**
     * Powerup system config group: how powerup warnings and alerts are
     * output
     *
     * <p>Only two items, and neither offers an "off" option--warning and
     * alert share one switch: when {@link #POWERUP_PREDICT} (hotkeyed) is
     * off, the two output items below simply have nothing to do
     */
    public static class Powerups {
        /**
         * Master switch for powerup prediction / drop alerts (hotkeyed,
         * default off): when off, both the round-start forecast and the "X
         * has dropped" alerts stop--this group has no separate output
         * switches
         */
        public static final ConfigBooleanHotkeyed POWERUP_PREDICT =
                new ConfigBooleanHotkeyed("powerupPredict", false, "").apply(PREFIX_POWERUP);
        /**
         * Where powerup alerts go: self / team (/pc) / public chat (/ac)--
         * "self" is the quietest, public chat stays visible all game
         */
        public static final ConfigOptionList ALERT_OUTPUT =
                new ConfigOptionList("alertOutput", AlertOutput.SELF).apply(PREFIX_POWERUP);

        /**
         * The two items written to the {@code Powerups} section; in the UI
         * they sit on the QoL page and the global page respectively, display
         * order is GlobalConfigGui's call, and this list only sets
         * persistence order
         */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                POWERUP_PREDICT,
                ALERT_OUTPUT
        );
    }

    // ----Round timing records (recorder)----

    /**
     * Round timing record config group: how often round stats are announced
     * in chat, one setting shared by all maps
     *
     * <p>Only frequency lives here, not on/off--the switch is
     * {@link QoL#RECORD_ENABLED} (hotkeyed), so this option carries no "off"
     * entry, keeping one semantic on one lock. One setting, no per-map
     * split: AA is a 105-round marathon while Dead End / Bad Blood / Prison
     * cap at 30, so the same setting naturally logs a different count per
     * game on each map type--to even the pace, pick "every 5 rounds" for
     * the short maps and "every 10 rounds" for AA
     */
    public static class Record {
        /** Chat announcement frequency for round stats (see {@link RecordTiming}, default: every round) */
        public static final ConfigOptionList ROUNDS_RECORD =
                new ConfigOptionList("roundsRecord", RecordTiming.ALL).apply(PREFIX_RECORD);

        /** The frequency item written to the {@code Record} section; persistence reads only this collection */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ROUNDS_RECORD
        );
    }

    // ----Alien Arcadium auto command (AA auto command)----

    /**
     * Alien Arcadium (AA) auto command config group: announcement output
     * channel and message template
     *
     * <p>On/off is owned by {@link QoL#AA_AUTO_COMMAND_ENABLED} (hotkeyed);
     * here only "where to say it, what to say". The template also supports
     * localized defaults that rewrite themselves on language switch; see
     * {@link I18nTemplateConfig}
     */
    public static class AAAutoCommand {
        /**
         * Output channel: self only / team (/pc) / public chat (/ac)--
         * reuses {@link AlertOutput} directly (same SELF/PARTY/CHAT, no
         * "off"--the switch is owned by AA_AUTO_COMMAND_ENABLED)
         */
        public static final ConfigOptionList OUTPUT =
                new ConfigOptionList("output", AlertOutput.SELF).apply(PREFIX_AA_AUTO_COMMAND);
        /**
         * Output message template (text field): supports four variables,
         * {round}/{point}/{boss}/{difficulty}; an empty string or an
         * unknown variable counts as invalid and silently falls back to the
         * default template--speak generically rather than error out.
         * Default/reset follows the client language
         * ({@code nomorezombies.aaautocommand.defaultTemplate}) and
         * rewrites itself on language switch; see {@link I18nTemplateConfig}
         */
        public static final I18nTemplateConfig TEMPLATE =
                new I18nTemplateConfig("template").apply(PREFIX_AA_AUTO_COMMAND);

        /** The output channel and localized template written to the {@code AAAutoCommand} section */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OUTPUT,
                TEMPLATE
        );
    }

    // ----HUD coordinates and scale (edited only in HUDEditor, not in the config UI)----

    /**
     * HUD layout config group: each HUD element's <b>anchor ratio</b>
     * (0.0-1.0), scale (0.5-2.0), and independent visibility
     *
     * <p>This whole group stays out of the config UI and is edited only by
     * dragging in {@link HUDEditor}
     *
     * <p><b>Coordinate semantics (incompatible with the legacy config file
     * format)</b>: 0.0 = leading edge (left/top), 0.5 = centered, 1.0 =
     * trailing edge (right/bottom); in-between values walk the matching
     * percentage of the available travel. Travel
     * {@code [reserve, screenW/H - contentSize - reserve]} is computed live
     * by the renderer each frame, so one number lands at the same relative
     * position at any resolution and any GUI scale--pinned right stays
     * pinned right, centered stays centered
     *
     * <p>Factory defaults therefore use only three levels (0/0.5/1):
     * negatives are outside the valid range and get folded into 0-1 on load
     * by {@link #normalizeAnchorKeys()}
     */
    public static class Hud {
        // Default anchors are the three levels (0 leading edge / 0.5 centered /
        // 1 trailing edge): they say only which edge, not how many pixels from
        // it--reserves are declared per HUD by reserveX/reserveY (see
        // RegisterHUD) and are resolution-independent
        /** Spawn time HUD X anchor (1.0 = pinned right) */
        public static final ConfigDouble X_SPAWN_TIME = new ConfigDouble("xSpawnTime", 1.0, 0.0, 1.0);
        /** Spawn time HUD Y anchor (1.0 = pinned bottom, above the hotbar) */
        public static final ConfigDouble Y_SPAWN_TIME = new ConfigDouble("ySpawnTime", 1.0, 0.0, 1.0);
        /** Powerup HUD X anchor (0.0 = pinned left) */
        public static final ConfigDouble X_POWERUP = new ConfigDouble("xPowerup", 0.0, 0.0, 1.0);
        /** Powerup HUD Y anchor (0.5 = vertically centered) */
        public static final ConfigDouble Y_POWERUP = new ConfigDouble("yPowerup", 0.5, 0.0, 1.0);
        /** Team stats HUD X anchor (0.0 = pinned left) */
        public static final ConfigDouble X_TEAM_STATS = new ConfigDouble("xTeamStats", 0.0, 0.0, 1.0);
        /** Team stats HUD Y anchor (0.0 = pinned top) */
        public static final ConfigDouble Y_TEAM_STATS = new ConfigDouble("yTeamStats", 0.0, 0.0, 1.0);
        /** Spawn time HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_SPAWN_TIME = new ConfigDouble("scaleSpawnTime", 1.0, 0.5, 2.0, true);
        /** Powerup HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_POWERUP = new ConfigDouble("scalePowerup", 1.0, 0.5, 2.0, true);
        /** Team stats HUD scale (0.5-2.0, default 1.0010775862068966) */
        public static final ConfigDouble SCALE_TEAM_STATS = new ConfigDouble("scaleTeamStats", 1.0010775862068966, 0.5, 2.0, true);
        /** Time HUD X anchor (1.0 = pinned right) */
        public static final ConfigDouble X_GAME_TIME = new ConfigDouble("xGameTime", 1.0, 0.0, 1.0);
        /** Time HUD Y anchor (0.0 = pinned top) */
        public static final ConfigDouble Y_GAME_TIME = new ConfigDouble("yGameTime", 0.0, 0.0, 1.0);
        /** Time HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_GAME_TIME = new ConfigDouble("scaleGameTime", 1.0, 0.5, 2.0, true);
        /** Lightning rod queue HUD X anchor (0.5 = horizontally centered) */
        public static final ConfigDouble X_LRQUEUE = new ConfigDouble("xLrQueue", 0.5, 0.0, 1.0);
        /** Lightning rod queue HUD Y anchor (1.0 = pinned bottom, above the hotbar) */
        public static final ConfigDouble Y_LRQUEUE = new ConfigDouble("yLrQueue", 1.0, 0.0, 1.0);
        /** Lightning rod queue HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_LRQUEUE = new ConfigDouble("scaleLrQueue", 1.0, 0.5, 2.0, true);
        /** AA auto command HUD X anchor (0.0 = pinned left) */
        public static final ConfigDouble X_AA_AUTO_COMMAND = new ConfigDouble("xAAAutoCommand", 0.0, 0.0, 1.0);
        /** AA auto command HUD Y anchor (1.0 = pinned bottom, above the hotbar) */
        public static final ConfigDouble Y_AA_AUTO_COMMAND = new ConfigDouble("yAAAutoCommand", 1.0, 0.0, 1.0);
        /** AA auto command HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_AA_AUTO_COMMAND = new ConfigDouble("scaleAAAutoCommand", 1.0, 0.5, 2.0, true);
        /** CPS HUD X anchor (1.0 = pinned right) */
        public static final ConfigDouble X_CPS = new ConfigDouble("xCps", 1.0, 0.0, 1.0);
        /** CPS HUD Y anchor (0.5 = vertically centered) */
        public static final ConfigDouble Y_CPS = new ConfigDouble("yCps", 0.5, 0.0, 1.0);
        /** CPS HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_CPS = new ConfigDouble("scaleCps", 1.0, 0.5, 2.0, true);
        /** Global overview HUD X anchor (1.0 = pinned right) */
        public static final ConfigDouble X_GLOBAL_OVERVIEW = new ConfigDouble("xGlobalOverview", 1.0, 0.0, 1.0);
        /** Global overview HUD Y anchor (0.0 = pinned top) */
        public static final ConfigDouble Y_GLOBAL_OVERVIEW = new ConfigDouble("yGlobalOverview", 0.0, 0.0, 1.0);
        /** Global overview HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_GLOBAL_OVERVIEW = new ConfigDouble("scaleGlobalOverview", 1.0, 0.5, 2.0, true);
        /** Status effects HUD X anchor (1.0 = pinned right) */
        public static final ConfigDouble X_STATUS_EFFECTS = new ConfigDouble("xStatusEffects", 1.0, 0.0, 1.0);
        /** Status effects HUD Y anchor: 0.45 = 45% of the travel (around the vanilla effect icons) */
        public static final ConfigDouble Y_STATUS_EFFECTS = new ConfigDouble("yStatusEffects", 0.45, 0.0, 1.0);
        /** Status effects HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_STATUS_EFFECTS = new ConfigDouble("scaleStatusEffects", 1.0, 0.5, 2.0, true);
        // Scoreboard HUD (a draggable version of the native right sidebar):
        // default pinned right + centered on the travel. With a 1px reserve,
        // "pinned right" coincides pixel-for-pixel with the native right edge
        // (W-1); Y takes the middle of the travel (the vanilla sidebar centers
        // the text lines, not the whole box, so the native position sits about
        // 14px higher--that small difference is handed to the player along
        // with draggability, not chased with a one-off offset)
        /** Scoreboard HUD X anchor (1.0 = pinned right, 1px reserve aligns with the native right edge) */
        public static final ConfigDouble X_SCOREBOARD = new ConfigDouble("xScoreboard", 1.0, 0.0, 1.0);
        /** Scoreboard HUD Y anchor (0.5 = vertically centered) */
        public static final ConfigDouble Y_SCOREBOARD = new ConfigDouble("yScoreboard", 0.5, 0.0, 1.0);
        /** Scoreboard HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_SCOREBOARD = new ConfigDouble("scaleScoreboard", 1.0, 0.5, 2.0, true);
        /** Roll stats HUD X anchor (0.0 = pinned left) */
        public static final ConfigDouble X_ROLL_STATS = new ConfigDouble("xRollStats", 0.0, 0.0, 1.0);
        /** Roll stats HUD Y anchor (0.0 = pinned top) */
        public static final ConfigDouble Y_ROLL_STATS = new ConfigDouble("yRollStats", 0.0, 0.0, 1.0);
        /** Roll stats HUD scale (0.5-2.0, default 1.0) */
        public static final ConfigDouble SCALE_ROLL_STATS = new ConfigDouble("scaleRollStats", 1.0, 0.5, 2.0, true);

        /**
         * Whether the client language is Simplified Chinese, <b>public</b>
         * on purpose: {@code ScoreboardHudRenderer}'s offline samples must
         * pick the Chinese or English set of captured real sidebar text per
         * language (Hypixel sidebar text follows the in-game language), so
         * the language test must have exactly one implementation.
         * Falls back to English while the language manager is not ready
         * (conservative: English is the baseline set)
         */
        public static boolean isChineseClient() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getLanguageManager() == null) {
                return false;
            }
            return "zh_cn".equals(client.getLanguageManager().getLanguage());
        }

        // Per-HUD independent visibility (edited only in HUDEditor, not in the
        // config UI). Out of the box only roll stats shows: it is placed on
        // the canvas and visible by default, so a fresh install sees one
        // block right away; the other ten components ship "not on screen",
        // waiting in the left library to be dragged up--where and whether is
        // entirely the player's call. The master switch HUD_MASTER defaults
        // on; turning it off hides all HUDs including roll stats
        //
        // The render condition is decided at a single point by the {@code *On()}
        // predicates below: master switch (QoL.HUD_MASTER) && placed (PLACED_*)
        // && this visibility (VISIBLE_*), all three required. <b>Placement is
        // a necessary condition</b>--a HUD never dragged onto the canvas is
        // never drawn in game, otherwise unplaced elements would crowd the
        // left screen edge at their anchor ratios.
        // (Existing true/false values in old configs are not reset, upgraded
        // layouts are kept as-is; loadFromFile applies defaults only when the
        // file is missing)
        /** Whether the spawn time HUD shows (factory off: waits for the player to drag it from the library onto the canvas) */
        public static final ConfigBoolean VISIBLE_SPAWN_TIME = new ConfigBoolean("visibleSpawnTime", false);
        /** Whether the powerup HUD shows (factory off) */
        public static final ConfigBoolean VISIBLE_POWERUP = new ConfigBoolean("visiblePowerup", false);
        /** Whether the team stats HUD shows (factory off) */
        public static final ConfigBoolean VISIBLE_TEAM_STATS = new ConfigBoolean("visibleTeamStats", false);
        /** Whether the time HUD shows (factory off) */
        public static final ConfigBoolean VISIBLE_GAME_TIME = new ConfigBoolean("visibleGameTime", false);
        /**
         * Whether the lightning rod queue HUD shows (factory off): inside AA
         * the renderer bypasses this value and force-shows it, but it still
         * obeys only the master switch and "placed"--see
         * {@link #lrQueueOn(boolean)}
         */
        public static final ConfigBoolean VISIBLE_LRQUEUE = new ConfigBoolean("visibleLrQueue", false);
        /**
         * Whether the AA auto command HUD shows (factory off): inside AA the
         * renderer bypasses this value and force-shows it, but it still
         * obeys only the master switch and "placed"--see
         * {@link #aaAutoCommandOn(boolean)}
         */
        public static final ConfigBoolean VISIBLE_AA_AUTO_COMMAND = new ConfigBoolean("visibleAAAutoCommand", false);
        /** Whether the CPS HUD shows (factory off) */
        public static final ConfigBoolean VISIBLE_CPS = new ConfigBoolean("visibleCps", false);
        /** Whether the global overview HUD shows (factory off) */
        public static final ConfigBoolean VISIBLE_GLOBAL_OVERVIEW = new ConfigBoolean("visibleGlobalOverview", false);
        /** Whether the status effects HUD shows (factory off) */
        public static final ConfigBoolean VISIBLE_STATUS_EFFECTS = new ConfigBoolean("visibleStatusEffects", false);
        /**
         * Whether the scoreboard HUD shows (factory off): when on,
         * {@code ScoreboardHudRenderer} takes over the native sidebar
         * rendering (draggable/scalable); when off, the whole sidebar is
         * hidden. Sidebar hiding is decided at one point by
         * {@link #scoreboardOn()}; there is no separate "hide native
         * scoreboard" switch
         */
        public static final ConfigBoolean VISIBLE_SCOREBOARD = new ConfigBoolean("visibleScoreboard", false);
        /** Whether the roll stats HUD shows (factory on) */
        public static final ConfigBoolean VISIBLE_ROLL_STATS = new ConfigBoolean("visibleRollStats", true);

        // ----The eleven editor "is on the canvas" keys----
        //
        // Two independent states, persisted separately from the eleven
        // VISIBLE_* above:
        //  VISIBLE_* = drawn in game or not (the HUD's life or death)
        //  PLACED_* = sits on the editor canvas or not (does it occupy the
        //  workspace)
        //
        // <b>In-game rendering requires both true</b> (plus the master
        // switch); see the {@code *On()} group below: a HUD not placed on
        // the canvas is simply never drawn in game--the canvas is the only
        // proof of "I put it there", and without that proof there is no
        // "where to draw it in game".
        // Conversely, "placed + visible: off" is still a legal and useful
        // combination: keep tuning its position on the canvas while the game
        // holds off (the canvas shows it with a red frame)
        //
        // Everything except roll stats ships false: only roll stats is on
        // the canvas; the other ten components wait in the left library
        /** Whether the spawn time HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_SPAWN_TIME = new ConfigBoolean("placedSpawnTime", false);
        /** Whether the powerup HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_POWERUP = new ConfigBoolean("placedPowerup", false);
        /** Whether the team stats HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_TEAM_STATS = new ConfigBoolean("placedTeamStats", false);
        /** Whether the time HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_GAME_TIME = new ConfigBoolean("placedGameTime", false);
        /** Whether the lightning rod queue HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_LRQUEUE = new ConfigBoolean("placedLrQueue", false);
        /** Whether the AA auto command HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_AA_AUTO_COMMAND = new ConfigBoolean("placedAAAutoCommand", false);
        /** Whether the CPS HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_CPS = new ConfigBoolean("placedCps", false);
        /** Whether the global overview HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_GLOBAL_OVERVIEW = new ConfigBoolean("placedGlobalOverview", false);
        /** Whether the status effects HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_STATUS_EFFECTS = new ConfigBoolean("placedStatusEffects", false);
        /** Whether the scoreboard HUD sits on the editor canvas (factory no) */
        public static final ConfigBoolean PLACED_SCOREBOARD = new ConfigBoolean("placedScoreboard", false);
        /** Whether the roll stats HUD sits on the editor canvas (factory yes) */
        public static final ConfigBoolean PLACED_ROLL_STATS = new ConfigBoolean("placedRollStats", true);

        // ----The single source of truth for in-game gating: the eleven *On()
        // predicates----
        //
        // Renderers and {@code InGameHudMixin} may only call the named
        // predicates here, never assemble "master switch && PLACED_* &&
        // VISIBLE_*" by hand--handwritten conjunctions drift, and once one
        // drifts, unplaced HUDs crowd the left screen edge at their anchor
        // ratios. The conjunction lives here and nowhere else; the
        // PLACED_/VISIBLE_* literals therefore appear only in this file
        // (HudEntry and RegisterHUD just wire the read/write accessors at
        // registration), and feature/ and mixin/ code may not reference
        // these keys directly
        //
        // The predicates <b>do not test the map</b>: the two AA entries'
        // in-map "bypass independent visibility" is carried by the
        // parameterized overloads, with the caller passing in whether the
        // current map is AA (map detection stays in LanguageUtils)

        /** Whether the spawn time HUD should draw in game right now */
        public static boolean spawnTimeOn() {
            return on(PLACED_SPAWN_TIME, VISIBLE_SPAWN_TIME);
        }

        /** Whether the powerup HUD should draw in game right now */
        public static boolean powerupOn() {
            return on(PLACED_POWERUP, VISIBLE_POWERUP);
        }

        /** Whether the team stats HUD should draw in game right now (the sidebar row filter also reads it) */
        public static boolean teamStatsOn() {
            return on(PLACED_TEAM_STATS, VISIBLE_TEAM_STATS);
        }

        /** Whether the time HUD should draw in game right now (the sidebar row filter also reads it) */
        public static boolean gameTimeOn() {
            return on(PLACED_GAME_TIME, VISIBLE_GAME_TIME);
        }

        /** Whether the CPS HUD should draw in game right now */
        public static boolean cpsOn() {
            return on(PLACED_CPS, VISIBLE_CPS);
        }

        /** Whether the global overview HUD should draw in game right now */
        public static boolean globalOverviewOn() {
            return on(PLACED_GLOBAL_OVERVIEW, VISIBLE_GLOBAL_OVERVIEW);
        }

        /** Whether the status effects HUD should draw in game right now */
        public static boolean statusEffectsOn() {
            return on(PLACED_STATUS_EFFECTS, VISIBLE_STATUS_EFFECTS);
        }

        /**
         * Whether the scoreboard HUD should take over the sidebar right
         * now--when {@code false}, {@code InGameHudMixin} cancels the
         * native rendering outright inside a Zombies game, so the whole
         * sidebar hides (not placed and "visible: off" have the same
         * consequence here: hiding)
         */
        public static boolean scoreboardOn() {
            return on(PLACED_SCOREBOARD, VISIBLE_SCOREBOARD);
        }

        /** Whether the roll stats HUD should draw in game right now */
        public static boolean rollStatsOn() {
            return on(PLACED_ROLL_STATS, VISIBLE_ROLL_STATS);
        }

        /**
         * Whether the AA auto command HUD should draw in game right now
         *
         * <p>Inside AA, bypassing this HUD's independent visibility stays a
         * hard semantic (AA mode auto-enables it), but <b>placement and the
         * master switch cannot be bypassed</b>--an element never dragged
         * onto the canvas appears on no map
         *
         * @param onAaMap whether the current map is Alien Arcadium
         * @return whether to render
         */
        public static boolean aaAutoCommandOn(boolean onAaMap) {
            return QoL.HUD_MASTER.getBooleanValue()
                    && PLACED_AA_AUTO_COMMAND.getBooleanValue()
                    && (VISIBLE_AA_AUTO_COMMAND.getBooleanValue() || onAaMap);
        }

        /**
         * Whether the lightning rod queue HUD should draw in game right
         * now--mirrors {@link #aaAutoCommandOn(boolean)}
         *
         * @param onAaMap whether the current map is Alien Arcadium
         * @return whether to render
         */
        public static boolean lrQueueOn(boolean onAaMap) {
            return QoL.HUD_MASTER.getBooleanValue()
                    && PLACED_LRQUEUE.getBooleanValue()
                    && (VISIBLE_LRQUEUE.getBooleanValue() || onAaMap);
        }

        /**
         * Three checks in one: master switch and placed and enabled--the
         * common tail of every {@code *On()} predicate
         *
         * @param placed whether the HUD sits on the canvas
         * @param visible the independent visibility switch
         * @return whether all three hold
         */
        private static boolean on(ConfigBoolean placed, ConfigBoolean visible) {
            return QoL.HUD_MASTER.getBooleanValue()
                    && placed.getBooleanValue()
                    && visible.getBooleanValue();
        }


        /**
         * How the HUD editor canvas sources its picture: the canvas
         * currently uses a static backdrop image, so nothing consumes this
         * option; it is kept for config round-trips only ({@link HudCanvas}'s
         * full sourcing implementation stays intact--swap the editor's
         * backdrop drawing back to {@code HudCanvas.render} and live
         * playback reconnects)
         *
         * <p>Default {@link HudCanvasMode#LIVE_GPU}--draws the main
         * framebuffer's color attachment directly as a texture: zero CPU
         * readback and a fresh image every frame. If it ever misrenders
         * (upside down / black), switch to
         * {@link HudCanvasMode#LIVE_SNAPSHOT} for the screenshot-path
         * fallback; the two paths share one interface, no code changes
         * needed
         */
        public static final ConfigOptionList HUD_CANVAS_MODE =
                new ConfigOptionList("hudCanvasMode", HudCanvasMode.LIVE_GPU);

        /**
         * Canvas sampling frequency (fps, 0-30, default 10): affects only
         * the snapshot / frozen paths; GPU direct sampling refreshes every
         * frame and ignores it.
         * 0 means "capture a single frame" (equivalent to frozen), for
         * squeezing cost to the minimum on low-end machines
         */
        public static final ConfigDouble HUD_CANVAS_FPS =
                new ConfigDouble("hudCanvasFps", 10.0, 0.0, 30.0, true);

        /**
         * Width of the editor's left component library (px, 160-320, default
         * 200): drag the library's right border to change it; pure UI
         * preference
         */
        public static final ConfigDouble HUD_LIBRARY_WIDTH =
                new ConfigDouble("hudLibraryWidth", 200.0, 160.0, 320.0, true);

        /**
         * Every HUD's coordinates, scale, and visibility in this group, plus
         * the canvas sourcing mode and sampling frequency, read and written
         * by MaLiLib under the {@code Hud} section
         *
         * <p>The element type is {@link IConfigBase} (not the
         * {@link IConfigValue} the other groups use): the canvas sourcing
         * mode is a {@link ConfigOptionList}, which implements only
         * IConfigBase, and {@code ConfigUtils.readConfigBase}/
         * {@code writeConfigBase} take exactly
         * {@code List<? extends IConfigBase>}--both element kinds fit one
         * list
         */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                X_SPAWN_TIME,
                Y_SPAWN_TIME,
                X_POWERUP,
                Y_POWERUP,
                X_TEAM_STATS,
                Y_TEAM_STATS,
                X_GAME_TIME,
                Y_GAME_TIME,
                X_LRQUEUE,
                Y_LRQUEUE,
                X_AA_AUTO_COMMAND,
                Y_AA_AUTO_COMMAND,
                X_CPS,
                Y_CPS,
                SCALE_SPAWN_TIME,
                SCALE_POWERUP,
                SCALE_TEAM_STATS,
                SCALE_GAME_TIME,
                SCALE_LRQUEUE,
                SCALE_AA_AUTO_COMMAND,
                SCALE_CPS,
                X_GLOBAL_OVERVIEW,
                Y_GLOBAL_OVERVIEW,
                SCALE_GLOBAL_OVERVIEW,
                X_STATUS_EFFECTS,
                Y_STATUS_EFFECTS,
                X_SCOREBOARD,
                Y_SCOREBOARD,
                SCALE_STATUS_EFFECTS,
                SCALE_SCOREBOARD,
                X_ROLL_STATS,
                Y_ROLL_STATS,
                SCALE_ROLL_STATS,
                VISIBLE_SPAWN_TIME,
                VISIBLE_POWERUP,
                VISIBLE_TEAM_STATS,
                VISIBLE_GAME_TIME,
                VISIBLE_LRQUEUE,
                VISIBLE_AA_AUTO_COMMAND,
                VISIBLE_CPS,
                VISIBLE_GLOBAL_OVERVIEW,
                VISIBLE_STATUS_EFFECTS,
                VISIBLE_SCOREBOARD,
                VISIBLE_ROLL_STATS,
                PLACED_SPAWN_TIME,
                PLACED_POWERUP,
                PLACED_TEAM_STATS,
                PLACED_GAME_TIME,
                PLACED_LRQUEUE,
                PLACED_AA_AUTO_COMMAND,
                PLACED_CPS,
                PLACED_GLOBAL_OVERVIEW,
                PLACED_STATUS_EFFECTS,
                PLACED_SCOREBOARD,
                PLACED_ROLL_STATS,
                HUD_CANVAS_MODE,
                HUD_CANVAS_FPS,
                HUD_LIBRARY_WIDTH
        );
    }

    // ----QoL----

    /**
     * QoL config group: the master switch per feature (most of them
     * hotkeyed), the visual effect switches (ESP, health bars, player hide,
     * and friends), and the shortcuts that open the config UI / HUD editor
     *
     * <p>Every {@link ConfigBooleanHotkeyed} here toggles in game with one
     * hotkey press--putting the most common action on a key is this
     * config's default posture; the menu is only the backup path
     */
    public static class QoL {
        /**
         * Master switch for all HUDs (default on--the one exception to the
         * Tweakeroo-style "everything off" defaults): turning it off hides
         * all 11 HUDs, outweighing the per-HUD visibility switches in the
         * editor. Of the eleven independent visibilities only roll stats
         * ships on, the other ten off, so a fresh game shows the roll stats
         * block first and every other HUD is dragged out of the editor on
         * demand; existing values in old config files are never reset
         * (defaults apply only when the file is missing), so upgrading
         * players keep their layouts as-is
         */
        public static final ConfigBooleanHotkeyed HUD_MASTER =
                new ConfigBooleanHotkeyed("utilityHud", true, "").apply(PREFIX_QOL);
        /**
         * Wave spawn sound master switch (hotkeyed): governs only "sound or
         * silence", covering both the wave spawn alerts and the final-wave
         * 3-2-1 countdown.
         * Map coverage (the 4 map switches) and sound ID / pitch details
         * live on the global config page; default off
         */
        public static final ConfigBooleanHotkeyed WAVE_SOUND_ENABLED =
                new ConfigBooleanHotkeyed("waveSoundEnabled", false, "").apply(PREFIX_QOL);
        /**
         * Round timing record master switch (hotkeyed, default off like the
         * other feature switches): governs only whether round time stats
         * are announced; density (every round / every 5 / every 10) is
         * picked per map on the global config page
         */
        public static final ConfigBooleanHotkeyed RECORD_ENABLED =
                new ConfigBooleanHotkeyed("recordEnabled", false, "").apply(PREFIX_QOL);
        /**
         * Teammate ESP: draws wireframes on roster teammates in combat
         * (red) or downed body (yellow)--teammate state at a glance.
         * The render mode ({@link #TEAMMATE_ESP_RENDER_MODE}) decides
         * LEQUAL-only or an extra ALWAYS through-wall layer
         */
        public static final ConfigBooleanHotkeyed TEAMMATE_ESP =
                new ConfigBooleanHotkeyed("teammateEsp", false, "").apply(PREFIX_QOL);
        /**
         * Zombie ESP: draws green wireframes on hostile mobs (zombies,
         * wolves, blazes, and friends)--threat positions at a glance while
         * clearing the map; turns orange when the boss special mark is on.
         * The render mode ({@link #ZOMBIE_ESP_RENDER_MODE}) decides
         * LEQUAL-only or an extra ALWAYS through-wall layer
         */
        public static final ConfigBooleanHotkeyed ZOMBIE_ESP =
                new ConfigBooleanHotkeyed("zombieEsp", false, "").apply(PREFIX_QOL);
        /**
         * Powerup ESP: draws white wireframes on spawned powerup armor
         * stands--no more powerups lost in a corner.
         * The render mode ({@link #POWERUP_ESP_RENDER_MODE}) decides
         * LEQUAL-only or an extra ALWAYS through-wall layer
         */
        public static final ConfigBooleanHotkeyed POWERUP_ESP =
                new ConfigBooleanHotkeyed("powerupEsp", false, "").apply(PREFIX_QOL);
        /**
         * Entity health bar master switch (hotkeyed, default off): draws
         * health bars over hostile entities, independent of the ESP
         * switches.
         * Through-wall behavior is decided by
         * {@link #HEALTH_BAR_RENDER_MODE}; boss-exclusive colors see
         * {@link #BOSS_HEALTH_BAR_MARK}
         */
        public static final ConfigBooleanHotkeyed ENTITY_HEALTH_BAR =
                new ConfigBooleanHotkeyed("entityHealthBar", false, "").apply(PREFIX_QOL);
        /**
         * Damage / heal floating number master switch (hotkeyed, default
         * off): an entity whose health changes pops a number on the spot--
         * red for damage, green for healing, the value being the health
         * change the client observed.
         * Instakills also produce only the number; there is no "INSTAKILL"
         * text branch. These are not vanilla particles, so
         * {@code NO_PARTICLES} does not cover them; active in Zombies games
         * only
         */
        public static final ConfigBooleanHotkeyed DAMAGE_NUMBER_ENABLED =
                new ConfigBooleanHotkeyed("damageNumber", false, "").apply(PREFIX_QOL);
        /**
         * Boss health bar special mark: boss-type mobs (giants, elders,
         * anything with a Hypixel health bar) get the exclusive four-stage
         * palette purple - yellow - orange - red and a raised bar, offset
         * from the mobs' green / yellow / red so they read at a glance.
         * Default on--a boss bar appears in its exclusive palette from the
         * start; turn it off manually only to make it look like a normal
         * mob.
         * Plain boolean, no hotkey; depends on {@link #ENTITY_HEALTH_BAR}
         * (the health bar master switch)
         */
        public static final ConfigBoolean BOSS_HEALTH_BAR_MARK =
                new ConfigBoolean("bossHealthBarMark", true).apply(PREFIX_QOL);
        /**
         * Boss ESP special mark: boss-type mobs get an orange wireframe
         * (vs the green one for normal mobs) to lock high-threat targets at
         * a glance.
         * Default on--a boss frame is orange from the start; turn it off
         * manually only to share the normal mobs' green.
         * Plain boolean, no hotkey; depends on {@link #ZOMBIE_ESP}
         */
        public static final ConfigBoolean BOSS_ESP_MARK =
                new ConfigBoolean("bossEspMark", true).apply(PREFIX_QOL);
        /**
         * Teammate ESP render mode: NORMAL draws only the LEQUAL depth
         * layer; THROUGH_WALLS adds a translucent ALWAYS layer so
         * wall-hidden parts show through--default NORMAL
         */
        public static final ConfigOptionList TEAMMATE_ESP_RENDER_MODE =
                new ConfigOptionList("teammateEspRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /** Zombie ESP render mode: values and side effects same as {@link #TEAMMATE_ESP_RENDER_MODE} */
        public static final ConfigOptionList ZOMBIE_ESP_RENDER_MODE =
                new ConfigOptionList("zombieEspRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /** Powerup ESP render mode: values and side effects same as {@link #TEAMMATE_ESP_RENDER_MODE} */
        public static final ConfigOptionList POWERUP_ESP_RENDER_MODE =
                new ConfigOptionList("powerupEspRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /**
         * Health bar render mode: NORMAL uses LEQUAL depth testing;
         * THROUGH_WALLS uses ALWAYS so the bar is always visible--default
         * NORMAL.
         * Shares the ESP enum, but the bar picks a single layer instead of
         * stacking two like ESP
         */
        public static final ConfigOptionList HEALTH_BAR_RENDER_MODE =
                new ConfigOptionList("healthBarRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /**
         * Damage / heal number render mode: NORMAL uses depth testing only
         * (hidden behind walls); THROUGH_WALLS uses SEE_THROUGH so numbers
         * show through walls, falling back to depth testing beyond
         * {@link #THROUGH_WALL_RENDER_DISTANCE}.
         * Shares the ESP / health bar enum; default NORMAL
         */
        public static final ConfigOptionList DAMAGE_NUMBER_RENDER_MODE =
                new ConfigOptionList("damageNumberRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /**
         * Global through-wall render distance (slider, 5-200 blocks, default
         * 100): the maximum range of through-wall rendering (the ESP
         * through-wall layer in that mode, and the health bar's
         * see-through display). Beyond it, entities stop showing through
         * walls--ESP wireframes fall back to the depth layer only, health
         * bars fall back to depth testing (hidden behind walls).
         * One global switch covering every ESP type and the health bar
         */
        public static final ConfigDouble THROUGH_WALL_RENDER_DISTANCE =
                new ConfigDouble("throughWallRenderDistance", 100.0, 5.0, 200.0, true).apply(PREFIX_QOL);
        /**
         * Auto-hide nearby players master switch (hotkeyed, default off):
         * cancels rendering of players within {@code <1.4} blocks
         * outright--out of sight, out of the way for teammates blocking the
         * view. Gating details in {@code HideNearbyPlayer.shouldHide}
         * (Zombies games only, not yourself, not sleeping,
         * {@code maxHealth < 100})
         */
        public static final ConfigBooleanHotkeyed PLAYER_INVISIBLE =
                new ConfigBooleanHotkeyed("playerInvisible", false, "").apply(PREFIX_QOL);
        /**
         * Intercept the native boss bar (top of screen): when on,
         * BossBarHud is no longer rendered, keeping the top of the screen
         * cleaner
         */
        public static final ConfigBooleanHotkeyed HIDE_BOSS_BAR =
                new ConfigBooleanHotkeyed("hideBossBar", false, "").apply(PREFIX_QOL);
        /**
         * Blocks right-click actions other than firing: the crosshair ray
         * ignores the invisible armor stands behind holograms (door
         * prices, machine hints, powerup labels), and interactive blocks
         * skip their right-click response--right click only fires, no
         * accidental interactions.
         * The cost: machines, doors, and other interactive blocks cannot
         * be used while on (temporarily switch it off before buying a gun
         * or opening a door); Zombies games only, default off
         */
        public static final ConfigBooleanHotkeyed RIGHT_CLICK_FIRE_ONLY =
                new ConfigBooleanHotkeyed("rightClickFireOnly", false, "").apply(PREFIX_QOL);
        /**
         * No particles: swallows every particle in the client world (muzzle
         * flash, block-break dust, fire smoke, item pickup, firework
         * bursts).
         * Cancels at the enqueue point
         * {@code ParticleManager.addParticle(Particle)}--particles never
         * tick, never enter the texture queue, never render; the emit side
         * remains, only the display layer is empty.
         * Zombies games only (like the other features), default off
         */
        public static final ConfigBooleanHotkeyed NO_PARTICLES =
                new ConfigBooleanHotkeyed("noParticles", false, "").apply(PREFIX_QOL);
        /**
         * No fire effect: removes the on-screen fire overlay entirely while
         * burning (a single switch--cancel the render, no repaint, nothing
         * else disturbed); Zombies games only, default off
         */
        public static final ConfigBooleanHotkeyed NO_FIRE_EFFECT =
                new ConfigBooleanHotkeyed("fireOverlay", false, "").apply(PREFIX_QOL);
        /**
         * Alien Arcadium auto command: at each round start on the AA map,
         * automatically announces the round command (recommended points /
         * boss / difficulty) via the output channel and template from the
         * global config page.
         * Alien Arcadium only; HUD display is governed independently by the
         * HUD master switch plus this HUD's own switch in the editor,
         * default off
         */
        public static final ConfigBooleanHotkeyed AA_AUTO_COMMAND_ENABLED =
                new ConfigBooleanHotkeyed("aaAutoCommandEnabled", false, "").apply(PREFIX_QOL);
        /**
         * Smooth zoom master switch (hotkeyed, default off): when on, the
         * zoom hotkey from the global config page (default C) zooms the
         * view inside a Zombies game.
         * Magnification / durations / easing / key behavior and the hotkey
         * binding all live on the global config page; this switch only
         * opens or closes the feature
         */
        public static final ConfigBooleanHotkeyed ZOOM_ENABLED =
                new ConfigBooleanHotkeyed("zoomEnabled", false, "").apply(PREFIX_QOL);
        /**
         * Always sneak master switch (hotkeyed, default off): forces
         * sneaking in Zombies games--crouch animation, edge protection,
         * smaller hitbox, via the full vanilla sneak state machine so you
         * never land in a weird state.
         * Does not apply inside GUIs (inventory and friends) by default;
         * change that on the global config page
         */
        public static final ConfigBooleanHotkeyed ALWAYS_SNEAK_ENABLED =
                new ConfigBooleanHotkeyed("alwaysSneakEnabled", false, "").apply(PREFIX_QOL);
        /**
         * Always sprint master switch (hotkeyed, default off): in a Zombies
         * game, walking forward with W auto-sprints, as if the sprint key
         * were held forever.
         * The vanilla sprint constraints--hunger, blindness, in water,
         * using an item--stay intact one and all: when the game says you
         * can't sprint, you can't
         */
        public static final ConfigBooleanHotkeyed ALWAYS_SPRINT_ENABLED =
                new ConfigBooleanHotkeyed("alwaysSprintEnabled", false, "").apply(PREFIX_QOL);
        /**
         * Gamma override master switch (hotkeyed, default off): in a
         * Zombies game, forces game brightness to the override value from
         * the global config page (default 16, night-vision level, not
         * limited by the vanilla brightness slider's 0-1); turning it off
         * restores the player's original brightness.
         * Pure client-side visuals: display only, no packets sent
         */
        public static final ConfigBooleanHotkeyed GAMMA_OVERRIDE_ENABLED =
                new ConfigBooleanHotkeyed("gammaOverrideEnabled", false, "").apply(PREFIX_QOL);
        /**
         * Free camera master switch (hotkeyed, default off): in a Zombies
         * game, moves the render view to a stand-in camera entity--the
         * camera detaches and flies freely (WASD + mouse) while the
         * player's body freezes in place.
         * Whether the player can move or interact while the camera flies is
         * decided by the playerMovement / playerInputs sub-options on the
         * global config page; disabling restores everything instantly,
         * zero residue on the player
         */
        public static final ConfigBooleanHotkeyed FREE_CAMERA_ENABLED =
                new ConfigBooleanHotkeyed("freeCameraEnabled", false, "").apply(PREFIX_QOL);
        /** Open the config UI (default combo Z+X)--one press straight to the MaLiLib config page, one of the most-used entries */
        public static final ConfigHotkey OPEN_GUI_CONFIGS =
                new ConfigHotkey("openConfigGui", "Z,X").apply(PREFIX_QOL);
        /** Open the HUD editor--the drag-in entry for HUD coordinates / scale / independent visibility; see {@link HUDEditor} */
        public static final ConfigHotkey OPEN_HUD_EDITOR =
                new ConfigHotkey("openHudEditor", "").apply(PREFIX_QOL);

        /** All of this group's feature switches, render parameters, and entry hotkeys, read/written by MaLiLib under the {@code QoL} section */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                HUD_MASTER,
                WAVE_SOUND_ENABLED,
                RECORD_ENABLED,
                TEAMMATE_ESP,
                ZOMBIE_ESP,
                POWERUP_ESP,
                ENTITY_HEALTH_BAR,
                DAMAGE_NUMBER_ENABLED,
                BOSS_HEALTH_BAR_MARK,
                BOSS_ESP_MARK,
                TEAMMATE_ESP_RENDER_MODE,
                ZOMBIE_ESP_RENDER_MODE,
                POWERUP_ESP_RENDER_MODE,
                HEALTH_BAR_RENDER_MODE,
                DAMAGE_NUMBER_RENDER_MODE,
                THROUGH_WALL_RENDER_DISTANCE,
                PLAYER_INVISIBLE,
                HIDE_BOSS_BAR,
                RIGHT_CLICK_FIRE_ONLY,
                NO_PARTICLES,
                NO_FIRE_EFFECT,
                AA_AUTO_COMMAND_ENABLED,
                ZOOM_ENABLED,
                ALWAYS_SNEAK_ENABLED,
                ALWAYS_SPRINT_ENABLED,
                GAMMA_OVERRIDE_ENABLED,
                FREE_CAMERA_ENABLED,
                OPEN_GUI_CONFIGS,
                OPEN_HUD_EDITOR
        );

        /**
         * The hotkeys within the QoL group (this group's feature-switch
         * hotkeys plus open query GUI), a component of {@link #ALL_HOTKEYS}
         */
        public static final List<IHotkey> HOTKEY_LIST = ImmutableList.of(
                HUD_MASTER,
                WAVE_SOUND_ENABLED,
                RECORD_ENABLED,
                TEAMMATE_ESP,
                ZOMBIE_ESP,
                POWERUP_ESP,
                ENTITY_HEALTH_BAR,
                DAMAGE_NUMBER_ENABLED,
                PLAYER_INVISIBLE,
                HIDE_BOSS_BAR,
                RIGHT_CLICK_FIRE_ONLY,
                NO_PARTICLES,
                NO_FIRE_EFFECT,
                AA_AUTO_COMMAND_ENABLED,
                ZOOM_ENABLED,
                ALWAYS_SNEAK_ENABLED,
                ALWAYS_SPRINT_ENABLED,
                GAMMA_OVERRIDE_ENABLED,
                FREE_CAMERA_ENABLED,
                OPEN_GUI_CONFIGS,
                OPEN_HUD_EDITOR,
                Query.OPEN_QUERY_GUI
        );

        /**
         * Every bindable hotkey (including each group's
         * {@link ConfigBooleanHotkeyed} feature switches).
         * A hotkey only works once registered with MaLiLib's key binding
         * manager--otherwise the config UI lets you bind it, but the press
         * silently does nothing; once a {@link ConfigBooleanHotkeyed}
         * switch is registered, MaLiLib provides the toggle callback, so
         * registration itself is the wiring and no manual event hooks are
         * needed
         */
        public static final List<IHotkey> ALL_HOTKEYS = ImmutableList.<IHotkey>builder()
                .addAll(HOTKEY_LIST)
                .add(Spawntimes.FINAL_WAVE_COUNTDOWN)
                .add(Spawntimes.COLOR_ALERT)
                .add(Powerups.POWERUP_PREDICT)
                .addAll(ImmutableList.of(
                        Hide.HIDE_GOLD,
                        Hide.HIDE_WINDOW,
                        Hide.HIDE_HIT_TARGET,
                        Hide.HIDE_LUCKY_CHEST,
                        Hide.HIDE_OPEN_AREA,
                        Hide.HIDE_PLAYER_CONNECTION))
                .add(Zoom.ZOOM_KEY) // pure hotkey, no boolean; the physical state is read per tick via isKeyDown
                .build();
    }

    // ----Player data query (player query)----

    /**
     * Player data query: the Hypixel API key (masked display + encrypted on
     * disk) and the hotkey that opens the query GUI
     *
     * <p>The key's plaintext reaches the query logic only through
     * {@link #getApiKeyPlain()}, and the UI text box always shows the
     * mask--even someone watching your screen cannot copy the full key
     */
    public static class Query {
        /**
         * Hypixel API key for player data queries (masked in the UI,
         * encrypted to disk via {@link ApiKeyCrypto});
         * its translation prefix reuses the QoL group's {@code PREFIX_QOL}
         */
        public static final ConfigApiKey API_KEY =
                new ConfigApiKey("apiKey", "").apply(PREFIX_QOL);
        /** Open the player data query GUI (free query / in-game query)--the single entry hotkey of the query feature */
        public static final ConfigHotkey OPEN_QUERY_GUI =
                new ConfigHotkey("openQueryGui", "").apply(PREFIX_QOL);

        /**
         * The API key and GUI hotkey written to the {@code Query} section;
         * load and save share this collection
         */
        public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(
                API_KEY,
                OPEN_QUERY_GUI
        );
    }

    /**
     * The Hypixel API key plaintext currently used by the query logic--read
     * only from the masked config item's dedicated plaintext accessor
     *
     * @return the configured key; an empty string when unset or decryption
     * failed
     */
    public static String getApiKeyPlain() {
        return Query.API_KEY.getPlainValue();
    }

    // ----Chat filter (chat filter)----

    /**
     * Chat message filter switches (all off by default; enable as needed),
     * uniformly {@link ConfigBooleanHotkeyed}: each filter can be toggled
     * anytime via its own hotkey, or switched directly with the toggle
     * button in the config UI
     *
     * <p>There is deliberately no hiding for "downed / revived / powerup
     * pickup"--those three message classes are the chat input this mod's
     * team stats and powerup detection feed on; hiding them would blind
     * those features, so the choice is left to the player
     */
    public static class Hide {
        /** Hide gold-gain messages (like "+N coins"), default off */
        public static final ConfigBooleanHotkeyed HIDE_GOLD =
                new ConfigBooleanHotkeyed("hideGold", false, "").apply(PREFIX_HIDE);
        /** Hide window repair start / finish and "window repaired" messages, default off */
        public static final ConfigBooleanHotkeyed HIDE_WINDOW =
                new ConfigBooleanHotkeyed("hideWindowRepair", false, "").apply(PREFIX_HIDE);
        /** Hide shot-hit-target messages like "X hit the target!", default off */
        public static final ConfigBooleanHotkeyed HIDE_HIT_TARGET =
                new ConfigBooleanHotkeyed("hideHitTarget", false, "").apply(PREFIX_HIDE);
        /** Hide lucky chest opening messages, default off */
        public static final ConfigBooleanHotkeyed HIDE_LUCKY_CHEST =
                new ConfigBooleanHotkeyed("hideLuckyChest", false, "").apply(PREFIX_HIDE);
        /** Hide "area opened" and "area unlocked" messages, default off */
        public static final ConfigBooleanHotkeyed HIDE_OPEN_AREA =
                new ConfigBooleanHotkeyed("hideOpenArea", false, "").apply(PREFIX_HIDE);
        /**
         * Player join/leave (default off): also hides "X left the game"
         * lines--the team stats LEFT event does not depend on that chat and
         * falls back to the scoreboard, so hiding it causes no false
         * reports
         */
        public static final ConfigBooleanHotkeyed HIDE_PLAYER_CONNECTION =
                new ConfigBooleanHotkeyed("hidePlayerConnection", false, "").apply(PREFIX_HIDE);

        /** The six chat filter switches, written to the {@code Hide} section in this order and shown to the config UI */
        public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(
                HIDE_GOLD,
                HIDE_WINDOW,
                HIDE_HIT_TARGET,
                HIDE_LUCKY_CHEST,
                HIDE_OPEN_AREA,
                HIDE_PLAYER_CONNECTION
        );
    }

    // ----Smooth zoom (smooth zoom)----

    /**
     * Smooth zoom (a simplified Zoomify): 5 parameters plus the zoom hotkey
     *
     * <p>Active only when ZOOM_ENABLED on the QoL page is on and you are
     * inside a Zombies game (gating in
     * {@link cn.gsfy.nmz.client.features.zoom.ZoomHandler#isActive()});
     * zoom is implemented as FOV division (injecting
     * {@link net.minecraft.client.render.GameRenderer#getFov}), so zooming
     * in rebuilds nothing--purely visual magnification
     */
    public static class Zoom {
        /**
         * Magnification at full zoom (1.0-10.0, default 4.0, matching
         * Zoomify's default)--more detail, also more sway
         */
        public static final ConfigDouble INITIAL_ZOOM =
                new ConfigDouble("initialZoom", 4.0, 1.0, 10.0, true).apply(PREFIX_ZOOM);
        /**
         * Zoom-in animation duration (seconds, 0.1-5.0, default 1.0)--too
         * fast jars the eyes, too slow delays firing; a middle value
         */
        public static final ConfigDouble ZOOM_IN_TIME =
                new ConfigDouble("zoomInTime", 1.0, 0.1, 5.0, true).apply(PREFIX_ZOOM);
        /** Zoom-out animation duration (seconds, 0.1-5.0, default 0.5)--zooming back is faster than zooming in, so the view recovers without lag */
        public static final ConfigDouble ZOOM_OUT_TIME =
                new ConfigDouble("zoomOutTime", 0.5, 0.1, 5.0, true).apply(PREFIX_ZOOM);
        /**
         * Animation easing curve: zoom-in uses the chosen curve, zoom-out
         * automatically its opposite--default ease-out exponential,
         * matching Zoomify; details in {@link ZoomEasing}
         */
        public static final ConfigOptionList EASING =
                new ConfigOptionList("easing", ZoomEasing.EASE_OUT_EXP).apply(PREFIX_ZOOM);
        /**
         * Key behavior: hold (HOLD) / press-to-toggle (TOGGLE)--
         * pick HOLD for peek-and-release, TOGGLE to keep zoom resident
         */
        public static final ConfigOptionList KEY_BEHAVIOUR =
                new ConfigOptionList("keyBehaviour", ZoomKeyBehaviour.HOLD).apply(PREFIX_ZOOM);
        /** Zoom hotkey (default C); together with KEY_BEHAVIOUR decides hold vs toggle */
        public static final ConfigHotkey ZOOM_KEY =
                new ConfigHotkey("zoomKey", "C").apply(PREFIX_ZOOM);

        /** This group's magnification, durations, curve, key behavior, and hotkey, written to the {@code Zoom} section in this order */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                INITIAL_ZOOM,
                ZOOM_IN_TIME,
                ZOOM_OUT_TIME,
                EASING,
                KEY_BEHAVIOUR,
                ZOOM_KEY
        );
    }

    // ----Always sneak (always sneak)----

    /**
     * Always sneak config group: the master switch is
     * {@link QoL#ALWAYS_SNEAK_ENABLED}; only its sub-options live here
     *
     * <p>The whole group is one switch, "keep forcing sneak inside GUIs"--
     * a refinement of the master switch's default behavior, not a separate
     * mechanism; with it on you also crouch in menus, otherwise entering
     * the inventory instantly un-sneaks you
     */
    public static class Sneak {
        /**
         * Keep forcing sneak inside GUIs (inventory / containers), default
         * off--GUIs release the force, so opening the inventory doesn't
         * drop the character into a crouch, which looks odd in a menu
         */
        public static final ConfigBoolean ALLOW_IN_GUIS =
                new ConfigBoolean("alwaysSneakAllowInGuis", false).apply(PREFIX_SNEAK);

        /** The GUI sneak sub-option written to the {@code Sneak} section */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ALLOW_IN_GUIS
        );
    }

    // ----Gamma override (gamma override)----

    /**
     * Gamma override config group: the master switch is
     * {@link QoL#GAMMA_OVERRIDE_ENABLED}; only the override brightness
     * lives here
     *
     * <p>A single parameter, answering "how bright" for the master switch;
     * it never writes the vanilla option directly--why it dodges that is in
     * {@link #OVERRIDE_VALUE}'s trap note below
     */
    public static class Gamma {
        /**
         * The brightness applied to the lightmap while enabled (0.0-32.0,
         * default 16.0, night-vision level; not limited by the vanilla
         * brightness slider's 0-1)
         *
         * <p>One trap here: the vanilla brightness option's codec accepts
         * only [0,1], so writing 16 straight into it makes
         * {@code GameOptions.save()} fail with an out-of-range error,
         * which then triggers a resource reload and a black screen--the
         * vanilla option field must not be touched. Instead
         * {@code LightmapBrightnessMixin} substitutes this value for the
         * gamma in the BrightnessFactor computation inside
         * {@code LightmapTextureManager.update()} (the vanilla lightmap
         * shader clamps BrightnessFactor to [0,1] in the end, so
         * {@code >1} safely produces night-vision-level brightness).
         * The original value needs no backup: the option field always
         * holds the player's own value, and leaving Zombies restores it
         * naturally
         */
        public static final ConfigDouble OVERRIDE_VALUE =
                new ConfigDouble("gammaOverrideValue", 16.0, 0.0, 32.0, true).apply(PREFIX_GAMMA);

        /** The brightness override written to the {@code Gamma} section */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OVERRIDE_VALUE
        );
    }

    // ----Free camera (free camera)----

    /**
     * Free camera config group: the master switch is
     * {@link QoL#FREE_CAMERA_ENABLED}; here live the player movement /
     * input allowances and the camera fly speed
     *
     * <p>The two boolean sub-options decide whether the player's body can
     * still move or act while the camera flies--both off is the simplest:
     * the camera just flies, the body stays frozen, no blind fumbling
     */
    public static class FreeCam {
        /**
         * Allow player movement: when on, the player can still walk while
         * the camera flies, making the camera the stationary observation
         * point; when off (default) the player is fully frozen and the
         * camera flies with WASD / mouse
         */
        public static final ConfigBoolean PLAYER_MOVEMENT =
                new ConfigBoolean("playerMovement", false).apply(PREFIX_FREECAM);
        /**
         * Allow player inputs: when on, attack / mine / use / interact
         * still work while the camera flies; when off (default) they are
         * fully blocked--a frozen body acting blindly is easy to misclick,
         * so the default locks it down
         */
        public static final ConfigBoolean PLAYER_INPUTS =
                new ConfigBoolean("playerInputs", false).apply(PREFIX_FREECAM);
        /** Camera fly speed multiplier (0.1-10.0, default 1.0); holding the sprint key multiplies by 3 again, fast enough to cover ground */
        public static final ConfigDouble SPEED =
                new ConfigDouble("speed", 1.0, 0.1, 10.0, true).apply(PREFIX_FREECAM);

        /** This group's movement, inputs, and speed, written to the {@code FreeCamera} section in this order */
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                PLAYER_MOVEMENT,
                PLAYER_INPUTS,
                SPEED
        );
    }

    /**
     * Shared implementation of {@code IConfigOptionListEntry.cycle}--one
     * click on the dropdown steps to the adjacent entry, wrapping around at
     * the ends
     *
     * <p>The five enums (record frequency / alert output / zoom easing /
     * zoom key behavior / ESP render mode) differ in cycling only by which
     * full table they use: {@code values()} or their own {@code VALUES}
     * snapshot; the logic is otherwise identical. Pulled into one static
     * entry point so changing the cycling semantics (say, "reverse while
     * holding Shift") touches one place
     *
     * @param values the enum's full ordered table; returns {@code null}
     * when the table is empty (callers handle that themselves)
     * @param current the current entry's index in that table; negatives are
     * treated as 0
     * @param forward true = step forward, false = step backward
     * @return the entry after stepping
     */
    static IConfigOptionListEntry cycleOption(java.util.List<? extends IConfigOptionListEntry> values,
                                              int current, boolean forward) {
        int size = values.size();
        if (size == 0) {
            return null;
        }
        int id = Math.max(0, current);
        if (forward) {
            id = ++id >= size ? 0 : id;
        } else {
            id = --id < 0 ? size - 1 : id;
        }
        return values.get(id);
    }

    // ----Enums----

    /**
     * Round timing record frequency option (no "off"--recording only picks
     * announcement density; on/off is owned by
     * {@link QoL#RECORD_ENABLED}): QUINTUPLE every 5 rounds, TENFOLD every
     * 10 rounds, ALL every round
     *
     * <p>Implementation anchor: starting at round 1, one entry every N
     * rounds ({@code currentRound%increment!=1} skips), so QUINTUPLE/
     * TENFOLD hit rounds 1, 6, 11... / 1, 11, 21..., not round 5/10 and
     * their multiples; ALL has increment 0 and records every round (same
     * convention as TimeRecorder/README)
     */
    public enum RecordTiming implements IConfigOptionListEntry {
        QUINTUPLE("quintuple"),
        TENFOLD("tenfold"),
        ALL("all");

        /** UI dropdown set--a full {@link #values()} snapshot, shared by the GUI, parsing, and cycling */
        public static final ImmutableList<RecordTiming> VALUES = ImmutableList.copyOf(values());

        private final String configString;

        RecordTiming(String configString) {
            this.configString = configString;
        }

        /** MaLiLib serialization value: the lowercase configString (e.g. "quintuple") */
        @Override
        public String getStringValue() {
            return this.configString;
        }

        /**
         * Display name looked up in the current language; translation key
         * {@code nomorezombies.config.record.timing.<configString>}
         */
        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.record.timing." + this.configString);
        }

        /** Config file string to enum: case-insensitive match; unknown values fall back to ALL (reason in the inline comment) */
        @Override
        public RecordTiming fromString(String value) {
            for (RecordTiming v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return ALL; // unknown values fall back to the default, so ConfigOptionList.value never goes null and crashes the screen
        }

        /** {@link IConfigOptionListEntry} cycling: forward to the next entry, wrapping at the ends; backward mirrors it */
        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            return cycleOption(java.util.Arrays.asList(values()), this.ordinal(), forward);
        }

        /** Same as getStringValue: some MaLiLib paths use toString() as the option value */
        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    /** Powerup alert output channel (mirrors NEZ ChatOutput; no "off"--the powerup alert switch is owned by {@link Powerups#POWERUP_PREDICT}): self / team / public chat */
    public enum AlertOutput implements IConfigOptionListEntry {
        SELF("self"),
        PARTY("party"),
        CHAT("chat");

        /** UI dropdown set; same convention as {@link RecordTiming#VALUES} */
        public static final ImmutableList<AlertOutput> VALUES = ImmutableList.copyOf(values());

        private final String configString;

        AlertOutput(String configString) {
            this.configString = configString;
        }

        /** Serialization same as {@link RecordTiming#getStringValue()} (lowercase configString) */
        @Override
        public String getStringValue() {
            return this.configString;
        }

        /**
         * Display name looked up in the current language; translation key
         * {@code nomorezombies.config.powerup.output.<configString>}
         */
        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.powerup.output." + this.configString);
        }

        /** Config file string to enum: unknown values fall back to SELF; matching rules in {@link RecordTiming#fromString(String)} */
        @Override
        public AlertOutput fromString(String value) {
            for (AlertOutput v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return SELF; // unknown/legacy values fall back to the default (reason in RecordTiming.fromString)
        }

        /** Cycling: wraps at the ends; same rules as {@link RecordTiming#cycle(boolean)} */
        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            return cycleOption(java.util.Arrays.asList(values()), this.ordinal(), forward);
        }

        /** Same as getStringValue (reason in {@link RecordTiming#toString()}) */
        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    // ----Smooth zoom (smooth zoom)----

    /**
     * Zoom animation easing (no "off"--on/off is owned by the
     * {@link QoL#ZOOM_ENABLED} master switch)
     *
     * <p>Trimmed to 4 core curves, a trade-off made when embedding into
     * the mod: zoom-in uses the chosen curve and zoom-out automatically
     * switches to {@link #opposite()}'s counterpart (pick EASE_OUT_SINE
     * and you get ease-out zooming in, ease-in zooming out, symmetric both
     * ways). The IN curves EASE_IN_SINE / EASE_IN_EXP stay in the enum
     * solely as {@link #opposite()}'s internal mapping and never enter the
     * dropdown--{@link #VALUES} is the single collection for UI display,
     * fromString, and cycle
     */
    public enum ZoomEasing implements IConfigOptionListEntry {
        LINEAR("linear") {
            @Override public double apply(double t) { return t; }
        },
        EASE_OUT_SINE("ease_out_sine") {
            @Override public double apply(double t) { return Math.sin(t * Math.PI / 2.0); }
            @Override public double inverse(double x) { return Math.asin(x) * 2.0 / Math.PI; }
            @Override public boolean hasInverse() { return true; }
        },
        EASE_IN_SINE("ease_in_sine") {
            @Override public double apply(double t) { return 1.0 - Math.cos(t * Math.PI / 2.0); }
            @Override public double inverse(double x) { return Math.acos(-(x - 1.0)) * 2.0 / Math.PI; }
            @Override public boolean hasInverse() { return true; }
        },
        EASE_OUT_EXP("ease_out_exp") {
            @Override public double apply(double t) {
                if (t == 0.0) return 0.0;
                if (t == 1.0) return 1.0;
                return 1.0 - Math.pow(2.0, 10.0 - LOG2_1023 - 10.0 * t) + INV_1023;
            }
            @Override public double inverse(double x) {
                if (x == 0.0) return 0.0;
                if (x == 1.0) return 1.0;
                return -((Math.log(1.0 - x + INV_1023) - TEN_LN_2 + LN_1023) / TEN_LN_2);
            }
            @Override public boolean hasInverse() { return true; }
        },
        EASE_IN_EXP("ease_in_exp") {
            @Override public double apply(double t) {
                if (t == 0.0) return 0.0;
                if (t == 1.0) return 1.0;
                return Math.pow(2.0, 10.0 * t - LOG2_1023) - INV_1023;
            }
            @Override public double inverse(double x) {
                if (x == 0.0) return 0.0;
                if (x == 1.0) return 1.0;
                return Math.log(1023.0 * x + 1.0) / TEN_LN_2;
            }
            @Override public boolean hasInverse() { return true; }
        },
        INSTANT("instant") {
            @Override public double apply(double t) { return t; }
        };

        /** UI display and config parsing iterate only these four selectable curves;
         *  the two IN curves serve {@link #opposite()} internally. */
        public static final ImmutableList<ZoomEasing> VALUES = ImmutableList.of(
                LINEAR, EASE_OUT_SINE, EASE_OUT_EXP, INSTANT);

        // Precomputed constants for the Zoomify TransitionType EXP curve: 1023 = 2^10 - 1
        // The four names say nothing; in order they are log2(1023) / 10ln(2) / ln(1023) / 1/1023
        private static final double LOG2_1023 = Math.log(1023.0) / Math.log(2.0);
        private static final double TEN_LN_2 = 10.0 * Math.log(2.0);
        private static final double LN_1023 = Math.log(1023.0);
        private static final double INV_1023 = 1.0 / 1023.0;

        private final String configString;

        ZoomEasing(String configString) {
            this.configString = configString;
        }

        /**
         * The easing core: each animation frame maps linear progress onto
         * curve progress.
         *
         * @param t linear progress, constrained by the caller to {@code [0, 1]}
         * @return the eased progress
         */
        public abstract double apply(double t);

        /**
         * When direction reverses, re-projects curve progress back into linear
         * space so the animation continues without a jump.
         *
         * @param x the curve progress to invert
         * @return the corresponding linear progress
         * @throws UnsupportedOperationException when the current curve has no inverse;
         *  check {@link #hasInverse()} before calling
         */
        public double inverse(double x) {
            throw new UnsupportedOperationException();
        }

        /** Whether {@link #inverse(double)} is defined; when false the inverse throws
         * {@link UnsupportedOperationException}, so gate the animation reversal with it first. */
        public boolean hasInverse() {
            return false;
        }

        /** The opposite direction's curve: zoom-in uses the selection, zoom-out automatically uses its opposite
         * (ease-in pairs with ease-out; LINEAR/INSTANT are their own). */
        public ZoomEasing opposite() {
            return switch (this) {
                case EASE_OUT_SINE -> EASE_IN_SINE;
                case EASE_IN_SINE -> EASE_OUT_SINE;
                case EASE_OUT_EXP -> EASE_IN_EXP;
                case EASE_IN_EXP -> EASE_OUT_EXP;
                default -> this;
            };
        }

        /** Serialized value, same as {@link RecordTiming#getStringValue()} (e.g. "ease_out_exp"). */
        @Override
        public String getStringValue() {
            return this.configString;
        }

        /** UI display name by the current language; translation key
         * {@code nomorezombies.config.zoom.easing.<configString>}. */
        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.zoom.easing." + this.configString);
        }

        /** Config file string -> enum: compared within {@link #VALUES} (no IN curves),
         * falling back to the default EASE_OUT_EXP on no match. */
        @Override
        public ZoomEasing fromString(String value) {
            for (ZoomEasing v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return EASE_OUT_EXP; // unknown/old values fall back to the default, so ConfigOptionList.value never turns null and crashes a screen
        }

        /** Cycles through {@link #VALUES}: the IN curves are not in the set and can never be cycled to. */
        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            return cycleOption(VALUES, VALUES.indexOf(this), forward);
        }

        /** Same as getStringValue (the reason is at {@link RecordTiming#toString()}). */
        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    /** Zoom key behaviour (no "off" - on/off belongs to the {@link QoL#ZOOM_ENABLED} master switch):
     * HOLD zooms while held, TOGGLE flips once per press. */
    public enum ZoomKeyBehaviour implements IConfigOptionListEntry {
        HOLD("hold"),
        TOGGLE("toggle");

        /** The UI dropdown set, accounting per {@link RecordTiming#VALUES}. */
        public static final ImmutableList<ZoomKeyBehaviour> VALUES = ImmutableList.copyOf(values());

        private final String configString;

        ZoomKeyBehaviour(String configString) {
            this.configString = configString;
        }

        /** Serialized value, same as {@link RecordTiming#getStringValue()} ("hold"/"toggle"). */
        @Override
        public String getStringValue() {
            return this.configString;
        }

        /** UI display name by the current language; translation key
         * {@code nomorezombies.config.zoom.keyBehaviour.<configString>}. */
        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.zoom.keyBehaviour." + this.configString);
        }

        /** Config file string -> enum: falls back to HOLD on no match;
         * comparison rules per {@link RecordTiming#fromString(String)}. */
        @Override
        public ZoomKeyBehaviour fromString(String value) {
            for (ZoomKeyBehaviour v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return HOLD; // unknown/old values fall back to the default (the reason is at RecordTiming.fromString)
        }

        /** Cycles: wraps to the start when out of range, same rule as {@link RecordTiming#cycle(boolean)}. */
        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            return cycleOption(java.util.Arrays.asList(values()), this.ordinal(), forward);
        }

        /** Same as getStringValue (the reason is at {@link RecordTiming#toString()}). */
        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    // ----ESP render mode----

    /** The ESP/health-bar render mode (no "off" - on/off belongs to each ESP/health bar's master switch):
     * NORMAL draws only the LEQUAL depth layer for ESP; THROUGH_WALLS stacks LEQUAL + ALWAYS
     * for ESP, keeping solid lines in front of walls and adding translucent x-ray behind; the
     * health bar reusing this enum picks LEQUAL or ALWAYS respectively. */
    public enum EspRenderMode implements IConfigOptionListEntry {
        NORMAL("normal"),
        THROUGH_WALLS("through_walls");

        /** The UI dropdown set, accounting per {@link RecordTiming#VALUES}. */
        public static final ImmutableList<EspRenderMode> VALUES = ImmutableList.copyOf(values());

        private final String configString;

        EspRenderMode(String configString) {
            this.configString = configString;
        }

        /** Serialized value, same as {@link RecordTiming#getStringValue()} (lower-case configString). */
        @Override
        public String getStringValue() {
            return this.configString;
        }

        /** UI display name by the current language; translation key
         * {@code nomorezombies.config.qol.espRenderMode.<configString>}. */
        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.qol.espRenderMode." + this.configString);
        }

        /** Config file string -> enum: falls back to NORMAL on no match;
         * comparison rules per {@link RecordTiming#fromString(String)}. */
        @Override
        public EspRenderMode fromString(String value) {
            for (EspRenderMode v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return NORMAL;
        }

        /** Cycles: wraps to the start when out of range, same rule as {@link RecordTiming#cycle(boolean)}. */
        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            return cycleOption(java.util.Arrays.asList(values()), this.ordinal(), forward);
        }

        /** Same as getStringValue (the reason is at {@link RecordTiming#toString()}). */
        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    // ----Persistence----

    /**
     * Reads all config groups from {@code config/nomorezombies.json} (MaLiLib
     * format), filling each group's OPTIONS back by section name.
     *
     * <p>A missing file keeps factory defaults and writes to disk immediately
     * (first creation); an existing but unreadable file is silently skipped
     * with factory defaults kept, so first boot never errors;
     * only a JSON parse failure logs one error, easing diagnosis of a corrupt
     * config file.
     *
     * <p>After reading, {@link #normalizeAnchorKeys()} always runs: the HUD
     * anchor ratios' legal range is {@code 0~1}, while old config files may
     * still carry old-semantics negative values, and unclamped they would be
     * interpreted as "hug the leading edge" at runtime (see that method's note).
     */
    public static void loadFromFile() {
        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile)) {
            JsonElement element = JsonUtils.parseJsonFileAsPath(configFile);

            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();

                ConfigUtils.readConfigBase(root, "Spawntimes", Spawntimes.OPTIONS);
                ConfigUtils.readConfigBase(root, "Powerups", Powerups.OPTIONS);
                ConfigUtils.readConfigBase(root, "Record", Record.OPTIONS);
                ConfigUtils.readConfigBase(root, "AAAutoCommand", AAAutoCommand.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hud", Hud.OPTIONS);
                ConfigUtils.readConfigBase(root, "QoL", QoL.OPTIONS);
                ConfigUtils.readConfigBase(root, "Query", Query.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hide", Hide.OPTIONS);
                ConfigUtils.readConfigBase(root, "Zoom", Zoom.OPTIONS);
                ConfigUtils.readConfigBase(root, "Sneak", Sneak.OPTIONS);
                ConfigUtils.readConfigBase(root, "Gamma", Gamma.OPTIONS);
                ConfigUtils.readConfigBase(root, "FreeCamera", FreeCam.OPTIONS);

                // Reads recognize only current key names; extra keys in the config file are ignored:
                // of the 11 HUDs' independent visibility, all are off except roll stats (on out of
                // the box), and the master switch HUD_MASTER defaults on;
                // defaults apply only when the config file is missing; existing same-key values in
                // old configs are kept as is.
                //
                // Coordinate keys are clamped uniformly after reading: anchor ratios' legal range is
                // 0~1 and negatives are not part of it;
                // out-of-range values are clamped into range (see normalizeAnchorKeys)
                normalizeAnchorKeys();
            } else {
                NoMoreZombies.LOGGER.error("loadFromFile(): Failed to parse config file '{}' as a JSON element.", configFile.toAbsolutePath());
            }
        } else if (!Files.exists(configFile)) {
            // First creation: write factory defaults to disk immediately (anchor ratio's three
            // tiers, resolution-independent and client-language-independent too, see
            // {@code Hud#isChineseClient()});
            // happens only when "the file does not exist" - once it exists, later client-language
            // changes never rearrange the layout
            // (the coordinates the player dragged are authoritative). Deliberately not
            // overwriting "exists but unreadable / unparseable" files: those are mostly
            // hand-broken by the player, and wiping to defaults equals losing the config - logging
            // only is safer
            saveToFile();
        }
    }

    /** Writes all config groups back to {@code config/nomorezombies.json} in MaLiLib format;
     *  the config directory is created first when missing, so a first save never misses. */
    public static void saveToFile() {
        Path dir = FileUtils.getConfigDirectoryAsPath();

        if (!Files.exists(dir)) {
            FileUtils.createDirectoriesIfMissing(dir);
        }

        if (Files.isDirectory(dir)) {
            JsonObject root = new JsonObject();

            ConfigUtils.writeConfigBase(root, "Spawntimes", Spawntimes.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Powerups", Powerups.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Record", Record.OPTIONS);
            ConfigUtils.writeConfigBase(root, "AAAutoCommand", AAAutoCommand.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hud", Hud.OPTIONS);
            ConfigUtils.writeConfigBase(root, "QoL", QoL.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Query", Query.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hide", Hide.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Zoom", Zoom.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Sneak", Sneak.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Gamma", Gamma.OPTIONS);
            ConfigUtils.writeConfigBase(root, "FreeCamera", FreeCam.OPTIONS);

            JsonUtils.writeJsonToFileAsPath(root, dir.resolve(CONFIG_FILE_NAME));
        }
    }

    /** The config-change callback: persist first, then reload, keeping file and memory always in sync -
     *  a change must take effect immediately and survive. */
    @Override
    public void onConfigsChanged() {
        saveToFile();
        loadFromFile();
    }

    /** MaLiLib lifecycle callback: reads the config file once at game startup, recovering the last settings. */
    @Override
    public void load() {
        loadFromFile();
    }

    /** MaLiLib lifecycle callback: writes current settings at exit/save time so closing the game never loses config. */
    @Override
    public void save() {
        saveToFile();
    }

    // ----HUD anchor resolution (anchor ratios, cross-resolution)----
    //
    // The return value is an <b>anchor ratio</b> (0.0-1.0), not a "top-left screen ratio":
    // 0.0 = hug the leading edge (left/top), 0.5 = centered, 1.0 = hug the trailing edge
    // (right/bottom).
    // The travel ([reserve, screenW - contentW - reserve]) is computed live by the renderer
    // from the screen width; this only answers "where along the stretch".
    //
    // Usage in a renderer:
    //   int w = Math.round(hudWidth(tr) * scale);
    //   int x = TotalHUDRenderer.anchorPixels(getXFoo(), screenWidth, w, RESERVE_X);
    // In the editor: workX is the same anchor ratio (resolveWorkX's sentinel fallback normalizes
    // to the same tier).
    //
    // The same-named coordinate keys in old config files carried different semantics;
    // loadFromFile() pulls out-of-range values back into 0~1 (see normalizeAnchorKeys).
    //
    // Factory defaults therefore only have three usable tiers: 0 / 0.5 / 1.

    // ----Wave time HUD: bottom-right by default, right- and bottom-hugging----

    /**
     * The wave time HUD's default right-hug (anchor ratio 1.0).
     *
     * <p>Width is no longer computed here: the renderer measures the component
     * width this frame and anchorPixels pins it to the right edge;
     * changing resolution keeps it right-hugging, because "right-hug" is now
     * literally the number 1.0.
     */
    public static double getXSpawnTime() {
        return clampAnchor(Hud.X_SPAWN_TIME.getDoubleValue());
    }

    /** The wave time HUD's default bottom-hug (anchor ratio 1.0; the renderer declares no reserve). */
    public static double getYSpawnTime() {
        return clampAnchor(Hud.Y_SPAWN_TIME.getDoubleValue());
    }

    // ----Power-up HUD: left side, vertically centered by default----

    /** The power-up HUD's default left-hug (anchor ratio 0.0). */
    public static double getXPowerup() {
        return clampAnchor(Hud.X_POWERUP.getDoubleValue());
    }

    /** The power-up HUD's default vertical center (anchor ratio 0.5). */
    public static double getYPowerup() {
        return clampAnchor(Hud.Y_POWERUP.getDoubleValue());
    }

    // ----Team stats HUD: top-left by default----

    /** The team stats HUD's default left-hug (anchor ratio 0.0); the table lays out from the left. */
    public static double getXTeamStats() {
        return clampAnchor(Hud.X_TEAM_STATS.getDoubleValue());
    }

    /** The team stats HUD's default top-hug (anchor ratio 0.0); the table lays out from the top. */
    public static double getYTeamStats() {
        return clampAnchor(Hud.Y_TEAM_STATS.getDoubleValue());
    }

    // ----Time HUD: top-right by default, right-hugging without overflow----

    /** The time HUD's default right-hug (anchor ratio 1.0). */
    public static double getXGameTime() {
        return clampAnchor(Hud.X_GAME_TIME.getDoubleValue());
    }

    /** The time HUD's default top-hug (anchor ratio 0.0), pinned firmly top-right. */
    public static double getYGameTime() {
        return clampAnchor(Hud.Y_GAME_TIME.getDoubleValue());
    }

    // ----Lightning rod queue HUD: horizontally centered, above the hotbar by default----

    /** The lightning rod queue HUD's default horizontal center (anchor ratio 0.5) - centering is resolution-independent. */
    public static double getXLRQueue() {
        return clampAnchor(Hud.X_LRQUEUE.getDoubleValue());
    }

    /** The lightning rod queue HUD's default just above the hotbar (anchor ratio 1.0). */
    public static double getYLRQueue() {
        return clampAnchor(Hud.Y_LRQUEUE.getDoubleValue());
    }

    // ----AA command HUD: bottom-left by default----

    /** The AA command HUD's default left-hug (anchor ratio 0.0); text rows lay out from the left. */
    public static double getXAAAutoCommand() {
        return clampAnchor(Hud.X_AA_AUTO_COMMAND.getDoubleValue());
    }

    /** The AA command HUD's default bottom-hug (anchor ratio 1.0; the renderer reserves hotbar space). */
    public static double getYAAAutoCommand() {
        return clampAnchor(Hud.Y_AA_AUTO_COMMAND.getDoubleValue());
    }

    // ----CPS HUD: middle-right by default----

    /** The CPS HUD's default right-hug (anchor ratio 1.0). */
    public static double getXCps() {
        return clampAnchor(Hud.X_CPS.getDoubleValue());
    }

    /** The CPS HUD's default vertical center (anchor ratio 0.5). */
    public static double getYCps() {
        return clampAnchor(Hud.Y_CPS.getDoubleValue());
    }

    // ----Global overview HUD: top-right by default----

    /** The global overview HUD's default right-hug (anchor ratio 1.0). */
    public static double getXGlobalOverview() {
        return clampAnchor(Hud.X_GLOBAL_OVERVIEW.getDoubleValue());
    }

    /** The global overview HUD's default top-hug (anchor ratio 0.0), on the same horizontal line as team stats. */
    public static double getYGlobalOverview() {
        return clampAnchor(Hud.Y_GLOBAL_OVERVIEW.getDoubleValue());
    }

    // ----Status effects HUD: upper-right, slightly toward center (near the vanilla effects HUD)----

    /** The status effects HUD's default right-hug (anchor ratio 1.0). */
    public static double getXStatusEffects() {
        return clampAnchor(Hud.X_STATUS_EFFECTS.getDoubleValue());
    }

    /** The status effects HUD's default Y ratio 0.45, upper-middle-right - exactly where vanilla's effect icons sit. */
    public static double getYStatusEffects() {
        return clampAnchor(Hud.Y_STATUS_EFFECTS.getDoubleValue());
    }

    // ----Scoreboard HUD: default pixel-coincident with the vanilla sidebar (right-hug + vertical center)----

    /**
     * The scoreboard HUD's default right-hug (anchor ratio 1.0).
     *
     * <p>"Right-hug" is just 1.0: the renderer measures the width from the
     * current screen's row count and positions; row-count changes never push
     * the sidebar off screen.
     */
    public static double getXScoreboard() {
        return clampAnchor(Hud.X_SCOREBOARD.getDoubleValue());
    }

    /** The scoreboard HUD's default vertical center (anchor ratio 0.5). */
    public static double getYScoreboard() {
        return clampAnchor(Hud.Y_SCOREBOARD.getDoubleValue());
    }

    // ----Roll stats HUD: top-left by default (in the "battle corner" with team stats)----

    /** The roll stats HUD's default left-hug (anchor ratio 0.0). */
    public static double getXRollStats() {
        return clampAnchor(Hud.X_ROLL_STATS.getDoubleValue());
    }

    /** The roll stats HUD's default top-hug (anchor ratio 0.0). */
    public static double getYRollStats() {
        return clampAnchor(Hud.Y_ROLL_STATS.getDoubleValue());
    }

    /** The anchor ratio's shared clamp: out-of-range/old sentinel values are clamped into 0~1 (see
     * {@code TotalHUDRenderer#clampAnchorRatio}). */
    private static double clampAnchor(double raw) {
        return cn.gsfy.nmz.client.features.gamehud.TotalHUDRenderer.clampAnchorRatio(raw);
    }

    /**
     * Clamps all HUD coordinate keys into the anchor ratio's legal range
     * {@code 0~1}.
     *
     * <p>{@code clampAnchor} would interpret a negative as "hug the leading
     * edge", and a negative is not a legal state under the anchor accounting;
     * old config files may still carry old-semantics coordinate values, which
     * must be explicitly clamped after reading - otherwise runtime would
     * interpret them as "everything hugging the top-left corner",
     * which is nothing like the factory layout.
     *
     * <p><b>Deliberately no numeric conversion</b>: the old semantics'
     * value was "the top-left corner's ratio of the screen width", and
     * converting to an anchor ratio needs the original screen width and
     * component width - neither stored in the config - so only range clamping
     * happens; to return to the factory layout, delete
     * {@code config/nomorezombies.json}.
     */
    private static void normalizeAnchorKeys() {
        int clamped = 0;
        for (ConfigDouble key : ANCHOR_KEYS) {
            double raw = key.getDoubleValue();
            double fixed = clampAnchor(raw);
            if (fixed != raw) {
                key.setDoubleValue(fixed);
                clamped++;
            }
        }
        if (clamped > 0) {
            NoMoreZombies.LOGGER.info(
                    "[配置] {} 个HUD锚点比例被收进0~1(旧口径的负值哨兵不再表示'未拖动');"
                            + "若布局不符合预期,删除配置文件即可回到出厂布局", clamped);
        }
    }

    /** All HUD coordinate keys (22) - iterated only by {@link #normalizeAnchorKeys()}. */
    private static final java.util.List<ConfigDouble> ANCHOR_KEYS = java.util.List.of(
            Hud.X_SPAWN_TIME, Hud.Y_SPAWN_TIME,
            Hud.X_POWERUP, Hud.Y_POWERUP,
            Hud.X_TEAM_STATS, Hud.Y_TEAM_STATS,
            Hud.X_GAME_TIME, Hud.Y_GAME_TIME,
            Hud.X_LRQUEUE, Hud.Y_LRQUEUE,
            Hud.X_AA_AUTO_COMMAND, Hud.Y_AA_AUTO_COMMAND,
            Hud.X_CPS, Hud.Y_CPS,
            Hud.X_GLOBAL_OVERVIEW, Hud.Y_GLOBAL_OVERVIEW,
            Hud.X_STATUS_EFFECTS, Hud.Y_STATUS_EFFECTS,
            Hud.X_SCOREBOARD, Hud.Y_SCOREBOARD,
            Hud.X_ROLL_STATS, Hud.Y_ROLL_STATS);
}