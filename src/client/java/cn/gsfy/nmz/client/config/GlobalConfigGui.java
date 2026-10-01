package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Objects;

/**
 * MaLiLib config GUI (extends GuiConfigsBase) - three tabs switched by the
 * top buttons, each config row carrying its own toggle/slider/hotkey bind
 * button. The Global page aggregates non-toggle parameters across groups
 * (hotkeys / API Key / sounds / zoom / camera, ...); the Hide Optimization
 * page holds the hide-type toggles (BossBar / shooting particles / fire
 * overlay / chat filtering, ...); the QoL Features page holds the
 * per-feature master switches (HUD / wave sounds / powerups / ESP / health
 * bar / invisibility / zoom, ...).
 *
 * <p>Opened via {@link ModMenuApi} and the openGui hotkey - the only entry
 * to the settings. Switching tabs rebuilds the list and resets the
 * scrollbar; otherwise the old page's row widths linger on the new page
 * and the rows look askew.
 */
public class GlobalConfigGui extends GuiConfigsBase {

    private static ConfigGuiTab tab = ConfigGuiTab.GLOBAL;

    /**
     * Builds the config screen - calls up the MaLiLib base class and
     * registers a screen factory that displays {@link NoMoreZombies#MOD_NAME},
     * keeping the all-lowercase mod id out of the mod switcher list.
     *
     * @param parent screen to return to when the config closes
     */
    public GlobalConfigGui(Screen parent) {
        super(10, 50, NoMoreZombies.MOD_ID, parent, "nomorezombies.config.title");
        // Register the config screen factory explicitly so the mod switcher
        // shows the CamelCase name NoMoreZombies: without this, MaLiLib's
        // auto registration builds the display name via splitCamelCase(modId),
        // and the all-lowercase mod id shows as "nomorezombies" as-is,
        // mismatching the "NoMoreZombies" used everywhere else
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(NoMoreZombies.MOD_ID, NoMoreZombies.MOD_NAME, () -> this));
    }

    /** Rebuilds the top tab buttons: clears the old options, then lays out a
     * switch button per {@link ConfigGuiTab} */
    @Override
    public void initGui() {
        super.initGui();

        this.clearOptions();

        int x = 10;
        int y = 26;

        for (ConfigGuiTab t : ConfigGuiTab.values()) {
            x += this.createButton(x, y, t) + 2;
        }
    }

    /** Adds one tab button at the top (the current tab is greyed out) and
     * returns its width so the next button lines up (width {@code -1} sizes
     * to the text) */
    private int createButton(int x, int y, ConfigGuiTab t) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, t.getDisplayName());
        button.setEnabled(tab != t);
        this.addButton(button, new ButtonListener(t, this));

        return button.getWidth();
    }

    /**
     * The config entries for the current tab, rendered row by row by MaLiLib.
     * Each tab aggregates different groups; the order here is the on-screen
     * order.
     *
     * <p>Split rationale: parameters you fine-tune (hotkeys, API Key, sounds,
     * render mechanics, zoom curve, ...) belong to the Global page; feature
     * master switches you flip in one click belong to the QoL Features page -
     * most carry hotkeys, so high-frequency toggling lives there; "hide or
     * not" tidy-up switches (BossBar / particles / fire overlay / chat
     * filtering) belong to the Hide Optimization page.
     */
    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs;

        switch (tab) {
            // Global page = settings/editor/query hotkeys + API Key + powerup
            // alert output + AA auto-command output mode and chat template +
            // round time record; wave sound map range and sound IDs/pitch +
            // ESP/health bar (boss marks + render mechanics); smooth zoom
            // (multiplier/duration/easing/key behaviour/zoom hotkey) + sneak
            // allowed in GUIs + gamma override value + freecam sub-options
            // (movement/input/speed)
            case GLOBAL -> configs = ImmutableList.<IConfigBase>builder()
                    .add(GlobalConfig.QoL.OPEN_GUI_CONFIGS)
                    .add(GlobalConfig.QoL.OPEN_HUD_EDITOR)
                    .add(GlobalConfig.Query.OPEN_QUERY_GUI)
                    .add(GlobalConfig.Query.API_KEY)
                    .add(GlobalConfig.Powerups.ALERT_OUTPUT)
                    .add(GlobalConfig.AAAutoCommand.OUTPUT)
                    .add(GlobalConfig.AAAutoCommand.TEMPLATE)
                    .add(GlobalConfig.Record.ROUNDS_RECORD)
                    .add(GlobalConfig.Spawntimes.WAVE_SOUND_AA)
                    .add(GlobalConfig.Spawntimes.WAVE_SOUND_DE)
                    .add(GlobalConfig.Spawntimes.WAVE_SOUND_BB)
                    .add(GlobalConfig.Spawntimes.WAVE_SOUND_PRISON)
                    .add(GlobalConfig.Spawntimes.PRECEDED_WAVE_SOUND)
                    .add(GlobalConfig.Spawntimes.PRECEDED_WAVE_PITCH)
                    .add(GlobalConfig.Spawntimes.LAST_WAVE_SOUND)
                    .add(GlobalConfig.Spawntimes.LAST_WAVE_PITCH)
                    .add(GlobalConfig.Spawntimes.COUNTDOWN_SOUND)
                    .add(GlobalConfig.Spawntimes.COUNTDOWN_PITCH)
                    // ESP/health bar block: boss mark toggles first, then each
                    // render mechanic parameter
                    .add(GlobalConfig.QoL.BOSS_HEALTH_BAR_MARK)
                    .add(GlobalConfig.QoL.BOSS_ESP_MARK)
                    .add(GlobalConfig.QoL.TEAMMATE_ESP_RENDER_MODE)
                    .add(GlobalConfig.QoL.ZOMBIE_ESP_RENDER_MODE)
                    .add(GlobalConfig.QoL.POWERUP_ESP_RENDER_MODE)
                    .add(GlobalConfig.QoL.HEALTH_BAR_RENDER_MODE)
                    .add(GlobalConfig.QoL.DAMAGE_NUMBER_RENDER_MODE)
                    .add(GlobalConfig.QoL.THROUGH_WALL_RENDER_DISTANCE)
                    .add(GlobalConfig.Zoom.INITIAL_ZOOM)
                    .add(GlobalConfig.Zoom.ZOOM_IN_TIME)
                    .add(GlobalConfig.Zoom.ZOOM_OUT_TIME)
                    .add(GlobalConfig.Zoom.EASING)
                    .add(GlobalConfig.Zoom.KEY_BEHAVIOUR)
                    .add(GlobalConfig.Zoom.ZOOM_KEY)
                    .add(GlobalConfig.Sneak.ALLOW_IN_GUIS)
                    .add(GlobalConfig.Gamma.OVERRIDE_VALUE)
                    .add(GlobalConfig.FreeCam.PLAYER_MOVEMENT)
                    .add(GlobalConfig.FreeCam.PLAYER_INPUTS)
                    .add(GlobalConfig.FreeCam.SPEED)
                    .build();
            // QoL page = per-feature master switches: HUD / wave sounds /
            // final wave countdown / AA color alerts / powerup prediction /
            // round time / AA auto-command / zoom + freecam / ESP
            // (teammates/zombies/powerups) / health bar / damage numbers /
            // invisibility / right-click fire-only / sneak, sprint, gamma
            case QOL -> configs = ImmutableList.<IConfigBase>builder()
                    .add(GlobalConfig.QoL.HUD_MASTER)
                    .add(GlobalConfig.QoL.WAVE_SOUND_ENABLED)
                    .add(GlobalConfig.Spawntimes.FINAL_WAVE_COUNTDOWN)
                    .add(GlobalConfig.Spawntimes.COLOR_ALERT)
                    .add(GlobalConfig.Powerups.POWERUP_PREDICT)
                    .add(GlobalConfig.QoL.RECORD_ENABLED)
                    .add(GlobalConfig.QoL.TEAMMATE_ESP)
                    .add(GlobalConfig.QoL.ZOMBIE_ESP)
                    .add(GlobalConfig.QoL.POWERUP_ESP)
                    .add(GlobalConfig.QoL.ENTITY_HEALTH_BAR)
                    .add(GlobalConfig.QoL.DAMAGE_NUMBER_ENABLED)
                    .add(GlobalConfig.QoL.PLAYER_INVISIBLE)
                    .add(GlobalConfig.QoL.RIGHT_CLICK_FIRE_ONLY)
                    .add(GlobalConfig.QoL.AA_AUTO_COMMAND_ENABLED)
                    .add(GlobalConfig.QoL.ZOOM_ENABLED)
                    .add(GlobalConfig.QoL.ALWAYS_SNEAK_ENABLED)
                    .add(GlobalConfig.QoL.ALWAYS_SPRINT_ENABLED)
                    .add(GlobalConfig.QoL.GAMMA_OVERRIDE_ENABLED)
                    .add(GlobalConfig.QoL.FREE_CAMERA_ENABLED)
                    .build();
            // Hide Optimization page = hide-type switches: vanilla BossBar +
            // no particles / no fire + chat filtering (gold/windows/hits/
            // chest/areas/join-leave); the scoreboard is not on this page,
            // see the HUD editor element toggles
            case HIDE_OPTIMIZATION -> configs = ImmutableList.<IConfigBase>builder()
                    .add(GlobalConfig.QoL.HIDE_BOSS_BAR)
                    .add(GlobalConfig.QoL.NO_PARTICLES)
                    .add(GlobalConfig.QoL.NO_FIRE_EFFECT)
                    .add(GlobalConfig.Hide.HIDE_GOLD)
                    .add(GlobalConfig.Hide.HIDE_WINDOW)
                    .add(GlobalConfig.Hide.HIDE_HIT_TARGET)
                    .add(GlobalConfig.Hide.HIDE_LUCKY_CHEST)
                    .add(GlobalConfig.Hide.HIDE_OPEN_AREA)
                    .add(GlobalConfig.Hide.HIDE_PLAYER_CONNECTION)
                    .build();
            default -> configs = GlobalConfig.QoL.OPTIONS;
        }

        return ConfigOptionWrapper.createFor(configs);
    }

    /** Top tab button listener: switch tab -> rebuild the list with the new
     * page's row widths -> reset the scrollbar -> redraw, avoiding bleed-over */
    private static class ButtonListener implements IButtonActionListener {

        private final GlobalConfigGui parent;
        private final ConfigGuiTab tab;

        /** Stores the target tab and the parent screen: initGui() creates a
         * fresh listener on every rebuild, so the current tab is only
         * reachable here; the parent triggers reCreateListWidget() and the
         * scrollbar reset on click */
        public ButtonListener(ConfigGuiTab tab, GlobalConfigGui parent) {
            this.tab = tab;
            this.parent = parent;
        }

        /** Tab click: switch page, rebuild the list with the new page's row
         * widths, reset the scrollbar, then redraw - keeps rows of one page
         * from bleeding into another */
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            GlobalConfigGui.tab = this.tab;

            this.parent.reCreateListWidget(); // rebuild the list to apply the new page's row widths
            Objects.requireNonNull(this.parent.getListWidget()).resetScrollbarPosition();
            this.parent.initGui();
        }
    }

    /** The tab enum at the top of the config screen: Global (parameters) /
     * QoL Features (switches) / Hide Optimization (hiding); display names go
     * through translation keys */
    private enum ConfigGuiTab {
        GLOBAL("nomorezombies.config.category.global"),
        HIDE_OPTIMIZATION("nomorezombies.config.category.hideOptimization"),
        QOL("nomorezombies.config.category.qol");

        private final String translationKey;

        ConfigGuiTab(String translationKey) {
            this.translationKey = translationKey;
        }

        /** Tab button display name (via translation key, follows the client
         * language) */
        public String getDisplayName() {
            return StringUtils.translate(this.translationKey);
        }
    }
}
