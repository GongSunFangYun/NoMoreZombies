package cn.gsfy.nmz.mixin.client.accessor;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes RenderLayer.of (a package-private static method)—the registration
 * entry point for custom RenderLayers.
 *
 * <p>The target is {@code net.minecraft.client.render.RenderLayer}.
 * {@code of} is package-private static and unreachable from outside;
 * {@code @Invoker} promotes it to a public static method, letting custom
 * layers (e.g. entity outlines) register. The target is the 5-arg version
 * of(String, VertexFormat, DrawMode, int, MultiPhaseParameters).
 *
 * <p>No runtime feature switch here: it only provides the structural
 * capability for {@code EspRenderLayer} to build layers, and whether a
 * layer is actually used is gated by the caller. But an Invoker alone is
 * not enough: the return type MultiPhase is equally package-private, and
 * the interface cannot declare it; so an accesswidener is paired in to
 * widen {@code RenderLayer$MultiPhase} to public—only then does the
 * Invoker get the exact return type, get matched by Mixin, and let
 * EspRenderLayer's custom LINES layer be built.
 */
@Mixin(RenderLayer.class)
public interface RenderLayerAccessor {

    /** Calls the package-private of: EspRenderLayer enters here to register
     *  a layer when building LINES. */
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