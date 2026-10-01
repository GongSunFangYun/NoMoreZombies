package cn.gsfy.nmz.client.shared.esp;

import cn.gsfy.nmz.client.data.model.DifficultyId;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.utils.DifficultyUtils;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.RoundUtils;
import cn.gsfy.nmz.client.utils.StringUtils;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.GiantEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import cn.gsfy.nmz.client.shared.game.GameTickHandler;

/**
 * Boss detection - Giants / Old Ones / mobs carrying Hypixel's native health
 * bar. A boss is a subset of {@link EntityEsp#isTarget}'s targets (the
 * Monster family) and is already collected by the scan; the verdict is
 * consumed only when the render layer refines (orange ESP outline / health
 * bar palette) and never changes the target set itself.
 *
 * <p><b>The health-bar channel (confirmed in real games)</b>: Hypixel's boss
 * health bar is <b>not on the mob</b> - the mob's own {@code getCustomName()}
 * is always null and {@code isCustomNameVisible()} false. The bar is a
 * <b>separate invisible marker armor stand</b> whose CustomName looks like
 * {@code [||||||||||||||||||||]} ({@code [}{@code ]} dark gray + 20 pipes
 * colored red/gray by health; the cell count is constant, only the colors
 * change), floating about 2.2 blocks above the boss, with <b>no riding
 * relation</b> to the mob - coordinates are the only link.
 *
 * <p><b>Similar carriers to avoid</b>: {@code marker=true} armor stands carry
 * all of Hypixel's floating text - the downed-revive bar
 * {@code ■■■■■■■■■■■■■■■}, countdowns {@code 23.9s}, "Hold SHIFT to revive!",
 * power-up names {@code 双倍金钱} etc. {@link #BAR_TAG} requires
 * <b>brackets around pipes</b>, so only boss bars are collected and the bare
 * {@code ■} revive bar is never mistaken for one.
 *
 * <p><b>Per-map split</b>: on AA even trash mobs' health can exceed 100, so
 * the health/bar channels would mislabel and the orange outline would drift -
 * <b>AA uses the type channel only</b> (Giant/Old One); on <b>non-AA maps</b>
 * (DE/BB/PR etc.) bosses do carry the Hypixel bar, so both scan channels run:
 * health outlier + bar coordinate association. <b>Scanning stops after game
 * end</b> (settlement action entities like the victory wither must not be
 * mislabeled), while already-marked bosses keep their outline.
 *
 * <p><b>The health outlier test is tiered, not one fixed line</b> (a real
 * trap: on RIP some high-health trash mobs got labeled boss). Three axes
 * tighten independently; failing any one disqualifies:
 * <ol>
 *  <li><b>Difficulty axis</b> ({@link #floorFor}) - the absolute floor is
 *  tiered by difficulty: NORMAL {@value #HP_FLOOR_NORMAL}, HARD
 *  {@value #HP_FLOOR_HARD}, RIP {@value #HP_FLOOR_RIP}; an unrecognized
 *  difficulty takes the stricter HARD floor: RIP trash health sits higher
 *  overall, and one hard-coded low line would overlap the trash band;</li>
 *  <li><b>Round axis</b> ({@link #isBossWindow}) - the baseline floor applies
 *  only when the current or previous round is a boss round, otherwise it is
 *  scaled by {@value #OFF_BOSS_ROUND_SCALE}. Hypixel spawns bosses in the
 *  round's last wave and they demonstrably survive into the next round (DE
 *  RIP's bosses of rounds 5/10/15/20/25/30 were caught in rounds
 *  5/9/14/19/24/29), so "this round + last round" covers a boss's whole
 *  lifetime; conversely non-boss rounds should have no boss at all, and
 *  getting labeled there demands harder evidence - this gate exists to block
 *  "a thick-health trash mob that happens to be the highest on the field";</li>
 *  <li><b>Relative axis</b> - the highest health must also be &gt;= the
 *  second-highest x {@value #HP_OUTLIER_RATIO}, guarding against "the whole
 *  wave spawned high" mutual labeling. Mobs below the floor join neither the
 *  candidates nor the comparison; comparison happens only among mobs past the
 *  line</li>
 * </ol>
 *
 * <p><b>The Old One's criteria on AA</b>: {@link #hasDiamondSword a diamond
 * sword in the main hand} is necessary, plus one of the
 * {@link ZombieEntity#isBaby() baby flag} or
 * {@link #OLD_ONE_HP_MIN abnormal health}. The baby flag cannot be
 * necessary: {@code isBaby()} reads the entity metadata
 * {@code ZombieEntity.BABY}, which Hypixel may not sync for Old Ones; an Old
 * One's sword need not carry Sharpness either - {@code minecraft:diamond_sword}
 * never appeared once in the test logs, let alone enchantments.
 * A "diamond-sword zombie with abnormal health" is caught, while AA's
 * ordinary baby zombies (maxHP only 8/14/16 in testing) fail the health line
 * and are never mislabeled.
 *
 * <p>Performance: a boss identity never changes for an entity's lifetime ->
 * cached permanently on first hit ({@link #BOSS_IDS}); later queries are one
 * HashSet hit. The world scan is <b>globally throttled once</b> per
 * {@value #RESCAN_MS} ms (not per entity); one pass costs one entity
 * iteration + a health sort + a small bars-x-mobs loop.
 * A world instance switch (map entry / reconnect both create a new
 * ClientWorld) invalidates everything wholesale. Called on the single render
 * thread; no concurrent containers needed.
 */
public final class BossDetector {

    /**
     * Boss bar shape: a pipe string wrapped in brackets (spaces allowed;
     * color codes/emoji already washed by StringUtils).
     * The brackets are the point - the downed-revive bar is a bare
     * {@code ■} string with no brackets and must fail.
     */
    private static final Pattern BAR_TAG = Pattern.compile("\\[\\s*[|｜│]+\\s*]");

    /** Throttle interval for the world scan of bar armor stands (ms): a boss is colored at most 0.5s after it appears. */
    private static final long RESCAN_MS = 500L;

    /** Horizontal (XZ) association radius between bar and mob: the bar floats right above the head, XZ nearly coincident. */
    private static final double BAR_XZ_RADIUS = 1.5;
    private static final double BAR_XZ_RADIUS_SQ = BAR_XZ_RADIUS * BAR_XZ_RADIUS;
    /** Vertical window of the bar relative to the mob's feet: below -0.5 it is under the mob (not its bar), above 3.5 is too far. */
    private static final double BAR_Y_MIN = -0.5;
    private static final double BAR_Y_MAX = 3.5;

    // ----Health outlier: per-axis tiered values----

    /** NORMAL floor: DE/BB/PR normal-difficulty bosses (Bombie etc. at 240+) pass comfortably; early weak mobs cannot reach it. */
    private static final float HP_FLOOR_NORMAL = 150.0F;
    /** HARD floor: HARD trash health sits one tier higher; 260 is what keeps the line above the trash band. */
    private static final float HP_FLOOR_HARD = 260.0F;
    /** RIP floor: RIP is the most extreme health tier - trash can exceed 210 and bosses reach 750 in testing;
     *  600 separates "RIP's thick-health trash" from "RIP's bosses". */
    private static final float HP_FLOOR_RIP = 600.0F;
    /** Floor when the difficulty is unrecognized ({@link DifficultyId#NULL}): the stricter HARD floor -
     *  better to miss a boss (the bar channel still covers it) than to label trash as boss under an unknown difficulty. */
    private static final float HP_FLOOR_UNKNOWN = HP_FLOOR_HARD;
    /** Health outlier ratio: the highest must be >= the second-highest x this multiplier (guards against two similar big mobs labeling each other). */
    private static final float HP_OUTLIER_RATIO = 1.5F;
    /** Floor multiplier outside a boss window (neither the current nor the previous round is a boss round). */
    private static final float OFF_BOSS_ROUND_SCALE = 2.0F;

    /**
     * The Old One's "abnormal health" line: AA does not use the tiered floors
     * above (those belong to the non-AA health-outlier channel), so a
     * self-contained line is needed here.
     *
     * <p>100 is the upper bound the testing propped up: AA normal baby
     * zombies' maxHP is only 8/14/16 (one real log saw 150k rows across the
     * three shapes), an order of magnitude below this line, so even carrying
     * a diamond sword never reads as an Old One.
     *
     * <p><b>Pending field calibration</b>: the Old One's true max health has
     * not been measured ({@code minecraft:diamond_sword} never appeared in the
     * logs; AA has not yielded a usable sample yet).
     * If real games show Old Ones below 100 (not caught), lower this line; if
     * ordinary mobs above 100 get mislabeled, raise it.
     */
    private static final float OLD_ONE_HP_MIN = 100.0F;

    /** Cache capacity guard: clearing wholesale once exceeded (entity IDs grow monotonically per game; normal games never hit it). */
    private static final int CACHE_MAX = 1024;

    private static World cachedWorld;
    /** Confirmed boss entity IDs: identity is permanent, cached forever. */
    private static final HashSet<Integer> BOSS_IDS = new HashSet<>();
    private static long nextRescanMs;

    /**
     * Called per entity by the render layer: the type channel (Giant/Old One)
     * decides immediately; then an explicit map gate - once the map is
     * confirmed AA, return false directly, skipping the health-outlier and
     * bar-association channels entirely and keeping only the type channel,
     * because AA trash health can exceed 100 and further scanning would pin
     * the orange outline onto high-health mobs (a real trap).
     * The two scan channels run only when the map is unrecognized (NULL) or
     * confirmed non-AA; NULL is not a confirmed AA.
     *
     * @param entity the entity the ESP or health bar renderer is checking
     * @return {@code true} when confirmed as a boss by type, health outlier or bar association, and cached
     */
    public static boolean isBoss(Entity entity) {
        World world = entity.getWorld();
        if (world != cachedWorld) {
            cachedWorld = world;
            BOSS_IDS.clear();
            nextRescanMs = 0L;
        }
        // Confirmed bosses hit the cache directly (type and scan channels share one cache,
        // avoiding duplicate marks)
        if (BOSS_IDS.contains(entity.getId())) {
            return true;
        }
        // The type channel is always available (pure instanceof, cheap): Giant / Old One
        if (entity instanceof GiantEntity) {
            markBoss(entity);
            return true;
        }
        // Old One: a diamond sword in the main hand is necessary, plus one of the baby flag or
        // abnormal health (see the class comment)
        if (entity instanceof ZombieEntity zombie
                && hasDiamondSword(zombie)
                && (zombie.isBaby() || zombie.getMaxHealth() >= OLD_ONE_HP_MIN)) {
            markBoss(entity);
            return true;
        }
        // AA map: bosses are only Giant/Old One; skip the health/bar channels entirely
        if (LanguageUtils.getMap() == MapId.ALIEN_ARCADIUM) {
            return false;
        }
        rescanBossBarsIfDue(world);
        return BOSS_IDS.contains(entity.getId());
    }

    /**
     * The globally throttled scan: one iteration collects both bar carriers
     * and candidate mobs, then runs <b>two independent channels</b> -
     * (1) health outlier (decidable from spawn, no bar needed);
     * (2) bar coordinate association (Hypixel's native bar).
     * The channels do not block each other: an empty bar window does not stop
     * the health channel and vice versa. The throttle is global (not per
     * entity): one world iteration per {@value #RESCAN_MS} ms.
     */
    private static void rescanBossBarsIfDue(World world) {
        long now = System.currentTimeMillis();
        if (now < nextRescanMs) {
            return;
        }
        nextRescanMs = now + RESCAN_MS;
        if (BOSS_IDS.size() >= CACHE_MAX) {
            BOSS_IDS.clear();
        }
        // Stop adding boss marks after game end (clear or wipe): Hypixel spawns settlement
        // action entities after "some players won"
        // (DE in practice: 34s after the Brood Mother died, an 812-HP wither spawned and got an
        // orange outline from the health outlier channel).
        // Cached boss outlines are kept (isBoss still hits the table); only "scanning new
        // entities" stops, guarding the settlement show from mislabels
        if (GameTickHandler.get().isGameOver()) {
            return;
        }
        // Only ClientWorld provides the full entity iterator (the World interface has no
        // getEntities)
        if (!(world instanceof ClientWorld clientWorld)) {
            return;
        }

        List<ArmorStandEntity> bars = null;
        List<LivingEntity> candidates = null;
        for (Entity e : clientWorld.getEntities()) {
            if (e instanceof ArmorStandEntity stand) {
                // Bar carrier: invisible marker armor stand + bracket-wrapped pipe name.
                // hasCustomName() and getCustomName() are asked separately: the latter returns
                // null when no custom name exists, and passing it straight to getRaw would blow
                // up while building the string
                Text standName = stand.isMarker() ? stand.getCustomName() : null;
                if (standName != null && BAR_TAG.matcher(StringUtils.getRaw(standName)).find()) {
                    if (bars == null) {
                        bars = new ArrayList<>(2);
                    }
                    bars.add(stand);
                }
            } else if ((e instanceof Monster || e instanceof WolfEntity) && e instanceof LivingEntity living) {
                if (candidates == null) {
                    candidates = new ArrayList<>();
                }
                candidates.add(living);
            }
        }

        // The floor in effect this pass (difficulty axis x round axis): shared by both channels
        float floor = floorFor(DifficultyUtils.getDifficulty())
                * (isBossWindow(currentRound()) ? 1.0F : OFF_BOSS_ROUND_SCALE);

        // (1) Health outlier channel: past the line (>= this pass's floor) and clearly above the
        // second-highest -> boss
        if (candidates != null) {
            LivingEntity outlier = findHpOutlier(candidates, floor);
            if (outlier != null) {
                markBoss(outlier);
            }
        }

        // (2) Bar coordinate association channel: each bar armor stand looks up the mob beneath it
        if (bars != null && candidates != null) {
            for (ArmorStandEntity bar : bars) {
                LivingEntity owner = findBarOwner(bar, candidates);
                if (owner != null) {
                    markBoss(owner);
                }
            }
        }
    }

    /** The current round number (0 before a round starts or when the singleton is missing; treated as "outside the boss window"). */
    private static int currentRound() {
        CheckSpawnTimes spawnTimes = CheckSpawnTimes.get();
        return spawnTimes == null ? 0 : spawnTimes.getCurrentRound();
    }

    /**
     * The round axis: a boss may appear only when the current or previous
     * round is a boss round.
     *
     * <p>Hypixel spawns bosses in the round's last wave and they demonstrably
     * survive into the next round: DE RIP's data table registers boss rounds
     * 5/10/15/20/25/30, while the actual hits landed in rounds
     * 5/9/14/19/24/29 - i.e. <b>the boss was spawned the previous round</b>.
     * Hence "this round + last round" covers its whole lifetime. Conversely,
     * non-boss rounds have no boss to begin with; raising the floor
     * {@value #OFF_BOSS_ROUND_SCALE}-fold blocks mislabels like "a
     * thick-health trash mob that happens to be the highest on the field":
     * in testing, that batch of 240/330/520 HP DE hits all happened in
     * non-boss rounds. An unrecognized round or map is treated as outside the
     * window (strict).
     *
     * <p>The cost, stated plainly: this gate reads only the round number, not
     * whether spawning actually happened early - if bosses are ever found to
     * appear <b>two rounds before</b> the boss round, this method must loosen
     * accordingly, not the floor.
     *
     * @param round the current round number
     * @return true when the current or previous round is a boss round
     */
    private static boolean isBossWindow(int round) {
        if (round <= 0) {
            return false;
        }
        MapId map = LanguageUtils.getMap();
        return RoundUtils.isBossRound(map, round) || RoundUtils.isBossRound(map, round - 1);
    }

    /**
     * The difficulty axis: the health-outlier absolute floor for the current
     * difficulty.
     *
     * @param difficulty the difficulty identified from the scoreboard; {@code null} or {@link DifficultyId#NULL} is treated as unrecognized
     * @return the floor health for that difficulty tier
     */
    private static float floorFor(DifficultyId difficulty) {
        if (difficulty == null) {
            return HP_FLOOR_UNKNOWN;
        }
        return switch (difficulty) {
            case RIP -> HP_FLOOR_RIP;
            case HARD -> HP_FLOOR_HARD;
            case NORMAL -> HP_FLOOR_NORMAL;
            case NULL -> HP_FLOOR_UNKNOWN;
        };
    }

    /**
     * Health outlier detection: find the candidate with the highest
     * maxHealth, requiring it >= this pass's floor (difficulty x round axes)
     * and >= the second-highest x {@value #HP_OUTLIER_RATIO} (the relative
     * axis). A single mob above the floor is labeled too (its second-highest
     * is null; no comparison means outlier) - note "past the floor" is itself
     * already a tiered result, so no extra "the lone one must be higher"
     * requirement is stacked on.
     *
     * <p>Mobs below the floor join neither candidates nor comparison:
     * comparison happens only among mobs past the line, otherwise a 600 floor
     * would be diluted by 100-HP trash into "relative outlier".
     *
     * @param candidates the mobs on the field
     * @param floor the floor in effect this pass (difficulty tier x round tier)
     * @return the mob passing all three axes; {@code null} when none
     */
    private static LivingEntity findHpOutlier(List<LivingEntity> candidates, float floor) {
        LivingEntity top = null;
        LivingEntity second = null;
        for (LivingEntity mob : candidates) {
            if (mob.isRemoved()) {
                continue;
            }
            float hp = mob.getMaxHealth();
            if (hp < floor) {
                continue;
            }
            if (top == null || hp > top.getMaxHealth()) {
                second = top;
                top = mob;
            } else if (second == null || hp > second.getMaxHealth()) {
                second = mob;
            }
        }
        if (top == null) {
            return null;
        }
        if (second != null && top.getMaxHealth() < second.getMaxHealth() * HP_OUTLIER_RATIO) {
            return null;
        }
        return top;
    }

    /**
     * Looks up the owner mob of a bar armor stand: XZ nearly coincident plus
     * the bar inside the vertical window above the mob. Several mobs may fall
     * inside the window in a crowd; take <b>the highest maxHealth</b> - boss
     * health far exceeds trash (in practice a boss zombie at 240 HP with no
     * trash above 100), and this tiebreak blocks the "nearest mob" mismatching
     * in crowds (a nearest-mob jitter onto a neighboring trash mob was seen
     * once in practice).
     */
    private static LivingEntity findBarOwner(ArmorStandEntity bar, List<LivingEntity> candidates) {
        LivingEntity best = null;
        float bestHp = -1.0F;
        for (LivingEntity mob : candidates) {
            double dx = mob.getX() - bar.getX();
            double dz = mob.getZ() - bar.getZ();
            if (dx * dx + dz * dz > BAR_XZ_RADIUS_SQ) {
                continue;
            }
            double dy = bar.getY() - mob.getY();
            if (dy < BAR_Y_MIN || dy > BAR_Y_MAX) {
                continue;
            }
            float hp = mob.getMaxHealth();
            if (hp > bestHp) {
                bestHp = hp;
                best = mob;
            }
        }
        return best;
    }

    /** On a hit, writes into the {@link #BOSS_IDS} permanent cache (boss identity is lifelong; later queries are one HashSet hit; the cache clears wholesale at {@value #CACHE_MAX}). */
    private static void markBoss(Entity entity) {
        BOSS_IDS.add(entity.getId());
    }

    /** First half of the Old One test: a diamond sword in the main hand. No enchantment check - an Old One's sword need not have Sharpness, and no ordinary AA mob carries a diamond sword (the item id never appeared in the test logs). */
    private static boolean hasDiamondSword(ZombieEntity zombie) {
        ItemStack stack = zombie.getMainHandStack();
        return stack.isOf(Items.DIAMOND_SWORD);
    }

    private BossDetector() {
    }
}
