package cn.gsfy.nmz.client.features.sneak;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.MinecraftClient;

/**
 * Permanent sneak—pins the sneak flag at the input layer (see
 * {@code AlwaysSneakMixin}: it rebuilds {@code playerInput} after
 * {@code KeyboardInput.tick} with sneak=true). The crouch animation,
 * ledge-drop prevention, hitbox shrink and sneak packets all go through the
 * vanilla state machine—no side channel.
 *
 * <p>Gate = the QoL master switch + in a Zombies game (same convention as
 * zoom) + not forced while a GUI is open by default (opening the inventory
 * or a container releases it, unless the "Global Config" page's
 * {@code Sneak.ALLOW_IN_GUIS} is on). Mixins query it statically; no wiring
 * in NoMoreZombiesClient.
 */
public final class AlwaysSneak {

    private AlwaysSneak() {
    }

    /** Whether currently active: master switch on, in a Zombies game, and
     *  (allow-in-GUIs is on or no screen is currently open). */
    public static boolean isActive() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            return false;
        }
        return GlobalConfig.QoL.ALWAYS_SNEAK_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies()
                && (GlobalConfig.Sneak.ALLOW_IN_GUIS.getBooleanValue() || mc.currentScreen == null);
    }
}