package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

/**
 * MaLiLib 热键提供者——把本 mod 的可绑定热键全部交给 MaLiLib 的热键管理。
 *
 * <p>addKeysToMap 把每个热键登记进键位表，按下才会被监听；addHotkeys 再把它们
 * 按分类展示（含设置界面里的按键绑定入口）。两个步骤缺一不可：
 * 只登记不展示，设置界面里就找不到键去改绑定。
 */
public class InputHandler implements IKeybindProvider {

    private static final InputHandler INSTANCE = new InputHandler();

    private InputHandler() {
        super();
    }

    public static InputHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : GlobalConfig.QoL.ALL_HOTKEYS) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(NoMoreZombies.MOD_NAME, "nomorezombies.hotkeys.category.feature", GlobalConfig.QoL.ALL_HOTKEYS);
    }
}
