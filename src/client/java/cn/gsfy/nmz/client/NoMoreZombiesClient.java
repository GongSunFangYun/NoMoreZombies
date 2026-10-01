package cn.gsfy.nmz.client;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.config.InitHandler;
import cn.gsfy.nmz.client.config.hud.RegisterHUD;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.features.damagenumber.DamageNumberRenderer;
import cn.gsfy.nmz.client.features.damagenumber.DamageNumberTracker;
import cn.gsfy.nmz.client.features.esp.EspRenderer;
import cn.gsfy.nmz.client.features.freecam.FreeCameraHandler;
import cn.gsfy.nmz.client.features.gamehud.AAAutoCommand;
import cn.gsfy.nmz.client.features.gamehud.CpsRenderer;
import cn.gsfy.nmz.client.features.gamehud.GlobalOverviewRenderer;
import cn.gsfy.nmz.client.features.gamehud.LightningRodQueue;
import cn.gsfy.nmz.client.features.gamehud.PowerupRenderer;
import cn.gsfy.nmz.client.features.gamehud.RollStatsRenderer;
import cn.gsfy.nmz.client.features.gamehud.SpawnTimeRenderer;
import cn.gsfy.nmz.client.features.gamehud.StatusEffectHudRenderer;
import cn.gsfy.nmz.client.features.gamehud.TeamStatsRenderer;
import cn.gsfy.nmz.client.features.gamehud.TimeHudRenderer;
import cn.gsfy.nmz.client.features.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.features.healthbar.HealthBarRenderer;
import cn.gsfy.nmz.client.features.invisibility.HideNearbyPlayer;
import cn.gsfy.nmz.client.features.querydata.QueryDataManager;
import cn.gsfy.nmz.client.features.powerups.PowerupDetect;
import cn.gsfy.nmz.client.features.rolls.RollStats;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.features.spawntimes.SpawnNotice;
import cn.gsfy.nmz.client.features.stats.TeamStatsManager;
import cn.gsfy.nmz.client.features.zoom.ZoomHandler;
import cn.gsfy.nmz.client.shared.game.DelayedTaskScheduler;
import cn.gsfy.nmz.client.shared.esp.EspTargets;
import cn.gsfy.nmz.client.shared.game.GameTickHandler;
import cn.gsfy.nmz.client.shared.game.ScoreboardManager;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.StringUtils;
import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Client mod entry (Fabric {@link ClientModInitializer}) - every feature
 * stands up from here.
 *
 * <p>{@link #onInitializeClient()} lights the machine up in "foundation
 * first, features after" order: MaLiLib (configs and hotkeys), the data
 * tables and the HUD registry, then the three event hooks (chat / join /
 * disconnect), and finally each feature (waves, powerups, HUD, team stats,
 * ESP, ...) one by one. Registration order is initialization priority;
 * cross-feature dependencies settle in each singleton's {@code init()} -
 * whoever inits first is usable first.
 *
 * <p>Two hard ordering constraints: <b>the HUD registry must come before any
 * editor is constructed</b> (editors know only the registry, not concrete
 * HUDs), and <b>the core foundation must come before features</b> (the
 * scoreboard poll feeds isInZombies and the wall clock feeds waves and
 * timers; callbacks attached in a feature's init read them immediately).
 * There are exactly three event hooks and none contain business logic:
 * chat messages are dispatched in a fixed order to powerup detection /
 * team stats / roll stats (each owns its own state, no cross-dependency;
 * order only affects same-tick visibility); JOIN only resets powerup
 * patterns (patterns are observed per round, void on entering a new world);
 * DISCONNECT resets in "environment caches -> timers -> per-feature state"
 * order - miss one step and the last round's leftovers leak into the next.
 */
public class NoMoreZombiesClient implements ClientModInitializer {

    /** Client entry: hook up MaLiLib, load the data tables/maps, attach the
     *  event hooks, then light up the feature singletons in dependency order */
    @Override
    public void onInitializeClient() {
        // MaLiLib: register the init handler - config loading and hotkey
        // registration both settle in its onGameInitDone
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());

        // Data tables (re-read along with F3+T resource reloads)
        DataManager.init();

        // HUD registry: registers the read/write ports, default positions and
        // samples of the 11 built-in HUDs into RegisterHUD.
        // Must run before any HUDEditor is constructed (editors know only the
        // registry, not concrete HUDs);
        // idempotent - the editor side guards once more, a repeat call only
        // logs one WARN
        RegisterHUD.registerBuiltins();

        // Core foundation: scoreboard polling, wall clock, delayed tasks
        new GameTickHandler().init();
        new DelayedTaskScheduler().init();
        new ScoreboardManager().init();

        // Unified chat entry: dispatches by feature (powerups/revives/team
        // stats/roll stats).
        // Uses the GAME event, not the ChatHud render layer: Lucky Chest
        // filtering blocks rendering only, not this path - filtered messages
        // must still feed roll stats
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String raw = StringUtils.getRaw(message);
            if (PowerupDetect.get() != null) {
                PowerupDetect.get().onChatReceived(raw);
            }
            TeamStatsManager.onChatReceived(raw);
            RollStats.onChatReceived(raw);
        });

        // State cleanup: reset on world join / full reset on disconnect
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (PowerupDetect.get() != null) {
                PowerupDetect.get().iniPowerupPatterns();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Clear the scoreboard cache first: it is the sole data source of
            // isInZombies(); resetting the environment verdict first lets the
            // per-feature resets below see "already left the game"
            ScoreboardManager.get().clear();
            GameTickHandler.get().setGameStarted(false);
            LanguageUtils.invalidateMapCache();
            TotalHUDRenderer.setShouldRender(false);
            QueryDataManager.get().clearCache();
            // Disconnect: zero this round's roll stats - cross-round leftovers
            // would book the last round's draws into the new one
            RollStats.reset();
            if (PowerupDetect.get() != null) {
                PowerupDetect.get().iniPowerupPatterns();
            }
            // Disconnect: cancel all pending delayed tasks - otherwise
            // leftovers fire wildly inside the new round
            if (DelayedTaskScheduler.get() != null) {
                DelayedTaskScheduler.get().cancelAll();
            }
            // Disconnect: restore freecam at once - camera residue would jam
            // the view
            FreeCameraHandler.forceDisable();
            // Disconnect: drop damage numbers and the health baseline - a
            // stale baseline makes a burst of fake numbers on re-entry
            DamageNumberTracker.clear();
        });

        // Wave timing: core logic + HUD + whole-second sounds
        new CheckSpawnTimes().init();
        new SpawnTimeRenderer().init();
        SpawnNotice.update(0);

        // Persistent timer HUD (total game time + current round)
        new TimeHudRenderer().init();

        // Powerups: detection engine + HUD
        new PowerupDetect().init();
        new PowerupRenderer().init();

        // Sidebar/timer/lightning rod queue (LR)
        new LightningRodQueue().init();

        // Alien Arcadium auto-command (HUD + per-round chat output)
        new AAAutoCommand().init();

        // AA auto-command: on client language switch, rewrite the template
        // config to the new language's default (only when never customized)
        ClientTickEvents.START_CLIENT_TICK.register(client -> GlobalConfig.AAAutoCommand.TEMPLATE.checkLanguageChanged());

        // Left/right click CPS HUD
        new CpsRenderer().init();

        // Global overview HUD (current + next 5 rounds, a 6-column boss/
        // powerup spawn table, static-table driven)
        new GlobalOverviewRenderer().init();

        // Status effect HUD (potion effect text list; replaces the vanilla
        // effect HUD inside Zombies rounds only)
        new StatusEffectHudRenderer().init();

        // Smooth zoom (FOV division, inside Zombies rounds only)
        ZoomHandler.INSTANCE.init();

        // Freecam (stand-in camera entity, per-tick start/stop polling, inside
        // Zombies rounds only)
        FreeCameraHandler.INSTANCE.init();

        // Combat/visual feature
        new HideNearbyPlayer().init();

        // Team stats: event intake (per-tick entity snapshot scan + chat
        // parsing) -> data/state machine -> HUD
        new TeamStatsManager().init();
        new TeamStatsRenderer().init();

        // Roll stats: chat parsing -> per-round counts and round table -> HUD
        // (leaving the game resets it via the tick callback in RollStats.init())
        RollStats.init();
        new RollStatsRenderer().init();

        // Player data query: in-round cache management (auto-request at round
        // start, cache destroyed at round end, tick-driven cleanup)
        new QueryDataManager().init();

        // Register the ESP trio together: they share one target scan
        // (EspTargets scans every 10 ticks), renderers only read the cache,
        // and the health bar and damage numbers reuse its refresh window
        EspTargets.init();
        EspRenderer.init();
        HealthBarRenderer.init();

        // Damage/heal numbers: per-tick health diffing produces the numbers,
        // AFTER_ENTITIES draws them in world space.
        // The pair has no mixin: gating self-checks in each init (master
        // switch + isInZombies)
        DamageNumberTracker.init();
        DamageNumberRenderer.init();
    }
}
