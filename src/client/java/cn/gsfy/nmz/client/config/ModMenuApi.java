package cn.gsfy.nmz.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;

/**
 * ModMenu integration entry - attaches a config button to NoMoreZombies in
 * ModMenu's mod list; clicking it opens the MaLiLib config screen.
 *
 * <p>{@code getModConfigScreenFactory} returns a factory that wires the
 * parent screen into {@link GlobalConfigGui}, so ESC walks all the way back
 * to ModMenu instead of getting stuck in the child screen.
 */
public class ModMenuApi implements com.terraformersmc.modmenu.api.ModMenuApi {

    /** Returns a factory that wires the parent screen into
     * {@link GlobalConfigGui}: ESC walks back to ModMenu instead of getting
     * stuck */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> {
            GlobalConfigGui gui = new GlobalConfigGui(screen);
            gui.setParent(screen); // parent points back to ModMenu
            return gui;
        };
    }
}
