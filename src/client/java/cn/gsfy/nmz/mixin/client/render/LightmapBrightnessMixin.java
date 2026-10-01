package cn.gsfy.nmz.mixin.client.render;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.features.gamma.GammaOverride;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gamma override's lighting-side injection—replaces the gamma in the
 * brightness uniform with an out-of-context config value.
 *
 * <p>The target is {@code net.minecraft.client.render.LightmapTextureManager}.
 * Vanilla {@link LightmapTextureManager#update(float)} passes brightness to
 * the lightmap shader as
 * {@code BrightnessFactor = max(0, gamma - darkness)} (gamma read from
 * {@code mc.options.getGamma()}, darkness the darkening attenuation). While
 * the override is on ({@link GammaOverride#isActive()}), this computation's
 * gamma is replaced with the config override value (may be &gt;1, default 16,
 * night-vision tier)—the option field itself always stays within vanilla's
 * legal [0,1] range (no more direct write; see {@code GammaOverride}), so
 * {@code GameOptions.save()}'s codec check never goes out of range and the
 * "Value 16.0 outside of range [0.0:1.0] → save failure → resource reload →
 * black screen" chain cannot recur. The vanilla lightmap shader finally
 * clamps BrightnessFactor to [0,1], so an override &gt;1 safely lights the
 * night-vision tier.
 */
@Mixin(LightmapTextureManager.class)
public class LightmapBrightnessMixin {

    /** Injection point: update's Math.max @Redirect—recompute gamma while
     *  the override is on. */
    @Redirect(method = "update",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"))
    private float nmz$applyGammaOverrideBrightness(float a, float b) {
        if (GammaOverride.isActive()) {
            // b = gamma-darknessFactor (vanilla intermediate); the gamma
            // component is swapped for the override value:
            // b-current+override=(gamma-darkness)-gamma+override=override-darkness
            double current = MinecraftClient.getInstance().options.getGamma().getValue();
            double override = GlobalConfig.Gamma.OVERRIDE_VALUE.getDoubleValue();
            return Math.max(0.0f, (float) (b - current + override));
        }
        return Math.max(a, b);
    }
}