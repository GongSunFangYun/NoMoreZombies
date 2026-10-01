package cn.gsfy.nmz.client.shared.powerup;

import cn.gsfy.nmz.client.utils.LanguageUtils;
import net.minecraft.entity.decoration.ArmorStandEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import cn.gsfy.nmz.client.shared.game.DelayedTaskScheduler;

/**
 * Power-up model (counterpart of the source Powerup). offsetTime's unit is
 * game ticks (1200 = 60s).
 *
 * <p>Each spawned power-up in the world = one armor stand entity + one
 * countdown, indexed by entity in {@code powerups}; on countdown zero it is
 * removed and blacklisted.
 * A predicted power-up binds no entity and lives only in
 * {@code incPowerups}; a picked-up, active one uses {@link ActivePowerup}'s
 * wall-clock countdown instead. The three are kept distinct.
 *
 * <p>The countdown is advanced by each entity's own 1-tick self-cancel task
 * (hooked into {@link DelayedTaskScheduler} at construction; on zero it
 * cancels itself), not by any external scan. The clearing timing has exactly
 * one entry: GameEventBus clears {@code incPowerups} / {@code pickedUpRound}
 * at round start, boss rounds then {@code claim()} each entry in
 * {@code powerups}, and disconnect is backstopped by
 * PowerupDetect.iniPowerupPatterns—so a cross-game leftover can only appear
 * in the "missed round title" case.
 */
public class PowerupParser {

    private final PowerupType powerupType;
    private final ArmorStandEntity armorStand;

    /** Spawned power-ups in the world: armor stand → Powerup (removed on countdown zero or pickup). */
    public static final LinkedHashMap<ArmorStandEntity, PowerupParser> powerups = new LinkedHashMap<>();
    /**
     * The power-ups that will spawn this round (predicted, no entity yet),
     * for the HUD's "undropped / dropped" states. Only records whether it
     * dropped, not the drop time—the HUD reads that one flag too.
     * Entries persist to round end; GameEventBus only clears and rebuilds at
     * round start.
     */
    public static final List<PowerupParser> incPowerups = new ArrayList<>();
    /** Expired armor stands—a short blacklist, preventing the same entity from being registered as two Powerups. */
    public static final List<ArmorStandEntity> expiredPowerups = new ArrayList<>();
    /**
     * Picked-up and currently active power-ups: each carries "type + expiry
     * instant" (wall-clock ms, matching the server's real duration).
     */
    public static final List<ActivePowerup> activePowerups = new ArrayList<>();
    /** The power-up types picked up this round (written when chat activation hits; cleared at round start).
     *  The HUD uses it not to fall back to "dropped" after the effect ends (the state machine only advances, never rewinds). */
    public static final Set<PowerupType> pickedUpRound = EnumSet.noneOf(PowerupType.class);

    private int offsetTime;

    /**
     * Whether this round's drop already happened (used by the prediction
     * entries in incPowerups only).
     */
    private boolean dropped;

    /**
     * A picked-up, currently active power-up. Remaining time is computed by
     * wall clock (same as NEZ's PowerUpHud): precise to real seconds, and it
     * avoids attaching a never-terminating ticker per active power-up, which
     * would leak.
     */
    public static class ActivePowerup {
        private final PowerupType powerupType;
        private final long expireMs;
        /**
         * true = a real-duration countdown; false = an instant power-up
         * (e.g. Max Ammo)'s "active" confirmation flash.
         */
        private final boolean timed;

        ActivePowerup(PowerupType type, long expireMs, boolean timed) {
            this.powerupType = type;
            this.expireMs = expireMs;
            this.timed = timed;
        }

        /** The active power-up's type (matching for HUD rendering and repeat-activation dedup both read it). */
        public PowerupType getPowerupType() {
            return powerupType;
        }

        /** The expiry instant (wall-clock ms; remaining time is computed from a now diff). */
        public long getExpireMs() {
            return expireMs;
        }

        /** Whether this is a real-duration countdown (false for instant power-ups, whose entry is just a pickup-confirmation flash). */
        public boolean isTimed() {
            return timed;
        }

        /**
         * The HUD queries remaining time against the current wall clock,
         * avoiding a per-power-up ticker.
         *
         * @param now the current wall-clock ms
         * @return remaining milliseconds; 0 when already expired
         */
        public long getRemainingMs(long now) {
            return Math.max(0L, expireMs - now);
        }
    }

    /**
     * Registers an active state via the chat activation channel; a repeat
     * activation of the same type replaces the old entry, so the HUD does
     * not show two at once.
     *
     * @param type the activated power-up's type
     * @param durationSeconds the duration seconds parsed from the message;
     *   ≤0 means an instant power-up, showing a 3s confirmation flash
     */
    public static void addActivePowerup(PowerupType type, int durationSeconds) {
        // Instant power-ups (durationSeconds<=0, e.g. Max Ammo) take a 3s
        // flash: just "it is active", no countdown, to avoid implying
        // "seconds of effect left"; real-duration power-ups count in seconds
        boolean timed = durationSeconds > 0;
        long expireMs = System.currentTimeMillis() + Math.max(durationSeconds, 3) * 1000L;
        // A repeat activation of the same type replaces the old entry, keeping only the most
        // recent (otherwise the HUD shows two)
        activePowerups.removeIf(a -> a.getPowerupType() == type);
        activePowerups.add(new ActivePowerup(type, expireMs, timed));
    }

    /**
     * Registers a field power-up via the armor stand detection channel;
     * already registered or still in the expiry blacklist refuses a
     * duplicate creation.
     *
     * @param type the power-up type identified from the armor stand name
     * @param stand the armor stand still in the client world
     * @return the newly registered power-up; {@code null} on a duplicate entity
     */
    public static PowerupParser deserialize(PowerupType type, ArmorStandEntity stand) {
        if (!powerups.containsKey(stand) && !expiredPowerups.contains(stand)) {
            PowerupParser powerup = new PowerupParser(type, stand);
            // incPowerups is not touched here: a prediction entry persists to round end,
            // continuing to show via the HUD's dropped branch; the field entry goes to
            // powerups. The two are recorded separately
            powerups.put(stand, powerup);
            return powerup;
        }
        return null;
    }

    /**
     * Creates a prediction entry with no bound entity at round start, for
     * the HUD's "will drop" display.
     *
     * @param type the power-up type whose committed pattern hits this round
     * @return the prediction entry already added to {@link #incPowerups}
     */
    public static PowerupParser deserialize(PowerupType type) {
        PowerupParser powerup = new PowerupParser(type, null);
        incPowerups.add(powerup);
        return powerup;
    }

    /**
     * Builds a field entry or a prediction entry: a field armor stand hooks
     * a client-tick countdown; a prediction entry only stores the type.
     *
     * @param type the entry's power-up type
     * @param armorStand the field power-up's armor stand; {@code null} for a
     *   prediction entry
     */
    public PowerupParser(PowerupType type, ArmorStandEntity armorStand) {
        this.powerupType = type;
        this.armorStand = armorStand;
        this.offsetTime = 1200;
        if (armorStand != null) {
            // One per tick (period=1); 1200 ticks = 60s; it self-cancels on expiry, preventing
            // two kinds of leak: a period=1 task that never terminates accumulates per power-up;
            // and the blacklist re-open guard also causes infinite delay=20 re-scheduling.
            // The task handle is captured by an array holder (a lambda cannot reference an
            // uninitialized local variable)
            DelayedTaskScheduler.Task[] self = new DelayedTaskScheduler.Task[1];
            self[0] = DelayedTaskScheduler.get().runTaskTimer(0, 1, () -> {
                if (offsetTime <= 0) {
                    if (self[0] != null) {
                        self[0].cancel();
                    }
                    onDeleteArmorStandFromExpiredList(armorStand);
                    powerups.remove(armorStand);
                } else {
                    offsetTime--;
                }
            });
        }
    }

    /**
     * Immediately zeroes the countdown and removes the field entry: called by
     * PowerupDetect on pickup / expiry, and by GameEventBus on boss rounds
     * for every field entry; the entity also enters the short blacklist to
     * prevent duplicate registration.
     */
    public void claim() {
        this.offsetTime = 0;
        if (armorStand != null) {
            onDeleteArmorStandFromExpiredList(armorStand);
            powerups.remove(armorStand);
        }
    }

    /** Pulls the expired armor stand into the 20-tick blacklist: a re-entry
     *  within the window is rejected by deserialize, preventing the same
     *  entity from being registered as two Powerups. */
    private void onDeleteArmorStandFromExpiredList(ArmorStandEntity stand) {
        if (expiredPowerups.contains(stand)) {
            return;
        }
        expiredPowerups.add(stand);
        DelayedTaskScheduler.get().runTaskLater(20, () -> expiredPowerups.remove(stand));
    }

    /**
     * PowerupDetect marks the prediction entry when the armor stand first
     * appears; the HUD therefore advances from "will drop" to the dropped
     * branch.
     */
    public void markDropped() {
        this.dropped = true;
    }

    /** Whether this round's drop already happened (set by markDropped; meaningful only on prediction entries). */
    public boolean isDropped() {
        return dropped;
    }

    /** The power-up type (the single identifier shared by the HUD's classified display and the drop notice). */
    public PowerupType getPowerupType() {
        return powerupType;
    }

    /** Remaining countdown (game ticks, 1200 = 60s; field power-ups show the vanish countdown from this). */
    public int getOffsetTime() {
        return offsetTime;
    }

    /**
     * The HUD and chat notice fetch the name via one shared translation key,
     * avoiding two separate mapping tables.
     *
     * @param type the power-up type
     * @return the matching {@code nomorezombies.powerup.*} key;
     *   {@link PowerupType#NULL} returns an empty string
     */
    public static String keyFor(PowerupType type) {
        return switch (type) {
            case INSTA_KILL -> "nomorezombies.powerup.instaKill";
            case MAX_AMMO -> "nomorezombies.powerup.maxAmmo";
            case DOUBLE_GOLD -> "nomorezombies.powerup.doubleGold";
            case CARPENTER -> "nomorezombies.powerup.carpenter";
            case BONUS_GOLD -> "nomorezombies.powerup.bonusGold";
            case SHOPPING_SPREE -> "nomorezombies.powerup.shoppingSpree";
            case NULL -> "";
        };
    }

    /** Power-up type enum: each carries a chat color code; wording goes through {@link #keyFor}'s lang keys for i18n. */
    public enum PowerupType {
        NULL(""),
        INSTA_KILL("§c"),
        MAX_AMMO("§9"),
        DOUBLE_GOLD("§6"),
        CARPENTER("§1"),
        BONUS_GOLD("§e"),
        SHOPPING_SPREE("§5");

        private final String colorCode;

        PowerupType(String colorCode) {
            this.colorCode = colorCode;
        }

        /** Chat color code (§ prefix; the drop notice colors by type). */
        public String getColorCode() {
            return colorCode;
        }

        /**
         * Armor stand name parsing and the chat fallback share this bilingual recognition.
         *
         * @param name the already de-formatted or raw power-up display name
         * @return the matching power-up type; {@link #NULL} when unrecognized
         */
        public static PowerupType fromName(String name) {
            if (LanguageUtils.isInstaKill(name)) return INSTA_KILL;
            if (LanguageUtils.isMaxAmmo(name)) return MAX_AMMO;
            if (LanguageUtils.isDoubleGold(name)) return DOUBLE_GOLD;
            if (LanguageUtils.isCarpenter(name)) return CARPENTER;
            if (LanguageUtils.isBonusGold(name)) return BONUS_GOLD;
            if (LanguageUtils.isShoppingSpree(name)) return SHOPPING_SPREE;
            return NULL;
        }
    }
}