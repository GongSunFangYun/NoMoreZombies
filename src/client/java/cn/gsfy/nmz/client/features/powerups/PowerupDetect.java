package cn.gsfy.nmz.client.features.powerups;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.PowerupPattern;
import cn.gsfy.nmz.client.features.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.shared.game.DelayedTaskScheduler;
import cn.gsfy.nmz.client.shared.game.GameTickHandler;
import cn.gsfy.nmz.client.shared.powerup.PowerupParser;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Power-up detection and pattern commitment (the original PowerupDetect,
 * NEZ-ified). Three channels recognize power-up events and back each other up:
 * channel A is the {@code onEntityTrackerUpdate} mixin metadata channel, where
 * an armor stand has a name the moment it enters; channel A' scans the field's
 * armor stand names every 4 ticks ({@link #scanArmorStands()}), for Hypixel's
 * new entities whose names arrive with the spawn packet and never enter
 * {@code onEntityTrackerUpdate}; channel B is the chat activation message
 * ({@link #onChatReceived(String)}), the last fallback when armor stands are
 * fully missed, which also carries the active countdown and the "picked up"
 * state.
 *
 * <p>The two armor stand channels share {@link #detectArmorstand} and
 * {@link #seenStandIds} for dedup, so one drop is never counted twice.
 * Pattern submission is commit-once: the first observation (armor stand or
 * chat) fixes the pattern index the type hit, locked for the whole game;
 * the {@link #nextRound} engine then predicts later rounds by "explicit
 * rounds + ones-digit extrapolation" and never changes course on a second
 * observation.
 */
public class PowerupDetect {

    /** The global singleton, non-null only after {@link #init()}; outsiders use {@link #get()} with a null check. */
    private static PowerupDetect instance;

    /** This game's committed power-up patterns: type -> pattern, locked on first observation, never changed in-game.
     *  On a data-table hit, the {@link PowerupPattern} found by index; with an empty table
     *  (no pattern for this type on this map) a synthetic single-point pattern is committed:
     *  {@code {rounds:[round], digits:[round%10]}} -
     *  so "this round (dropped)" can reach the HUD and later rounds with the same ones digit
     *  can keep being predicted. */
    private final Map<PowerupParser.PowerupType, PowerupPattern> committedPattern = new EnumMap<>(PowerupParser.PowerupType.class);

    /**
     * Armor stand entity IDs already registered this game - the mixin
     * metadata channel and the every-4-ticks scan channel share this dedup,
     * so one armor stand is recorded once.
     */
    private final Set<Integer> seenStandIds = new HashSet<>();

    /**
     * Scan throttle counter: the field's armor stands are scanned once every
     * 4 ticks, avoiding a full entity iteration each tick.
     */
    private int scanCounter;

    /** The global singleton: non-null after init(), fetched with a null check. */
    public static PowerupDetect get() {
        return instance;
    }

    /**
     * Builds the singleton and hooks the every-4-ticks armor stand fallback
     * scan (for entities that never enter onEntityTrackerUpdate).
     */
    public void init() {
        instance = this;
        // The NEZ-style fallback channel: scan the field's armor stand names every 4 ticks -
        // Hypixel's new entities get their names with the spawn packet, bypassing
        // onEntityTrackerUpdate; without actively scanning the world they are missed, and this
        // channel plugs that hole
        ClientTickEvents.END_CLIENT_TICK.register(client -> scanArmorStands());
    }

    /**
     * Clears all patterns and cached state on a new game - hooked to both the
     * JOIN/DISCONNECT entry points of the connection events (registered in
     * NoMoreZombiesClient), so nothing survives across games and the old
     * game's patterns cannot leak into the new one.
     */
    public void iniPowerupPatterns() {
        PowerupParser.powerups.clear();
        PowerupParser.incPowerups.clear();
        PowerupParser.expiredPowerups.clear();
        PowerupParser.activePowerups.clear();
        PowerupParser.pickedUpRound.clear();
        committedPattern.clear();
        seenStandIds.clear();
        scanCounter = 0;
    }

    /**
     * Scans the field's armor stands every 4 ticks (the NEZ
     * LivingUpdateEventHandler approach); non-Zombies worlds skip outright,
     * saving the iteration.
     */
    private void scanArmorStands() {
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        // The active list expires each tick - cleared even when the HUD is off; at most 6
        // entries, so full filtering is nearly free
        PowerupParser.activePowerups.removeIf(a -> a.getExpireMs() <= System.currentTimeMillis());
        if (++scanCounter % 4 != 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof ArmorStandEntity stand) {
                Text customName = stand.getCustomName();
                if (customName != null) {
                    detectArmorstand(customName.getString(), stand.getId());
                }
            }
        }
    }

    // ====================Channel A: armor stand names====================

    /**
     * The armor stand entry shared by the mixin metadata channel and the
     * every-4-ticks scan channel; only when the name is recognizable, the
     * entity still present and the ID unregistered does it record the drop,
     * commit the pattern and broadcast - no double counting across channels.
     *
     * @param armorStandName the armor stand's current display name
     * @param entityId the entity ID in the client world, used to look the entity up and dedup across channels
     */
    public void detectArmorstand(String armorStandName, int entityId) {
        int round = CheckSpawnTimes.get().getCurrentRound();
        if (round == 0) {
            return;
        }
        PowerupParser.PowerupType type = PowerupParser.PowerupType.fromName(armorStandName);
        if (type == PowerupParser.PowerupType.NULL) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        Entity entity = client.world != null ? client.world.getEntityById(entityId) : null;
        if (!(entity instanceof ArmorStandEntity stand)) {
            return;
        }
        if (seenStandIds.contains(entityId)) {
            return; // already registered - the mixin and scan channels share the dedup, so one stand is never counted twice
        }
        PowerupParser powerup = PowerupParser.deserialize(type, stand);
        if (powerup == null) {
            return;
        }
        seenStandIds.add(entityId);
        // Also mark the round's prediction entry dropped - only then can HUD state 3 switch from
        // "undropped" to dropped, with the display channel re-attached to the on-ground item's
        // vanish countdown
        markDropped(type);
        learnPattern(type, round);
        notifyPowerupDropped(type);
    }

    /**
     * Marks this round's prediction entry of the type as dropped. Only after
     * the dropped flag does the HUD's dropped branch light: the display
     * channel switches from the "undropped" prediction countdown to the
     * on-ground armor stand's vanish countdown.
     */
    private void markDropped(PowerupParser.PowerupType type) {
        for (PowerupParser p : new ArrayList<>(PowerupParser.incPowerups)) {
            if (p.getPowerupType() == type && !p.isDropped()) {
                p.markDropped();
                return;
            }
        }
        // Backfill: when a predictable type (dataKey != null) had no prediction entry at the
        // round's start - a mid-game join, or the pattern committed mid-round - add one, so a
        // prediction entry exists and is marked dropped (the HUD switches its dropped branch by
        // it). Unpredictable types (double gold etc.) are not backfilled; they have their own
        // "spawned 00:XX" display channel
        if (dataKey(type) != null) {
            // deserialize(type) always returns a fresh entry (no dedup semantics); no null check
            // needed
            PowerupParser.deserialize(type).markDropped();
        }
    }

    /**
     * The power-up drop chat notice (NEZ PowerUpAlert style): "XX dropped" as
     * soon as the armor stand is detected, gated by {@code POWERUP_PREDICT};
     * the output channel follows {@code ALERT_OUTPUT}, falling back to SELF on
     * an unrecognized enum value.
     */
    private void notifyPowerupDropped(PowerupParser.PowerupType type) {
        if (!GlobalConfig.Powerups.POWERUP_PREDICT.getBooleanValue()) {
            return;
        }
        Text powerupText = Text.literal(type.getColorCode() + Text.translatable(PowerupParser.keyFor(type)).getString());
        Text message = Text.literal("[NoMoreZombies] ").formatted(Formatting.GOLD)
                .copy()
                .append(powerupText)
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable("nomorezombies.msg.powerup.dropped").formatted(Formatting.WHITE));
        PlayerUtils.sendMessage(message,
                GlobalConfig.Powerups.ALERT_OUTPUT.getOptionListValue() instanceof GlobalConfig.AlertOutput o
                        ? o : GlobalConfig.AlertOutput.SELF);
    }

    // ====================Channel B: chat activation messages====================

    /**
     * The client chat entry hands de-formatted messages here as the
     * activation fallback when armor stands are missed; messages containing a
     * player chat colon or outside a Zombies game are ignored outright.
     *
     * @param message the de-formatted chat text
     */
    public void onChatReceived(String message) {
        if (message.contains(":") || message.contains("：")) {
            return;
        }
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        if (!LanguageUtils.isActivatedMessage(message)) {
            return;
        }
        int round = CheckSpawnTimes.get().getCurrentRound();
        if (round == 0) {
            return;
        }

        PowerupParser.PowerupType type = detectFromMessage(message);
        if (type == PowerupParser.PowerupType.NULL) {
            return;
        }
        int duration = LanguageUtils.extractPowerupDuration(message);
        // Chat pickup is the missed-detection fallback (NEZ onPowerUpPickup style); commit-once,
        // no re-commit once committed
        learnPattern(type, round);
        // Record picked-up this round: after the effect ends the HUD does not fall back to
        // "dropped"; the state machine only advances, never rewinds
        PowerupParser.pickedUpRound.add(type);
        // Active countdown: non-instant power-ups (double gold / insta kill / shopping spree
        // etc.) run the chat's real duration; instant ones (Max Ammo, duration <= 0) take the 3s
        // "Active" confirmation flash
        PowerupParser.addActivePowerup(type, duration);

        DelayedTaskScheduler.get().runTaskLater(5, () -> {
            // Only armor stand entries already gone (picked up / expired) are recycled here;
            // markDropped is deliberately not called:
            // (1) pickup time != drop time, and a mislabel would pollute the dropped-state test;
            // (2) picking up a previous round's leftover across rounds would wrongly mark this
            // round's prediction entry dropped.
            // The dropped flag trusts only the armor stand detection channel
            // (detectArmorstand -> markDropped)
            for (Map.Entry<ArmorStandEntity, PowerupParser> entry : new ArrayList<>(PowerupParser.powerups.entrySet())) {
                if (entry.getValue().getPowerupType() == type) {
                    ArmorStandEntity stand = entry.getKey();
                    if (stand == null || stand.isRemoved()) {
                        entry.getValue().claim();
                    }
                }
            }
        });
    }

    /** Recognizes the power-up type from a chat activation message (Chinese/English variants compared one by one); NULL when unrecognized. */
    private PowerupParser.PowerupType detectFromMessage(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("insta kill") || message.contains("秒杀") || message.contains("一擊必殺")
                || message.contains("瞬间击杀") || message.contains("瞬間擊殺")) {
            return PowerupParser.PowerupType.INSTA_KILL;
        }
        if (lower.contains("max ammo") || message.contains("满弹药") || message.contains("滿彈藥")
                || message.contains("弹药满载") || message.contains("彈藥滿載")) {
            return PowerupParser.PowerupType.MAX_AMMO;
        }
        if (lower.contains("shopping spree") || lower.contains("shop spree")
                || message.contains("购物狂潮") || message.contains("購物狂潮")) {
            return PowerupParser.PowerupType.SHOPPING_SPREE;
        }
        if (lower.contains("carpenter") || message.contains("木匠")) {
            return PowerupParser.PowerupType.CARPENTER;
        }
        if (lower.contains("bonus gold") || message.contains("额外金币") || message.contains("額外金幣")) {
            return PowerupParser.PowerupType.BONUS_GOLD;
        }
        if (lower.contains("double gold") || message.contains("双倍金币") || message.contains("雙倍金幣")
                || message.contains("双倍金钱") || message.contains("雙倍金錢")) {
            return PowerupParser.PowerupType.DOUBLE_GOLD;
        }
        return PowerupParser.PowerupType.NULL;
    }

    // ====================Pattern commitment (NEZ commit-once)====================

    /**
     * On the type's first observation, fixes which pattern it hit and locks
     * it; already-committed types are ignored - the second half of
     * commit-once.
     */
    private void learnPattern(PowerupParser.PowerupType type, int round) {
        if (committedPattern.containsKey(type)) {
            return;
        }
        List<PowerupPattern> pats = patterns(type);
        int idx = patternIndexFor(type, round);
        if (idx >= 0) {
            committedPattern.put(type, pats.get(idx));
            return;
        }
        // Cross-round ownership fix (mirroring PowerupPredictor.match's early logic): a power-up
        // observed right after a round starts (<= 1s) may belong to the previous round - the
        // round title flips first, while the armor stand scan / chat activation arrive slightly
        // later - so round-1 is tried once more, attributing the late event to the correct round
        if (GameTickHandler.get() != null && GameTickHandler.get().getGameTick() <= 1000L) {
            int prevIdx = patternIndexFor(type, round - 1);
            if (prevIdx >= 0) {
                committedPattern.put(type, pats.get(prevIdx));
                return;
            }
        }
        // Empty table (no pattern for this type on this map, e.g. no shopping_spree on
        // DE/BB/Prison): commit a synthetic single-point pattern {rounds:[round], digits:[round
        // % 10]} on first observation, so "this round (dropped)" reaches the HUD immediately and
        // later rounds with the same ones digit stay predictable.
        // Synthesis happens only when the table is empty; an existing table with no hit this
        // round stays uncommitted, never polluting the real pattern table
        if (pats.isEmpty()) {
            committedPattern.put(type, new PowerupPattern(new int[]{round}, new int[]{round % 10}));
        }
    }

    /**
     * The first index in the type's pattern table whose rounds contain the
     * round; -1 when none do, meaning this round hit no pattern.
     */
    private int patternIndexFor(PowerupParser.PowerupType type, int round) {
        List<PowerupPattern> pats = patterns(type);
        for (int i = 0; i < pats.size(); i++) {
            if (contains(pats.get(i).getRounds(), round)) {
                return i;
            }
        }
        return -1;
    }

    /** Fetches the type's pattern table by map: converts to dataKey first; no key (an unpredictable type) gives an empty list. */
    private List<PowerupPattern> patterns(PowerupParser.PowerupType type) {
        String key = dataKey(type);
        if (key == null) {
            return List.of();
        }
        return DataManager.get().getPowerupPatterns(LanguageUtils.getMap(), key);
    }

    /** Type -> the powerup_patterns.json typeKey;
     *  unpredictable types have no key and return null. */
    private static String dataKey(PowerupParser.PowerupType type) {
        return switch (type) {
            case INSTA_KILL -> "insta_kill";
            case MAX_AMMO -> "max_ammo";
            case SHOPPING_SPREE -> "shopping_spree";
            default -> null;
        };
    }

    // ====================Prediction engine (NEZ getNextPowerUpRound)====================

    /**
     * The prediction engine queries the first spawn at or after the given
     * round: the explicit table first, then ones-digit extrapolation.
     *
     * @param type the power-up type to query
     * @param currentRound the query lower bound with {@code >=} semantics, so this round itself may return
     * @return the next spawn round; {@code -1} when no pattern is committed or extrapolation fails
     */
    public int nextRound(PowerupParser.PowerupType type, int currentRound) {
        PowerupPattern p = committedPattern.get(type);
        if (p == null) {
            return -1;
        }

        // Explicit phase: the smallest round in rounds that is >= currentRound - while the table
        // still has one, use it
        int best = -1;
        for (int r : p.getRounds()) {
            if (r >= currentRound && (best < 0 || r < best)) {
                best = r;
            }
        }
        if (best >= 0) {
            return best;
        }

        // Extrapolation phase: the explicit table exhausted, extrapolate by ones digit -
        // tensDown sweeps ten rounds per decade, predicting even beyond the explicit table
        int[] digits = p.getDigits();
        if (digits.length == 0) {
            return -1;
        }
        int tensDown = currentRound - currentRound % 10;
        for (int i = 0; i < 10; i++) {
            for (int digit : digits) {
                int res = tensDown + digit;
                if (res >= currentRound) {
                    return res;
                }
            }
            tensDown += 10;
        }
        return -1;
    }

    /**
     * @param type the power-up type to test
     * @param round the current round number
     * @return {@code true} when the explicit table or ones-digit extrapolation places the next spawn on this round
     */
    public boolean isPowerupRound(PowerupParser.PowerupType type, int round) {
        return nextRound(type, round) == round;
    }

    /**
     * @param type the power-up type to query
     * @return {@code true} when the type has completed commit-once and is usable for prediction and chat output
     */
    public boolean hasCommitted(PowerupParser.PowerupType type) {
        return committedPattern.containsKey(type);
    }

    /** Whether value is in the rounds array:
     *  pattern rounds are int[], element-wise comparison suffices. */
    private boolean contains(int[] array, int value) {
        for (int i : array) {
            if (i == value) {
                return true;
            }
        }
        return false;
    }
}