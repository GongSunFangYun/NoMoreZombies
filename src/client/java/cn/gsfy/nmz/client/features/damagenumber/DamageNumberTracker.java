package cn.gsfy.nmz.client.features.damagenumber;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.esp.EntityEsp;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Detection layer for damage/heal numbers—compares entity health once per
 * client tick and spawns a number when health changes.
 *
 * <p>Health diffing rather than hooking entity metadata packets: both read
 * the same {@code getHealth()} value, but diffing needs only a tick
 * callback—no mixin, no concern about which thread the injection point
 * lands on. The cost is that multiple hits within one tick merge into one
 * number, which is what a player sees anyway: how much came off this swing.
 *
 * <p>Gating has two stages, and this is the easiest thing in this class to
 * break: the health baseline updates <b>unconditionally</b> (otherwise
 * health jumps like a respawn or a rejoin would get counted into the next
 * diff), while "spawn a number" alone consults
 * {@link EntityEsp#isTarget(Entity)} and the master switch. The baseline
 * table is cleaned by "seen this tick"; an entity that unloads or leaves
 * view drops its baseline, so an entity ID reused later cannot produce a
 * phantom number from a stale health reading.
 */
public final class DamageNumberTracker {

    /** Health-delta deadzone: jitter below this never spawns a number, so
     *  float noise cannot keep flickering digits. */
    private static final float MIN_DELTA = 0.05f;

    /** Cap on simultaneous numbers. An Insta Kill-style wipe on every
     *  entity would grow unbounded; over the cap, the oldest goes first. */
    private static final int MAX_PARTICLES = 256;

    /** Entity ID → last health and the tick it was seen. */
    private static final Map<Integer, Sample> BASELINES = new HashMap<>();
    /** Currently live numbers. Only this class adds and removes. */
    private static final List<DamageNumberParticle> PARTICLES = new ArrayList<>();

    /** World the baseline table belongs to. A world change (entering a
     *  map, reconnecting) discards the whole table. */
    private static World trackedWorld;
    /** This class's tick count, used only to check whether a baseline was
     *  seen this tick. */
    private static int tickCounter;

    /**
     * Registers the per-tick health-diff callback. Call once from the
     * client entrypoint.
     *
     * <p>Hooked on {@code END_CLIENT_TICK}: by then this tick's entity
     * health has settled, so every read sees the same moment.
     */
    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(DamageNumberTracker::onTick);
    }

    /**
     * Clears every baseline, number, and the world reference.
     *
     * <p>Leaving, changing worlds, and disconnecting all take this path—a
     * stale baseline would spawn a burst of fake numbers from a health
     * jump on rejoin.
     */
    public static void clear() {
        BASELINES.clear();
        PARTICLES.clear();
        trackedWorld = null;
    }

    /**
     * Currently live numbers. <b>Returns the internal list</b>, whose
     * structure only this class adds to or removes from.
     *
     * <p>The renderer iterates it only inside {@code AFTER_ENTITIES};
     * adds and removes happen only on the client tick. Both live on the
     * render thread and alternate in frame order, so there is no
     * concurrent modification.
     *
     * @return the current number list; not guaranteed to be valid after
     *   this class's next tick
     */
    static List<DamageNumberParticle> particles() {
        return PARTICLES;
    }

    /**
     * Per-tick entry: gates on the master switch and in-game state, scans
     * entities, then advances the numbers.
     *
     * <p>When the switch is off or the player is out of game, the baselines
     * are cleared along with everything else—re-enabling re-baselines every
     * entity, so health deltas built up while disabled are never backfilled.
     *
     * @param client the client instance
     */
    private static void onTick(MinecraftClient client) {
        boolean active = client.world != null && client.player != null
                && GlobalConfig.QoL.DAMAGE_NUMBER_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies();

        if (!active) {
            if (trackedWorld != null || !BASELINES.isEmpty() || !PARTICLES.isEmpty()) {
                clear();
            }
            return;
        }

        if (trackedWorld != client.world) {
            clear();
            trackedWorld = client.world;
        }

        tickCounter++;
        scan(client.world, client.player);
        tickParticles();
    }

    /**
     * Scans the world's living entities, updates baselines, and spawns
     * numbers from health deltas.
     *
     * <p>Excludes the local player and armor stands: your own numbers in
     * your face are just noise, and armor stands carry all of Hypixel's
     * floating text (holograms, downed-rescue bars, item names) with
     * constant health, so skipping them saves a baseline per stand.
     *
     * <p>Takes {@link ClientWorld} rather than {@code World} because
     * {@code getEntities()} is declared only on the former—the "entities
     * loaded this frame" set, which is exactly this overload's semantics.
     *
     * @param world the current client world
     * @param self the local player
     */
    private static void scan(ClientWorld world, PlayerEntity self) {
        for (Entity entity : world.getEntities()) {
            if (entity == self || !(entity instanceof LivingEntity living)
                    || living instanceof ArmorStandEntity || entity.isRemoved()) {
                continue;
            }

            int id = living.getId();
            float health = living.getHealth();
            Sample previous = BASELINES.put(id, new Sample(health, tickCounter));

            if (previous == null) {
                // First sighting: only establish a baseline, so entering the
                // map does not backfill one number per entity on screen.
                continue;
            }

            float delta = health - previous.health();
            if (Math.abs(delta) < MIN_DELTA || !EntityEsp.isTarget(living)) {
                continue;
            }

            add(living, delta);
        }

        BASELINES.values().removeIf(sample -> sample.seenTick() != tickCounter);
    }

    /**
     * Spawns one number from the health delta: red for damage, green for
     * heal, positioned at the entity's eye height.
     *
     * @param entity the entity whose health changed
     * @param delta health delta; negative means damage
     */
    private static void add(LivingEntity entity, float delta) {
        float x = (float) entity.getX();
        float y = (float) entity.getEyeY();
        float z = (float) entity.getZ();

        DamageNumberParticle particle = delta < 0
                ? DamageNumberParticle.damage(x, y, z, delta)
                : DamageNumberParticle.heal(x, y, z, delta);

        if (PARTICLES.size() >= MAX_PARTICLES) {
            // At capacity, evict the oldest—it sits at the head, and
            // removeFirst shifts the whole list (O(n)), but the list is
            // capped by MAX_PARTICLES, so the cost is bounded.
            PARTICLES.removeFirst();
        }

        PARTICLES.add(particle);
    }

    /** Advances every number by one tick, removing those whose lifetime
     *  is up. */
    private static void tickParticles() {
        // Deliberately not removeIf: tick() in the predicate is a
        // side-effecting advance, not a pure test. The explicit iterator
        // says "advance one step, drop what's due" and is harder to misread.
        for (Iterator<DamageNumberParticle> iterator = PARTICLES.iterator(); iterator.hasNext(); ) {
            if (!iterator.next().tick()) {
                iterator.remove();
            }
        }
    }

    /**
     * One entity's last observed health and the tick that observation
     * happened on.
     *
     * @param health the last health reading
     * @param seenTick the tick count it was last seen
     */
    private record Sample(float health, int seenTick) {
    }

    private DamageNumberTracker() {
    }
}