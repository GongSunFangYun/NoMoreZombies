package cn.gsfy.nmz.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;

/**
 * ModMenu 集成入口——ModMenu 的列表里给 NoMoreZombies 挂上配置按钮，
 * 点它打开 MaLiLib 配置界面。
 *
 * <p>getModConfigScreenFactory 返回一个把父界面绑进 GlobalConfigGui 的工厂：
 * 这样按 ESC 能一路退回 ModMenu，而不是卡在子界面出不去。
 */
public class ModMenuApi implements com.terraformersmc.modmenu.api.ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> {
            GlobalConfigGui gui = new GlobalConfigGui(screen);
            gui.setParent(screen); // 把 ModMenu 设为父界面：按 ESC 才能退回 ModMenu，否则会直接关掉配置页
            return gui;
        };
    }
}
