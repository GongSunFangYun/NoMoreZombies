package cn.gsfy.nmz.client.shared.esp;

import cn.gsfy.nmz.mixin.client.accessor.RenderLayerAccessor;
import cn.gsfy.nmz.mixin.client.accessor.RenderPhaseAccessor;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

import java.util.OptionalDouble;

/**
 * Provides ESP-only RenderLayers—four draw layers prepared for wireframe
 * rendering.
 *
 * <p>Top/bottom horizontal edges use the thick width
 * ({@value #HORIZONTAL_WIDTH}), vertical edges the thin width
 * ({@value #VERTICAL_WIDTH}): with the default 1px width the bounding box's
 * top/bottom faces are nearly invisible, and the thick horizontal edges make
 * the horizontal direction clearly visible.
 *
 * <p>The phases match vanilla {@link RenderLayer#getLines()} exactly except
 * for the width; the depth test is what distinguishes the layers.
 */
public final class EspRenderLayer {

    /** Vertical edge width (GL pixels)—thin, keeping the side edges unblurred. */
    private static final double VERTICAL_WIDTH = 3.0;
    /** Top/bottom horizontal edge width (thickened)—with 1px the top/bottom faces are nearly invisible; thickening makes them clearly visible. */
    private static final double HORIZONTAL_WIDTH = 6.0;

    /** Regular depth (LEQUAL): vertical edges. */
    public static final RenderLayer LINES =
            create("nmz_esp_lines", VERTICAL_WIDTH, RenderPhaseAccessor.getLequalDepthTest());
    /** Regular depth (LEQUAL): top/bottom horizontal edges (thick). */
    public static final RenderLayer LINES_THICK =
            create("nmz_esp_lines_thick", HORIZONTAL_WIDTH, RenderPhaseAccessor.getLequalDepthTest());
    /** Through-walls (ALWAYS): vertical edges. */
    public static final RenderLayer LINES_THROUGH_WALLS =
            create("nmz_esp_lines_through_walls", VERTICAL_WIDTH, RenderPhaseAccessor.getAlwaysDepthTest());
    /** Through-walls (ALWAYS): top/bottom horizontal edges (thick). */
    public static final RenderLayer LINES_THROUGH_WALLS_THICK =
            create("nmz_esp_lines_through_walls_thick", HORIZONTAL_WIDTH, RenderPhaseAccessor.getAlwaysDepthTest());

    /** Assembles one wireframe RenderLayer: phases copied from vanilla
     *  getLines, only the width and depth test swapped (private API called
     *  through accessors; the four layers share this one construction). */
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