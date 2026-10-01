package cn.gsfy.nmz.mixin.client.accessor;

import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes RenderPhase's protected constants—so a custom RenderLayer needn't
 * build its material parameters from scratch.
 *
 * <p>No runtime feature switch here: the accessor only serves
 * {@code EspRenderLayer}'s layer assembly, and whether the finished layer
 * is used is gated by the caller. The target is
 * {@code net.minecraft.client.render.RenderPhase}: these constants are all
 * protected, unreachable directly from a custom {@code RenderLayer};
 * {@code @Accessor} promotes them to public static getters, usable on
 * demand. Field names follow yarn 1.21.4+build.8 (javap-verified). One
 * further trap: line width depends on the Normal direction—LINES width is
 * expanded from the vertex Normal attribute, and this accessor only hands
 * out the constant; when drawing a line, the normal must carry the line's
 * real direction (EspRenderer.line has the trap record).
 */
@Mixin(RenderPhase.class)
public interface RenderPhaseAccessor {

    /** Exposes the LINES_PROGRAM constant—EspRenderLayer uses it to
     *  assemble the LINES material. */
    @Accessor("LINES_PROGRAM")
    static RenderPhase.ShaderProgram getLinesProgram() {
        throw new AssertionError();
    }

    /** Exposes the TRANSLUCENT_TRANSPARENCY constant—EspRenderLayer uses it
     *  to assemble the LINES material. */
    @Accessor("TRANSLUCENT_TRANSPARENCY")
    static RenderPhase.Transparency getTranslucentTransparency() {
        throw new AssertionError();
    }

    /** Exposes the VIEW_OFFSET_Z_LAYERING constant—EspRenderLayer uses it to
     *  assemble the LINES material. */
    @Accessor("VIEW_OFFSET_Z_LAYERING")
    static RenderPhase.Layering getViewOffsetZLayering() {
        throw new AssertionError();
    }

    /** Exposes the ITEM_ENTITY_TARGET constant—EspRenderLayer uses it to
     *  assemble the LINES material. */
    @Accessor("ITEM_ENTITY_TARGET")
    static RenderPhase.Target getItemEntityTarget() {
        throw new AssertionError();
    }

    /** Exposes the ALL_MASK constant—EspRenderLayer uses it to assemble the
     *  LINES material. */
    @Accessor("ALL_MASK")
    static RenderPhase.WriteMaskState getAllMask() {
        throw new AssertionError();
    }

    /** Exposes the DISABLE_CULLING constant—EspRenderLayer uses it to
     *  assemble the LINES material. */
    @Accessor("DISABLE_CULLING")
    static RenderPhase.Cull getDisableCulling() {
        throw new AssertionError();
    }

    /** Exposes the ALWAYS_DEPTH_TEST constant—EspRenderLayer uses it to
     *  assemble the through-walls layer's material. */
    @Accessor("ALWAYS_DEPTH_TEST")
    static RenderPhase.DepthTest getAlwaysDepthTest() {
        throw new AssertionError();
    }

    /** Exposes the LEQUAL_DEPTH_TEST constant—EspRenderLayer uses it to
     *  assemble the regular layer's material. */
    @Accessor("LEQUAL_DEPTH_TEST")
    static RenderPhase.DepthTest getLequalDepthTest() {
        throw new AssertionError();
    }
}