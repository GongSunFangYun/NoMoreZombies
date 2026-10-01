package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

/**
 * MaLiLib hotkey provider - hands every bindable hotkey of this mod to
 * MaLiLib's keybind management; the single data source is
 * {@code GlobalConfig.QoL.ALL_HOTKEYS}.
 *
 * <p>Registration takes two steps and both are required: addKeysToMap puts
 * each hotkey into the keybind map so presses get listened to; addHotkeys
 * then lists them by category (including the bind entries in the config
 * screen) - register without listing and there is no way to rebind the keys
 * in the settings.
 */
public class InputHandler implements IKeybindProvider {

    private static final InputHandler INSTANCE = new InputHandler();

    private InputHandler() {
        super();
    }

    /** Singleton accessor - MaLiLib's init registers this provider via
     * {@code getInstance()} */
    public static InputHandler getInstance() {
        return INSTANCE;
    }

    /**
     * Registers every hotkey into MaLiLib's keybind map - skip this and key
     * presses go unheard.
     *
     * @param manager MaLiLib keybind manager
     */
    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : GlobalConfig.QoL.ALL_HOTKEYS) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    /**
     * Lists the hotkeys by category for MaLiLib: category key
     * {@code nomorezombies.hotkeys.category.feature}, group display name
     * {@code MOD_NAME}. This step only makes them findable in the settings;
     * the listen side is {@link #addKeysToMap(IKeybindManager)}.
     *
     * @param manager MaLiLib keybind manager
     */
    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(NoMoreZombies.MOD_NAME, "nomorezombies.hotkeys.category.feature", GlobalConfig.QoL.ALL_HOTKEYS);
    }
}
