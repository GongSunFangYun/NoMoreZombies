package cn.gsfy.nmz.client.shared;

import cn.gsfy.nmz.mixin.client.RenderLayerAccessor;
import cn.gsfy.nmz.mixin.client.RenderPhaseAccessor;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

import java.util.OptionalDouble;

/**
 * 提供 ESP 专用的 RenderLayer——给线框渲染备好四种绘制层。
 *
 * <p>上下面的横棱用粗线（{@value #HORIZONTAL_WIDTH}），竖棱用细线（{@value #VERTICAL_WIDTH}）：
 * 默认全 1px 线时包围盒的上下面几乎看不见，粗横棱让水平方向清晰可辨。phases 与原版
 * {@link RenderLayer#getLines()} 一致，仅线宽不同、深度测试按层区分。
 */
public final class EspRenderLayer {

    /** 竖棱线宽（GL 像素）——细线，保证侧棱不糊 */
    private static final double VERTICAL_WIDTH = 2.0;
    /** 上下面的横棱线宽（加粗）——全 1px 时上下面几乎看不见，加粗后才清晰可辨 */
    private static final double HORIZONTAL_WIDTH = 4.0;

    /** 正常深度（LEQUAL）：竖棱 */
    public static final RenderLayer LINES =
            create("nmz_esp_lines", VERTICAL_WIDTH, RenderPhaseAccessor.getLequalDepthTest());
    /** 正常深度（LEQUAL）：上下面的横棱（粗） */
    public static final RenderLayer LINES_THICK =
            create("nmz_esp_lines_thick", HORIZONTAL_WIDTH, RenderPhaseAccessor.getLequalDepthTest());
    /** 穿墙（ALWAYS）：竖棱 */
    public static final RenderLayer LINES_THROUGH_WALLS =
            create("nmz_esp_lines_through_walls", VERTICAL_WIDTH, RenderPhaseAccessor.getAlwaysDepthTest());
    /** 穿墙（ALWAYS）：上下面的横棱（粗） */
    public static final RenderLayer LINES_THROUGH_WALLS_THICK =
            create("nmz_esp_lines_through_walls_thick", HORIZONTAL_WIDTH, RenderPhaseAccessor.getAlwaysDepthTest());

    private static RenderLayer create(String name, double width, RenderPhase.DepthTest depthTest) {
        return RenderLayerAccessor.invokeOf(
                name,
                VertexFormats.LINES,
                VertexFormat.DrawMode.LINES,
                1536,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(RenderPhaseAccessor.getLinesProgram())
                        .lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(width)))
                        .layering(RenderPhaseAccessor.getViewOffsetZLayering())
                        .transparency(RenderPhaseAccessor.getTranslucentTransparency())
                        .target(RenderPhaseAccessor.getItemEntityTarget())
                        .writeMaskState(RenderPhaseAccessor.getAllMask())
                        .cull(RenderPhaseAccessor.getDisableCulling())
                        .depthTest(depthTest)
                        .build(false)
        );
    }

    private EspRenderLayer() {
    }
}
