package cn.gsfy.nmz.mixin.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 RenderLayer.of（包私有静态方法）——自定义 RenderLayer 的注册入口。
 *
 * <p>of 是包私有静态方法，外部够不着；用 {@code @Invoker} 把它揭成 public 静态方法，
 * 自定义渲染层（如实体描边）才能注册。目标为 5 参版本
 * of(String, VertexFormat, DrawMode, int, MultiPhaseParameters)。
 */
@Mixin(RenderLayer.class)
public interface RenderLayerAccessor {

    @Invoker("of")
    static RenderLayer.MultiPhase invokeOf(
            String name,
            net.minecraft.client.render.VertexFormat vertexFormat,
            VertexFormat.DrawMode drawMode,
            int expectedBufferSize,
            RenderLayer.MultiPhaseParameters phases
    ) {
        throw new AssertionError();
    }
}
