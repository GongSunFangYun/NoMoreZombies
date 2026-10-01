package cn.gsfy.nmz.mixin.client.accessor;

import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasHolder;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code SpriteAtlasHolder.getSprite(Identifier)} (protected →
 * Invoker)—the status-effect HUD's editor preview needs to fetch a sprite
 * directly by effect id (speed/strength/regeneration), while
 * {@link net.minecraft.client.texture.StatusEffectSpriteManager}'s public
 * overload accepts only {@code RegistryEntry<StatusEffect>}; the preview is
 * a static sample with no registry instance available, so an Invoker opens
 * the protected method.
 *
 * <p>No runtime feature switch here: it is only called when the
 * {@code HUDEditor} preview needs a static sample. The target is
 * {@link SpriteAtlasHolder}: {@code getSprite(Identifier)} is declared on
 * this superclass, and an Invoker only searches its own declaration—so
 * targeting {@code StatusEffectSpriteManager} is wrong: Mixin crashes with
 * "No candidates found" (hit in practice). The caller holds a
 * {@code StatusEffectSpriteManager} instance (a SpriteAtlasHolder subclass)
 * and just casts to this interface to use it.
 */
@Mixin(SpriteAtlasHolder.class)
public interface StatusEffectSpriteManagerAccessor {

    /** Exposes the superclass's protected getSprite: the preview fetches
     *  the icon directly by effect id. */
    @Invoker("getSprite")
    Sprite nmz$getSprite(Identifier id);
}