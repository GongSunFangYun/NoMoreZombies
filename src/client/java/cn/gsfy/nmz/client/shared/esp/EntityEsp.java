package cn.gsfy.nmz.client.shared.esp;

import cn.gsfy.nmz.client.features.stats.TeamStats;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * ESP / health-bar target check for entities—answers "should this entity be
 * boxed / shown a health bar," always aligned with the team state machine
 * {@link TeamStats} (the machine is authoritative).
 *
 * <p>Hostile mobs go down one straight line: anything implementing
 * {@link Monster} (zombies, piglins, blazes, endermites, silverfish, giants,
 * slimes, magma cubes, ghasts, etc.) plus wolves plus iron golems (Zombies
 * mode's summons)—green outline / health bar.
 *
 * <p>Players are three-state: on the roster with IN_COMBAT → red outline;
 * off-roster but a random-name downed body (associated by entity ID at the
 * downed instant, reverse-looked-up via
 * {@link TeamStats#getDownedBodyOwner(int)}) → yellow outline, and only while
 * that roster player's status is DOWNED (the state machine is authoritative).
 * Self downed / dead / left → not boxed.
 *
 * <p>During death spectating (pseudo-spectating) the server sends dead
 * teammates' corpse entities back to the client too, and a roster-only check
 * would box teammates who are just as dead. So two extra filters sit on top
 * of the roster status (IN_COMBAT): first, a whitelist of teammates "still
 * in combat at the local player's death instant"
 * ({@link TeamStats#wasAliveWhenSelfDied(String)})—a corpse is not boxed
 * even if the roster is misjudged as in-combat; second, an entity-alive
 * check (health &gt; 0)—a corpse entity at 0 health is excluded outright
 * (no effect on ordinary in-combat players).
 *
 * <p>Applies in Zombies games only; pure client-side rendering, sends no
 * data to the server.
 */
public final class EntityEsp {

    /**
     * Decides whether an entity is an ESP / health-bar target—called by the
     * renderer per entity; a hit boxes it / shows the bar.
     *
     * @param entity the entity to test
     * @return {@code true} when the entity should be boxed / shown a health bar
     */
    public static boolean isTarget(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof PlayerEntity player) {
            if (player.getGameProfile() == null) {
                return false;
            }
            String name = player.getGameProfile().getName();
            TeamStats.Status status = TeamStats.getStatus(name);
            if (status == null) {
                // Off-roster player entity: only a "random-name downed body" is boxed -
                // the coordinate association was built by entity ID at the downed instant,
                // so a name lookup alone cannot find it.
                // Self downed is not boxed (one's own body is never associated); spectator /
                // misc entities have no association either and are not boxed.
                String owner = TeamStats.getDownedBodyOwner(player.getId());
                if (owner == null) {
                    return false;
                }
                // The state machine is authoritative: box only while downed, guarding
                // against false boxes from a delayed body cleanup or a reused entity ID
                return TeamStats.getStatus(owner) == TeamStats.Status.DOWNED;
            }
            if (status == TeamStats.Status.IN_COMBAT) {
                // (1) Death-spectating filter: while the local player is death spectating
                // (pseudo-spectating), box only teammates "still in combat at the local death
                // instant" - a corpse is not boxed even if the roster was misjudged as
                // in-combat; while the local player is alive this whitelist has no effect.
                boolean selfSpectating  = TeamStats.isSelfSpectating();
                boolean wasAliveAtDeath = TeamStats.wasAliveWhenSelfDied(name);
                boolean corpseGuard     = selfSpectating && !wasAliveAtDeath;

                // (2) Entity-alive fallback: a corpse entity at 0 health is not boxed
                // (no effect on ordinary in-combat players).
                boolean entityAlive = player.isAlive() && player.getHealth() > 0.0F;

                return !corpseGuard && entityAlive;
            }
            // DOWNED (self's own entity present while downed) / DEAD / LEFT → not boxed
            return false;
        }
        // Hostile mobs (the Monster interface, including non-HostileEntity mobs like
        // slimes / magma cubes / ghasts) + wolves + iron golems (Zombies mode summons)
        // → green outline / health bar
        if (entity instanceof Monster || entity instanceof WolfEntity || entity instanceof IronGolemEntity) {
            return PlayerUtils.isInZombies();
        }
        return false;
    }

    private EntityEsp() {
    }
}