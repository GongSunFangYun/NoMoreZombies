package cn.gsfy.nmz.client.features.gamma;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;

/**
 * Gamma override (the lighting-side implementation)—while on, pushes
 * brightness to the override value on the "Global Config" page (default 16,
 * night-vision tier, not bound by vanilla's 0–1 brightness slider cap), and
 * restores the player's original brightness on leaving Zombies.
 *
 * <p><b>The override never touches the {@code mc.options.getGamma()} option
 * field</b>: that option's codec accepts only [0,1]—once an override value
 * (say 16) is written into it, any {@code GameOptions.save()} (auto-save
 * after settings change / game exit) encoding 16 goes out of range and
 * fails, logging
 * {@code Error saving option Brightness: Value 16.0 outside of range [0.0:1.0]}
 * —and from there a client resource reload → full-screen black. So the
 * option field always keeps the player's own value; the override applies
 * only at the lighting consumer:
 * {@link cn.gsfy.nmz.mixin.client.render.LightmapBrightnessMixin} replaces
 * BrightnessFactor (computed by vanilla as
 * {@code max(0, gamma-darkness)}) with {@code max(0, override - darkness)}
 * inside {@code LightmapTextureManager.update()}—the vanilla lightmap shader
 * always clamps any BrightnessFactor to [0,1], so an override &gt;1 lights
 * the night-vision tier safely without touching any save path (the option
 * field stays legal forever).
 *
 * <p>Gate = the QoL master switch + in a Zombies game (same convention as
 * zoom): the lobby and singleplayer keep the original brightness; the
 * override applies only in Zombies.
 */
public final class GammaOverride {

    private GammaOverride() {
    }

    /** Whether currently active: master switch on and in a Zombies game
     *  (queried every frame by LightmapBrightnessMixin). */
    public static boolean isActive() {
        return GlobalConfig.QoL.GAMMA_OVERRIDE_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies();
    }
}