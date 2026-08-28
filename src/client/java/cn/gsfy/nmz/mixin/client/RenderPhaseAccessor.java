package cn.gsfy.nmz.mixin.client;

import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 RenderPhase 的 protected 常量——自定义 RenderLayer 拼材质参数时不用自己造。
 *
 * <p>RenderPhase 的这些常量都是 protected，自定义 {@code RenderLayer} 直接够不着；用
 * {@code @Accessor} 把它们揭成 public 静态 getter，随取随用。字段名以 yarn 1.21.4+build.8 为准
 * （javap 实证）。
 */
@Mixin(RenderPhase.class)
public interface RenderPhaseAccessor {

    @Accessor("LINES_PROGRAM")
    static RenderPhase.ShaderProgram getLinesProgram() {
        throw new AssertionError();
    }

    @Accessor("TRANSLUCENT_TRANSPARENCY")
    static RenderPhase.Transparency getTranslucentTransparency() {
        throw new AssertionError();
    }

    @Accessor("VIEW_OFFSET_Z_LAYERING")
    static RenderPhase.Layering getViewOffsetZLayering() {
        throw new AssertionError();
    }

    @Accessor("ITEM_ENTITY_TARGET")
    static RenderPhase.Target getItemEntityTarget() {
        throw new AssertionError();
    }

    @Accessor("ALL_MASK")
    static RenderPhase.WriteMaskState getAllMask() {
        throw new AssertionError();
    }

    @Accessor("DISABLE_CULLING")
    static RenderPhase.Cull getDisableCulling() {
        throw new AssertionError();
    }

    @Accessor("ALWAYS_DEPTH_TEST")
    static RenderPhase.DepthTest getAlwaysDepthTest() {
        throw new AssertionError();
    }

    @Accessor("LEQUAL_DEPTH_TEST")
    static RenderPhase.DepthTest getLequalDepthTest() {
        throw new AssertionError();
    }
}
