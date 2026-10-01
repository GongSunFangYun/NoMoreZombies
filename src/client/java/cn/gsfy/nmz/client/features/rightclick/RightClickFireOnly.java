package cn.gsfy.nmz.client.features.rightclick;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;

/**
 * Blocks every right-click action except firing: hologram pass-through plus
 * skipping the right-click response of interactable blocks.
 *
 * <p>In Hypixel Zombies, the floating text for door prices, machine hints
 * and item names is all invisible armor stands. The crosshair ray hits one
 * of them first, so the right-click goes to "interact with armor stand"
 * instead of "use item (fire)", the gun never fires, and the hand sticks.
 * With this on, two places cooperate:
 * <ul>
 *  <li>The crosshair's entity ray excludes armor stands (see
 *  {@code GameRendererCrosshairMixin});</li>
 *  <li>The right-click response of interactable blocks (machines / buttons
 *  / doors) is skipped; the right-click is only for firing
 *  (see {@code MinecraftClientItemUseMixin})</li>
 * </ul>
 *
 * <p>The cost: while this is on, machines and doors cannot be operated -
 * turn the switch off temporarily before buying a gun or opening a door.
 * Applies in Zombies only. Mixins query it statically; no wiring in
 * NoMoreZombiesClient.
 */
public final class RightClickFireOnly {

    private RightClickFireOnly() {
    }

    /** Whether currently active: master switch on and in a Zombies game. */
    public static boolean isActive() {
        return GlobalConfig.QoL.RIGHT_CLICK_FIRE_ONLY.getBooleanValue()
                && PlayerUtils.isInZombies();
    }
}