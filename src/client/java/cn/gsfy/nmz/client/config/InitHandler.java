package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.config.hud.HUDEditor;
import cn.gsfy.nmz.client.features.querydata.QueryDataScreen;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import net.minecraft.client.MinecraftClient;

/**
 * MaLiLib initialization handler - runs only after the client is constructed
 * (onGameInitDone), registering configs and hotkeys in one pass.
 *
 * <p>registerModHandlers is called back by MaLiLib itself, nothing needs to
 * trigger it; once it returns, MaLiLib automatically runs loadAllConfigs()
 * and updateUsedKeys(), so this class only puts configs and hotkeys on the
 * table - loading and key sync are the framework's job.
 */
public class InitHandler implements IInitializationHandler {

    /** Registers the config handler, the hotkey provider and the three
     * hotkey callbacks with MaLiLib in one pass - the framework handles
     * loading and key sync */
    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(NoMoreZombies.MOD_ID, new GlobalConfig());

        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());

        GlobalConfig.QoL.OPEN_GUI_CONFIGS.getKeybind().setCallback(new CallbackOpenConfigGui());
        GlobalConfig.QoL.OPEN_HUD_EDITOR.getKeybind().setCallback(new CallbackOpenHudEditor());
        GlobalConfig.Query.OPEN_QUERY_GUI.getKeybind().setCallback(new CallbackOpenQueryGui());
    }

    /**
     * "Player query" hotkey: opens the player data query screen on top of
     * the current screen.
     *
     * <p>Returning true marks the key as consumed, so MaLiLib stops further
     * processing - all three callbacks do this.
     */
    private static class CallbackOpenQueryGui implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            MinecraftClient.getInstance().setScreen(
                    new QueryDataScreen(MinecraftClient.getInstance().currentScreen));
            return true;
        }
    }

    /**
     * "Config" hotkey: opens the MaLiLib config screen on top of the current
     * screen; see {@link CallbackOpenQueryGui} for why it returns true.
     */
    private static class CallbackOpenConfigGui implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            GuiBase.openGui(new GlobalConfigGui(MinecraftClient.getInstance().currentScreen));
            return true;
        }
    }

    /**
     * "HUD editor" hotkey: opens the HUD drag editor on top of the current
     * screen; see {@link CallbackOpenQueryGui} for why it returns true.
     */
    private static class CallbackOpenHudEditor implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            GuiBase.openGui(new HUDEditor(MinecraftClient.getInstance().currentScreen));
            return true;
        }
    }
}
