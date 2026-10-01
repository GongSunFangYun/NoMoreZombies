package cn.gsfy.nmz.client.features.invisibility;

import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Player invisibility (counterpart of the source PlayerInvisibility).
 *
 * <p>Current implementation: a close (&lt;1.4), non-sleeping player with
 * maxHealth &lt; 100 has its rendering cancelled outright—a player hugging
 * the camera and blocking the view is best kept out of sight. Distance
 * compares squared values, no sqrt. Opacity fading (VCP wrapping) is left as
 * a later enhancement.
 *
 * <p>The two exemptions each block one class of false positive. <b>Sleeping
 * entities are not hidden</b>: a downed teammate's body is a sleeping-pose
 * player entity (TeamStatsManager uses it to associate downed-body
 * coordinates), and hiding it would take the body and the "where did someone
 * go down" information with it. <b>The maxHealth floor is a conservative
 * exclusion only</b>: an entity at health ≥ 100 is not a target. Outside a
 * Zombies game, always pass through—the lobby is unaffected.
 */
public class HideNearbyPlayer {

    private static HideNearbyPlayer instance;

    /** Global singleton, non-null only after {@link #init}; mixins always
     *  reach it through {@link #shouldHide}, never touching the singleton
     *  directly. */
    public static HideNearbyPlayer get() {
        return instance;
    }

    /** Builds the singleton—pure instantiation, no event subscription; the
     *  decision logic is pulled by EntityRendererMixin at render time through
     *  {@link #shouldHide}. */
    public void init() {
        instance = this;
    }

    /**
     * Called by EntityRendererMixin just before {@code shouldRender}: hides
     * only other ordinary players close to the camera inside a Zombies game.
     *
     * <p>The maxHealth floor is a conservative exclusion only: an entity at
     * health ≥ 100 is not a target.
     *
     * @param entity the entity to test; {@code null} or a non-player entity
     *   passes through
     * @return {@code true} when in-game, not self, not sleeping,
     *   {@code maxHealth < 100} and the squared distance is below
     *   {@code 1.4*1.4}
     */
    public boolean shouldHide(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        if (!PlayerUtils.isInZombies()) {
            return false;
        }
        if (!(entity instanceof PlayerEntity player)) {
            return false;
        }
        if (player == client.player) {
            return false;
        }
        if (player.isSleeping()) {
            return false;
        }
        if (player.getMaxHealth() >= 100) {
            return false;
        }
        return client.player.squaredDistanceTo(player) < 1.4 * 1.4;
    }

    /** Constructed once at client initialization; {@link #init} then publishes
     *  the singleton, for render-mixin null-checked fetches. */
    public HideNearbyPlayer() {
    }
}