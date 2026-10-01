package cn.gsfy.nmz.client.features.fireoverlay;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;

/**
 * No-fire effect: fully removes the screen fire overlay while burning—one
 * switch, HEAD cancel of the original render, no redraw, no opacity control.
 *
 * <p>The vanilla fire overlay is only a first-person screen texture with no
 * accompanying logic, so canceling the layer entirely doesn't touch damage,
 * sound, or AI. "Remove completely" is safe.
 *
 * <p>Applies only inside a Zombies game; the lobby and singleplayer are
 * untouched. Mixins query it statically, so no wiring in
 * NoMoreZombiesClient is needed.
 */
public final class FireOverlayManager {

    private FireOverlayManager() {
    }

    /** Whether the fire overlay should be hidden: switch on and in a
     *  Zombies game. */
    public static boolean shouldHide() {
        return GlobalConfig.QoL.NO_FIRE_EFFECT.getBooleanValue()
                && PlayerUtils.isInZombies();
    }
}