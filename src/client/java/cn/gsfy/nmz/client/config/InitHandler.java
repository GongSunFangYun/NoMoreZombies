package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.feature.playerquery.PlayerQueryScreen;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import net.minecraft.client.MinecraftClient;

/**
 * MaLiLib 初始化处理器——客户端构造完成（onGameInitDone）后才轮到我登场，
 * 把配置与热键一次性注册好。
 *
 * <p>registerModHandlers 由 MaLiLib 统一回调，不需要我们主动触发；它跑完后
 * MaLiLib 会自动 loadAllConfigs() + updateUsedKeys()，所以我们只负责把配置
 * 和热键摆上台面，加载与键位同步交给框架兜底。
 */
public class InitHandler implements IInitializationHandler {

    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(NoMoreZombies.MOD_ID, new GlobalConfig());

        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());

        GlobalConfig.QoL.OPEN_GUI_CONFIGS.getKeybind().setCallback(new CallbackOpenConfigGui());
        GlobalConfig.QoL.OPEN_HUD_EDITOR.getKeybind().setCallback(new CallbackOpenHudEditor());
        GlobalConfig.Query.OPEN_QUERY_GUI.getKeybind().setCallback(new CallbackOpenQueryGui());
    }

    private static class CallbackOpenQueryGui implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            MinecraftClient.getInstance().setScreen(
                    new PlayerQueryScreen(MinecraftClient.getInstance().currentScreen));
            return true;
        }
    }

    private static class CallbackOpenConfigGui implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            GuiBase.openGui(new GlobalConfigGui(MinecraftClient.getInstance().currentScreen));
            return true;
        }
    }

    private static class CallbackOpenHudEditor implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            GuiBase.openGui(new HUDEditor(MinecraftClient.getInstance().currentScreen));
            return true;
        }
    }
}
