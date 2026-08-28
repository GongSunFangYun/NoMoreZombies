package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.feature.gamma.GammaOverride;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 伽马覆写的光照侧注入——把亮度 uniform 里的 gamma 换成语境外的配置值。
 *
 * <p>vanilla {@link LightmapTextureManager#update(float)} 把亮度作为
 * {@code BrightnessFactor = max(0, gamma - darkness)} uniform 传给 lightmap shader
 * （gamma 读自 {@code mc.options.getGamma()}，darkness 为黑暗度衰减）。覆写生效时
 * （{@link GammaOverride#isActive()}）把该计算中的 gamma 替换为配置覆写值（可 &gt;1，默认 16，
 * 夜视级）——选项字段本身始终在原版合法范围 [0,1]（不再直写，见 {@code GammaOverride}），
 * 因此 {@code GameOptions.save()} 的 codec 校验永不过界
 * （修复「Value 16.0 outside of range [0.0:1.0]」保存失败 → 资源重载 → 黑屏）。原版 lightmap
 * shader 对 BrightnessFactor 最终 clamp 到 [0,1]，覆写值 &gt;1 安全地产生夜视级亮度。
 */
@Mixin(LightmapTextureManager.class)
public class LightmapBrightnessMixin {

    @Redirect(method = "update",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"))
    private float nmz$applyGammaOverrideBrightness(float a, float b) {
        if (GammaOverride.isActive()) {
            // b = gamma - darknessFactor（vanilla 中间值）。用覆写值替换 gamma 分量：
            // b - current + override = (gamma - darkness) - gamma + override = override - darkness
            double current = MinecraftClient.getInstance().options.getGamma().getValue();
            double override = GlobalConfig.Gamma.OVERRIDE_VALUE.getDoubleValue();
            return Math.max(0.0f, (float) (b - current + override));
        }
        return Math.max(a, b);
    }
}
